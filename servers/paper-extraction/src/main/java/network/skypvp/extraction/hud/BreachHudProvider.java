package network.skypvp.extraction.hud;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachExtractService;
import network.skypvp.extraction.gameplay.BreachExtractZoneSchedule;
import network.skypvp.extraction.gameplay.BreachExtractZoneVisualService;
import network.skypvp.extraction.gameplay.BreachStaminaService;
import network.skypvp.extraction.model.BreachMapMeta;
import network.skypvp.extraction.integration.BreachPlaceholderTags;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.item.ShieldCombatService;
import network.skypvp.extraction.item.ShieldSocketReference;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.shared.currency.CurrencyFormat;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.paper.hud.ScoreboardText;
import network.skypvp.paper.service.PartyScoreboardData;
import network.skypvp.paper.service.PartyScoreboardLines;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.List;
import network.skypvp.paper.chat.ChatFormatService;
import network.skypvp.paper.service.RankService;
import network.skypvp.paper.tabboard.TabBoardContext;
import network.skypvp.paper.tabboard.TabBoardLines;
import network.skypvp.paper.tabboard.TabBoardSpec;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public final class BreachHudProvider implements HudProvider {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final long SHIELD_DOWN_FLASH_MILLIS = 500L;

    // Action bar layout lives in BreachHudLine (graphical skypvp:hud font panel, 384px fixed).

    private final BreachEngine engine;
    private final BreachExtractService extractService;
    private final BreachScoreboardData scoreboardData;
    private final PartyScoreboardData partyData;
    private final WeaponMechanicsBridge weaponMechanicsBridge;
    private final BreachStaminaService staminaService;
    private final RankService rankService;
    private final ChatFormatService chatFormats;

    /** Per-player render cache: only rebuilds the action bar component when a tracked value actually changes. */
    private final Map<UUID, CachedBar> actionBarCache = new ConcurrentHashMap<>();

    public BreachHudProvider(
            BreachEngine engine,
            BreachExtractService extractService,
            BreachScoreboardData scoreboardData,
            PartyScoreboardData partyData,
            WeaponMechanicsBridge weaponMechanicsBridge,
            BreachStaminaService staminaService,
            RankService rankService,
            ChatFormatService chatFormats
    ) {
        this.engine = engine;
        this.extractService = extractService;
        this.scoreboardData = scoreboardData;
        this.partyData = partyData;
        this.weaponMechanicsBridge = weaponMechanicsBridge;
        this.staminaService = staminaService;
        this.rankService = rankService;
        this.chatFormats = chatFormats;
    }

    @Override
    public String modeKey() {
        return "extraction";
    }

    @Override
    public Optional<Component> actionBar(ActionBarContext context) {
        Player player = context.base().player();
        UUID id = player.getUniqueId();
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty()) {
            actionBarCache.remove(id);
            return Optional.empty();
        }

        BreachInstance breach = instance.get();
        // Keep vitals/weapon HUD visible while extracting — extract feedback lives on boss bar + titles.
        Vitals vitals = isLiveRaider(player, breach) ? readVitals(player) : null;
        BreachHudLine.WeaponHud weapon = readWeaponHud(player);

        // Only the changing values feed the signature; when it matches the cache we skip the MiniMessage parse and
        // pixel layout entirely and reuse the last-built component (the action bar is still resent to stay visible).
        String signature = (vitals == null ? "" : vitals.signature()) + '\u0001' + weaponSignature(weapon);
        CachedBar cached = actionBarCache.get(id);
        if (cached != null && cached.signature().equals(signature)) {
            return Optional.of(cached.line());
        }

        if (vitals == null && weapon == null) {
            actionBarCache.remove(id);
            return Optional.empty();
        }
        BreachHudLine.Vitals hudVitals = vitals == null ? null : new BreachHudLine.Vitals(
                vitals.healthRatio(),
                vitals.hasShield(),
                vitals.shieldRatio(),
                vitals.destroyed(),
                vitals.depleted(),
                vitals.flashOn(),
                vitals.staminaRatio()
        );

        Component line = BreachHudLine.compose(hudVitals, weapon);
        pruneCacheIfNeeded();
        actionBarCache.put(id, new CachedBar(signature, line));
        return Optional.of(line);
    }

    private void pruneCacheIfNeeded() {
        if (actionBarCache.size() <= 256) {
            return;
        }
        actionBarCache.keySet().removeIf(uuid -> {
            Player online = Bukkit.getPlayer(uuid);
            return online == null || !online.isOnline();
        });
    }

    private boolean isLiveRaider(Player player, BreachInstance breach) {
        java.util.UUID id = player.getUniqueId();
        return breach.state() == BreachState.ACTIVE
                && breach.containsPlayer(id)
                && !breach.hasExtracted(id)
                && !breach.isEliminated(id)
                && !breach.isPendingJoin(id)
                && !engine.isSpectating(player);
    }

    /** Snapshot of the values that drive the vitals segment; its {@link #signature()} gates the render cache. */
    private record Vitals(
            double healthRatio,
            boolean hasShield,
            double shieldRatio,
            boolean destroyed,
            boolean depleted,
            boolean flashOn,
            double staminaRatio
    ) {
        String signature() {
            // Stamina renders as 10 ticks, so bucket to 10 steps to keep the cache useful.
            int stamina = staminaRatio < 0.0D ? -1 : (int) Math.round(staminaRatio * 10.0D);
            int hp = (int) Math.round(healthRatio * 200.0D);
            if (!hasShield) {
                return "h" + hp + "t" + stamina;
            }
            if (destroyed) {
                return "h" + hp + "B" + (flashOn ? 1 : 0) + "t" + stamina;
            }
            if (depleted) {
                return "h" + hp + "D" + (flashOn ? 1 : 0) + "t" + stamina;
            }
            return "h" + hp + "s" + (int) Math.round(shieldRatio * 100.0D) + "t" + stamina;
        }
    }

    private Vitals readVitals(Player player) {
        double health = Math.max(0.0D, player.getHealth());
        AttributeInstance maxAttr = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = maxAttr != null ? maxAttr.getValue() : 20.0D;
        double healthRatio = maxHealth <= 0.0D ? 0.0D : health / maxHealth;

        boolean hasShield = false;
        double shieldRatio = 0.0D;
        boolean destroyed = false;
        boolean depleted = false;
        PaperCorePlugin core = coreOrNull();
        if (core != null) {
            Optional<ShieldSocketReference> equipped = ShieldCombatService.equippedShield(core, player);
            if (equipped.isPresent()) {
                ShieldSocketReference shield = equipped.get();
                hasShield = true;
                destroyed = shield.destroyed();
                depleted = shield.isDepleted();
                double max = shield.maxPoints();
                shieldRatio = max <= 0.0D ? 0.0D : shield.currentPoints() / max;
            }
        }
        boolean flashOn = (System.currentTimeMillis() / SHIELD_DOWN_FLASH_MILLIS) % 2L == 0L;
        double staminaRatio = staminaService != null && staminaService.isEnrolled(player.getUniqueId())
                ? Math.max(0.0D, Math.min(1.0D, staminaService.ratio(player)))
                : -1.0D;
        return new Vitals(healthRatio, hasShield, shieldRatio, destroyed, depleted, flashOn, staminaRatio);
    }

    /**
     * True when {@code player} is a live raider whose shield is depleted (SHIELD DOWN) or broken (BROKEN SHIELD),
     * i.e. the flash is active and their action bar needs a faster refresh cadence to animate the toggle.
     */
    public boolean shouldFlashRefresh(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty() || !isLiveRaider(player, instance.get())) {
            return false;
        }
        PaperCorePlugin core = coreOrNull();
        if (core == null) {
            return false;
        }
        return ShieldCombatService.equippedShield(core, player)
                .map(shield -> shield.destroyed() || shield.isDepleted())
                .orElse(false);
    }

    private PaperCorePlugin coreOrNull() {
        return engine.gameplayCoordinator() == null ? null : engine.gameplayCoordinator().core();
    }

    /** Reload spinner frame duration; the changing frame index also busts the render cache. */
    private static final long RELOAD_FRAME_MILLIS = 120L;

    private static String weaponSignature(BreachHudLine.WeaponHud weapon) {
        if (weapon == null) {
            return "";
        }
        return weapon.name() + '|' + weapon.clip() + '|' + (weapon.reloading() ? weapon.reloadFrame() : -1);
    }

    /** Right zone: held-weapon snapshot rendered as name + graphical ammo counter / reload spinner. */
    private BreachHudLine.WeaponHud readWeaponHud(Player player) {
        if (weaponMechanicsBridge == null || !weaponMechanicsBridge.isAvailable()) {
            return null;
        }
        WeaponMechanicsBridge.WeaponHudSnapshot weapon = weaponMechanicsBridge.readHeldWeaponStatus(player);
        if (!weapon.hasWeapon()) {
            return null;
        }
        int reloadFrame = (int) ((System.currentTimeMillis() / RELOAD_FRAME_MILLIS) % 4L);
        return new BreachHudLine.WeaponHud(
                weapon.displayName(),
                weapon.formattedClipAmmo(),
                weapon.reloading(),
                reloadFrame
        );
    }

    /** Cached action bar render: the last-built line plus the value signature that produced it. */
    private record CachedBar(String signature, Component line) {
    }

    @Override
    public Optional<ScoreboardFrame> scoreboard(ScoreboardContext context) {
        Context base = context.base();
        Player player = base.player();
        long tick = base.tickMillis();
        String locale = base.clientLocale();

        String date = LocalDate.now().format(DATE_FORMAT);
        String server = ExtractionTexts.toSmallCaps(compactServerName(base.serverId()));

        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (shouldShowBreachScoreboard(player, instance)) {
            BreachInstance breach = instance.or(() -> engine.instanceForWorld(player.getWorld())).orElse(null);
            return Optional.of(buildBreachScoreboard(player, breach, tick, date, locale));
        }

        BreachScoreboardData.Snapshot snap = scoreboardData != null
                ? scoreboardData.get(player.getUniqueId())
                : BreachScoreboardData.EMPTY;
        return Optional.of(buildHubScoreboard(snap, tick, date, server, locale));
    }

    @Override
    public Optional<TabFrame> tabList(TabListContext context) {
        Context base = context.base();
        Player player = base.player();
        String locale = base.clientLocale();
        String date = LocalDate.now().format(DATE_FORMAT);
        PartyScoreboardData.PartyView party = partyData != null
                ? partyData.get(player.getUniqueId())
                : PartyScoreboardData.EMPTY;

        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (shouldShowBreachScoreboard(player, instance)) {
            BreachInstance breach = instance.or(() -> engine.instanceForWorld(player.getWorld())).orElse(null);
            Component header = breach == null
                    ? Component.empty()
                    : ServerTextUtil.miniMessageComponent(
                            "<gradient:dark_purple:#ff66cc><b>" + breach.mapMeta().displayName() + "</b></gradient>"
                                    + " <dark_gray>| <gray>"
                                    + BreachPlaceholderTags.phaseLabel(breach, engine.configService(), locale)
                    );
            Component footer = buildBreachTabFooter(player, breach, locale);
            if (party.inParty()) {
                header = header.append(Component.newline()).append(TabBoardLines.partyHeader(party, date));
            }
            return Optional.of(new TabFrame(header, footer));
        }

        BreachScoreboardData.Snapshot snap = scoreboardData != null
                ? scoreboardData.get(player.getUniqueId())
                : BreachScoreboardData.EMPTY;
        Component header = party.inParty()
                ? TabBoardLines.partyHeader(party, date)
                : ServerTextUtil.miniMessageComponent("<gradient:#00c6ff:#0072ff><b>Extraction</b></gradient>");
        Component footer = ServerTextUtil.miniMessageComponent(
                "<gray>" + ExtractionTexts.text("extraction.hud.scoreboard.extractions", locale, snap.extractions())
                        + " <dark_gray>| "
                        + ExtractionTexts.text("extraction.hud.scoreboard.kills", locale, snap.kills())
                        + " <dark_gray>| "
                        + ExtractionTexts.text("extraction.hud.scoreboard.deaths", locale, snap.deaths())
                        + "\n<gray>" + CurrencyFormat.formatCompact(snap.gold()) + " gold <dark_gray>| "
                        + CurrencyFormat.formatCompact(snap.coins()) + " coins"
        );
        return Optional.of(new TabFrame(header, footer));
    }

    @Override
    public Optional<TabBoardSpec> tabBoard(TabListContext context) {
        Player player = context.base().player();
        PartyScoreboardData.PartyView party = partyData != null
                ? partyData.get(player.getUniqueId())
                : PartyScoreboardData.EMPTY;
        long graceMillis = engine.configService().disconnectedGraceMillis();
        Optional<BreachInstance> instance = engine.instanceFor(player);
        BreachInstance breach = instance.orElse(null);
        BreachScoreboardData.Snapshot snap = scoreboardData != null
                ? scoreboardData.get(player.getUniqueId())
                : BreachScoreboardData.EMPTY;
        String locale = context.base().clientLocale();
        boolean inBreach = breach != null && shouldShowBreachScoreboard(player, instance);
        return Optional.of(TabBoardLines.build(new TabBoardContext(
                player,
                party,
                graceMillis,
                context.base().tickMillis(),
                chatFormats,
                rankService,
                buildTabStatLines(player, breach, snap, locale),
                tabNearbyPlayers(player, inBreach ? breach : null),
                inBreach ? "breach" : "hub",
                buildBoardHeader(player, breach, inBreach, locale, context.base()),
                buildBoardFooter(player, breach, inBreach, snap, locale)
        )));
    }

    /** Polished tab header: brand line plus a context line (mode, phase, online count, ping). */
    private Component buildBoardHeader(
            Player player,
            BreachInstance breach,
            boolean inBreach,
            String locale,
            Context base
    ) {
        String brand;
        String detail;
        if (inBreach) {
            brand = "<gradient:dark_purple:#ff66cc><b>" + breach.mapMeta().displayName() + "</b></gradient>";
            detail = "<gray>" + BreachPlaceholderTags.phaseLabel(breach, engine.configService(), locale)
                    + " <dark_gray>| <gray>" + player.getPing() + "ms";
        } else {
            brand = "<gradient:#00c6ff:#0072ff><b>" + ServerTextUtil.toSmallCaps("SkyPvP")
                    + " " + ServerTextUtil.toSmallCaps("Extraction") + "</b></gradient>";
            detail = "<gray>" + ServerTextUtil.toSmallCaps(compactServerName(base.serverId()))
                    + " <dark_gray>| <gray>" + base.onlinePlayers() + " online"
                    + " <dark_gray>| <gray>" + player.getPing() + "ms";
        }
        return ServerTextUtil.miniMessageComponent(brand + "\n" + detail + "\n");
    }

    /** Polished tab footer: session/lifetime stats and currencies. */
    private Component buildBoardFooter(
            Player player,
            BreachInstance breach,
            boolean inBreach,
            BreachScoreboardData.Snapshot snap,
            String locale
    ) {
        if (inBreach) {
            Component base = buildBreachTabFooter(player, breach, locale);
            return Component.newline().append(base);
        }
        String line = "<gray>" + ExtractionTexts.text("extraction.hud.scoreboard.extractions", locale, snap.extractions())
                + " <dark_gray>| "
                + ExtractionTexts.text("extraction.hud.scoreboard.kills", locale, snap.kills())
                + " <dark_gray>| "
                + ExtractionTexts.text("extraction.hud.scoreboard.deaths", locale, snap.deaths())
                + "\n<gold>" + CurrencyFormat.formatCompact(snap.gold()) + " gold <dark_gray>| "
                + "<yellow>" + CurrencyFormat.formatCompact(snap.coins()) + " coins";
        return ServerTextUtil.miniMessageComponent("\n" + line);
    }

    private List<Component> buildTabStatLines(
            Player player,
            BreachInstance instance,
            BreachScoreboardData.Snapshot snap,
            String locale
    ) {
        List<Component> lines = new ArrayList<>();
        boolean inBreach = instance != null && shouldShowBreachScoreboard(player, Optional.of(instance));
        Vitals vitals = instance != null && isLiveRaider(player, instance) ? readVitals(player) : null;
        if (vitals != null) {
            int hpPct = (int) Math.round(vitals.healthRatio() * 100.0D);
            lines.add(ServerTextUtil.miniMessageComponent("<red>HP <white>" + hpPct + "%"));
            if (!vitals.hasShield()) {
                lines.add(ServerTextUtil.miniMessageComponent("<gray>No shield"));
            } else if (vitals.destroyed()) {
                lines.add(ServerTextUtil.miniMessageComponent("<dark_red>Shield broken"));
            } else if (vitals.depleted()) {
                lines.add(ServerTextUtil.miniMessageComponent("<red>Shield down"));
            } else {
                int shieldPct = (int) Math.round(vitals.shieldRatio() * 100.0D);
                lines.add(ServerTextUtil.miniMessageComponent("<aqua>Shield <white>" + shieldPct + "%"));
            }
            if (staminaService != null && staminaService.isEnrolled(player.getUniqueId())) {
                int stamina = (int) Math.round(staminaService.ratio(player) * 100.0D);
                lines.add(ServerTextUtil.miniMessageComponent("<yellow>Stamina <white>" + stamina + "%"));
            }
            lines.add(Component.empty());
        }
        if (inBreach) {
            lines.add(ServerTextUtil.miniMessageComponent(
                    "<gray>Kills <white>" + instance.sessionKills(player.getUniqueId())
            ));
            lines.add(ServerTextUtil.miniMessageComponent(
                    "<gray>Deaths <white>" + instance.sessionDeaths(player.getUniqueId())
            ));
            lines.add(ServerTextUtil.miniMessageComponent(
                    "<gray>Extracted <white>" + instance.extractedCount()
            ));
            lines.add(Component.empty());
        }
        lines.add(ServerTextUtil.miniMessageComponent(
                "<gray>K <white>" + snap.kills() + " <gray>D <white>" + snap.deaths()
                        + " <gray>X <white>" + snap.extractions()
        ));
        lines.add(ServerTextUtil.miniMessageComponent(
                "<gold>" + CurrencyFormat.formatCompact(snap.gold()) + " gold"
        ));
        lines.add(ServerTextUtil.miniMessageComponent(
                "<yellow>" + CurrencyFormat.formatCompact(snap.coins()) + " coins"
        ));
        return lines;
    }

    private List<Player> tabNearbyPlayers(Player viewer, BreachInstance instance) {
        List<Player> nearby = new ArrayList<>();
        if (instance == null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online == null || !online.isOnline() || online.equals(viewer)) {
                    continue;
                }
                if (engine.instanceFor(online).isEmpty()) {
                    nearby.add(online);
                }
            }
            return nearby;
        }
        for (UUID participantId : instance.participantsSnapshot()) {
            if (participantId.equals(viewer.getUniqueId())) {
                continue;
            }
            Player other = Bukkit.getPlayer(participantId);
            if (other == null || !other.isOnline() || !isLiveRaider(other, instance)) {
                continue;
            }
            nearby.add(other);
        }
        return nearby;
    }

    private Component buildBreachTabFooter(Player player, BreachInstance instance, String locale) {
        if (instance == null) {
            return Component.empty();
        }
        StringBuilder line = new StringBuilder();
        line.append(ExtractionTexts.text(
                "extraction.hud.scoreboard.raid_kills",
                locale,
                instance.sessionKills(player.getUniqueId())
        ));
        line.append(" <dark_gray>| ");
        line.append(ExtractionTexts.text(
                "extraction.hud.scoreboard.raid_deaths",
                locale,
                instance.sessionDeaths(player.getUniqueId())
        ));
        line.append(" <dark_gray>| ");
        line.append(ExtractionTexts.text(
                "extraction.hud.scoreboard.extracted_count",
                locale,
                instance.extractedCount()
        ));
        if (staminaService != null && isLiveRaider(player, instance) && staminaService.isEnrolled(player.getUniqueId())) {
            int current = (int) Math.round(staminaService.current(player));
            int max = (int) Math.round(staminaService.max(player));
            line.append("\n").append(ExtractionTexts.text("extraction.hud.scoreboard.stamina", locale, current, max));
        }
        return ServerTextUtil.miniMessageComponent(line.toString());
    }

    private ScoreboardText.Builder scoreboardText(long tick, String locale) {
        return ScoreboardText.builder(tick)
                .locale(locale)
                .localizer(ExtractionTexts::localizeTemplate);
    }

    private boolean shouldShowBreachScoreboard(Player player, Optional<BreachInstance> instance) {
        if (!player.getWorld().getName().startsWith("breach_")) {
            return false;
        }
        return instance.isEmpty() || !instance.get().isPendingJoin(player.getUniqueId());
    }

    private ScoreboardFrame buildHubScoreboard(
            BreachScoreboardData.Snapshot snap,
            long tick,
            String date,
            String server,
            String locale
    ) {
        ScoreboardText.BuiltScoreboard built = scoreboardText(tick, locale)
                .animatedTitle(ExtractionTexts.catalogSource("extraction.hud.scoreboard.hub_title"))
                .dynamicLine("<gray>" + date)
                .dynamicCentered("<dark_gray>" + server)
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.hub.tagline_1"))
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.hub.tagline_2"))
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.hub.tagline_3"))
                .animatedCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.gold_header"))
                .dynamicCentered("<gray>" + CurrencyFormat.formatCompact(snap.gold()))
                .blank()
                .animatedCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.stats_header"))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.extractions", locale, snap.extractions()))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.kills", locale, snap.kills()))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.deaths", locale, snap.deaths()))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.coins", locale, CurrencyFormat.formatCompact(snap.coins())))
                .build();
        return pinFooter(built, locale);
    }

    private ScoreboardFrame buildBreachScoreboard(Player player, BreachInstance instance, long tick, String date, String locale) {
        BreachConfigService config = engine.configService();
        String mapName = instance == null
                ? ExtractionTexts.text("extraction.hud.scoreboard.unknown_map", locale)
                : instance.mapMeta().displayName();
        String sessionLabel = breachSessionIdLabel(instance);
        ScoreboardText.Builder builder = scoreboardText(tick, locale)
                .staticTitle("<gradient:dark_purple:#ff66cc><b>" + mapName)
                .dynamicLine("<gray>" + date)
                .staticCentered("<dark_gray>" + sessionLabel);

        if (instance == null) {
            ScoreboardText.BuiltScoreboard built = builder
                    .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.no_instance"))
                    .blank()
                    .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.use_leave"))
                    .build();
            return pinFooter(built, locale);
        }

        builder.dynamicLine(ExtractionTexts.text(
                        "extraction.hud.scoreboard.phase_line",
                        locale,
                        BreachPlaceholderTags.phaseLabel(instance, config, locale)
                ))
                .dynamicLine(ExtractionTexts.text(
                        "extraction.hud.scoreboard.cycle_line",
                        locale,
                        instance.formattedRemainingTime(),
                        BreachPlaceholderTags.timeRemainingPercent(instance)
                ))
                .blank()
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.extractions_open_header"));

        ScoreboardText.BuiltScoreboard header = builder.build();
        List<Component> body = new ArrayList<>(header.lines());

        int matchDuration = Math.max(1, instance.mapMeta().durationSeconds());
        int remainingSeconds = instance.remainingSeconds();
        BreachState state = instance.state();
        BreachExtractZoneSchedule schedule = instance.extractZoneSchedule();
        List<BreachMapMeta.ExtractZone> zones = instance.mapMeta().extractZones();
        int lineWidth = ServerTextUtil.SCOREBOARD_LINE_WIDTH_PIXELS;

        for (BreachMapMeta.ExtractZone zone : zones) {
            body.add(extractZoneScoreboardLine(
                    zone.id(),
                    schedule,
                    state,
                    remainingSeconds,
                    matchDuration,
                    locale,
                    lineWidth
            ));
        }

        body.add(Component.empty());

        if (extractService.isCombatTagged(player)) {
            body.add(ServerTextUtil.miniMessageComponent(ExtractionTexts.text(
                    "extraction.hud.scoreboard.combat_tag",
                    locale,
                    extractService.combatTagRemainingSeconds(player)
            )));
        }
        if (instance.isEliminated(player.getUniqueId())) {
            body.add(ScoreboardText.renderStatic(
                    ExtractionTexts.catalogSource("extraction.hud.scoreboard.eliminated"),
                    locale,
                    ExtractionTexts::localizeTemplate
            ));
        } else if (instance.hasExtracted(player.getUniqueId())) {
            body.add(ScoreboardText.renderStatic(
                    ExtractionTexts.catalogSource("extraction.hud.scoreboard.extracted_status"),
                    locale,
                    ExtractionTexts::localizeTemplate
            ));
        } else if (instance.isInOpenExtractZone(player.getLocation())) {
            body.add(ScoreboardText.renderStatic(
                    ExtractionTexts.catalogSource("extraction.hud.scoreboard.in_extract_zone"),
                    locale,
                    ExtractionTexts::localizeTemplate
            ));
        }

        Component footer = ScoreboardText.renderStatic(
                ExtractionTexts.catalogSource("extraction.hud.scoreboard.footer"),
                locale,
                ExtractionTexts::localizeTemplate
        );
        footer = ServerTextUtil.centerComponent(footer, Math.max(lineWidth, ServerTextUtil.componentVisibleWidth(header.title())));
        return new ScoreboardFrame(header.title(), PartyScoreboardLines.buildSidebar(body, footer));
    }

    private static ScoreboardFrame pinFooter(ScoreboardText.BuiltScoreboard built, String locale) {
        Component footer = ScoreboardText.renderStatic(
                ExtractionTexts.catalogSource("extraction.hud.scoreboard.footer"),
                locale,
                ExtractionTexts::localizeTemplate
        );
        int width = Math.max(
                ServerTextUtil.SCOREBOARD_LINE_WIDTH_PIXELS,
                ServerTextUtil.componentVisibleWidth(built.title())
        );
        for (Component line : built.lines()) {
            width = Math.max(width, ServerTextUtil.componentVisibleWidth(line));
        }
        footer = ServerTextUtil.centerComponent(footer, width);
        return new ScoreboardFrame(built.title(), PartyScoreboardLines.buildSidebar(built.lines(), footer));
    }

    private static Component extractZoneScoreboardLine(
            String zoneId,
            BreachExtractZoneSchedule schedule,
            BreachState state,
            int remainingSeconds,
            int matchDuration,
            String locale,
            int lineWidth
    ) {
        String label = formatExtractZoneLabel(zoneId);
        Component name = ServerTextUtil.miniMessageComponent("<white>🚪 " + label);
        Component timer;
        if (schedule == null) {
            BreachExtractZoneVisualService.ExtractAvailability global =
                    BreachExtractZoneVisualService.resolveAvailability(state, remainingSeconds, 30);
            if (global == BreachExtractZoneVisualService.ExtractAvailability.CLOSED) {
                timer = ScoreboardText.renderStatic(
                        ExtractionTexts.catalogSource("extraction.hud.scoreboard.extract_closed"),
                        locale,
                        ExtractionTexts::localizeTemplate
                );
            } else {
                int untilClose = Math.max(0, remainingSeconds);
                int totalOpen = Math.max(1, matchDuration);
                timer = ServerTextUtil.miniMessageComponent(formatExtractTimer(untilClose, totalOpen));
            }
        } else if (!schedule.isZoneUsable(zoneId, state, remainingSeconds)) {
            timer = ScoreboardText.renderStatic(
                    ExtractionTexts.catalogSource("extraction.hud.scoreboard.extract_closed"),
                    locale,
                    ExtractionTexts::localizeTemplate
            );
        } else {
            int untilClose = schedule.secondsUntilClose(zoneId, state, remainingSeconds);
            int totalOpen = schedule.totalOpenSeconds(zoneId, matchDuration);
            timer = ServerTextUtil.miniMessageComponent(formatExtractTimer(untilClose, totalOpen));
        }
        return ServerTextUtil.layoutThreeZone(name, Component.empty(), timer, lineWidth);
    }

    static String formatExtractTimer(int secondsRemaining, int totalOpenSeconds) {
        int safeRemaining = Math.max(0, secondsRemaining);
        int safeTotal = Math.max(1, totalOpenSeconds);
        double fraction = (double) safeRemaining / (double) safeTotal;
        String color;
        if (fraction > 0.50D) {
            color = "<green>";
        } else if (fraction > 0.25D) {
            color = "<#FFAA00>";
        } else {
            color = "<red>";
        }
        return color + "<b>" + formatExtractCountdown(safeRemaining);
    }

    static String formatExtractCountdown(int secondsRemaining) {
        int safe = Math.max(0, secondsRemaining);
        if (safe >= 60) {
            return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
        }
        return safe + "s";
    }

    static String formatExtractZoneLabel(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return "Extract";
        }
        String raw = zoneId.trim();
        if (raw.toLowerCase(Locale.ROOT).endsWith("-extract")) {
            raw = raw.substring(0, raw.length() - "-extract".length());
        }
        String[] parts = raw.replace('_', '-').split("-");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        String label = out.toString();
        if (label.length() > 14) {
            return label.substring(0, 13) + "…";
        }
        return label.isBlank() ? "Extract" : label;
    }

    private static String breachSessionIdLabel(BreachInstance instance) {
        if (instance == null || instance.instanceId() == null || instance.instanceId().isBlank()) {
            return "—";
        }
        return instance.instanceId().split("-")[0];
    }

    private static String compactServerName(String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return "extraction";
        }
        String trimmed = serverId.startsWith("skypvp-") ? serverId.substring("skypvp-".length()) : serverId;
        return trimmed.isBlank() ? serverId : trimmed;
    }

    @Override
    public Optional<BossBarFrame> bossBar(BossBarContext context) {
        Player player = context.base().player();
        String locale = context.base().clientLocale();
        BreachScoreboardData.Snapshot balances = scoreboardData != null
                ? scoreboardData.get(player.getUniqueId())
                : BreachScoreboardData.EMPTY;
        String goldText = CurrencyFormat.formatCompact(balances.gold());
        String coinsText = CurrencyFormat.formatCompact(balances.coins());

        // Extract dwell rides THIS bar (fixed center section + green progress). The extract
        // service used to show a second BossBar of its own while this one was hidden — the
        // two mechanisms raced and the balance readout vanished/jumped mid-extract.
        if (extractService.isExtracting(player)) {
            float dwell = extractService.extractProgress(player);
            return Optional.of(new BossBarFrame(
                    BreachBalanceLine.compose(
                            ExtractionTexts.miniMessage(
                                    player,
                                    "extraction.extract.bossbar.extracting",
                                    extractService.extractRemainingSeconds(player)
                            ),
                            goldText,
                            coinsText
                    ),
                    dwell < 0.0F ? 0.0F : dwell,
                    BossBar.Color.GREEN,
                    BossBar.Overlay.PROGRESS
            ));
        }

        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty()) {
            // Hub: balance-only readout. WHITE boss bar art is blanked in the pack, so just
            // the centered Gold/Coins cluster floats at the top of the screen.
            return Optional.of(new BossBarFrame(
                    BreachBalanceLine.compose(null, goldText, coinsText),
                    0.0F,
                    BossBar.Color.WHITE,
                    BossBar.Overlay.PROGRESS
            ));
        }

        BreachInstance breach = instance.get();
        BreachState state = breach.state();
        int joinCountdown = breach.joinCountdownSeconds(player.getUniqueId());
        if (joinCountdown > 0) {
            int totalSeconds = engine.configService().joiningCountdownSeconds();
            return Optional.of(new BossBarFrame(
                    BreachBalanceLine.compose(
                            ExtractionTexts.miniMessage(player, "extraction.hud.bossbar.joining", joinCountdown),
                            goldText,
                            coinsText
                    ),
                    totalSeconds <= 0 ? 0.0F : Math.max(0.0F, Math.min(1.0F, (float) joinCountdown / totalSeconds)),
                    BossBar.Color.YELLOW,
                    BossBar.Overlay.PROGRESS
            ));
        }

        int totalSeconds;
        int remainingSeconds;
        BossBar.Color color;
        Component title;

        switch (state) {
            case ACTIVE -> {
                totalSeconds = breach.mapMeta().durationSeconds();
                remainingSeconds = Math.max(0, breach.remainingSeconds());
                color = remainingSeconds <= 60 ? BossBar.Color.RED : BossBar.Color.BLUE;
                title = ExtractionTexts.miniMessage(
                        player,
                        "extraction.hud.bossbar.cycle",
                        formatDuration(remainingSeconds)
                );
            }
            case TOXIC -> {
                totalSeconds = Math.max(1, engine.configService().toxicityMaxPhaseSeconds());
                remainingSeconds = Math.max(0, totalSeconds - breach.toxicElapsedSeconds());
                color = BossBar.Color.PURPLE;
                title = ExtractionTexts.miniMessage(
                        player,
                        "extraction.hud.bossbar.toxic",
                        formatDuration(remainingSeconds)
                );
            }
            case ENDING, RESETTING -> {
                totalSeconds = engine.configService().resetDelaySeconds();
                remainingSeconds = engine.configService().resetDelaySeconds();
                color = BossBar.Color.PURPLE;
                title = ExtractionTexts.miniMessage(player, "extraction.hud.bossbar.resetting");
            }
            default -> {
                totalSeconds = 1;
                remainingSeconds = 1;
                color = BossBar.Color.WHITE;
                title = ExtractionTexts.miniMessage(player, "extraction.hud.bossbar.unavailable");
            }
        }

        float progress = totalSeconds <= 0 ? 0.0F : Math.max(0.0F, Math.min(1.0F, (float) remainingSeconds / totalSeconds));
        return Optional.of(new BossBarFrame(
                BreachBalanceLine.compose(title, goldText, coinsText),
                progress,
                color,
                BossBar.Overlay.PROGRESS
        ));
    }

    private static String formatDuration(int seconds) {
        int minutes = seconds / 60;
        int remainder = seconds % 60;
        return String.format("%d:%02d", minutes, remainder);
    }
}
