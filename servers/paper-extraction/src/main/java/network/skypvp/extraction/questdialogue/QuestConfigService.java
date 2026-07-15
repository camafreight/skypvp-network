package network.skypvp.extraction.questdialogue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import network.skypvp.paper.questdialogue.DialogueNode;
import network.skypvp.paper.questdialogue.DialogueOption;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Loads config-defined quest dialogues from {@code quests.yml}. Definitions are immutable
 * templates; all runtime state (current node, choices made) is per-player inside
 * {@code QuestDialogueService} and its choice store, so any number of players can run the
 * same quest independently.
 */
public final class QuestConfigService {

    /** One config-defined quest dialogue tree. */
    public record QuestDefinition(
            String id,
            String npcName,
            String startNodeId,
            Map<String, DialogueNode> nodes
    ) {
        public DialogueNode startNode() {
            return nodes.get(startNodeId);
        }
    }

    private final JavaPlugin plugin;
    private final Map<String, QuestDefinition> quests = new ConcurrentHashMap<>();

    public QuestConfigService(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        reload();
    }

    public Optional<QuestDefinition> quest(String questId) {
        if (questId == null || questId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(quests.get(questId.trim().toLowerCase(Locale.ROOT)));
    }

    public Set<String> questIds() {
        return Set.copyOf(quests.keySet());
    }

    public void reload() {
        quests.clear();
        File file = new File(plugin.getDataFolder(), "quests.yml");
        if (!file.exists()) {
            plugin.saveResource("quests.yml", false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("quests");
        if (root == null) {
            plugin.getLogger().warning("[Quests] quests.yml has no 'quests' section.");
            return;
        }
        for (String questId : root.getKeys(false)) {
            ConfigurationSection questSection = root.getConfigurationSection(questId);
            if (questSection == null) {
                continue;
            }
            QuestDefinition definition = parseQuest(questId, questSection);
            if (definition != null) {
                quests.put(definition.id(), definition);
            }
        }
        plugin.getLogger().info("[Quests] Loaded " + quests.size() + " quest dialogue(s): "
                + String.join(", ", quests.keySet()));
    }

    private QuestDefinition parseQuest(String questId, ConfigurationSection section) {
        String npcName = section.getString("npc-name", "NPC");
        String startNodeId = section.getString("start", "intro");
        ConfigurationSection nodesSection = section.getConfigurationSection("nodes");
        if (nodesSection == null) {
            plugin.getLogger().warning("[Quests] '" + questId + "' has no nodes; skipped.");
            return null;
        }
        Map<String, DialogueNode> nodes = new HashMap<>();
        for (String nodeId : nodesSection.getKeys(false)) {
            ConfigurationSection nodeSection = nodesSection.getConfigurationSection(nodeId);
            if (nodeSection == null) {
                continue;
            }
            List<String> lines = nodeSection.getStringList("lines");
            if (lines.isEmpty()) {
                plugin.getLogger().warning("[Quests] '" + questId + "." + nodeId + "' has no lines; skipped.");
                continue;
            }
            // Node-level "next" auto-advances (cutscene-style) when the node has no options.
            nodes.put(nodeId, new DialogueNode(
                    nodeId, npcName, lines, parseOptions(nodeSection), nodeSection.getString("next", null)));
        }
        if (!nodes.containsKey(startNodeId)) {
            plugin.getLogger().warning("[Quests] '" + questId + "' start node '" + startNodeId + "' missing; skipped.");
            return null;
        }
        // Validate option targets so broken links fail at load time, not mid-dialogue.
        for (DialogueNode node : nodes.values()) {
            for (DialogueOption option : node.options()) {
                if (option.targetNodeId() != null && !nodes.containsKey(option.targetNodeId())) {
                    plugin.getLogger().warning("[Quests] '" + questId + "." + node.id() + "' option '"
                            + option.id() + "' points to unknown node '" + option.targetNodeId() + "'.");
                }
            }
            if (node.nextNodeId() != null && !nodes.containsKey(node.nextNodeId())) {
                plugin.getLogger().warning("[Quests] '" + questId + "." + node.id()
                        + "' auto-advance points to unknown node '" + node.nextNodeId() + "'.");
            }
        }
        return new QuestDefinition(
                questId.trim().toLowerCase(Locale.ROOT),
                npcName,
                startNodeId,
                Map.copyOf(nodes)
        );
    }

    private List<DialogueOption> parseOptions(ConfigurationSection nodeSection) {
        List<DialogueOption> options = new ArrayList<>();
        for (Map<?, ?> raw : nodeSection.getMapList("options")) {
            String id = asString(raw.get("id"));
            String text = asString(raw.get("text"));
            if (id == null || text == null) {
                continue;
            }
            options.add(new DialogueOption(id, text, asString(raw.get("next")), asString(raw.get("action"))));
        }
        return options;
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
