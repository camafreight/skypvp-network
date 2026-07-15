package network.skypvp.extraction.questdialogue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import network.skypvp.extraction.crafting.CraftingConfigService;
import network.skypvp.extraction.crafting.CraftingMaterialService;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.gameplay.BreachLobbyProtection;
import network.skypvp.extraction.gameplay.scrapper.ScrapperMenu;
import network.skypvp.extraction.gameplay.scrapper.ScrapperProgressRepository;
import network.skypvp.extraction.gameplay.scrapper.ScrapperService;
import network.skypvp.extraction.gameplay.scrapper.ScrapperTierConfigService;
import network.skypvp.extraction.item.ExtractionCustomItemProvider;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.questdialogue.DialogueNode;
import network.skypvp.paper.questdialogue.DialogueOption;
import network.skypvp.paper.questdialogue.QuestDialogueActionExecutor;
import network.skypvp.paper.questdialogue.QuestDialogueBridge;
import network.skypvp.paper.questdialogue.QuestDialogueService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** Extraction quest dialogues (scrapper upgrade NPC, stranded pilot, YAML quests). */
public final class ExtractionQuestDialogueBridge implements QuestDialogueBridge, QuestDialogueActionExecutor {

    public static final String SCRAPPER_INTRO = "scrapper_intro";
    public static final String STRANDED_PILOT = PilotQuestSignalProvider.QUEST_ID;

    private static final String ACTION_SCRAPPER_UPGRADE = "quest.scrapper.upgrade";
    private static final String ACTION_PILOT_ACCEPT = "quest.pilot.accept";
    private static final String ACTION_PILOT_REFUSE = "quest.pilot.refuse";
    private static final String ACTION_PILOT_TURN_IN = "quest.pilot.turn_in";
    private static final long COLLECT_SHOUT_COOLDOWN_MS = 45_000L;

    private final PaperCorePlugin core;
    private final BreachEngine engine;
    private final CraftingConfigService craftingConfig;
    private final QuestDialogueService dialogueService;
    private final ScrapperService scrapperService;
    private final ScrapperProgressRepository scrapperProgress;
    private final ScrapperTierConfigService scrapperTiers;
    private final CraftingMaterialService craftingMaterials;
    private final QuestConfigService questConfig;
    private final QuestProgressRepository questProgress;
    private final Map<UUID, Long> lastCollectShoutMs = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<Player, String>> actions = new ConcurrentHashMap<>();

    public ExtractionQuestDialogueBridge(
            PaperCorePlugin core,
            BreachEngine engine,
            CraftingConfigService craftingConfig,
            QuestDialogueService dialogueService,
            ScrapperService scrapperService,
            ScrapperProgressRepository scrapperProgress,
            ScrapperTierConfigService scrapperTiers,
            CraftingMaterialService craftingMaterials,
            QuestConfigService questConfig,
            QuestProgressRepository questProgress
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.craftingConfig = Objects.requireNonNull(craftingConfig, "craftingConfig");
        this.dialogueService = Objects.requireNonNull(dialogueService, "dialogueService");
        this.scrapperService = Objects.requireNonNull(scrapperService, "scrapperService");
        this.scrapperProgress = Objects.requireNonNull(scrapperProgress, "scrapperProgress");
        this.scrapperTiers = Objects.requireNonNull(scrapperTiers, "scrapperTiers");
        this.craftingMaterials = Objects.requireNonNull(craftingMaterials, "craftingMaterials");
        this.questConfig = Objects.requireNonNull(questConfig, "questConfig");
        this.questProgress = Objects.requireNonNull(questProgress, "questProgress");
        registerDefaultActions();
    }

    /** Registers or replaces a dialogue action handler ({@code quest.*} ids from quests.yml). */
    public void registerAction(String actionId, BiConsumer<Player, String> handler) {
        if (actionId == null || actionId.isBlank() || handler == null) {
            return;
        }
        actions.put(actionId.trim().toLowerCase(java.util.Locale.ROOT), handler);
    }

