package network.skypvp.extraction.crafting;

import java.util.List;

/** Snapshot returned after reloading crafting JSON configs. */
public record CraftingReloadResult(
        int materials,
        int blueprints,
        int blackMarketListings,
        int itemOverrides
) {
}
