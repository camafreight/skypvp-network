package network.skypvp.extraction.crafting;

/** Material stash container settings (separate from gear vault). */
public final class MaterialStashConstants {

    public static final String CONTAINER = "MATERIAL_STASH";
    /** Stash stacks are not capped at 64 — materials consolidate here. */
    public static final int MAX_STACK_SIZE = 1_000_000;
    public static final int MAX_SLOTS = 36;

    private MaterialStashConstants() {
    }
}
