package network.skypvp.paper.library;

import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;

public final class NpcBlockPropHandle {

    private final String id;
    private final Location blockAnchor;
    private final BlockDisplay display;
    private final Interaction interaction;

    public NpcBlockPropHandle(String id, Location blockAnchor, BlockDisplay display, Interaction interaction) {
        this.id = id;
        this.blockAnchor = blockAnchor.clone();
        this.display = display;
        this.interaction = interaction;
    }

    public String id() {
        return id;
    }

    public Location blockAnchor() {
        return blockAnchor.clone();
    }

    public BlockDisplay display() {
        return display;
    }

    public Interaction interaction() {
        return interaction;
    }

    public void remove() {
        if (display != null && display.isValid()) {
            display.remove();
        }
        if (interaction != null && interaction.isValid()) {
            interaction.remove();
        }
    }

    public boolean isValid() {
        return display != null && display.isValid() && !display.isDead()
                && interaction != null && interaction.isValid() && !interaction.isDead();
    }
}