    private void registerDefaultActions() {
        registerAction(ACTION_SCRAPPER_UPGRADE, (player, dialogueId) -> attemptScrapperUpgrade(player));
        registerAction(ACTION_PILOT_ACCEPT, (player, dialogueId) -> {
            questProgress.setStage(player.getUniqueId(), STRANDED_PILOT, QuestProgressRepository.STAGE_ACCEPTED);
            refreshSignals(player);
            player.sendMessage(Component.text("Quest accepted: recover the flight recorder.", NamedTextColor.GREEN));
        });
        registerAction(ACTION_PILOT_REFUSE, (player, dialogueId) ->
                questProgress.setStage(player.getUniqueId(), STRANDED_PILOT, QuestProgressRepository.STAGE_REFUSED));
        registerAction(ACTION_PILOT_TURN_IN, (player, dialogueId) -> attemptPilotTurnIn(player));
    }

    @Override
    public void open(Player player, String dialogueId, Location anchor, Entity speaker) {
        if (player == null || dialogueId == null || dialogueId.isBlank()) {
            return;
        }
        if (dialogueService.isInDialogue(player.getUniqueId())) {
            dialogueService.cancel(player);
        }
        scrapperProgress.warmTier(player.getUniqueId());
        switch (dialogueId.trim()) {
            case SCRAPPER_INTRO -> openScrapperNpc(player, anchor, speaker);
            default -> openConfigQuest(player, dialogueId.trim(), speaker);
        }
    }

    /** Config-defined quests from quests.yml; start node respects quest progress + inventory. */
    private void openConfigQuest(Player player, String dialogueId, Entity speaker) {
        var quest = questConfig.quest(dialogueId);
        if (quest.isEmpty()) {
            player.sendMessage(Component.text("Unknown dialogue: " + dialogueId, NamedTextColor.RED));
            return;
        }
        QuestConfigService.QuestDefinition definition = quest.get();
        DialogueNode start = resolveStartNode(player, definition);
        if (start == null) {
            player.sendMessage(Component.text("Quest dialogue is misconfigured: " + dialogueId, NamedTextColor.RED));
            return;
        }
        dialogueService.begin(
                player,
                definition.id(),
                definition.npcName(),
                start,
                definition.nodes()::get,
                () -> {
                },
                speaker
        );
    }

    private DialogueNode resolveStartNode(Player player, QuestConfigService.QuestDefinition definition) {
        Map<String, DialogueNode> nodes = definition.nodes();
        if (!STRANDED_PILOT.equals(definition.id())) {
            return definition.startNode();
        }
        String stage = questProgress.stageOf(player.getUniqueId(), STRANDED_PILOT).orElse("");
        if (QuestProgressRepository.STAGE_COMPLETED.equals(stage)) {
            return nodes.getOrDefault("completed", definition.startNode());
        }
        if (QuestProgressRepository.STAGE_ACCEPTED.equals(stage)) {
            if (ExtractionCustomItemProvider.hasFlightRecorder(core.customItemService(), player)) {
                return nodes.getOrDefault("turn_in_offer", nodes.getOrDefault("in_progress", definition.startNode()));
            }
            return nodes.getOrDefault("in_progress", definition.startNode());
        }
        return definition.startNode();
    }

    @Override
    public void execute(Player player, String dialogueId, String questActionId) {
        if (player == null || questActionId == null || questActionId.isBlank()) {
            return;
        }
        BiConsumer<Player, String> handler = actions.get(questActionId.trim().toLowerCase(java.util.Locale.ROOT));
        if (handler != null) {
            handler.accept(player, dialogueId == null ? "" : dialogueId);
        }
    }

    private void attemptPilotTurnIn(Player player) {
        if (!ExtractionCustomItemProvider.consumeFlightRecorder(core.customItemService(), player)) {
            dialogueService.jumpToNode(player, "turn_in_failed");
            return;
        }
        questProgress.setStage(player.getUniqueId(), STRANDED_PILOT, QuestProgressRepository.STAGE_COMPLETED);
        craftingMaterials.grant(player.getUniqueId(), "cloth_scrap", 8);
        craftingMaterials.grant(player.getUniqueId(), "metal_shards", 6);
        craftingMaterials.grant(player.getUniqueId(), "aether_resin", 1);
        if (core.questSignals() != null) {
            core.questSignals().complete(player, STRANDED_PILOT);
        }
        player.sendMessage(Component.text(
                "Flight recorder returned. Materials added to your stash.",
                NamedTextColor.GREEN
        ));
        dialogueService.jumpToNode(player, "turn_in_success");
    }

