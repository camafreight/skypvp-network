package network.skypvp.paper.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.library.InteractionActionTypes;
import network.skypvp.paper.model.WorldPoint;
import network.skypvp.shared.ServerTextUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/**
 * {@code /quest} — staff tooling for the dynamic quest NPC system.
 *
 * <ul>
 *   <li>{@code /quest location add|set|remove|alias|list|tp|scope} — shared POI pool with alias
 *       sub-locations so NPCs sharing a POI don't stack on the same block; scope moves POIs
 *       between gamemodes (lobby ↔ extraction)</li>
 *   <li>{@code /quest npc …} — create real wandering NPCs, assign POIs (tab-completed from the
 *       pool), daily schedules, greetings, interact actions, and hub-join waypoint beacons</li>
 *   <li>{@code /quest clock …} — the schedule clock (virtual day for hubs with frozen world time)</li>
 * </ul>
 */
public final class QuestCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX_ERR = "<#FF5555>[!]</#FF5555> <#888888>";
    private static final String PREFIX_OK = "<#55FF88>[✓]</#55FF88> <#cccccc>";

    private final PaperCorePlugin plugin;
    private final QuestNpcService service;

    public QuestCommand(PaperCorePlugin plugin, QuestNpcService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!player.hasPermission("skypvp.staff")) {
            err(player, "No permission.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "location", "loc" -> handleLocation(player, args);
            case "npc" -> handleNpc(player, args);
            case "clock" -> handleClock(player, args);
            case "debug" -> handleDebug(player);
            case "reload" -> {
                service.reload();
                ok(player, "Quest locations, NPCs, and clock reloaded from the database.");
            }
            default -> sendHelp(player);
        }
        return true;
    }

    // --- /quest location -----------------------------------------------------------------------

    private void handleLocation(Player player, String[] args) {
        if (args.length < 2) {
            sendLocationHelp(player);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) {
                    err(player, "Usage: /quest location add <name>");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                if (service.locations().get(name) != null) {
                    err(player, "Location '" + name + "' already exists — use /quest location set " + name + " to move it.");
                    return;
                }
                QuestPoi created = new QuestPoi(name, WorldPoint.fromLocation(player.getLocation()));
                created.scope = service.decorationScope();
                service.locations().put(created);
                ok(player, "Location <white>" + name + "</white> added in scope <aqua>" + created.scope
                        + "</aqua> at your position.");
            }
            case "set" -> {
                if (args.length < 3) {
                    err(player, "Usage: /quest location set <name>");
                    return;
                }
                QuestPoi poi = service.locations().get(args[2]);
                if (poi == null) {
                    err(player, "Unknown location '" + args[2] + "'.");
                    return;
                }
                poi.anchor = WorldPoint.fromLocation(player.getLocation());
                service.locations().put(poi);
                ok(player, "Location <white>" + poi.name + "</white> moved to your position.");
            }
            case "remove" -> {
                if (args.length < 3) {
                    err(player, "Usage: /quest location remove <name>");
                    return;
                }
                String name = args[2].toLowerCase(Locale.ROOT);
                List<String> users = npcsUsingPoi(name);
                if (!service.locations().remove(name)) {
                    err(player, "Unknown location '" + name + "'.");
                    return;
                }
                ok(player, "Location <white>" + name + "</white> removed."
                        + (users.isEmpty() ? "" : " <#FFAA55>Still referenced by: " + String.join(", ", users) + "</#FFAA55>"));
            }
            case "alias" -> handleLocationAlias(player, args);
            case "list" -> {
                Map<String, QuestPoi> all = new LinkedHashMap<>(service.locations().all());
                if (all.isEmpty()) {
                    err(player, "No quest locations in scope <aqua>" + service.decorationScope()
                            + "</aqua> — stand somewhere and run /quest location add <name>, "
                            + "or move one with /quest location scope <name> "
                            + service.decorationScope() + " from the other gamemode.");
                    return;
                }
                msg(player, "<gold><bold>Quest locations</bold></gold> <gray>scope=<aqua>"
                        + service.decorationScope() + "</aqua> (" + all.size() + ")</gray>");
                for (QuestPoi poi : all.values()) {
                    msg(player, "<gray>• <white>" + poi.name + "</white> <gray>scope=<aqua>"
                            + (poi.normalizedScope().isEmpty() ? service.decorationScope() : poi.normalizedScope())
                            + "</aqua> @ "
                            + formatPoint(poi.anchor)
                            + (poi.aliases.isEmpty() ? "" : " — aliases: <aqua>" + String.join(", ", poi.aliases.keySet()) + "</aqua>"));
                }
            }
            case "tp" -> {
                if (args.length < 3) {
                    err(player, "Usage: /quest location tp <name[:alias]>");
                    return;
                }
                WorldPoint point = service.locations().resolve(args[2]);
                World world = point == null ? null : plugin.getServer().getWorld(point.world);
                if (point == null || world == null) {
                    err(player, "Unknown location '" + args[2] + "' (or its world isn't loaded).");
                    return;
                }
                player.teleportAsync(new Location(world, point.x, point.y, point.z, point.yaw, point.pitch));
                ok(player, "Teleported to <white>" + args[2].toLowerCase(Locale.ROOT) + "</white>.");
            }
            case "scope" -> {
                if (args.length < 4) {
                    err(player, "Usage: /quest location scope <name> <lobby|extraction>");
                    return;
                }
                QuestPoi poi = service.locations().get(args[2]);
                if (poi == null) {
                    err(player, "Unknown location '" + args[2] + "'.");
                    return;
                }
                String newScope = QuestScopes.normalize(args[3]);
                if (!QuestScopes.isQuestScope(newScope)) {
                    err(player, "Scope must be one of: " + String.join(", ", QuestScopes.KNOWN));
                    return;
                }
                String oldScope = poi.normalizedScope().isEmpty()
                        ? service.decorationScope() : poi.normalizedScope();
                if (oldScope.equals(newScope)) {
                    ok(player, "<white>" + poi.name + "</white> is already in scope <aqua>" + newScope + "</aqua>.");
                    return;
                }
                List<String> users = npcsUsingPoi(poi.name);
                if (!service.moveLocationScope(poi, newScope)) {
                    err(player, "Could not move <white>" + poi.name + "</white> — target scope may already have that name.");
                    return;
                }
                ok(player, "Moved location <white>" + poi.name + "</white> <gray>" + oldScope + " → </gray><aqua>"
                        + newScope + "</aqua>. Reload the destination gamemode (or wait for boot) to load it."
                        + (users.isEmpty() ? "" : " <#FFAA55>Still referenced by NPCs here: "
                        + String.join(", ", users) + "</#FFAA55>"));
            }
            default -> sendLocationHelp(player);
        }
    }

    private void handleLocationAlias(Player player, String[] args) {
        // /quest location alias <add|remove|list> <location> [alias]
        if (args.length < 4) {
            err(player, "Usage: /quest location alias <add|remove|list> <location> [alias]");
            return;
        }
        QuestPoi poi = service.locations().get(args[3]);
        if (poi == null) {
            err(player, "Unknown location '" + args[3] + "'.");
            return;
        }
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 5) {
                    err(player, "Usage: /quest location alias add <location> <alias> (stand on the sub-spot first)");
                    return;
                }
                String alias = args[4].toLowerCase(Locale.ROOT);
                poi.aliases.put(alias, WorldPoint.fromLocation(player.getLocation()));
                service.locations().put(poi);
                ok(player, "Alias <aqua>" + poi.name + ":" + alias + "</aqua> saved at your position.");
            }
            case "remove" -> {
                if (args.length < 5) {
                    err(player, "Usage: /quest location alias remove <location> <alias>");
                    return;
                }
                String alias = args[4].toLowerCase(Locale.ROOT);
                if (poi.aliases.remove(alias) == null) {
                    err(player, "Location '" + poi.name + "' has no alias '" + alias + "'.");
                    return;
                }
                service.locations().put(poi);
                ok(player, "Alias <aqua>" + poi.name + ":" + alias + "</aqua> removed.");
            }
            case "list" -> {
                msg(player, "<gold>Aliases of <white>" + poi.name + "</white>:</gold>");
                msg(player, "<gray>• <white>(anchor)</white> @ " + formatPoint(poi.anchor));
                poi.aliases.forEach((alias, point) ->
                        msg(player, "<gray>• <aqua>" + alias + "</aqua> @ " + formatPoint(point)));
            }
            default -> err(player, "Usage: /quest location alias <add|remove|list> <location> [alias]");
        }
    }

    // --- /quest npc ------------------------------------------------------------------------

    private void handleNpc(Player player, String[] args) {
        if (args.length < 2) {
            sendNpcHelp(player);
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("create")) {
            if (args.length < 3) {
                err(player, "Usage: /quest npc create <id> [entityType]");
                return;
            }
            String id = args[2].toLowerCase(Locale.ROOT);
            if (service.profile(id) != null) {
                err(player, "Quest NPC '" + id + "' already exists.");
                return;
            }
            QuestNpcProfile profile = new QuestNpcProfile();
            profile.id = id;
            profile.displayName = "<gold><bold>" + id + "</bold></gold>";
            profile.home = WorldPoint.fromLocation(player.getLocation());
            profile.scope = service.decorationScope();
            if (args.length >= 4) {
                if (!isMobType(args[3])) {
                    err(player, "'" + args[3] + "' is not a spawnable mob type.");
                    return;
                }
                profile.entityType = args[3].toUpperCase(Locale.ROOT);
            }
            service.putProfile(profile);
            ok(player, "Quest NPC <white>" + id + "</white> created in scope <aqua>" + profile.scope
                    + "</aqua> — home set to your position. "
                    + "Assign POIs with <white>/quest npc poi add " + id + " <location></white>.");
            return;
        }
        if (sub.equals("list")) {
            Map<String, QuestNpcProfile> all = service.profiles();
            if (all.isEmpty()) {
                err(player, "No quest NPCs yet — /quest npc create <id>.");
                return;
            }
            msg(player, "<gold><bold>Quest NPCs</bold></gold> <gray>scope=<aqua>"
                    + service.decorationScope() + "</aqua> (" + all.size() + ")</gray>");
            for (String line : service.debugLines()) {
                msg(player, line);
            }
            return;
        }
        if (sub.equals("scope")) {
            if (args.length < 4) {
                err(player, "Usage: /quest npc scope <id> <lobby|extraction>");
                return;
            }
            QuestNpcProfile profile = service.profile(args[2]);
            if (profile == null) {
                err(player, "Unknown quest NPC '" + args[2] + "'.");
                return;
            }
            String newScope = QuestScopes.normalize(args[3]);
            if (!QuestScopes.isQuestScope(newScope)) {
                err(player, "Scope must be one of: " + String.join(", ", QuestScopes.KNOWN));
                return;
            }
            String oldScope = profile.normalizedScope().isEmpty()
                    ? service.decorationScope() : profile.normalizedScope();
            if (oldScope.equals(newScope)) {
                ok(player, "<white>" + profile.key() + "</white> is already in scope <aqua>" + newScope + "</aqua>.");
                return;
            }
            if (!service.moveProfileScope(profile, newScope)) {
                err(player, "Could not move <white>" + profile.key() + "</white> — target scope may already have that id.");
                return;
            }
            ok(player, "Moved <white>" + profile.key() + "</white> <gray>" + oldScope + " → </gray><aqua>"
                    + newScope + "</aqua>. Reload the destination gamemode (or wait for boot) to spawn it.");
            return;
        }
        if (args.length < 3) {
            sendNpcHelp(player);
            return;
        }
        QuestNpcProfile profile = service.profile(args[2]);
        if (profile == null) {
            err(player, "Unknown quest NPC '" + args[2] + "'.");
            return;
        }
        switch (sub) {
            case "remove" -> {
                service.removeProfile(profile.id);
                ok(player, "Quest NPC <white>" + profile.key() + "</white> removed.");
            }
            case "info" -> sendNpcInfo(player, profile);
            case "name" -> handleNpcName(player, profile, args);
            case "entity" -> {
                if (args.length < 4 || !isMobType(args[3])) {
                    err(player, "Usage: /quest npc entity <id> <mobType>");
                    return;
                }
                profile.entityType = args[3].toUpperCase(Locale.ROOT);
                service.applyProfileEdit(profile, true);
                ok(player, "Entity type set to <white>" + profile.entityType + "</white> (NPC respawns shortly).");
            }
            case "home" -> {
                profile.home = WorldPoint.fromLocation(player.getLocation());
                service.applyProfileEdit(profile, false);
                ok(player, "Home of <white>" + profile.key() + "</white> set to your position.");
            }
            case "schedule" -> {
                if (args.length < 5) {
                    err(player, "Usage: /quest npc schedule <id> <start> <end> — HH:MM or ticks (0 = 06:00).");
                    return;
                }
                long start = QuestClock.parseTimeOfDay(args[3]);
                long end = QuestClock.parseTimeOfDay(args[4]);
                if (start < 0 || end < 0) {
                    err(player, "Couldn't parse times — use HH:MM (e.g. 09:30) or ticks 0..23999.");
                    return;
                }
                profile.scheduleStartTick = (int) start;
                profile.scheduleEndTick = (int) end;
                service.applyProfileEdit(profile, false);
                ok(player, "Schedule set: <white>" + QuestClock.formatTick(start) + "</white> → <white>"
                        + QuestClock.formatTick(end) + "</white> (" + start + "t → " + end + "t).");
            }
            case "wander" -> {
                if (args.length < 4) {
                    err(player, "Usage: /quest npc wander <id> <radius|off>");
                    return;
                }
                double radius = args[3].equalsIgnoreCase("off") ? 0.0D : parseDouble(args[3], -1.0D);
                if (radius < 0.0D) {
                    err(player, "Radius must be a number ≥ 0 (or 'off').");
                    return;
                }
                profile.wanderRadius = radius;
                service.applyProfileEdit(profile, false);
                ok(player, radius <= 0.0D ? "Strolling disabled." : "Stroll radius set to <white>" + radius + "</white> blocks.");
            }
            case "speed" -> {
                double speed = args.length >= 4 ? parseDouble(args[3], -1.0D) : -1.0D;
                if (speed <= 0.0D || speed > 3.0D) {
                    err(player, "Usage: /quest npc speed <id> <0.1..3.0>");
                    return;
                }
                profile.walkSpeed = speed;
                service.applyProfileEdit(profile, false);
                ok(player, "Walk speed set to <white>" + speed + "</white>.");
            }
            case "poi" -> handleNpcPoi(player, profile, args);
            case "greet" -> {
                if (args.length < 4) {
                    err(player, "Usage: /quest npc greet <id> <message…|off>");
                    return;
                }
                if (args[3].equalsIgnoreCase("off")) {
                    profile.greeting = null;
                    service.applyProfileEdit(profile, false);
                    ok(player, "Greeting disabled.");
                    return;
                }
                profile.greeting = joinFrom(args, 3);
                service.applyProfileEdit(profile, false);
                ok(player, "Greeting set — whispered to players within 4 blocks (60s cooldown).");
            }
            case "action" -> {
                if (args.length < 4) {
                    err(player, "Usage: /quest npc action <id> <TYPE> [data…] — types: "
                            + String.join(", ", InteractionActionTypes.NPC_TAB_TYPES));
                    return;
                }
                profile.actionType = args[3].toUpperCase(Locale.ROOT);
                profile.actionData = args.length >= 5 ? joinFrom(args, 4) : "";
                service.applyProfileEdit(profile, false);
                ok(player, "Interact action set to <white>" + profile.actionType + "</white>.");
            }
            case "beacon" -> {
                boolean on = args.length >= 4 ? args[3].equalsIgnoreCase("on") : !profile.beacon;
                profile.beacon = on;
                service.applyProfileEdit(profile, false);
                ok(player, on
                        ? "Beacon enabled — players entering the world get a navigator beam to this NPC while it's on duty."
                        : "Beacon disabled.");
            }
            case "pause" -> {
                profile.paused = true;
                service.applyProfileEdit(profile, false);
                ok(player, "<white>" + profile.key() + "</white> paused (frozen in place).");
            }
            case "resume" -> {
                profile.paused = false;
                service.applyProfileEdit(profile, false);
                ok(player, "<white>" + profile.key() + "</white> resumed.");
            }
            case "tp" -> {
                QuestNpcAgent agent = service.agent(profile.id);
                if (agent == null || !agent.entity().isValid()) {
                    err(player, "NPC isn't spawned right now.");
                    return;
                }
                player.teleportAsync(agent.currentLocation());
                ok(player, "Teleported to <white>" + profile.key() + "</white>.");
            }
            case "summon" -> {
                profile.home = WorldPoint.fromLocation(player.getLocation());
                service.applyProfileEdit(profile, true);
                ok(player, "Home moved here; <white>" + profile.key() + "</white> respawns at your position shortly.");
            }
            default -> sendNpcHelp(player);
        }
    }

    private void handleNpcPoi(Player player, QuestNpcProfile profile, String[] args) {
        // /quest npc poi <id> handled upstream; here: args[3] = add|remove|list, args[4] = ref
        if (args.length < 4) {
            err(player, "Usage: /quest npc poi <id> <add|remove|list> [location[:alias]]");
            return;
        }
        switch (args[3].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 5) {
                    err(player, "Usage: /quest npc poi <id> add <location[:alias]>");
                    return;
                }
                String ref = args[4].toLowerCase(Locale.ROOT);
                if (!service.locations().refExists(ref)) {
                    err(player, "Unknown POI ref '" + ref + "' — create it with /quest location add first.");
                    return;
                }
                if (profile.pois.contains(ref)) {
                    err(player, "NPC already has POI '" + ref + "'.");
                    return;
                }
                profile.pois.add(ref);
                service.applyProfileEdit(profile, false);
                ok(player, "POI <aqua>" + ref + "</aqua> assigned to <white>" + profile.key() + "</white>.");
            }
            case "remove" -> {
                if (args.length < 5 || !profile.pois.remove(args[4].toLowerCase(Locale.ROOT))) {
                    err(player, "Usage: /quest npc poi <id> remove <assigned-ref>");
                    return;
                }
                service.applyProfileEdit(profile, false);
                ok(player, "POI <aqua>" + args[4].toLowerCase(Locale.ROOT) + "</aqua> unassigned.");
            }
            case "list" -> {
                msg(player, "<gold>POIs of <white>" + profile.key() + "</white>:</gold> "
                        + (profile.pois.isEmpty() ? "<gray>none</gray>" : "<aqua>" + String.join(", ", profile.pois) + "</aqua>"));
            }
            default -> err(player, "Usage: /quest npc poi <id> <add|remove|list> [location[:alias]]");
        }
    }

    private void sendNpcInfo(Player player, QuestNpcProfile profile) {
        QuestNpcAgent agent = service.agent(profile.id);
        msg(player, "<gold><bold>" + profile.key() + "</bold></gold> <gray>(" + profile.entityType + ")</gray> — " + profile.displayName);
        msg(player, "<gray>• Scope: <aqua>" + (profile.normalizedScope().isEmpty()
                ? service.decorationScope() : profile.normalizedScope()) + "</aqua>");
        msg(player, "<gray>• Home: " + formatPoint(profile.home));
        msg(player, "<gray>• Schedule: <white>" + QuestClock.formatTick(profile.scheduleStartTick) + " → "
                + QuestClock.formatTick(profile.scheduleEndTick) + "</white> <gray>(" + profile.scheduleStartTick
                + "t → " + profile.scheduleEndTick + "t)");
        msg(player, "<gray>• POIs: " + (profile.pois.isEmpty() ? "none" : "<aqua>" + String.join(", ", profile.pois) + "</aqua>"));
        msg(player, "<gray>• Stroll: <white>" + profile.wanderRadius + "</white> — Speed: <white>" + profile.walkSpeed
                + "</white> — Beacon: " + (profile.beacon ? "<green>on</green>" : "<red>off</red>")
                + " — Paused: " + (profile.paused ? "<red>yes</red>" : "<green>no</green>"));
        msg(player, "<gray>• Action: <white>" + profile.actionType + "</white>"
                + (profile.actionData == null || profile.actionData.isBlank() ? "" : " <gray>(" + profile.actionData + ")"));
        msg(player, "<gray>• Live: state=<yellow>" + (agent == null ? "DESPAWNED" : agent.state())
                + "</yellow> slot=<aqua>" + (agent == null ? "-" : agent.slotLabel()) + "</aqua>");
    }

    // --- /quest clock ------------------------------------------------------------------------

    private void handleClock(Player player, String[] args) {
        QuestClock clock = service.clock();
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            long tick = clock.tickOfDay(player.getWorld());
            msg(player, "<gold><bold>Quest clock</bold></gold> <gray>mode=<white>" + clock.mode()
                    + "</white> time=<white>" + QuestClock.formatTick(tick) + "</white> (" + tick + "t)"
                    + (clock.mode() == QuestClock.Mode.VIRTUAL
                    ? " speed=<white>" + clock.settings().ticksPerSecond + "</white> ticks/s" : ""));
            msg(player, "<gray>Note: hub worlds pin World#getTime — VIRTUAL keeps schedules moving there.");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "mode" -> {
                if (args.length < 3 || (!args[2].equalsIgnoreCase("world") && !args[2].equalsIgnoreCase("virtual"))) {
                    err(player, "Usage: /quest clock mode <world|virtual>");
                    return;
                }
                clock.setMode(args[2].equalsIgnoreCase("world") ? QuestClock.Mode.WORLD : QuestClock.Mode.VIRTUAL);
                service.saveClock();
                ok(player, "Clock mode set to <white>" + clock.mode() + "</white>.");
            }
            case "speed" -> {
                double speed = args.length >= 3 ? parseDouble(args[2], -1.0D) : -1.0D;
                if (speed < 0.0D) {
                    err(player, "Usage: /quest clock speed <ticksPerSecond> (20 = vanilla pace)");
                    return;
                }
                clock.setTicksPerSecond(speed);
                service.saveClock();
                ok(player, "Virtual clock speed set to <white>" + speed + "</white> ticks/s.");
            }
            case "set" -> {
                long tick = args.length >= 3 ? QuestClock.parseTimeOfDay(args[2]) : -1L;
                if (tick < 0) {
                    err(player, "Usage: /quest clock set <HH:MM|ticks>");
                    return;
                }
                clock.setTimeOfDay(tick);
                service.saveClock();
                ok(player, "Quest time set to <white>" + QuestClock.formatTick(tick) + "</white> (" + tick + "t). "
                        + "NPCs adjust on their next tick.");
            }
            default -> err(player, "Usage: /quest clock <status|mode|speed|set>");
        }
    }

    private void handleDebug(Player player) {
        msg(player, "<gold><bold>Quest debug</bold></gold> <gray>time=<white>"
                + QuestClock.formatTick(service.clock().tickOfDay(player.getWorld())) + "</white>");
        List<String> lines = service.debugLines();
        if (lines.isEmpty()) {
            msg(player, "<gray>No quest NPCs.");
        }
        for (String line : lines) {
            msg(player, line);
        }
        Map<String, String> reservations = service.locations().reservationsSnapshot();
        if (!reservations.isEmpty()) {
            msg(player, "<gold>Slot reservations:</gold>");
            reservations.forEach((slot, npc) -> msg(player, "<gray>• <aqua>" + slot + "</aqua> ← <white>" + npc + "</white>"));
        }
    }

    // --- Help / util -------------------------------------------------------------------------

    private void sendHelp(Player player) {
        msg(player, "<gold><bold>/quest</bold></gold> <gray>— dynamic quest NPC system</gray>");
        msg(player, "<gray>• <white>/quest location</white> add|set|remove|alias|list|tp|scope — shared POI pool");
        msg(player, "<gray>• <white>/quest npc</white> create|remove|list|info|home|schedule|poi|wander|speed|name|entity|greet|action|beacon|pause|resume|tp|summon|scope");
        msg(player, "<gray>• <white>/quest clock</white> status|mode|speed|set — schedule clock");
        msg(player, "<gray>• <white>/quest debug</white> · <white>/quest reload</white>");
    }

    private void sendLocationHelp(Player player) {
        msg(player, "<gold>/quest location</gold> <gray>add <name> · set <name> · remove <name> · list · tp <name[:alias]>");
        msg(player, "<gold>/quest location alias</gold> <gray>add <location> <alias> · remove <location> <alias> · list <location>");
        msg(player, "<gold>/quest location scope</gold> <gray><name> <lobby|extraction> — move POI between gamemodes");
        msg(player, "<gray>Aliases are sub-spots of a location so NPCs sharing it don't stand on the same block.");
    }

    /**
     * {@code /quest npc name <id> …} — multi-line nametag editing.
     * Bare text keeps the legacy behavior (sets line 1); {@code add}/{@code set}/{@code remove}/{@code list}
     * manage the extra lines rendered under it.
     */
    private void handleNpcName(Player player, QuestNpcProfile profile, String[] args) {
        if (args.length < 4) {
            err(player, "Usage: /quest npc name <id> <minimessage…> · add <mm…> · set <line> <mm…> · remove <line> · list");
            return;
        }
        if (profile.extraNameLines == null) {
            profile.extraNameLines = new ArrayList<>();
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        int totalLines = 1 + profile.extraNameLines.size();
        switch (action) {
            case "add" -> {
                if (args.length < 5) {
                    err(player, "Usage: /quest npc name <id> add <minimessage…>");
                    return;
                }
                profile.extraNameLines.add(joinFrom(args, 4));
                service.applyNameEdit(profile);
                ok(player, "Added name line <white>" + (totalLines + 1) + "</white>: " + joinFrom(args, 4));
            }
            case "set" -> {
                if (args.length < 6) {
                    err(player, "Usage: /quest npc name <id> set <line> <minimessage…>");
                    return;
                }
                int line = (int) parseDouble(args[4], -1.0D);
                if (line < 1 || line > totalLines) {
                    err(player, "Line must be 1.." + totalLines + " — see /quest npc name " + profile.key() + " list.");
                    return;
                }
                String text = joinFrom(args, 5);
                if (line == 1) {
                    profile.displayName = text;
                } else {
                    profile.extraNameLines.set(line - 2, text);
                }
                service.applyNameEdit(profile);
                ok(player, "Name line <white>" + line + "</white> updated: " + text);
            }
            case "remove" -> {
                if (args.length < 5) {
                    err(player, "Usage: /quest npc name <id> remove <line>");
                    return;
                }
                int line = (int) parseDouble(args[4], -1.0D);
                if (line < 1 || line > totalLines) {
                    err(player, "Line must be 1.." + totalLines + " — see /quest npc name " + profile.key() + " list.");
                    return;
                }
                if (line == 1) {
                    if (profile.extraNameLines.isEmpty()) {
                        err(player, "Cannot remove the only name line — use <white>set 1 <minimessage></white> instead.");
                        return;
                    }
                    // Promote the next line so the NPC always keeps a primary display name.
                    profile.displayName = profile.extraNameLines.remove(0);
                } else {
                    profile.extraNameLines.remove(line - 2);
                }
                service.applyNameEdit(profile);
                ok(player, "Name line <white>" + line + "</white> removed.");
            }
            case "list" -> {
                msg(player, "<gold><bold>Name lines</bold></gold> <gray>for <white>" + profile.key() + "</white>:</gray>");
                int index = 1;
                for (String line : profile.nameLines()) {
                    msg(player, "<gray>" + index++ + ".</gray> " + line);
                }
            }
            default -> {
                profile.displayName = joinFrom(args, 3);
                service.applyNameEdit(profile);
                ok(player, "Display name updated.");
            }
        }
    }

    private void sendNpcHelp(Player player) {
        msg(player, "<gold>/quest npc</gold> <gray>create <id> [mobType] · remove|info|tp|summon|pause|resume <id> · list");
        msg(player, "<gray>· home <id> (feet) · schedule <id> <start> <end> · poi <id> add|remove|list [ref]");
        msg(player, "<gray>· wander <id> <radius|off> · speed <id> <n> · entity <id> <type>");
        msg(player, "<gray>· name <id> <mm> | add <mm> | set <line> <mm> | remove <line> | list");
        msg(player, "<gray>· greet <id> <msg|off> · action <id> <TYPE> [data] · beacon <id> on|off");
        msg(player, "<gray>· scope <id> <lobby|extraction> — move NPC between gamemodes");
    }

    private List<String> npcsUsingPoi(String poiName) {
        List<String> users = new ArrayList<>();
        for (QuestNpcProfile profile : service.profiles().values()) {
            for (String ref : profile.pois) {
                if (ref.equals(poiName) || ref.startsWith(poiName + ":")) {
                    users.add(profile.key());
                    break;
                }
            }
        }
        return users;
    }

    private static String formatPoint(WorldPoint point) {
        if (point == null) {
            return "<red>unset</red>";
        }
        return "<white>" + point.world + " " + Math.round(point.x) + "," + Math.round(point.y) + "," + Math.round(point.z) + "</white>";
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String joinFrom(String[] args, int start) {
        return String.join(" ", java.util.Arrays.asList(args).subList(start, args.length));
    }

    private void msg(Player player, String miniMessage) {
        player.sendMessage(ServerTextUtil.miniMessageComponent(miniMessage));
    }

    private void ok(Player player, String miniMessage) {
        msg(player, PREFIX_OK + miniMessage + "</#888888>");
    }

    private void err(Player player, String miniMessage) {
        msg(player, PREFIX_ERR + miniMessage + "</#888888>");
    }

    // --- Tab completion ------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("skypvp.staff")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("location", "npc", "clock", "debug", "reload"), args[0]);
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "location", "loc" -> completeLocation(args);
            case "npc" -> completeNpc(args);
            case "clock" -> completeClock(args);
            default -> List.of();
        };
    }

    private List<String> completeLocation(String[] args) {
        if (args.length == 2) {
            return filter(List.of("add", "set", "remove", "alias", "list", "tp", "scope"), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("alias")) {
            if (args.length == 3) {
                return filter(List.of("add", "remove", "list"), args[2]);
            }
            if (args.length == 4) {
                return filter(service.locations().names(), args[3]);
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("remove")) {
                QuestPoi poi = service.locations().get(args[3]);
                return poi == null ? List.of() : filter(new ArrayList<>(poi.aliases.keySet()), args[4]);
            }
            return List.of();
        }
        if (args.length == 3 && (sub.equals("set") || sub.equals("remove") || sub.equals("scope"))) {
            return filter(service.locations().names(), args[2]);
        }
        if (args.length == 3 && sub.equals("tp")) {
            return filter(allPoiRefs(), args[2]);
        }
        if (args.length == 4 && sub.equals("scope")) {
            return filter(QuestScopes.KNOWN, args[3]);
        }
        return List.of();
    }

    private List<String> completeNpc(String[] args) {
        if (args.length == 2) {
            return filter(List.of(
                    "create", "remove", "list", "info", "home", "schedule", "poi", "wander", "speed",
                    "name", "entity", "greet", "action", "beacon", "pause", "resume", "tp", "summon", "scope"
            ), args[1]);
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && !sub.equals("create") && !sub.equals("list")) {
            return filter(new ArrayList<>(service.profiles().keySet()), args[2]);
        }
        switch (sub) {
            case "create" -> {
                if (args.length == 4) {
                    return filter(mobTypeNames(), args[3]);
                }
            }
            case "name" -> {
                if (args.length == 4) {
                    return filter(List.of("add", "set", "remove", "list"), args[3]);
                }
                if (args.length == 5
                        && (args[3].equalsIgnoreCase("set") || args[3].equalsIgnoreCase("remove"))) {
                    QuestNpcProfile profile = service.profile(args[2]);
                    int lines = profile == null ? 1 : profile.nameLines().size();
                    List<String> numbers = new ArrayList<>();
                    for (int index = 1; index <= Math.max(1, lines); index++) {
                        numbers.add(Integer.toString(index));
                    }
                    return filter(numbers, args[4]);
                }
            }
            case "scope" -> {
                if (args.length == 4) {
                    return filter(QuestScopes.KNOWN, args[3]);
                }
            }
            case "entity" -> {
                if (args.length == 4) {
                    return filter(mobTypeNames(), args[3]);
                }
            }
            case "poi" -> {
                if (args.length == 4) {
                    return filter(List.of("add", "remove", "list"), args[3]);
                }
                if (args.length == 5) {
                    if (args[3].equalsIgnoreCase("remove")) {
                        QuestNpcProfile profile = service.profile(args[2]);
                        return profile == null ? List.of() : filter(profile.pois, args[4]);
                    }
                    return filter(allPoiRefs(), args[4]);
                }
            }
            case "schedule" -> {
                if (args.length == 4 || args.length == 5) {
                    return filter(List.of("06:00", "09:00", "12:00", "18:00", "22:00", "0", "6000", "12000", "18000"),
                            args[args.length - 1]);
                }
            }
            case "beacon" -> {
                if (args.length == 4) {
                    return filter(List.of("on", "off"), args[3]);
                }
            }
            case "wander" -> {
                if (args.length == 4) {
                    return filter(List.of("off", "4", "6", "10"), args[3]);
                }
            }
            case "action" -> {
                if (args.length == 4) {
                    return filter(InteractionActionTypes.NPC_TAB_TYPES, args[3]);
                }
            }
            case "greet" -> {
                if (args.length == 4) {
                    return filter(List.of("off"), args[3]);
                }
            }
            default -> {
            }
        }
        return List.of();
    }

    private List<String> completeClock(String[] args) {
        if (args.length == 2) {
            return filter(List.of("status", "mode", "speed", "set"), args[1]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("mode")) {
            return filter(List.of("world", "virtual"), args[2]);
        }
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            return filter(List.of("06:00", "09:00", "12:00", "18:00", "0", "6000"), args[2]);
        }
        return List.of();
    }

    /** Every completable POI ref: bare names plus {@code name:alias} pairs. */
    private List<String> allPoiRefs() {
        List<String> refs = new ArrayList<>();
        for (QuestPoi poi : service.locations().all().values()) {
            refs.add(poi.name);
            for (String alias : poi.aliases.keySet()) {
                refs.add(poi.name + ":" + alias);
            }
        }
        return refs;
    }

    private static List<String> mobTypeNames() {
        List<String> names = new ArrayList<>();
        for (EntityType type : EntityType.values()) {
            Class<?> clazz = type.getEntityClass();
            if (clazz != null && Mob.class.isAssignableFrom(clazz)) {
                names.add(type.name());
            }
        }
        return names;
    }

    private static boolean isMobType(String raw) {
        try {
            Class<?> clazz = EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT)).getEntityClass();
            return clazz != null && Mob.class.isAssignableFrom(clazz);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
