package network.skypvp.paper.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable model describing a single hologram: its unique {@code id}, world
 * {@code anchor}, text {@code lines}, interaction settings, and optional
 * parent/offset wiring used for multi-line or child holograms.
 */
public class HologramDefinition {
   /** Decoration scope ({@code server_id} column). */
   public String scope;
   public String id = "hologram";
   public WorldPoint anchor = new WorldPoint();
   public List<String> lines = new ArrayList<>();
   public boolean interactive = false;
   public int hitboxSize = 1;
   public String actionType = "NONE";
   public String actionData = "";
   public String parentId = null;
   public double offsetX = 0.0;
   public double offsetY = 0.0;
   public double offsetZ = 0.0;
   public boolean perPlayer = false;
   public String billboard = "CENTER";
   public double scale = 1.0;
   /** Flat semi-transparent TextDisplay background (ARGB 64,0,0,0 when true). */
   public boolean background = false;
   public boolean seeThrough = false;
   public boolean shadowed = false;
   /** LEFT, CENTER, or RIGHT. */
   public String textAlignment = "CENTER";
   /** Display entity view range multiplier (Paper), typically 0.1–5.0. */
   public float viewRange = 1.0F;
   /** When true, interpolation/teleport durations stay 0 so lines snap instead of lerping. */
   public boolean freeze = true;

   public HologramDefinition() {
   }
}
