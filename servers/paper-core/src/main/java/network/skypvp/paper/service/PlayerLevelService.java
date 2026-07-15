package network.skypvp.paper.service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.PlayerLevelRepository;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Network player level system: XP + levels rendered on the vanilla experience bar, gating custom
 * item/armor tiers, with prestige-style badge milestones.
 *
 * <p><b>Experience bar takeover</b> — the vanilla bar is hijacked as a pure display:
 * <ul>
 *   <li>{@link PlayerExpChangeEvent} is zeroed so orbs never move the bar (Mending repairs happen
 *       BEFORE the event's residual XP is added, so gear repair still works).</li>
 *   <li>{@link PlayerLevelChangeEvent} re-asserts our level when something external (death, /xp,
 *       another plugin) changes it — guarded by a reentrancy flag so our own writes don't loop.</li>
 *   <li>Respawns and world changes re-apply one tick later: the client resets the bar on those
 *       packets and immediate writes race the respawn state.</li>
 * </ul>
 */
public final class PlayerLevelService implements Listener {

    public static final int MAX_LEVEL = 100;

    /** XP required to complete a level: linear ramp keeps early levels quick, late levels a grind. */
    static long xpToCompleteLevel(int level) {
        return 80L + (long) (Math.max(1, level) - 1) * 25L;
    }

    /** Total XP required to REACH a level (level 1 = 0 XP). */
    public static long totalXpForLevel(int level) {
        int clamped = Math.max(1, Math.min(level, MAX_LEVEL));
        long total = 0L;
        for (int current = 1; current < clamped; current++) {
            total += xpToCompleteLevel(current);
        }
        return total;
    }

    public static int levelForXp(long xp) {
        int level = 1;
        long remaining = Math.max(0L, xp);
        while (level < MAX_LEVEL && remaining >= xpToCompleteLevel(level)) {
            remaining -= xpToCompleteLevel(level);
            level++;
        }
        return level;
    }

    private static final class LevelState {
        volatile long xp;
        volatile int prestige;
        volatile boolean loaded;
    }

    private final PaperCorePlugin plugin;
    private final PlayerLevelRepository repository;
    private final Map<UUID, LevelState> states = new ConcurrentHashMap<>();
    /** Players whose bar WE are currently writing (suppresses the level-change re-assert). */
    private final Set<UUID> applyingBar = ConcurrentHashMap.newKeySet();

    /** @param repository nullable — without Postgres the system still runs, session-only. */
    public PlayerLevelService(PaperCorePlugin plugin, PlayerLevelRepository repository) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.repository = repository;
    }

    public void start() {
        if (repository != null) {
            repository.ensureSchema().exceptionally(error -> {
                plugin.getLogger().warning("[Levels] Failed to prepare player_levels table: " + error.getMessage());
                return null;
            });
        }
    }

    // --- Public API ---------------------------------------------------------------------------

    public int level(UUID playerId) {
        LevelState state = playerId == null ? null : states.get(playerId);
        return state == null ? 1 : levelForXp(state.xp);
    }

    public long xp(UUID playerId) {
        LevelState state = playerId == null ? null : states.get(playerId);
        return state == null ? 0L : state.xp;
    }

    public int prestige(UUID playerId) {
        LevelState state = playerId == null ? null : states.get(playerId);
        return state == null ? 0 : state.prestige;
    }

    /**
     * Gate check for custom item/armor tiers. When {@code notify} is set, denial plays a soft cue
     * and tells the player which level (with its badge) the tier unlocks at.
     */
    public boolean meetsLevel(Player player, int requiredLevel, boolean notify) {
        if (player == null) {
            return false;
        }
        int current = level(player.getUniqueId());
        if (current >= requiredLevel) {
            return true;
        }
        if (notify) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8F, 0.6F);
            player.sendMessage(Component.text()
                    .append(PlayerLevelBadges.badge(requiredLevel))
                    .append(Component.text(" Requires level ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(requiredLevel), PlayerLevelBadges.tierColor(requiredLevel)))
                    .append(Component.text(" — you are level " + current + ".", NamedTextColor.GRAY))
                    .build());
        }
        return false;
    }

    /** Badge + level line for embedding in menus, tab, holograms. */
    public Component badgeLine(UUID playerId) {
        int level = level(playerId);
        return Component.text()
                .append(PlayerLevelBadges.badge(level))
                .append(Component.text(" " + level, PlayerLevelBadges.tierColor(level)))
                .build();
    }

    /**
     * Grants XP with full feedback (bar fill, magnet particles, badge pulse, level-up celebration).
     * Call from any thread; work lands on the player's region thread.
     */
    public void addXp(Player player, long amount, String reason) {
        if (player == null || amount <= 0L) {
            return;
        }
        plugin.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            LevelState state = states.computeIfAbsent(player.getUniqueId(), ignored -> new LevelState());
            int levelBefore = levelForXp(state.xp);
            long cap = totalXpForLevel(MAX_LEVEL);
            state.xp = Math.min(cap, state.xp + amount);
            int levelAfter = levelForXp(state.xp);
            persist(player.getUniqueId(), state);
            applyBar(player);
            playXpGain(player, amount, levelAfter);
            if (levelAfter > levelBefore) {
                celebrateLevelUp(player, levelAfter);
            }
        });
    }

    // --- Vanilla bar takeover -----------------------------------------------------------------

    /** Renders the custom level + progress on the vanilla experience bar. */
    public void applyBar(Player player) {
        LevelState state = states.get(player.getUniqueId());
        long xp = state == null ? 0L : state.xp;
        int level = levelForXp(xp);
        long intoLevel = xp - totalXpForLevel(level);
        float progress = level >= MAX_LEVEL
                ? 0.999F
                : (float) Math.max(0.0D, Math.min(0.999D, intoLevel / (double) xpToCompleteLevel(level)));
        UUID playerId = player.getUniqueId();
        applyingBar.add(playerId);
        try {
            player.setLevel(level);
            player.setExp(progress);
        } finally {
            applyingBar.remove(playerId);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        LevelState state = states.computeIfAbsent(playerId, ignored -> new LevelState());
        applyBarLater(player);
        if (repository == null || state.loaded) {
            return;
        }
        repository.load(playerId).whenComplete((row, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "[Levels] Load failed for " + player.getName(), error);
                return;
            }
            state.xp = row.xp();
            state.prestige = row.prestige();
            state.loaded = true;
            applyBarLater(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /** Orbs feed nothing into the display bar; Mending already consumed what it needed. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExpChange(PlayerExpChangeEvent event) {
        event.setAmount(0);
    }

    /** External level writes (death, /xp, plugins) get overwritten with the custom level. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelChange(PlayerLevelChangeEvent event) {
        if (applyingBar.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        applyBarLater(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        applyBarLater(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        applyBarLater(event.getPlayer());
    }

    private void applyBarLater(Player player) {
        plugin.platformScheduler().runOnPlayerLater(player, () -> {
            if (player.isOnline()) {
                applyBar(player);
            }
        }, 2L);
    }

    private void persist(UUID playerId, LevelState state) {
        if (repository == null) {
            return;
        }
        repository.save(playerId, state.xp, state.prestige).exceptionally(error -> {
            plugin.getLogger().warning("[Levels] Save failed for " + playerId + ": " + error.getMessage());
            return null;
        });
    }

    // --- Feedback -----------------------------------------------------------------------------

    /**
     * Magnet-style gain: enchant glyph particles stream INTO the player's chest (the ENCHANT
     * particle moves from its offset toward the spawn point), orb chimes, and the badge pulses
     * between its normal and glow frame on the action bar with the gained amount.
     */
    private void playXpGain(Player player, long amount, int level) {
        Location chest = player.getLocation().add(0.0D, 1.1D, 0.0D);
        player.getWorld().spawnParticle(Particle.ENCHANT, chest, 28, 1.7D, 1.1D, 1.7D, 0.9D);
        player.playSound(chest, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55F, 1.35F);
        plugin.platformScheduler().runOnPlayerLater(player, () -> {
            if (player.isOnline()) {
                player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0.0D, 1.1D, 0.0D), 16, 0.9D, 0.7D, 0.9D, 0.7D);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5F, 1.7F);
            }
        }, 4L);
        TextColor color = PlayerLevelBadges.tierColor(level);
        // 6 action-bar frames, 4 ticks apart: badge <-> glow with the amount, reading as absorption.
        for (int frame = 0; frame < 6; frame++) {
            boolean glow = (frame & 1) == 0;
            long delay = frame * 4L;
            plugin.platformScheduler().runOnPlayerLater(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                Component badge = glow ? PlayerLevelBadges.badgeGlow(level) : PlayerLevelBadges.badge(level);
                player.sendActionBar(Component.text()
                        .append(badge)
                        .append(Component.text(" +" + amount + " XP", color))
                        .build());
            }, delay);
        }
    }

    private void celebrateLevelUp(Player player, int newLevel) {
        boolean evolved = PlayerLevelBadges.isTierMilestone(newLevel);
        TextColor color = PlayerLevelBadges.tierColor(newLevel);
        Component main = Component.text()
                .append(PlayerLevelBadges.badge(newLevel))
                .append(Component.text("  LEVEL " + newLevel, color))
                .build();
        Component subtitle = evolved
                ? Component.text("BADGE EVOLVED", color)
                : Component.text("Level up!", NamedTextColor.GRAY);
        player.showTitle(Title.title(
                main,
                subtitle,
                Title.Times.times(
                        java.time.Duration.ofMillis(200),
                        java.time.Duration.ofMillis(evolved ? 2600 : 1800),
                        java.time.Duration.ofMillis(400))));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.9F, 1.1F);
        if (evolved) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8F, 1.0F);
            player.getWorld().spawnParticle(Particle.END_ROD, player.getLocation().add(0.0D, 1.2D, 0.0D), 40, 0.8D, 1.0D, 0.8D, 0.08D);
        }
    }
}
