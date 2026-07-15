package network.skypvp.paper.questdialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.platform.PlatformTask;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Non-blocking NPC callout: a floating dialogue board in front of the player that scales with
 * distance to the anchor (NPC).
 *
 * <p><b>Prefer {@link network.skypvp.paper.waypoint.WaypointNavigatorService}</b> for guiding players
 * to a location (beams + destination holograms). This shout board remains for short typewriter callouts
 * that are not navigation.
 *
 * <p>Folia-safe by construction: each board line is a {@link TextDisplay} riding the player as
 * a passenger, so the displays are always owned by the player's region (the same thread the
 * per-player ticker runs on) and follow across region boundaries with zero teleports. The
 * board position in front of the player is expressed through the display transformation's
 * translation, updated each tick. Boards are per-player (spawned hidden, shown only to the
 * owning viewer) so any number of players can have independent shouts simultaneously.
 */
public final class QuestDialogueShoutService implements Listener {

    private static final double LINE_SPACING = 0.32D;
    private static final double BOARD_DISTANCE = 2.4D;
    private static final long MS_PER_CHAR = 50L;
    private static final long HOLD_AFTER_REVEAL_MS = 4_500L;
    private static final float MIN_SCALE = 0.55F;
    private static final float MAX_SCALE = 1.85F;
    private static final double MIN_DISTANCE = 2.0D;
    private static final double MAX_DISTANCE = 28.0D;
    /** Vertical offset from the player-passenger mount point down to eye-ish board height. */
    private static final double MOUNT_TO_BOARD_Y = -0.75D;

    private final PaperCorePlugin core;
    private final Map<UUID, ActiveShout> active = new ConcurrentHashMap<>();

    public QuestDialogueShoutService(PaperCorePlugin core) {
        this.core = Objects.requireNonNull(core, "core");
        core.getServer().getPluginManager().registerEvents(this, core);
    }

    public boolean isShouting(UUID playerId) {
        return active.containsKey(playerId);
    }

