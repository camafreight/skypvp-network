package network.skypvp.paper.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable model describing the server-wide player nametag layout rendered above every player's head.
 *
 * <p>Each entry in {@code lines} is a MiniMessage template. Lines support PlaceholderAPI placeholders,
 * built-in placeholders ({@code %health%}, {@code %max_health%}, {@code %food%}, {@code %level%},
 * {@code %world%}, {@code %ping%}, {@code %player_name%}), item lines ({@code <item:MATERIAL[:scale]>}),
 * hologram-style animation tags ({@code <anim:glow|rainbow|blink|scroll|typewriter>}) and an entity glow
 * outline tag ({@code <glow:color>}).
 */
public class NametagDefinition {
   /** Decoration scope bucket this row is stored under in PostgreSQL ({@code global}, {@code extraction}, ...). */
   public String scope;
   public boolean enabled = true;
   /**
    * Decoration scopes this layout renders on ({@code global} = every server, {@code extraction},
    * {@code lobby}, ...). Multiple scopes may be listed; an empty list behaves like {@code global}.
    */
   public List<String> scopes = new ArrayList<>(List.of("global"));
   public List<String> lines = new ArrayList<>();
   /** Vertical offset (blocks) of the lowest line above the player's head. */
   public double baseHeight = 0.32;
   /** Vertical spacing (blocks) between lines, multiplied by {@link #scale}. */
   public double lineSpacing = 0.27;
   public float scale = 1.0F;
   /** Placeholder refresh period in ticks (animated lines refresh faster automatically). */
   public int refreshTicks = 20;
   /** Hide the vanilla name above heads so it does not double up with the custom tag. */
   public boolean hideVanillaName = true;
   /** Whether players can see their own tag (off by default, like the vanilla nametag). */
   public boolean visibleToSelf = false;
   /** Render the vanilla-style translucent background behind text lines. */
   public boolean background = true;

   public NametagDefinition() {
   }

   public NametagDefinition copy() {
      NametagDefinition copy = new NametagDefinition();
      copy.scope = this.scope;
      copy.enabled = this.enabled;
      copy.scopes = new ArrayList<>(this.scopes == null ? List.of() : this.scopes);
      copy.lines = new ArrayList<>(this.lines == null ? List.of() : this.lines);
      copy.baseHeight = this.baseHeight;
      copy.lineSpacing = this.lineSpacing;
      copy.scale = this.scale;
      copy.refreshTicks = this.refreshTicks;
      copy.hideVanillaName = this.hideVanillaName;
      copy.visibleToSelf = this.visibleToSelf;
      copy.background = this.background;
      return copy;
   }
}
