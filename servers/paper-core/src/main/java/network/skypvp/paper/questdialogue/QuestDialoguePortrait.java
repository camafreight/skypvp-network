package network.skypvp.paper.questdialogue;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * Viewer-only player-head {@link ItemDisplay} that approximates the action-bar portrait hole.
 * Destroyed when dialogue ends.
 */
final class QuestDialoguePortrait {

    private final PaperCorePlugin core;
    private final ConcurrentHashMap<UUID, ItemDisplay> portraits = new ConcurrentHashMap<>();

    QuestDialoguePortrait(PaperCorePlugin core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    boolean hasPortrait(UUID playerId) {
        ItemDisplay display = portraits.get(playerId);
        return display != null && display.isValid() && !display.isDead();
    }

    void spawn(Player viewer, PlayerProfile profile) {
        if (viewer == null || profile == null) {
            return;
        }
        remove(viewer.getUniqueId());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(copyTextures(profile));
        head.setItemMeta(meta);

        Location spawnAt = portraitLocation(viewer);
        ItemDisplay display = viewer.getWorld().spawn(spawnAt, ItemDisplay.class, entity -> {
            entity.setVisibleByDefault(false);
            entity.setItemStack(head);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(org.bukkit.entity.Display.Billboard.CENTER);
            entity.setInterpolationDuration(0);
            entity.setTeleportDuration(1);
            entity.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(0.35f, 0.35f, 0.35f),
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));
            entity.setPersistent(false);
            entity.addScoreboardTag("skypvp_quest_dialogue_portrait");
        });
        viewer.showEntity(core, display);
        portraits.put(viewer.getUniqueId(), display);
    }

    void tick(Player viewer) {
        if (viewer == null) {
            return;
        }
        ItemDisplay display = portraits.get(viewer.getUniqueId());
        if (display == null || !display.isValid() || display.isDead()) {
            portraits.remove(viewer.getUniqueId(), display);
            return;
        }
        Location target = portraitLocation(viewer);
        if (display.getWorld() != null && display.getWorld().equals(target.getWorld())) {
            display.teleportAsync(target);
        }
    }

    void remove(UUID playerId) {
        if (playerId == null) {
            return;
        }
        ItemDisplay display = portraits.remove(playerId);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    static PlayerProfile profileOf(Entity speaker) {
        if (speaker instanceof Player player) {
            return copyTextures(player.getPlayerProfile());
        }
        return null;
    }

    private static PlayerProfile copyTextures(PlayerProfile source) {
        if (source == null) {
            return null;
        }
        String name = source.getName() == null ? "speaker" : source.getName();
        UUID id = source.getId() == null ? UUID.randomUUID() : source.getId();
        PlayerProfile clean = org.bukkit.Bukkit.createProfile(id, name);
        for (ProfileProperty property : source.getProperties()) {
            if ("textures".equals(property.getName())) {
                clean.setProperty(new ProfileProperty("textures", property.getValue(), property.getSignature()));
                break;
            }
        }
        return clean;
    }

    private static Location portraitLocation(Player viewer) {
        Location eye = viewer.getEyeLocation();
        Vector forward = eye.getDirection().normalize();
        Vector right = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        } else {
            right.normalize();
        }
        Vector up = right.clone().crossProduct(forward).normalize();
        // Lower-left of view — aligns with speech-panel portrait cutout (BetonQuest layout).
        return eye.clone()
                .add(forward.multiply(0.78))
                .add(right.multiply(-0.55))
                .add(up.multiply(-0.32));
    }
}
