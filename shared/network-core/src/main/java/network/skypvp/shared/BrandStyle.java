package network.skypvp.shared;

/**
 * Centralized brand colour palette for the SkyPvP network.
 * Contains only plain Java constants so the shared module stays free of
 * Adventure / platform dependencies.  Each hex string is a valid input for
 * Adventure's {@code TextColor.fromHexString()} on both proxy and backend.
 */
public final class BrandStyle {

    // ── Network gradient (amber-gold) ──────────────────────────────────────
    public static final String BRAND_FROM    = "#FFB300";
    public static final String BRAND_MID     = "#FFA000";
    public static final String BRAND_TO      = "#FF6F00";

    // ── Rank hex colours ───────────────────────────────────────────────────
    public static final String RANK_DEFAULT      = "#AAAAAA";
    public static final String RANK_VIP          = "#55FF55";
    public static final String RANK_VIP_PLUS     = "#00FFFF";
    public static final String RANK_MVP          = "#5599FF";
    public static final String RANK_MVP_PLUS_FROM = "#AA00FF";
    public static final String RANK_MVP_PLUS_TO  = "#FF55FF";
    public static final String RANK_LEGEND_FROM  = "#FF8800";
    public static final String RANK_LEGEND_TO    = "#FFDD00";
    public static final String RANK_STAFF        = "#FF5555";
    public static final String RANK_MOD          = "#FF9900";
    public static final String RANK_ADMIN        = "#CC0000";
    public static final String RANK_OWNER        = "#FF0000";

    // ── UI tones ──────────────────────────────────────────────────────────
    public static final String TEXT_MUTED   = "#888888";
    public static final String TEXT_BRIGHT  = "#FFFFFF";
    public static final String TEXT_ACCENT  = "#FFD700";
    public static final String TEXT_DIVIDER = "#555555";

    private BrandStyle() {}

    /**
     * Returns the primary hex colour string for a given {@code rankKey}
     * (case-insensitive).  This is the single source of truth for rank
     * colour on both the proxy and the backend.
     */
    public static String hexForRankKey(String rankKey) {
        if (rankKey == null) return RANK_DEFAULT;
        return switch (rankKey.toLowerCase()) {
            case "vip"                       -> RANK_VIP;
            case "vip+"                      -> RANK_VIP_PLUS;
            case "mvp"                       -> RANK_MVP;
            case "mvp+", "mvp++"            -> RANK_MVP_PLUS_FROM;
            case "legend"                    -> RANK_LEGEND_FROM;
            case "helper"                    -> RANK_MOD;
            case "mod", "moderator", "staff" -> RANK_STAFF;
            case "admin"                     -> RANK_ADMIN;
            case "owner", "founder"         -> RANK_OWNER;
            default                          -> RANK_DEFAULT;
        };
    }

    /**
     * Builds a MiniMessage gradient string for the rank prefix.
     * Staff/admin/owner ranks return a solid bold colour rather than gradient.
     */
    public static String miniMessagePrefix(String rankKey, String prefixText) {
        if (prefixText == null || prefixText.isBlank()) return "";
        String key = rankKey == null ? "" : rankKey.toLowerCase();
        return switch (key) {
            case "mvp+", "mvp++" ->
                    "<gradient:" + RANK_MVP_PLUS_FROM + ":" + RANK_MVP_PLUS_TO + "><bold>"
                            + prefixText + "</bold></gradient>";
            case "legend" ->
                    "<gradient:" + RANK_LEGEND_FROM + ":" + RANK_LEGEND_TO + "><bold>"
                            + prefixText + "</bold></gradient>";
            case "owner", "founder" ->
                    "<" + RANK_OWNER + "><bold>" + prefixText + "</bold><reset>";
            default -> {
                String hex = hexForRankKey(rankKey);
                yield "<" + hex + "><bold>" + prefixText + "</bold><reset>";
            }
        };
    }

    /**
     * Builds the MiniMessage tag opening for a rank's name colour
     * (no bold, just the colour).
     */
    public static String miniMessageNameColor(String rankKey) {
        return hexForRankKey(rankKey);
    }

    /**
     * Returns the message text colour hex that a player of {@code rankKey}
     * should use in chat.  Gradient ranks get a distinct warm or cool tint so
     * that high-priority chat messages stand out without being overpowering.
     *
     * @param rankKey the rank key (case-insensitive)
     * @return hex colour string suitable for Adventure TextColor.fromHexString
     */
    public static String chatMessageColor(String rankKey) {
        if (rankKey == null) return "#AAAAAA";
        return switch (rankKey.toLowerCase()) {
            case "owner", "founder"          -> "#FFCCCC"; // warm pink-white
            case "admin"                     -> "#FFB0B0"; // soft red-pink
            case "mod", "moderator", "staff" -> "#FFCFCF"; // gentle staff tint
            case "helper"                    -> "#FFF0CC"; // warm amber hint
            case "legend"                    -> "#FFE0A0"; // warm gold
            case "mvp++", "mvp+"            -> "#CCE0FF"; // cool blue
            case "mvp"                       -> "#DDEEFF"; // lighter blue
            case "vip+"                      -> "#CCFFEE"; // teal-green
            case "vip"                       -> "#CCFFCC"; // light green
            default                          -> "#AAAAAA"; // default gray
        };
    }
}
