package network.skypvp.extraction.crafting;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;

/** Catalog entry for a hub crafting material (stored in player stash, not vanilla items). */
public record CraftingMaterialDefinition(
        String id,
        String displayName,
        Material icon,
        CraftingMaterialTier tier,
        String description
) {

    public static Optional<CraftingMaterialDefinition> byId(String raw, Iterable<CraftingMaterialDefinition> catalog) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CraftingMaterialDefinition def : catalog) {
            if (def.id().equals(normalized)) {
                return Optional.of(def);
            }
        }
        return Optional.empty();
    }
}