    public void shout(Player player, Location anchor, String npcDisplayName, List<String> lines) {
        if (player == null || lines == null || lines.isEmpty()) {
            return;
        }
        end(player);
        List<String> wrapped = DialogueText.wrapLines(lines);
        if (wrapped.isEmpty()) {
            return;
        }
        ActiveShout shout = new ActiveShout(
                player.getUniqueId(),
                anchor == null ? null : anchor.clone(),
                npcDisplayName == null ? "NPC" : npcDisplayName,
                wrapped,
                System.currentTimeMillis()
        );
        active.put(player.getUniqueId(), shout);
        // Spawn + mount on the player's own region thread; passengers then stay owned
        // by that region wherever the player goes.
        core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline() || active.get(player.getUniqueId()) != shout) {
                return;
            }
            spawnDisplays(player, shout);
            shout.task = core.platformScheduler().runOnPlayerTimer(player, () -> tick(player), 1L, 2L);
        });
    }

    public void end(Player player) {
        if (player == null) {
            return;
        }
        end(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        end(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        // Death ejects passengers; drop the board instead of leaving lines at the corpse.
        end(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        end(event.getPlayer().getUniqueId());
    }

    private void end(UUID playerId) {
        ActiveShout shout = active.remove(playerId);
        if (shout == null) {
            return;
        }
        if (shout.task != null) {
            shout.task.cancel();
        }
        // Removal is per-display on each display's own scheduler: always the owning
        // region regardless of where the player (or an ejected display) ended up.
        for (TextDisplay display : shout.displays) {
            core.platformScheduler().runAtEntity(display, () -> {
                if (display.isValid()) {
                    display.remove();
                }
            });
        }
        shout.displays.clear();
    }

    private void tick(Player player) {
        ActiveShout shout = active.get(player.getUniqueId());
        if (shout == null || !player.isOnline()) {
            end(player.getUniqueId());
            return;
        }
        long now = System.currentTimeMillis();
        if (shouldEnd(shout, now)) {
            end(player.getUniqueId());
            return;
        }
        // Runs on the player's region thread; the passenger displays share that region.
        updateBoard(player, shout, now);
    }

    private void spawnDisplays(Player player, ActiveShout shout) {
        Location spawnAt = player.getLocation();
        for (int index = 0; index < shout.lines.size(); index++) {
            int lineIndex = index;
            TextDisplay display = player.getWorld().spawn(
                    spawnAt,
                    TextDisplay.class,
                    entity -> configureDisplay(entity, shout, lineIndex)
            );
            player.showEntity(core, display);
            if (!player.addPassenger(display)) {
                display.remove();
                continue;
            }
            shout.displays.add(display);
        }
        updateBoard(player, shout, shout.startedAtMillis);
    }

    private void configureDisplay(TextDisplay display, ActiveShout shout, int lineIndex) {
        display.text(lineText(shout, lineIndex, shout.startedAtMillis));
        display.setBillboard(Display.Billboard.CENTER);
        display.setVisibleByDefault(false);
        display.setSeeThrough(true);
        display.setShadowed(true);
        display.setDefaultBackground(lineIndex == 0);
        if (lineIndex == 0) {
            display.setBackgroundColor(Color.fromARGB(170, 8, 12, 24));
        } else {
            display.setBackgroundColor(Color.fromARGB(150, 8, 12, 24));
        }
        display.setLineWidth(220);
        display.setPersistent(false);
        display.setViewRange(64.0F);
        // Smooth the per-tick translation/scale updates client-side.
        display.setInterpolationDuration(2);
        display.setTransformation(new Transformation(
                new Vector3f(),
                new AxisAngle4f(),
                new Vector3f(MIN_SCALE, MIN_SCALE, MIN_SCALE),
                new AxisAngle4f()
        ));
    }

    /** Repositions the board via display translations — never teleports. */
    private void updateBoard(Player player, ActiveShout shout, long nowMillis) {
        Vector facing = facingToward(player, shout.anchor);
        float scale = scaleForDistance(distanceToAnchor(player, shout.anchor));
        float offsetX = (float) (facing.getX() * BOARD_DISTANCE);
        float offsetZ = (float) (facing.getZ() * BOARD_DISTANCE);
        for (int index = 0; index < shout.displays.size(); index++) {
            TextDisplay display = shout.displays.get(index);
            if (!display.isValid()) {
                continue;
            }
            float offsetY = (float) (MOUNT_TO_BOARD_Y + (shout.lines.size() - 1 - index) * LINE_SPACING);
            display.text(lineText(shout, index, nowMillis));
            display.setInterpolationDelay(0);
            display.setTransformation(new Transformation(
                    new Vector3f(offsetX, offsetY, offsetZ),
                    new AxisAngle4f(),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f()
            ));
        }
    }

    private static Component lineText(ActiveShout shout, int lineIndex, long nowMillis) {
        String line = shout.lines.get(lineIndex);
        String visible = typewriter(line, shout.startedAtMillis, nowMillis);
        if (lineIndex == 0) {
            return ServerTextUtil.miniMessageComponent(
                    QuestDialogueRenderer.DIALOGUE_ICON + "<aqua>" + shout.npcDisplayName + ": <white>" + visible
            );
        }
        return ServerTextUtil.miniMessageComponent("<white>" + visible);
    }

    private static String typewriter(String text, long startedAtMillis, long nowMillis) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        long elapsed = Math.max(0L, nowMillis - startedAtMillis);
        int chars = (int) Math.min(text.length(), Math.max(1L, elapsed / MS_PER_CHAR));
        return text.substring(0, chars);
    }

    private static boolean shouldEnd(ActiveShout shout, long nowMillis) {
        int totalChars = shout.lines.stream().mapToInt(String::length).sum();
        long revealMillis = totalChars * MS_PER_CHAR;
        return nowMillis - shout.startedAtMillis >= revealMillis + HOLD_AFTER_REVEAL_MS;
    }

    private static Vector facingToward(Player player, Location anchor) {
        if (anchor != null && anchor.getWorld() != null && anchor.getWorld().equals(player.getWorld())) {
            Vector toward = anchor.toVector().subtract(player.getEyeLocation().toVector());
            toward.setY(0.0D);
            if (toward.lengthSquared() > 0.04D) {
                return toward.normalize();
            }
        }
        Vector forward = player.getLocation().getDirection();
        forward.setY(0.0D);
        if (forward.lengthSquared() <= 0.04D) {
            return new Vector(0.0D, 0.0D, 1.0D);
        }
        return forward.normalize();
    }

    private static double distanceToAnchor(Player player, Location anchor) {
        if (anchor == null || anchor.getWorld() == null || !anchor.getWorld().equals(player.getWorld())) {
            return MIN_DISTANCE;
        }
        Location playerLoc = player.getLocation();
        double dx = anchor.getX() - playerLoc.getX();
        double dz = anchor.getZ() - playerLoc.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static float scaleForDistance(double distance) {
        double clamped = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, distance));
        double t = (clamped - MIN_DISTANCE) / (MAX_DISTANCE - MIN_DISTANCE);
        return (float) (MIN_SCALE + t * (MAX_SCALE - MIN_SCALE));
    }

    private static final class ActiveShout {
        private final UUID playerId;
        private final Location anchor;
        private final String npcDisplayName;
        private final List<String> lines;
        private final long startedAtMillis;
        private final List<TextDisplay> displays = new ArrayList<>();
        private PlatformTask task;

        private ActiveShout(UUID playerId, Location anchor, String npcDisplayName, List<String> lines, long startedAtMillis) {
            this.playerId = playerId;
            this.anchor = anchor;
            this.npcDisplayName = npcDisplayName;
            this.lines = List.copyOf(lines);
            this.startedAtMillis = startedAtMillis;
        }
    }
}