    private void refreshSignals(Player player) {
        if (core.questSignals() != null) {
            core.questSignals().refresh(player);
        }
    }

    private void openScrapperNpc(Player player, Location anchor, Entity speaker) {
        if (!BreachLobbyProtection.isLobbySafe(engine, player)) {
            player.sendMessage(Component.text(
                    "The Scrap Technician is back at the extraction hub.",
                    NamedTextColor.RED
            ));
            return;
        }
        if (scrapperService.hasBufferedMaterials(player)) {
            core.platformScheduler().runOnPlayer(player, () -> {
                maybeShoutCollectReminder(player, anchor);
                core.platformScheduler().runOnPlayerLater(player, () -> core.guiManager().open(
                        player,
                        new ScrapperMenu(core, scrapperService, craftingConfig)
                ), 2L);
            });
            return;
        }
        openScrapperIntro(player, speaker);
    }

    private void maybeShoutCollectReminder(Player player, Location anchor) {
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        Long last = lastCollectShoutMs.get(playerId);
        if (last != null && now - last < COLLECT_SHOUT_COOLDOWN_MS) {
            return;
        }
        lastCollectShoutMs.put(playerId, now);
        int buffered = scrapperService.sessionBuffered(player);
        if (core.waypointNavigator() != null && anchor != null && anchor.getWorld() != null) {
            core.waypointNavigator().navigate(
                    player,
                    network.skypvp.paper.waypoint.Waypoint.of(
                            network.skypvp.paper.questsignal.QuestSignalService.waypointIdFor(
                                    ScrapperQuestSignalProvider.QUEST_ID),
                            anchor,
                            "Scrap Technician",
                            org.bukkit.Color.fromRGB(255, 200, 60),
                            0.0D
                    ).withMarker(network.skypvp.paper.waypoint.WaypointMarker.octagon(
                            org.bukkit.Color.fromRGB(255, 200, 60), "<white>⚒</white>"))
            );
            player.sendActionBar(Component.text(
                    "Navigator → Scrap Technician (" + buffered + " materials ready)",
                    NamedTextColor.GOLD
            ));
            return;
        }
        dialogueService.shouts().shout(
                player,
                anchor,
                "Scrap Technician",
                List.of(
                        "I've got salvage for you!",
                        buffered + " materials ready.",
                        "Collect them here."
                )
        );
        player.sendMessage(Component.text("[Scrapper] ", NamedTextColor.GRAY)
                .append(Component.text(
                        "I have items for you to collect! come see me when you have a chance.",
                        NamedTextColor.YELLOW
                )));
    }

    private void openScrapperIntro(Player player, Entity speaker) {
        int tier = scrapperProgress.cachedTier(player.getUniqueId());
        ScrapperTierConfigService.TierDefinition current = scrapperTiers.tier(tier);
        var nextTier = scrapperTiers.nextTier(tier);
        List<String> introLines = List.of(
                "Need something, raider?",
                "Your scrapper gathers mats while you fight.",
                "Collect salvage here after a raid.",
                "Tier: " + current.name() + " (" + current.sessionCap() + "/raid)."
        );
        List<DialogueOption> options = new java.util.ArrayList<>();
        if (nextTier.isPresent()) {
            options.add(new DialogueOption(
                    "upgrade",
                    "Upgrade my scrapper.",
                    "upgrade_offer",
                    null
            ));
        } else {
            options.add(new DialogueOption("maxed", "Any other tips?", "upgrade_maxed", null));
        }
        options.add(new DialogueOption("bye", "Bye.", null, null));

        DialogueNode start = new DialogueNode("intro", "Scrap Technician", introLines, options, null);
        Map<String, DialogueNode> nodes = buildScrapperNodes(player, tier, current, nextTier.orElse(null));
        dialogueService.begin(
                player,
                SCRAPPER_INTRO,
                "Scrap Technician",
                start,
                nodes::get,
                () -> {
                },
                speaker
        );
    }

