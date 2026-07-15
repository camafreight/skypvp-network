package network.skypvp.extraction.item;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public record InfuseArmorPayload(
        GearRarity rarity,
        ArmorMark mark,
        ArmorSet armorSet,
        List<String> modules,
        String shieldModule,
        String overclockModule,
        String pieceModule
) {

    private static final Gson GSON = new GsonBuilder().create();

    public InfuseArmorPayload {
        if (modules == null) {
            modules = List.of();
        } else {
            modules = modules.stream()
                    .map(module -> module == null ? "" : module)
                    .toList();
        }
        if (rarity == null) {
            rarity = GearRarity.COMMON;
        }
        if (mark == null) {
            mark = ArmorMark.MK1;
        }
        if (armorSet == null) {
            armorSet = ArmorSet.VANGUARD;
        }
        if (shieldModule == null) {
            shieldModule = "";
        }
        if (pieceModule == null) {
            pieceModule = "";
        }
    }

    public static InfuseArmorPayload defaults(GearRarity rarity) {
        return new InfuseArmorPayload(
                rarity,
                ArmorMark.MK1,
                ArmorSet.VANGUARD,
                emptyModules(rarity.moduleSockets()),
                "",
                null,
                ""
        );
    }

    /** Chestplate keeps shield/overclock sockets; all pieces use tiered module socket lists. */
    public static InfuseArmorPayload forPiece(InfuseArmorPiece piece, GearRarity rarity, ArmorSet set) {
        ArmorSet resolved = set == null ? ArmorSet.VANGUARD : set;
        return new InfuseArmorPayload(rarity, ArmorMark.MK1, resolved, emptyModules(rarity.moduleSockets()), "", null, "");
    }

    public static InfuseArmorPayload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return defaults(GearRarity.COMMON);
        }
        try {
            String json = new String(payload, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            GearRarity rarity = root.has("rarity")
                    ? GearRarity.valueOf(root.get("rarity").getAsString())
                    : GearRarity.COMMON;
            ArmorMark mark = root.has("mark")
                    ? ArmorMark.parse(root.get("mark").getAsString())
                    : ArmorMark.MK1;
            List<String> modules;
            if (root.has("modules") && root.get("modules").isJsonArray()) {
                JsonArray array = root.getAsJsonArray("modules");
                String[] values = new String[array.size()];
                for (int i = 0; i < array.size(); i++) {
                    values[i] = array.get(i).getAsString();
                }
                modules = normalizeModuleList(List.of(values), rarity.moduleSockets());
            } else {
                modules = emptyModules(rarity.moduleSockets());
            }
            String shieldModule = root.has("shieldModule") ? root.get("shieldModule").getAsString() : "";
            String overclockModule = root.has("overclockModule") && !root.get("overclockModule").isJsonNull()
                    ? root.get("overclockModule").getAsString()
                    : null;
            String pieceModule = root.has("pieceModule") ? root.get("pieceModule").getAsString() : "";
            ArmorSet armorSet = root.has("armorSet")
                    ? ArmorSet.parse(root.get("armorSet").getAsString(), ArmorSet.VANGUARD)
                    : ArmorSet.VANGUARD;
            InfuseArmorPayload loaded = new InfuseArmorPayload(rarity, mark, armorSet, modules, shieldModule, overclockModule, pieceModule);
            return migratePieceModule(loaded);
        } catch (RuntimeException ignored) {
            return defaults(GearRarity.COMMON);
        }
    }

    private static InfuseArmorPayload migratePieceModule(InfuseArmorPayload payload) {
        if (payload.pieceModule() == null || payload.pieceModule().isBlank()) {
            return payload;
        }
        boolean modulesEmpty = payload.moduleSockets().stream().allMatch(id -> id == null || id.isBlank());
        if (!modulesEmpty) {
            return payload;
        }
        return payload.withModule(0, payload.pieceModule());
    }

    public byte[] encode() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public InfuseArmorPayload withMark(ArmorMark newMark) {
        return new InfuseArmorPayload(rarity, newMark, armorSet, modules, shieldModule, overclockModule, pieceModule);
    }

    public InfuseArmorPayload withArmorSet(ArmorSet newSet) {
        return new InfuseArmorPayload(rarity, mark, newSet == null ? ArmorSet.VANGUARD : newSet, modules, shieldModule, overclockModule, pieceModule);
    }

    public InfuseArmorPayload withShield(ShieldSocketReference socketed) {
        String encoded = socketed == null ? "" : socketed.encode();
        return new InfuseArmorPayload(rarity, mark, armorSet, modules, encoded, overclockModule, pieceModule);
    }

    public InfuseArmorPayload withoutShield() {
        return new InfuseArmorPayload(rarity, mark, armorSet, modules, "", overclockModule, pieceModule);
    }

    /** Returns a copy with {@code moduleId} placed in module socket {@code index} (blank clears the socket). */
    public InfuseArmorPayload withModule(int index, String moduleId) {
        int sockets = rarity.moduleSockets();
        if (index < 0 || index >= sockets) {
            return this;
        }
        List<String> copy = new java.util.ArrayList<>(normalizeModuleList(modules, sockets));
        copy.set(index, moduleId == null ? "" : moduleId);
        return new InfuseArmorPayload(rarity, mark, armorSet, copy, shieldModule, overclockModule, pieceModule);
    }

    public InfuseArmorPayload withoutModule(int index) {
        return withModule(index, "");
    }

    public InfuseArmorPayload withPieceModule(String moduleId) {
        String value = moduleId == null ? "" : moduleId;
        return new InfuseArmorPayload(rarity, mark, armorSet, modules, shieldModule, overclockModule, value);
    }

    public InfuseArmorPayload withoutPieceModule() {
        return withPieceModule("");
    }

    public InfuseArmorPayload withOverclock(String moduleId) {
        String value = moduleId == null || moduleId.isBlank() ? null : moduleId;
        return new InfuseArmorPayload(rarity, mark, armorSet, modules, shieldModule, value, pieceModule);
    }

    public InfuseArmorPayload withoutOverclock() {
        return withOverclock(null);
    }

    /** Module socket ids sized to this rarity's socket count (empty entries are blank strings). */
    public List<String> moduleSockets() {
        return normalizeModuleList(modules, rarity.moduleSockets());
    }

    private static List<String> normalizeModuleList(List<String> modules, int expectedSize) {
        if (modules.size() == expectedSize) {
            return modules.stream().map(module -> module == null ? "" : module).toList();
        }
        String[] resized = new String[expectedSize];
        for (int i = 0; i < expectedSize; i++) {
            resized[i] = i < modules.size() && modules.get(i) != null ? modules.get(i) : "";
        }
        return List.of(resized);
    }

    private static List<String> emptyModules(int count) {
        return Collections.nCopies(Math.max(0, count), "");
    }
}
