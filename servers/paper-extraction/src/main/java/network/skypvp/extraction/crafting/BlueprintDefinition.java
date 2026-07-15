package network.skypvp.extraction.crafting;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/** One craftable blueprint loaded from JSON and/or generated at runtime. */
public record BlueprintDefinition(
        String id,
        BlueprintCategory category,
        String displayName,
        Material icon,
        boolean starterDiscovered,
        List<MaterialCost> materials,
        BlueprintOutput output
) {

    public record MaterialCost(String materialId, int amount) {
    }

    public record BlueprintOutput(OutputType type, String recipeKey) {
    }

    public enum OutputType {
        ARMOR,
        SHIELD,
        MODULE,
        MEDIC,
        RECHARGER,
        SHIELD_REPAIR_KIT,
        WEAPON
    }
}
