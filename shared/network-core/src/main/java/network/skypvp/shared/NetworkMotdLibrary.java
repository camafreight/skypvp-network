package network.skypvp.shared;

import java.util.Objects;
import net.kyori.adventure.text.Component;

/**
 * Composable server-list MOTD builder for the SkyClub network.
 * <p>
 * <strong>Server-list MOTDs are static snapshots.</strong> Unlike in-network scoreboards,
 * tab headers, and GUIs, the ping description cannot tick or animate — each ping response
 * is one frozen image until the client refreshes the server list. This library therefore
 * uses fixed gradients, symbols, and copy only ({@link AnimatedText} and frame-based effects
 * are intentionally not used here).
 * <p>
 * Promo lines may change between ping refreshes (when a player re-opens the multiplayer
 * list), but they do not animate while on screen.
 * <p>
 * Both MOTD lines are centered with pixel-aware measurement ({@link ServerTextUtil#centerMotdMiniMessageLine(String)})
 * so bold text, unicode symbols, and gradients do not skew alignment. Line 2 automatically compacts when it would
 * overflow the drawable MOTD slot (~265px / ~45 visible chars per line in the server-list text column).
 */
public final class NetworkMotdLibrary {

    public static final String NETWORK_NAME = "SKYCLUB";
    public static final String NETWORK_DOMAIN = "play.skyclub.net";

    /** Primary headline shown when no promo override is set. */
    public static final String DEFAULT_PROMO = "AETHER BREACH OUT NOW!";

    /** Static join nudge on line 2. */
    public static final String DEFAULT_JOIN_CALLOUT = "JOIN NOW";

    /** Static brand gradient (MiniMessage) — no per-frame effects. */
    public static final String BRAND_MINI_MESSAGE =
        "<gradient:#7c3aed:#c4b5fd:#fde68a><bold>" + NETWORK_NAME + "</bold></gradient>";

    /** @see ServerTextUtil#MOTD_LINE_WIDTH_CHARS */
    public static final int LINE_WIDTH_CHARS = ServerTextUtil.MOTD_LINE_WIDTH_CHARS;

    /** @see ServerTextUtil#MOTD_LINE_WIDTH_PIXELS */
    public static final int LINE_WIDTH_PIXELS = ServerTextUtil.MOTD_LINE_WIDTH_PIXELS;

    private static final String BRAND_FLANK = "✦";
    private static final String CTA_ARROW_LEFT = "»";
    private static final String CTA_ARROW_RIGHT = "«";

    /**
     * Alternate static promos — one is chosen per ping refresh via {@link #promoTaglineForRefresh(long)}.
     * Not animated; only varies when the server list is queried again.
     */
    public static final String[] PROMO_TAGLINES = {
        "AETHER BREACH OUT NOW!",
        "NEW EXTRACTION SHOOTER",
        "LOOT • EXTRACT • SURVIVE",
        "AETHER RUINS — RAID LIVE",
        "JOIN THE BREACH TODAY",
        "EXTRACTION PODS ONLINE",
        "SEASON 1 — PLAY NOW",
        "JAVA + BEDROCK WELCOME",
        "SKYCLUB NETWORK LIVE",
        "COMPETE • RAID • ESCAPE",
    };

    private static final String MAINTENANCE_SUFFIX = "Back soon — thank you for waiting";

    private NetworkMotdLibrary() {
    }

    // ── Plain-text helpers (legacy / config authoring) ───────────────────────

    /**
     * Centers plain ASCII text with spaces for a fixed character width.
     * Prefer {@link ServerTextUtil#centerMotdMiniMessageLine(String)} for formatted MOTD lines.
     */
    public static String centerText(String plainText) {
        return ServerTextUtil.centerPlainMotdText(plainText);
    }

    public static String centerText(String plainText, int lineWidthChars) {
        return ServerTextUtil.centerPlainMotdText(plainText, lineWidthChars);
    }

    /** Wraps {@code text} with decorative brackets, e.g. {@code » SKYCLUB «}. */
    public static String bracketedName(String text, String left, String right) {
        String safe = text == null ? "" : text;
        String open = left == null ? "" : left;
        String close = right == null ? "" : right;
        return open + safe + close;
    }

