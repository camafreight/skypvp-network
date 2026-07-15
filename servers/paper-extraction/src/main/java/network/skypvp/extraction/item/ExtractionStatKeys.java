package network.skypvp.extraction.item;

/** Named custom stats contributed by Infuse gear and modules while equipped. */
public final class ExtractionStatKeys {

    public static final String STAMINA_MAX_MULT = "extraction:stamina_max_mult";
    public static final String STAMINA_REGEN_MULT = "extraction:stamina_regen_mult";
    public static final String STAMINA_DRAIN_MULT = "extraction:stamina_drain_mult";
    /** Applied as {@link org.bukkit.attribute.Attribute#MOVEMENT_SPEED} MULTIPLY_SCALAR_1 on armor set bonuses. */
    public static final String MOVEMENT_SPEED_MULT = "extraction:movement_speed_mult";

    private ExtractionStatKeys() {
    }
}
