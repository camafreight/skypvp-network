package network.skypvp.extraction.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import network.skypvp.extraction.config.HitscanSettings;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.gameplay.BreachCombatDebugService;
import network.skypvp.extraction.gameplay.BreachExtractService;
import network.skypvp.extraction.gameplay.BreachFatalDamageMath;
import network.skypvp.extraction.integration.HitscanLaserDebugService;
import network.skypvp.extraction.item.ExtractionCombatDefense;
import network.skypvp.extraction.item.ShieldCombatService;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import org.bukkit.Color;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class CombatCommand implements CommandExecutor, TabCompleter {

    private static final int DEFAULT_LOG_COUNT = 15;
    private static final int MAX_LOG_COUNT = 48;
    private static final double DEFAULT_LASER_TEST_LENGTH = 24.0;
    private static final long DEFAULT_LASER_TEST_LIFETIME = 40L;
    private static final List<String> LASER_COLOR_PRESETS = List.of(
            "cyan", "red", "orange", "green", "purple", "yellow", "white", "pink"
    );

    private final BreachEngine engine;
    private final BreachExtractService extractService;
    private final PaperCorePlugin core;
    private final BreachCombatDebugService combatDebug;

    public CombatCommand(
            BreachEngine engine,
            BreachExtractService extractService,
            PaperCorePlugin core,
            BreachCombatDebugService combatDebug
    ) {
        this.engine = engine;
        this.extractService = extractService;
        this.core = core;
        this.combatDebug = combatDebug;
    }

    private static void send(CommandSender sender, String miniMessage) {
        String locale = sender instanceof Player player
                ? ExtractionTexts.locale(player)
                : ExtractionTexts.defaultLocale();
        sender.sendMessage(ExtractionTexts.miniMessageTemplate(miniMessage, locale));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "<gray>Usage: /combat <status|log|lasertest> ...");
            return true;
        }
        if (!sender.hasPermission("skypvp.combat.debug")) {
            send(sender, "<red>You do not have permission.");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "status" -> handleStatus(sender);
            case "log" -> handleLog(sender, args);
            case "lasertest" -> handleLaserTest(sender, args);
            default -> {
                send(sender, "<red>Unknown subcommand. Use status, log, or lasertest.");
                yield true;
            }
        };
    }

    private boolean handleStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }

        double health = player.getHealth();
        double absorption = player.getAbsorptionAmount();
        double vitality = BreachFatalDamageMath.remainingVitality(player);
        double defense = ExtractionCombatDefense.defenseFraction(core, player);
        double multiplier = ExtractionCombatDefense.damageMultiplier(core, player);
        String items = BreachCombatDebugService.summarizeEquippedDefense(core, player);

        double maxHealth = core != null && core.playerHealthService() != null
                ? core.playerHealthService().maxHealth(player)
                : health;
        int healthPercent = maxHealth <= 0.0D ? 0 : (int) Math.round(health / maxHealth * 100.0D);

        send(sender, "<aqua>--- Combat Status ---");
        send(sender, "<gray>Health: <white>" + formatHp(health) + "<gray>/<white>" + formatHp(maxHealth)
                + " <gray>(" + healthPercent + "%) <gray>+ absorption <white>"
                + formatHp(absorption) + " <gray>= vitality <white>" + formatHp(vitality));
        send(sender, "<gray>Infuse defense: <white>" + Math.round(defense * 100.0D) + "% <gray>(multiplier "
                + String.format(Locale.ROOT, "%.2f", multiplier) + ")");
        ShieldCombatService.equippedShield(core, player).ifPresent(shield -> {
            if (shield.destroyed()) {
                send(sender, "<gray>Shield: <red>DESTROYED <gray>(repair at armory)");
            } else {
                send(sender, "<gray>Shield: <aqua>" + shield.displayLabel() + " <white>"
                        + String.format(Locale.ROOT, "%.1f/%.1f", shield.currentPoints(), shield.maxPoints())
                        + " <gray>| integrity <white>"
                        + String.format(Locale.ROOT, "%.0f/%.0f", shield.remainingIntegrity(), shield.integrity()));
            }
        });
        send(sender, "<gray>Custom gear: <yellow>" + items + "</yellow>");

        if (extractService.isCombatTagged(player)) {
            send(sender, "<gray>Combat tag: <red>active <gray>("
                    + extractService.combatTagRemainingSeconds(player) + "s remaining)");
        } else {
            send(sender, "<gray>Combat tag: <green>inactive");
        }

        Optional<BreachInstance> instanceOptional = engine.instanceFor(player);
        if (instanceOptional.isEmpty()) {
            send(sender, "<gray>Breach: <white>not in raid");
            return true;
        }
        BreachInstance instance = instanceOptional.get();
        send(sender, "<gray>Breach: <white>" + instance.mapMeta().displayName()
                + " <gray>(" + instance.instanceId() + ") | state " + instance.state().name());
        if (engine.isSpectating(player)) {
            send(sender, "<gray>Role: <yellow>spectator");
        } else if (instance.isEliminated(player.getUniqueId())) {
            send(sender, "<gray>Role: <red>eliminated");
        } else if (instance.hasExtracted(player.getUniqueId())) {
            send(sender, "<gray>Role: <green>extracted");
        } else {
            send(sender, "<gray>Role: <green>active raider");
        }
        return true;
    }

    private boolean handleLog(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }

        int count = DEFAULT_LOG_COUNT;
        if (args.length >= 2) {
            try {
                count = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                send(sender, "<red>Count must be a number (1-" + MAX_LOG_COUNT + ").");
                return true;
            }
        }
        count = Math.max(1, Math.min(count, MAX_LOG_COUNT));

        List<BreachCombatDebugService.CombatLogEntry> entries = combatDebug.recentLogs(player.getUniqueId(), count);
        send(sender, "<aqua>--- Combat Log (last " + entries.size() + ") ---");
        if (entries.isEmpty()) {
            send(sender, "<gray>No combat events recorded yet. Take damage in an active breach raid.");
            return true;
        }
        String locale = ExtractionTexts.locale(player);
        for (BreachCombatDebugService.CombatLogEntry entry : entries) {
            Component line = ExtractionTexts.miniMessageTemplate(combatDebug.formatEntry(entry), locale);
            player.sendMessage(line);
        }
        return true;
    }

    private boolean handleLaserTest(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }

        HitscanLaserDebugService debug = HitscanLaserDebugService.optional().orElse(null);
        if (debug == null) {
            send(sender, "<red>Hitscan laser debug is unavailable (hitscan disabled or WM missing).");
            return true;
        }

        HitscanSettings settings = debug.settings();
        Color fallback = settings.laserGlowColor();
        Color color = fallback;
        if (args.length >= 2) {
            color = parseLaserColor(args[1], fallback);
        }

        double length = DEFAULT_LASER_TEST_LENGTH;
        if (args.length >= 3) {
            try {
                length = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                send(sender, "<red>Length must be a number (blocks).");
                return true;
            }
        }
        length = Math.max(2.0, Math.min(length, settings.maxRangeBlocks()));

        double thickness = settings.laserThickness();
        if (args.length >= 4) {
            try {
                thickness = Double.parseDouble(args[3]);
            } catch (NumberFormatException ex) {
                send(sender, "<red>Thickness must be a number.");
                return true;
            }
        }
        thickness = Math.max(0.08, Math.min(thickness, 2.0));

        long lifetime = DEFAULT_LASER_TEST_LIFETIME;
        if (args.length >= 5) {
            try {
                lifetime = Long.parseLong(args[4]);
            } catch (NumberFormatException ex) {
                send(sender, "<red>Lifetime must be a whole number of ticks.");
                return true;
            }
        }
        lifetime = Math.max(5L, Math.min(lifetime, 200L));

        debug.spawnPreview(player, color, length, thickness, lifetime);
        send(sender, "<green>Laser preview spawned "
                + "<gray>(color <white>#" + String.format(Locale.ROOT, "%06X", color.asRGB() & 0xFFFFFF)
                + "<gray>, length <white>" + String.format(Locale.ROOT, "%.1f", length)
                + "<gray>, thickness <white>" + String.format(Locale.ROOT, "%.2f", thickness)
                + "<gray>, lifetime <white>" + lifetime + "t<gray>).");
        send(sender, "<gray>Config color key: <white>hitscan.laser.color <gray>or <white>glow-color");
        return true;
    }

    private static Color parseLaserColor(String raw, Color fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "cyan" -> Color.fromRGB(0x40F0FF);
            case "red" -> Color.fromRGB(0xFF4040);
            case "orange" -> Color.fromRGB(0xFF9020);
            case "green" -> Color.fromRGB(0x40FF80);
            case "purple" -> Color.fromRGB(0xC060FF);
            case "yellow" -> Color.fromRGB(0xFFE040);
            case "white" -> Color.WHITE;
            case "pink" -> Color.fromRGB(0xFF60C0);
            default -> HitscanSettings.parseColor(raw, fallback);
        };
    }

    private static String formatHp(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("skypvp.combat.debug")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(List.of("status", "log", "lasertest"), args[0]);
        }
        if (args.length == 2 && "log".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("10", "15", "25", "48"), args[1]);
        }
        if (args.length == 2 && "lasertest".equalsIgnoreCase(args[0])) {
            return filterPrefix(LASER_COLOR_PRESETS, args[1]);
        }
        if (args.length == 3 && "lasertest".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("12", "24", "48", "64"), args[2]);
        }
        if (args.length == 4 && "lasertest".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("0.35", "0.45", "0.6", "0.8"), args[3]);
        }
        return List.of();
    }

    private static List<String> filterPrefix(List<String> values, String prefix) {
        String normalized = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
