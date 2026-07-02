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
   public String entityType = "VILLAGER";
   public WorldPoint location = new WorldPoint();
   public String actionType = "NONE";
   public String actionData = "";
   public String skinUrl = null;
   public String skinSignature = null;
   public boolean glow = false;
   public String glowColor = null;
   public boolean facePlayer = false;
   public double scale = 1.0;
   public List<String> hologramLines = new ArrayList<>();

   public NpcDefinition() {
   }
}
