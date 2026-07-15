package network.skypvp.paper.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable model describing a network NPC: its unique {@code id}, display name,
 * entity type, world {@code location}, click action, optional skin, glow, and
 * hologram lines. Subclassed per-gamemode (e.g. {@code LobbyNpcDefinition}).
 */
public class NpcDefinition {
   /** Decoration scope ({@code server_id} column). Not persisted on the row itself when scoped queries omit it. */
   public String scope;
   public String id = "npc";
   public String displayName = "<gold><bold>NPC</bold></gold>";
   /**
    * Bukkit {@link org.bukkit.entity.EntityType} name, {@code PLAYER}, or a block prop type such as
    * {@code BLOCK:CHEST} / {@code CHEST} (any placeable {@link org.bukkit.Material} block).
    */
   public String entityType = "VILLAGER";
   public WorldPoint location = new WorldPoint();
   public String actionType = "NONE";
   public String actionData = "";
   public String skinUrl = null;
   public String skinSignature = null;
   public boolean glow = false;
   public String glowColor = null;
   public boolean facePlayer = false;
   /** When true, interacting with this NPC toggles a {@code WaypointNavigator} to its location. */
   public boolean navigator = false;
   public double scale = 1.0;
   public List<String> hologramLines = new ArrayList<>();
   /** Display options applied to the NPC's synthesized {@code <id>_holo} hologram. */
   public boolean hologramBackground = false;
   public boolean hologramSeeThrough = false;
   public boolean hologramShadowed = false;
   public String hologramAlignment = "CENTER";
   public float hologramViewRange = 1.0F;
   public boolean hologramFreeze = true;
   public String hologramBillboard = "CENTER";
   public double hologramScale = 1.0;

   public NpcDefinition() {
   }
}
