package network.skypvp.paper.quest;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.paper.repository.QuestNpcRepository;

/**
 * Shared pool of quest POIs ({@code /quest location …}) plus runtime slot reservations.
 *
 * <p>Reservations are the collision-avoidance half of the alias feature: when an NPC wants to
 * work POI {@code market}, {@link #reserveSlot} hands out the anchor or a free alias so NPCs
 * sharing the POI spread across its sub-locations. Reservations are runtime-only state; the
 * POI definitions persist to Postgres via {@link QuestNpcRepository} — servers boot from
 * ephemeral images, so nothing may be written to the local filesystem.
 */
public final class QuestLocationRegistry {

    /** A granted slot: POI + concrete spot ({@code alias == null} means the anchor). */
    public record Slot(String poi, String alias, WorldPoint point) {
        public String key() {
            return alias == null ? poi : poi + ":" + alias;
        }
    }

    private static final Gson GSON = new Gson();

    private final QuestNpcRepository repository;
    private final Logger logger;
    private final Map<String, QuestPoi> pois = new ConcurrentHashMap<>();
    /** slot key ({@code poi} or {@code poi:alias}) → owning NPC id. */
    private final Map<String, String> reservations = new ConcurrentHashMap<>();

    public QuestLocationRegistry(QuestNpcRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // --- Pool management -------------------------------------------------------------------

    public QuestPoi get(String name) {
        return name == null ? null : pois.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, QuestPoi> all() {
        return Map.copyOf(pois);
    }

    public List<String> names() {
        return new ArrayList<>(pois.keySet());
    }

    public void put(QuestPoi poi) {
        String key = poi.name.toLowerCase(Locale.ROOT);
        if (poi.normalizedScope().isEmpty()) {
            poi.scope = "lobby";
        }
        pois.put(key, poi);
        if (repository != null) {
            repository.upsertPoi(key, poi.normalizedScope(), GSON.toJson(poi)).exceptionally(error -> null);
        }
    }

    public boolean remove(String name) {
        String key = name == null ? null : name.toLowerCase(Locale.ROOT);
        QuestPoi removed = unload(key);
        if (removed != null && repository != null) {
            repository.deletePoi(key, removed.normalizedScope()).exceptionally(error -> null);
        }
        return removed != null;
    }

    /**
     * Drops a POI from the in-memory pool (and its slot reservations) without touching Postgres.
     * Used when moving a location to another gamemode scope.
     */
    public QuestPoi unload(String name) {
        String key = name == null ? null : name.toLowerCase(Locale.ROOT);
        if (key == null) {
            return null;
        }
        QuestPoi removed = pois.remove(key);
        if (removed != null) {
            reservations.entrySet().removeIf(entry -> {
                String slot = entry.getKey();
                return slot.equals(key) || slot.startsWith(key + ":") || slot.startsWith(key + "#");
            });
        }
        return removed;
    }

    /**
     * Resolves a POI ref ({@code market} or {@code market:stall2}) to its configured spot,
     * ignoring reservations. Null when the POI or alias doesn't exist.
     */
    public WorldPoint resolve(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String[] parts = ref.split(":", 2);
        QuestPoi poi = get(parts[0]);
        if (poi == null) {
            return null;
        }
        return poi.spot(parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : null);
    }

    public boolean refExists(String ref) {
        return resolve(ref) != null;
    }

    // --- Slot reservations -----------------------------------------------------------------

    /**
     * Reserves a working spot at {@code ref} for {@code npcId}. A pinned ref ({@code poi:alias})
     * always yields that exact spot; a bare POI name yields the anchor or the first alias not
     * held by another NPC (falling back to the anchor when everything is taken — NPCs sharing
     * an over-booked POI still show up, just less spread out).
     *
     * @return granted slot, or null when the ref doesn't resolve
     */
    public Slot reserveSlot(String ref, String npcId) {
        if (ref == null || ref.isBlank() || npcId == null) {
            return null;
        }
        String[] parts = ref.split(":", 2);
        String poiName = parts[0].toLowerCase(Locale.ROOT);
        QuestPoi poi = get(poiName);
        if (poi == null) {
            return null;
        }
        releaseSlots(npcId);
        if (parts.length > 1) {
            String alias = parts[1].toLowerCase(Locale.ROOT);
            WorldPoint point = poi.spot(alias);
            if (point == null) {
                return null;
            }
            Slot slot = new Slot(poiName, alias, point);
            reservations.put(slot.key(), npcId);
            return slot;
        }
        List<Slot> candidates = new ArrayList<>();
        candidates.add(new Slot(poiName, null, poi.anchor));
        for (Map.Entry<String, WorldPoint> alias : poi.aliases.entrySet()) {
            candidates.add(new Slot(poiName, alias.getKey(), alias.getValue()));
        }
        for (Slot candidate : candidates) {
            String holder = reservations.get(candidate.key());
            if (holder == null || holder.equals(npcId)) {
                reservations.put(candidate.key(), npcId);
                return candidate;
            }
        }
        Slot fallback = candidates.get(0);
        reservations.put(fallback.key() + "#shared:" + npcId, npcId);
        return fallback;
    }

    /** Frees every slot held by {@code npcId} (leaving a POI, going home, despawn). */
    public void releaseSlots(String npcId) {
        if (npcId == null) {
            return;
        }
        reservations.entrySet().removeIf(entry -> npcId.equals(entry.getValue()));
    }

    /** Current holder of a slot key, for {@code /quest debug}. */
    public Map<String, String> reservationsSnapshot() {
        return Map.copyOf(reservations);
    }

    // --- Persistence -----------------------------------------------------------------------

    /**
     * Replaces the in-memory pool with repository rows already filtered to {@code decorationScope}.
     * Stamps blank legacy payloads with the active scope so later upserts keep the composite key.
     */
    public void applyLoaded(Map<String, String> payloadsByName, String decorationScope) {
        pois.clear();
        if (payloadsByName == null) {
            return;
        }
        String scope = decorationScope == null || decorationScope.isBlank() ? "lobby" : decorationScope.trim().toLowerCase(Locale.ROOT);
        payloadsByName.forEach((name, json) -> {
            try {
                QuestPoi poi = GSON.fromJson(json, QuestPoi.class);
                if (poi != null && poi.anchor != null) {
                    poi.name = name.toLowerCase(Locale.ROOT);
                    if (poi.aliases == null) {
                        poi.aliases = new LinkedHashMap<>();
                    }
                    poi.scope = scope;
                    pois.put(poi.name, poi);
                }
            } catch (RuntimeException ex) {
                logger.warning("[QuestLocations] Skipping malformed POI row '" + name + "': " + ex.getMessage());
            }
        });
    }
}
