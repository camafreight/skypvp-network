package network.skypvp.extraction.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import network.skypvp.extraction.config.BreachConfigService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.engine.BreachInstance;
import network.skypvp.extraction.text.ExtractionTexts;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class BreachCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("play", "leave", "status", "list", "set");
    private static final String TIME_SET_PERMISSION = "skypvp.breach.admin";

    private final BreachEngine engine;
    private final BreachConfigService configService;

    public BreachCommand(BreachEngine engine, BreachConfigService configService) {
        this.engine = engine;
        this.configService = configService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ExtractionTexts.text(
                    "extraction.command.players_only",
                    ExtractionTexts.defaultLocale()
            ));
            return true;
        }
        String locale = ExtractionTexts.locale(player);
        if (args.length == 0) {
            player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.command.usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(BreachCommand.class).getLogger().info(
                "[Breach] /breach " + sub + " invoked by " + player.getName()
                        + " (world=" + player.getWorld().getName() + ", thread=" + Thread.currentThread().getName() + ")");
        switch (sub) {
            case "play" -> {
                String mapId = args.length >= 2 ? args[1] : configService.defaultMapId();
                engine.play(player, mapId);
            }
            case "leave" -> engine.leave(player);
            case "status" -> player.sendMessage(
                    ExtractionTexts.miniMessage(player, "extraction.command.status", engine.localizedStatusFor(player, locale))
            );
            case "list" -> {
                List<String> maps = configService.enabledMapIds();
                if (maps.isEmpty()) {
                    player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.command.no_maps"));
                } else {
                    player.sendMessage(ExtractionTexts.miniMessage(
                            player,
                            "extraction.command.map_list",
                            String.join(", ", maps)
                    ));
                }
                List<BreachInstance> instances = engine.activeInstances();
                if (!instances.isEmpty()) {
                    player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.command.active_instances_header"));
                    for (BreachInstance instance : instances) {
                        player.sendMessage(ExtractionTexts.miniMessage(
                                player,
                                "extraction.command.instance_line",
                                instance.localizedStatusLine(locale)
                        ));
                    }
                }
            }
            case "set" -> handleSetTime(player, args);
            default -> player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.command.unknown_subcommand"));
        }
        return true;
    }

    /**
     * Test tool: {@code /breach set <timeLeftSeconds>} jumps the caller's breach clock so
     * session phases (extraction closings, toxicity, reset) can be exercised on demand.
     */
    private void handleSetTime(Player player, String[] args) {
        if (!player.hasPermission(TIME_SET_PERMISSION) && !player.isOp()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "You lack permission for /breach set.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Usage: /breach set <timeLeftSeconds>",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[1].trim());
        } catch (NumberFormatException invalid) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Not a number: " + args[1],
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        BreachInstance instance = engine.instanceFor(player).orElse(null);
        if (instance == null) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "You are not in a breach instance.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        if (instance.debugSetRemainingSeconds(seconds)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "Breach clock set: " + Math.max(0, seconds) + "s remaining. "
                            + "Zone schedules, HUD, and phase transitions follow on the next engine tick.",
                    net.kyori.adventure.text.format.NamedTextColor.GREEN));
        } else {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "The clock only applies while the breach is ACTIVE (current: "
                            + instance.state() + ").",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && "play".equalsIgnoreCase(args[0])) {
            return filterPrefix(configService.enabledMapIds(), args[1]);
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