    /** Returns the static SKYCLUB brand block for line 1 (MiniMessage). */
    public static String networkNameSection() {
        return networkNameSection("1.20–1.21.11");
    }

    public static String networkNameSection(String versionRange) {
        String version = versionRange == null || versionRange.isBlank()
            ? ""
            : " <#cbd5e1><bold>•</bold></#cbd5e1> <#e9d5ff>[" + versionRange.trim() + "]</#e9d5ff>";
        return "<#64748b>" + BRAND_FLANK + " </#64748b>"
            + BRAND_MINI_MESSAGE
            + version
            + " <#64748b> " + BRAND_FLANK + "</#64748b>";
    }

    /**
     * Picks one static promo for this ping response. Changes only when the client
     * refreshes the server list — not animated on screen.
     */
    public static String promoTaglineForRefresh(long epochMillis) {
        int index = (int) Math.floorMod(epochMillis / 65_000L, PROMO_TAGLINES.length);
        return PROMO_TAGLINES[index];
    }

    /** @deprecated use {@link #promoTaglineForRefresh(long)} — MOTD promos are not animated */
    @Deprecated
    public static String promoTagline(long tickMillis) {
        return promoTaglineForRefresh(tickMillis);
    }

    public static String joinCallout() {
        return DEFAULT_JOIN_CALLOUT;
    }

    /** Static styled promo segment with gradient emphasis (no motion). */
    public static String callToAction(String promoText) {
        String text = promoText == null || promoText.isBlank() ? DEFAULT_PROMO : promoText.trim();
        return "<#c4b5fd>" + CTA_ARROW_LEFT + "</#c4b5fd> "
            + "<gradient:#22d3ee:#a78bfa:#fbbf24><bold>" + text + "</bold></gradient> "
            + "<#c4b5fd>" + CTA_ARROW_RIGHT + "</#c4b5fd>";
    }

