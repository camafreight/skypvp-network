package network.skypvp.paper.currency;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.MiniMessage;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.repository.PlayerCurrencyRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class CurrencyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("list", "add", "set", "take");
    private static final List<String> AMOUNT_SUGGESTIONS = List.of("1", "10", "100", "1000", "10000");
    private static final NumberFormat AMOUNT_FORMAT = NumberFormat.getIntegerInstance(Locale.US);
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final PaperCorePlugin plugin;
    private final CurrencyRegistry registry;

    public CurrencyCommand(PaperCorePlugin plugin, CurrencyRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skypvp.admin.currency")) {
            sender.sendMessage(MINI.deserialize("<red>You do not have permission to use this command.</red>"));
            return true;
        }

        PlayerCurrencyRepository repository = this.plugin.playerCurrencyRepository();
        if (repository == null) {
            sender.sendMessage(MINI.deserialize("<red>Currency storage is not available on this server.</red>"));
            return true;
        }

        if (args.length == 0) {
            this.sendCurrencyCatalog(sender);
            sender.sendMessage(MINI.deserialize(
                    "<gray>Usage: /currency list [player] | add|set|take <player> <currency> <amount></gray>"
            ));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "list" -> this.handleList(sender, repository, args);
            case "add" -> this.handleModify(sender, repository, args, ModifyMode.ADD);
            case "set" -> this.handleModify(sender, repository, args, ModifyMode.SET);
            case "take" -> this.handleModify(sender, repository, args, ModifyMode.TAKE);
            default -> {
                sender.sendMessage(MINI.deserialize(
                        "<red>Unknown subcommand.</red> <gray>Use: list, add, set, take</gray>"
                ));
                yield true;
            }
        };
    }

    private boolean handleList(CommandSender sender, PlayerCurrencyRepository repository, String[] args) {
        if (args.length == 1) {
            this.sendCurrencyCatalog(sender);
            return true;
        }
        OfflinePlayer target = this.resolvePlayer(sender, args[1]);
        if (target == null) {
            return true;
        }
        UUID playerId = target.getUniqueId();
        repository.ensurePlayer(playerId);
        this.plugin.platformScheduler().runAsync(() -> {
            PlayerCurrencyRepository.Balance balance = repository.getBalance(playerId).orElse(new PlayerCurrencyRepository.Balance(0L, 0L));
            String name = target.getName() == null ? args[1] : target.getName();
            this.plugin.platformScheduler().runGlobal(() -> sender.sendMessage(MINI.deserialize(
                    "<gold>" + name + "'s balances:</gold>\n"
                            + "<yellow>Gold:</yellow> <white>" + formatAmount(balance.gold()) + "</white>\n"
                            + "<yellow>Coins:</yellow> <white>" + formatAmount(balance.coins()) + "</white>"
            )));
        });
        return true;
    }

    private boolean handleModify(
            CommandSender sender,
            PlayerCurrencyRepository repository,
            String[] args,
            ModifyMode mode
    ) {
        if (args.length < 4) {
            sender.sendMessage(MINI.deserialize(
                    "<gray>Usage: /currency " + mode.label + " <player> <currency> <amount></gray>"
            ));
            return true;
        }
        OfflinePlayer target = this.resolvePlayer(sender, args[1]);
        if (target == null) {
            return true;
        }
        String currency = args[2].toLowerCase(Locale.ROOT);
        if (PlayerCurrencyRepository.validateColumn(currency) == null) {
            sender.sendMessage(MINI.deserialize(
                    "<red>Unknown currency <white>" + args[2] + "</white>.</red> "
                            + "<gray>Available: " + String.join(", ", this.registry.currencyIds()) + "</gray>"
            ));
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(MINI.deserialize("<red>Amount must be a whole number.</red>"));
            return true;
        }
        if (amount <= 0L) {
            sender.sendMessage(MINI.deserialize("<red>Amount must be greater than zero.</red>"));
            return true;
        }

        UUID playerId = target.getUniqueId();
        String targetName = target.getName() == null ? args[1] : target.getName();
        repository.ensurePlayer(playerId);

        CompletableFuture<Boolean> future = switch (mode) {
            case ADD -> repository.addCurrencyAsync(playerId, currency, amount).thenApply(ignored -> true);
            case SET -> repository.setCurrencyAsync(playerId, currency, amount).thenApply(ignored -> true);
            case TAKE -> repository.takeCurrencyAsync(playerId, currency, amount);
        };

        future.thenAcceptAsync(success -> this.plugin.platformScheduler().runGlobal(() -> {
            if (!success && mode == ModifyMode.TAKE) {
                sender.sendMessage(MINI.deserialize(
                        "<red>Could not take " + formatAmount(amount) + " " + currency
                                + " from <white>" + targetName + "</white> — insufficient balance.</red>"
                ));
                return;
            }
            String verb = switch (mode) {
                case ADD -> "Added";
                case SET -> "Set";
                case TAKE -> "Took";
            };
            sender.sendMessage(MINI.deserialize(
                    "<green>" + verb + " <white>" + formatAmount(amount) + "</white> "
                            + currency + " for <white>" + targetName + "</white>.</green>"
            ));
        }), runnable -> this.plugin.platformScheduler().runAsync(runnable))
                .exceptionally(error -> {
                    this.plugin.platformScheduler().runGlobal(() -> sender.sendMessage(MINI.deserialize(
                            "<red>Currency operation failed: " + error.getMessage() + "</red>"
                    )));
                    return null;
                });
        return true;
    }

    private void sendCurrencyCatalog(CommandSender sender) {
        sender.sendMessage(MINI.deserialize("<gold><bold>Registered currencies</bold></gold>"));
        for (String id : this.registry.currencyIds()) {
            var definition = this.registry.getRegisteredCurrencies().get(id);
            String name = definition != null && definition.has("name") ? definition.get("name").getAsString() : id;
            String description = definition != null && definition.has("description")
                    ? definition.get("description").getAsString()
                    : "";
            sender.sendMessage(MINI.deserialize(
                    "<yellow>" + id + "</yellow> <gray>—</gray> <white>" + name + "</white>"
                            + (description.isBlank() ? "" : " <dark_gray>(" + description + ")</dark_gray>")
            ));
        }
    }

    private OfflinePlayer resolvePlayer(CommandSender sender, String name) {
        if (name == null || name.isBlank()) {
            sender.sendMessage(MINI.deserialize("<red>Player name is required.</red>"));
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (!offline.hasPlayedBefore() && !offline.isOnline()) {
            sender.sendMessage(MINI.deserialize("<red>Unknown player <white>" + name + "</white>.</red>"));
            return null;
        }
        return offline;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("skypvp.admin.currency")) {
            return List.of();
        }
        if (args.length == 1) {
            return filterPrefix(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("list".equals(sub)) {
                return filterPrefix(onlinePlayerNames(), args[1]);
            }
            if (SUBCOMMANDS.contains(sub) && !"list".equals(sub)) {
                return filterPrefix(onlinePlayerNames(), args[1]);
            }
        }
        if (args.length == 3 && isModifySubcommand(args[0])) {
            return filterPrefix(this.registry.currencyIds(), args[2]);
        }
        if (args.length == 4 && isModifySubcommand(args[0])) {
            return filterPrefix(AMOUNT_SUGGESTIONS, args[3]);
        }
        return List.of();
    }

    private static boolean isModifySubcommand(String subcommand) {
        String sub = subcommand.toLowerCase(Locale.ROOT);
        return "add".equals(sub) || "set".equals(sub) || "take".equals(sub);
    }

    private static List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filterPrefix(List<String> options, String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return options.stream()
                .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(needle))
                .toList();
    }

    private static String formatAmount(long amount) {
        return AMOUNT_FORMAT.format(amount);
    }

    private enum ModifyMode {
        ADD("add"),
        SET("set"),
        TAKE("take");

        private final String label;

        ModifyMode(String label) {
            this.label = label;
        }
    }
}
