package network.skypvp.extraction;

import java.util.List;
import network.skypvp.paper.gamemode.api.GameMechanicScope;
import network.skypvp.paper.gamemode.api.GameModeModuleDefinition;
import network.skypvp.paper.gamemode.api.MechanicClassification;

public final class AetherBreachMechanicCatalog implements GameModeModuleDefinition {

    @Override
    public String modeKey() {
        return "aether-breach";
    }

    @Override
    public String pluginId() {
        return "paper-extraction";
    }

    @Override
    public List<MechanicClassification> mechanics() {
        return List.of(
                new MechanicClassification(
                        "aether-breach.instance-lifecycle",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "Multi-world breach instance pool with match state machine and reset cycle."
                ),
                new MechanicClassification(
                        "aether-breach.extract-zones",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "Region-based extraction win condition during active breach matches."
                ),
                new MechanicClassification(
                        "aether-breach.queue-routing",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "In-pod player queue and instance assignment for breach matches."
                ),
                new MechanicClassification(
                        "aether-breach.match-hud",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "Boss bar match timer and action bar player count overlays."
                )
        );
    }
}
