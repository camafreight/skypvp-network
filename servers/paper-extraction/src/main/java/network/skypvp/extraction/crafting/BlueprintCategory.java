package network.skypvp.extraction.crafting;

/** Blueprint browser categories for the global craft workbench. */
public enum BlueprintCategory {
    ALL("All", org.bukkit.Material.BOOK),
    ARMOR("Armor", org.bukkit.Material.NETHERITE_CHESTPLATE),
    MODULE("Modules", org.bukkit.Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
    WEAPONS("Weapons", org.bukkit.Material.CROSSBOW),
    HEALING("Healing", org.bukkit.Material.WHITE_WOOL),
    STAMINA("Stamina", org.bukkit.Material.HONEY_BOTTLE),
    MISC("Misc", org.bukkit.Material.PAPER);

    private final String title;
    private final org.bukkit.Material icon;

    BlueprintCategory(String title, org.bukkit.Material icon) {
        this.title = title;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public org.bukkit.Material icon() {
        return icon;
    }

    public BlueprintCategory next() {
        BlueprintCategory[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