    /** Compact extraction telemetry segment for line 2. */
    public static String extractionStatsSection(int extractionPods, int openBreachSlots, int queuedPlayers) {
        if (extractionPods <= 0 && openBreachSlots <= 0 && queuedPlayers <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        if (extractionPods > 0) {
            out.append("<#ddd6fe><bold>").append(extractionPods).append("</bold> pod").append(extractionPods == 1 ? "" : "s").append("</#ddd6fe>");
        }
        if (openBreachSlots > 0) {
            appendBullet(out);
            out.append("<#c4b5fd><bold>").append(openBreachSlots).append("</bold> breach slots</#c4b5fd>");
        }
        if (queuedPlayers > 0) {
            appendBullet(out);
            out.append("<#f0abfc><bold>").append(queuedPlayers).append("</bold> queued</#f0abfc>");
        }
        return out.toString();
    }

    public static Component centerLine(String miniMessageText) {
        return ServerTextUtil.centerMotdMiniMessageLine(miniMessageText);
    }

    /** Pixel width of a MiniMessage fragment in the MOTD drawable slot. */
    public static int lineWidth(String miniMessageText) {
        return ServerTextUtil.motdMiniMessageWidth(miniMessageText);
    }

    public static boolean fitsMotdLine(String miniMessageText) {
        return ServerTextUtil.fitsMotdLine(miniMessageText);
    }

    public static Component buildDescription(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String line1Text = snapshot.customLine1() != null ? snapshot.customLine1() : defaultLine1(snapshot);
        String line2Text = snapshot.customLine2() != null ? snapshot.customLine2() : defaultLine2(snapshot);
        Component line1 = centerLine(line1Text);
        Component line2 = centerLine(line2Text);
        return line1.append(Component.newline()).append(line2);
    }

    public static Composer compose() {
        return new Composer();
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    private static String defaultLine1(Snapshot snapshot) {
        return networkNameSection(snapshot.versionRange());
    }

    private static String defaultLine2(Snapshot snapshot) {
        if (snapshot.maintenance()) {
            return maintenanceLine();
        }
        String[] candidates = {
            buildPromoStatsLine(snapshot, true),
            buildPromoStatsLine(snapshot, false),
            buildPromoJoinLine(snapshot, true),
            buildPromoJoinLine(snapshot, false),
            compactJoinLine()
        };
        for (String candidate : candidates) {
            if (fitsMotdLine(candidate)) {
                return candidate;
            }
        }
        return compactJoinLine();
    }

    private static String buildPromoJoinLine(Snapshot snapshot, boolean bracketed) {
        String promo = snapshot.promoOverride() != null && !snapshot.promoOverride().isBlank()
            ? snapshot.promoOverride().trim()
            : promoTaglineForRefresh(snapshot.refreshEpochMillis());
        StringBuilder line = new StringBuilder();
        if (bracketed) {
            line.append(callToAction(promo));
        } else {
            line.append("<gradient:#22d3ee:#a78bfa:#fbbf24><bold>").append(promo).append("</bold></gradient>");
        }
        line.append(" <#475569>·</#475569> ");
        line.append("<gradient:#fde68a:#fbbf24><bold>").append(joinCallout()).append("</bold></gradient>");
        return line.toString();
    }

    private static String compactJoinLine() {
        return "<gradient:#22d3ee:#fbbf24><bold>" + joinCallout() + "</bold></gradient> "
            + "<#94a3b8>· " + NETWORK_DOMAIN + "</#94a3b8>";
    }

    private static String buildPromoStatsLine(Snapshot snapshot, boolean includeExtraction) {
        String promo = snapshot.promoOverride() != null && !snapshot.promoOverride().isBlank()
            ? snapshot.promoOverride().trim()
            : promoTaglineForRefresh(snapshot.refreshEpochMillis());
        StringBuilder line = new StringBuilder();
        line.append(callToAction(promo));
        if (includeExtraction) {
            String extraction = extractionStatsSection(snapshot.extractionPods(), snapshot.openBreachSlots(), snapshot.queuedPlayers());
            if (!extraction.isBlank()) {
                line.append(" <#475569>•</#475569> ").append(extraction);
            }
        }
        line.append(" <#475569>•</#475569> ");
        line.append("<gradient:#fde68a:#fbbf24><bold>").append(joinCallout()).append("</bold></gradient>");
        return line.toString();
    }

    private static String maintenanceLine() {
        return "<#fbbf24>⚠</#fbbf24> "
            + "<gradient:#fde68a:#f97316><bold>MAINTENANCE</bold></gradient> "
            + "<#94a3b8>• " + MAINTENANCE_SUFFIX + " • " + NETWORK_DOMAIN + "</#94a3b8>";
    }

    private static void appendBullet(StringBuilder out) {
        if (!out.isEmpty()) {
            out.append(" <#475569>•</#475569> ");
        }
    }

    // ── Snapshot + composer ────────────────────────────────────────────────────

    public record Snapshot(
        long refreshEpochMillis,
        int proxyOnlinePlayers,
        int networkOnlinePlayers,
        int maxPlayers,
        boolean maintenance,
        int extractionPods,
        int openBreachSlots,
        int queuedPlayers,
        String versionRange,
        String promoOverride,
        String customLine1,
        String customLine2
    ) {
        public int displayOnlinePlayers() {
            return networkOnlinePlayers > 0 ? networkOnlinePlayers : proxyOnlinePlayers;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private long refreshEpochMillis = System.currentTimeMillis();
            private int proxyOnlinePlayers;
            private int networkOnlinePlayers;
            private int maxPlayers = 5000;
            private boolean maintenance;
            private int extractionPods;
            private int openBreachSlots;
            private int queuedPlayers;
            private String versionRange = "1.20–1.21.11";
            private String promoOverride;
            private String customLine1;
            private String customLine2;

            public Builder refreshEpochMillis(long refreshEpochMillis) {
                this.refreshEpochMillis = refreshEpochMillis;
                return this;
            }

            /** @deprecated use {@link #refreshEpochMillis(long)} */
            @Deprecated
            public Builder tickMillis(long tickMillis) {
                return refreshEpochMillis(tickMillis);
            }

            public Builder proxyOnlinePlayers(int proxyOnlinePlayers) {
                this.proxyOnlinePlayers = Math.max(0, proxyOnlinePlayers);
                return this;
            }

            public Builder networkOnlinePlayers(int networkOnlinePlayers) {
                this.networkOnlinePlayers = Math.max(0, networkOnlinePlayers);
                return this;
            }

            public Builder maxPlayers(int maxPlayers) {
                this.maxPlayers = Math.max(1, maxPlayers);
                return this;
            }

            public Builder maintenance(boolean maintenance) {
                this.maintenance = maintenance;
                return this;
            }

            public Builder extractionPods(int extractionPods) {
                this.extractionPods = Math.max(0, extractionPods);
                return this;
            }

            public Builder openBreachSlots(int openBreachSlots) {
                this.openBreachSlots = Math.max(0, openBreachSlots);
                return this;
            }

            public Builder queuedPlayers(int queuedPlayers) {
                this.queuedPlayers = Math.max(0, queuedPlayers);
                return this;
            }

            public Builder versionRange(String versionRange) {
                this.versionRange = versionRange;
                return this;
            }

            public Builder promoOverride(String promoOverride) {
                this.promoOverride = promoOverride;
                return this;
            }

            public Builder customLine1(String customLine1) {
                this.customLine1 = customLine1;
                return this;
            }

            public Builder customLine2(String customLine2) {
                this.customLine2 = customLine2;
                return this;
            }

            public Snapshot build() {
                return new Snapshot(
                    refreshEpochMillis,
                    proxyOnlinePlayers,
                    networkOnlinePlayers,
                    maxPlayers,
                    maintenance,
                    extractionPods,
                    openBreachSlots,
                    queuedPlayers,
                    versionRange,
                    promoOverride,
                    customLine1,
                    customLine2
                );
            }
        }
    }

    public static final class Composer {
        private final Snapshot.Builder snapshot = Snapshot.builder();

        public Composer refreshAt(long epochMillis) {
            snapshot.refreshEpochMillis(epochMillis);
            return this;
        }

        /** @deprecated use {@link #refreshAt(long)} */
        @Deprecated
        public Composer tick(long tickMillis) {
            return refreshAt(tickMillis);
        }

        public Composer players(int online, int max) {
            snapshot.proxyOnlinePlayers(online).maxPlayers(max);
            return this;
        }

        public Composer networkPlayers(int networkOnline) {
            snapshot.networkOnlinePlayers(networkOnline);
            return this;
        }

        public Composer maintenance(boolean enabled) {
            snapshot.maintenance(enabled);
            return this;
        }

        public Composer extractionTelemetry(int pods, int openBreachSlots, int queuedPlayers) {
            snapshot.extractionPods(pods).openBreachSlots(openBreachSlots).queuedPlayers(queuedPlayers);
            return this;
        }

        public Composer versionRange(String versionRange) {
            snapshot.versionRange(versionRange);
            return this;
        }

        public Composer promo(String promoText) {
            snapshot.promoOverride(promoText);
            return this;
        }

        /** Sets a fully custom MiniMessage template for line 1 (still auto-centered). */
        public Composer line1(String miniMessageLine) {
            snapshot.customLine1(miniMessageLine);
            return this;
        }

        /** Sets a fully custom MiniMessage template for line 2 (still auto-centered). */
        public Composer line2(String miniMessageLine) {
            snapshot.customLine2(miniMessageLine);
            return this;
        }

        /** Uses {@link #networkNameSection(String)} for line 1. */
        public Composer brandLine() {
            Snapshot draft = snapshot.build();
            snapshot.customLine1(networkNameSection(draft.versionRange()));
            return this;
        }

        /** Builds line 2 from promo + optional extraction telemetry + join callout. */
        public Composer promoStatsLine() {
            Snapshot draft = snapshot.build();
            snapshot.customLine2(defaultLine2(draft));
            return this;
        }

        public Snapshot snapshot() {
            return snapshot.build();
        }

        public Component buildDescription() {
            return NetworkMotdLibrary.buildDescription(snapshot.build());
        }
    }
}
