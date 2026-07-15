package network.skypvp.paper.model;

public record WorldPresetMeta(
        String presetId,
        String description,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        float spawnPitch
) {
}