    private Map<String, DialogueNode> buildScrapperNodes(
            Player player,
            int tier,
            ScrapperTierConfigService.TierDefinition current,
            ScrapperTierConfigService.TierDefinition next
    ) {
        Map<String, DialogueNode> nodes = new HashMap<>();
        if (next != null) {
            String costLine = formatUpgradeCost(next);
            nodes.put("upgrade_offer", new DialogueNode(
                    "upgrade_offer",
                    "Scrap Technician",
                    List.of(
                            "Bring materials from your stash and I'll tune the scrapper.",
                            "Next tier: " + next.name() + " — cap " + next.sessionCap() + "/raid.",
                            "Cost: " + costLine
                    ),
                    List.of(
                            new DialogueOption("confirm_upgrade", "Pay materials & upgrade.", "upgrade_pending", ACTION_SCRAPPER_UPGRADE),
                            new DialogueOption("back", "Maybe later.", "intro", null)
                    ),
                    null
            ));
        }
        nodes.put("upgrade_pending", new DialogueNode(
                "upgrade_pending",
                "Scrap Technician",
                List.of("Let me see what you've got..."),
                List.of(),
                null
        ));
        nodes.put("upgrade_maxed", new DialogueNode(
                "upgrade_maxed",
                "Scrap Technician",
                List.of("Your scrapper is fully tuned. Keep raiding and I'll keep the salvage flowing."),
                List.of(),
                null
        ));
        nodes.put("upgrade_success", new DialogueNode(
                "upgrade_success",
                "Scrap Technician",
                List.of("Done. Your scrapper will pull rarer salvage this raid."),
                List.of(),
                null
        ));
        nodes.put("upgrade_failed", new DialogueNode(
                "upgrade_failed",
                "Scrap Technician",
                List.of("You're short on materials. Check your material stash and come back."),
                List.of(),
                null
        ));
        return nodes;
    }

    private void attemptScrapperUpgrade(Player player) {
        UUID playerId = player.getUniqueId();
        int tier = scrapperProgress.cachedTier(playerId);
        var nextTier = scrapperTiers.nextTier(tier);
        if (nextTier.isEmpty()) {
            player.sendMessage(Component.text("Your scrapper is already fully upgraded.", NamedTextColor.GRAY));
            return;
        }
        ScrapperTierConfigService.TierDefinition upgradeTarget = nextTier.get();
        Map<String, Integer> costs = new HashMap<>();
        for (ScrapperTierConfigService.MaterialCost cost : upgradeTarget.upgradeMaterials()) {
            costs.put(cost.materialId(), cost.amount());
        }
        if (costs.isEmpty()) {
            player.sendMessage(Component.text("No upgrade is configured for the next tier.", NamedTextColor.RED));
            return;
        }
        if (!craftingMaterials.trySpend(playerId, costs)) {
            player.sendMessage(Component.text("Not enough materials in your stash.", NamedTextColor.RED));
            dialogueService.jumpToNode(player, "upgrade_failed");
            return;
        }
        scrapperProgress.tryUpgrade(playerId, tier).thenAccept(success -> core.platformScheduler().runOnPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (!success) {
                costs.forEach((materialId, amount) -> craftingMaterials.grant(playerId, materialId, amount));
                player.sendMessage(Component.text("Upgrade failed — try again.", NamedTextColor.RED));
                dialogueService.jumpToNode(player, "upgrade_failed");
                return;
            }
            scrapperService.refreshPlayerTier(playerId);
            player.sendMessage(Component.text(
                    "Scrapper upgraded to " + upgradeTarget.name() + "!",
                    NamedTextColor.GREEN
            ));
            dialogueService.jumpToNode(player, "upgrade_success");
        }));
    }

    private static String formatUpgradeCost(ScrapperTierConfigService.TierDefinition next) {
        if (next.upgradeMaterials().isEmpty()) {
            return "none";
        }
        StringBuilder builder = new StringBuilder();
        for (ScrapperTierConfigService.MaterialCost cost : next.upgradeMaterials()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(cost.amount()).append(' ').append(cost.materialId());
        }
        return builder.toString();
    }
}
