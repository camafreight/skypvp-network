package network.skypvp.extraction.hud;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachExtractService;
import network.skypvp.extraction.gameplay.BreachExtractZoneVisualService;
import network.skypvp.extraction.gameplay.BreachGameplayCoordinator;
import network.skypvp.extraction.gameplay.loot.BreachLootChestRegistry;
import network.skypvp.extraction.integration.BreachPlaceholderTags;
import network.skypvp.extraction.integration.WeaponMechanicsBridge;
import network.skypvp.extraction.model.BreachState;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.gamemode.api.HudProvider;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class BreachHudProvider implements HudProvider {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    private final BreachEngine engine;
    private final BreachExtractService extractService;
    private final BreachScoreboardData scoreboardData;
    private final WeaponMechanicsBridge weaponMechanicsBridge;

    public BreachHudProvider(
            BreachEngine engine,
            BreachExtractService extractService,
            BreachScoreboardData scoreboardData,
            WeaponMechanicsBridge weaponMechanicsBridge
    ) {
        this.engine = engine;
        this.extractService = extractService;
        this.scoreboardData = scoreboardData;
        this.weaponMechanicsBridge = weaponMechanicsBridge;
    }

    @Override
    public String modeKey() {
        return "extraction";
    }

    @Override
    public Optional<Component> actionBar(ActionBarContext context) {
        Player player = context.base().player();
        String locale = context.base().clientLocale();
        if (extractService.isExtracting(player)) {
            return Optional.empty();
        }
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty()) {
            return Optional.empty();
        }

        BreachInstance breach = instance.get();
        Component left = buildActionBarLeft(player, breach, locale);
        Component center = buildActionBarWeapon(player, locale);
        Component right = buildActionBarRight(player, breach, locale);
        if (isEmptySegment(left) && isEmptySegment(center) && isEmptySegment(right)) {
            return Optional.empty();
        }

        return Optional.of(ServerTextUtil.layoutThreeZone(
                left,
                center,
                right,
                ServerTextUtil.ACTION_BAR_LINE_WIDTH_PIXELS
        ));
    }

    private Component buildActionBarLeft(Player player, BreachInstance breach, String locale) {
        if (breach.isPendingJoin(player.getUniqueId())) {
            return miniMessage("extraction.hud.actionbar.left.join", locale, breach.joinCountdownSeconds(player.getUniqueId()));
        }
        if (extractService.isCombatTagged(player) && breach.state() == BreachState.ACTIVE) {
            return miniMessage(
                    "extraction.hud.actionbar.left.combat",
                    locale,
                    extractService.combatTagRemainingSeconds(player)
            );
        }
        if (breach.state() == BreachState.ACTIVE) {
            return miniMessage("extraction.hud.actionbar.left.cycle", locale, breach.formattedRemainingTime());
        }
        String phase = BreachPlaceholderTags.phaseLabel(breach, engine.configService(), locale);
        return miniMessage("extraction.hud.actionbar.left.phase", locale, phase);
    }

    private Component buildActionBarRight(Player player, BreachInstance breach, String locale) {
        if (breach.isEliminated(player.getUniqueId())) {
            return miniMessage("extraction.hud.actionbar.right.eliminated", locale);
        }
        if (breach.hasExtracted(player.getUniqueId())) {
            return miniMessage("extraction.hud.actionbar.right.extracted", locale);
        }
        if (breach.isInExtractZone(player.getLocation())) {
            return miniMessage("extraction.hud.actionbar.right.extract_zone", locale);
        }

        int active = breach.activeParticipantCount();
        String state = ExtractionTexts.text(
                "extraction.state." + breach.state().name().toLowerCase(Locale.ROOT),
                locale
        );
        if (breach.state() == BreachState.ACTIVE) {
            BreachLootChestRegistry.WorldLootStats lootStats = lootStats(breach);
            double lootPercent = BreachPlaceholderTags.lootPercent(lootStats);
            return miniMessage(
                    "extraction.hud.actionbar.right.raid_loot",
                    locale,
                    active,
                    state,
                    BreachPlaceholderTags.formatPercent(lootPercent)
            );
        }
        return miniMessage(
                "extraction.hud.actionbar.right.raid",
                locale,
                breach.mapMeta().displayName(),
                active,
                state
        );
    }

    private Component buildActionBarWeapon(Player player, String locale) {
        if (weaponMechanicsBridge == null || !weaponMechanicsBridge.isAvailable()) {
            return Component.empty();
        }
        WeaponMechanicsBridge.WeaponHudSnapshot weapon = weaponMechanicsBridge.readHeldWeaponStatus(player);
        if (!weapon.hasWeapon()) {
            return Component.empty();
        }
        String ammoDisplay = weapon.formattedClipAmmo();
        String fireMode = fireModeLabel(weapon.selectiveFire(), locale);
        if (weapon.reloading()) {
            if (fireMode.isBlank()) {
                return miniMessage(
                        "extraction.hud.actionbar.weapon.reloading",
                        locale,
                        weapon.displayName(),
                        ammoDisplay
                );
            }
            return miniMessage(
                    "extraction.hud.actionbar.weapon.reloading_fire",
                    locale,
                    weapon.displayName(),
                    ammoDisplay,
                    fireMode
            );
        }
        if (weapon.scoping()) {
            if (fireMode.isBlank()) {
                return miniMessage(
                        "extraction.hud.actionbar.weapon.scoping_simple",
                        locale,
                        weapon.displayName(),
                        ammoDisplay
                );
            }
            return miniMessage(
                    "extraction.hud.actionbar.weapon.scoping",
                    locale,
                    weapon.displayName(),
                    ammoDisplay,
                    fireMode
            );
        }
        if (fireMode.isBlank()) {
            return miniMessage(
                    "extraction.hud.actionbar.weapon.ammo_simple",
                    locale,
                    weapon.displayName(),
                    ammoDisplay
            );
        }
        return miniMessage(
                "extraction.hud.actionbar.weapon.ammo",
                locale,
                weapon.displayName(),
                ammoDisplay,
                fireMode
        );
    }

    private static String fireModeLabel(int selectiveFire, String locale) {
        if (selectiveFire < 0) {
            return "";
        }
        String key = switch (selectiveFire) {
            case 1 -> "extraction.hud.actionbar.weapon.fire_burst";
            case 2 -> "extraction.hud.actionbar.weapon.fire_auto";
            default -> "extraction.hud.actionbar.weapon.fire_single";
        };
        return ExtractionTexts.text(key, locale);
    }

    private static Component miniMessage(String catalogKey, String locale, Object... args) {
        return ServerTextUtil.miniMessageComponent(ExtractionTexts.text(catalogKey, locale, args));
    }

    private static boolean isEmptySegment(Component component) {
        return component == null || component.equals(Component.empty()) || ServerTextUtil.componentVisibleWidth(component) == 0;
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
        AetherScoreboardText.BuiltScoreboard built = AetherScoreboardText.builder(tick)
                .locale(locale)
                .animatedTitle(ExtractionTexts.catalogSource("extraction.hud.scoreboard.hub_title"))
                .dynamicLine("<gray>" + date)
                .dynamicCentered("<dark_gray>" + server)
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.hub.tagline_1"))
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.hub.tagline_2"))
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.hub.tagline_3"))
                .animatedCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.gold_header"))
                .dynamicCentered("<gray>" + snap.gold())
                .blank()
                .animatedCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.stats_header"))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.extractions", locale, snap.extractions()))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.kills", locale, snap.kills()))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.deaths", locale, snap.deaths()))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.coins", locale, snap.coins()))
                .blank()
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.footer"))
                .build();
        return new ScoreboardFrame(built.title(), built.lines());
    }

    private ScoreboardFrame buildBreachScoreboard(Player player, BreachInstance instance, long tick, String date, String locale) {
        BreachConfigService config = engine.configService();
        String mapName = instance == null
                ? ExtractionTexts.text("extraction.hud.scoreboard.unknown_map", locale)
                : instance.mapMeta().displayName();
        String sessionLabel = breachSessionIdLabel(instance);
        AetherScoreboardText.Builder builder = AetherScoreboardText.builder(tick)
                .locale(locale)
                .staticTitle("<gradient:dark_purple:#ff66cc><b>" + mapName)
                .dynamicLine("<gray>" + date)
                .staticCentered("<dark_gray>" + sessionLabel);

        if (instance == null) {
            AetherScoreboardText.BuiltScoreboard built = builder
                    .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.no_instance"))
                    .blank()
                    .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.use_leave"))
                    .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.footer"))
                    .build();
            return new ScoreboardFrame(built.title(), built.lines());
        }

        BreachLootChestRegistry.WorldLootStats lootStats = lootStats(instance);
        double lootPercent = BreachPlaceholderTags.lootPercent(lootStats);
        BreachExtractZoneVisualService.ExtractAvailability zoneAvailability = BreachExtractZoneVisualService.resolveAvailability(
                instance.state(),
                instance.remainingSeconds(),
                config.extractClosingSoonSeconds()
        );

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
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.raid_header"))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.alive", locale, instance.activeParticipantCount()))
                .dynamicLine(ExtractionTexts.text(
                        "extraction.hud.scoreboard.loot",
                        locale,
                        BreachPlaceholderTags.lootStateLabel(lootPercent, locale),
                        BreachPlaceholderTags.formatPercent(lootPercent)
                ))
                .dynamicLine(ExtractionTexts.text(
                        "extraction.hud.scoreboard.extract",
                        locale,
                        zoneAvailabilityLabel(zoneAvailability, locale)
                ))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.extracted_count", locale, instance.extractedCount()))
                .blank()
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.session_header"))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.raid_kills", locale, instance.sessionKills(player.getUniqueId())))
                .dynamicLine(ExtractionTexts.text("extraction.hud.scoreboard.raid_deaths", locale, instance.sessionDeaths(player.getUniqueId())));

        if (extractService.isCombatTagged(player)) {
            builder.dynamicLine(ExtractionTexts.text(
                    "extraction.hud.scoreboard.combat_tag",
                    locale,
                    extractService.combatTagRemainingSeconds(player)
            ));
        }
        if (instance.isEliminated(player.getUniqueId())) {
            builder.staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.eliminated"));
        } else if (instance.hasExtracted(player.getUniqueId())) {
            builder.staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.extracted_status"));
        } else if (instance.isInExtractZone(player.getLocation())) {
            builder.staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.in_extract_zone"));
        }

        AetherScoreboardText.BuiltScoreboard built = builder
                .blank()
                .staticCentered(ExtractionTexts.catalogSource("extraction.hud.scoreboard.footer"))
                .build();
        return new ScoreboardFrame(built.title(), built.lines());
    }

    private BreachLootChestRegistry.WorldLootStats lootStats(BreachInstance instance) {
        BreachGameplayCoordinator coordinator = engine.gameplayCoordinator();
        World world = instance.world();
        if (coordinator == null || world == null) {
            return BreachLootChestRegistry.WorldLootStats.empty();
        }
        return coordinator.lootService().chestRegistry().aggregateStatsForWorld(world);
    }

    private static String zoneAvailabilityLabel(BreachExtractZoneVisualService.ExtractAvailability availability, String locale) {
        String key = switch (availability) {
            case OPEN -> "extraction.extract.zone.open";
            case CLOSING_SOON -> "extraction.extract.zone.closing_soon";
            case CLOSED -> "extraction.extract.zone.closed";
        };
        return ExtractionTexts.text(key, locale);
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
        if (extractService.isExtracting(player)) {
            return Optional.empty();
        }
        Optional<BreachInstance> instance = engine.instanceFor(player);
        if (instance.isEmpty()) {
            return Optional.empty();
        }

        BreachInstance breach = instance.get();
        BreachState state = breach.state();
        int joinCountdown = breach.joinCountdownSeconds(player.getUniqueId());
        if (joinCountdown > 0) {
            int totalSeconds = engine.configService().joiningCountdownSeconds();
            return Optional.of(new BossBarFrame(
                    ExtractionTexts.miniMessage(player, "extraction.hud.bossbar.joining", joinCountdown),
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
        return Optional.of(new BossBarFrame(title, progress, color, BossBar.Overlay.PROGRESS));
    }

    private static String formatDuration(int seconds) {
        int minutes = seconds / 60;
        int remainder = seconds % 60;
        return String.format("%d:%02d", minutes, remainder);
    }
}
