package network.skypvp.paper.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Cyclic background animation for managed GUIs. Each frame updates only the slots it defines;
 * button slots rendered by {@link GuiMenuBuilder} are layered on top and are not overwritten.
 */
public final class GuiAnimation {

    private final long periodTicks;
    private final List<GuiTextureFrame> frames;

    private GuiAnimation(long periodTicks, List<GuiTextureFrame> frames) {
        this.periodTicks = Math.max(1L, periodTicks);
        this.frames = List.copyOf(frames);
        if (this.frames.isEmpty()) {
            throw new IllegalArgumentException("GuiAnimation requires at least one frame");
        }
    }

    public static Builder builder(long periodTicks) {
        return new Builder(periodTicks);
    }

    public long periodTicks() {
        return this.periodTicks;
    }

    public int frameCount() {
        return this.frames.size();
    }

    public void apply(Inventory inventory, int frameIndex) {
        if (inventory == null) {
            return;
        }
        GuiTextureFrame frame = this.frames.get(Math.floorMod(frameIndex, this.frames.size()));
        for (Map.Entry<Integer, ItemStack> entry : frame.items().entrySet()) {
            ItemStack item = entry.getValue();
            inventory.setItem(entry.getKey(), item == null ? null : item.clone());
        }
    }

    public static final class Builder {
        private final long periodTicks;
        private final List<GuiTextureFrame> frames = new ArrayList<>();

        private Builder(long periodTicks) {
            this.periodTicks = periodTicks;
        }

        public Builder frame(GuiTextureFrame frame) {
            this.frames.add(Objects.requireNonNull(frame, "frame"));
            return this;
        }

        public GuiAnimation build() {
            return new GuiAnimation(this.periodTicks, this.frames);
        }
    }
}
