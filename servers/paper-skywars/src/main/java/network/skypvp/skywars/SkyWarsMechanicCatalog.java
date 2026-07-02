package network.skypvp.skywars;

import java.util.List;
import network.skypvp.paper.gamemode.api.GameMechanicScope;
import network.skypvp.paper.gamemode.api.GameModeModuleDefinition;
import network.skypvp.paper.gamemode.api.MechanicClassification;

public final class SkyWarsMechanicCatalog implements GameModeModuleDefinition {

    @Override
    public String modeKey() {
        return "skywars";
    }

    @Override
    public String pluginId() {
        return "paper-skywars";
    }

    @Override
    public List<MechanicClassification> mechanics() {
        return List.of(
                new MechanicClassification(
                        "skywars.chest-loot-roll",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "Mode-specific chest loot generation and rarity balance."
                ),
                new MechanicClassification(
                        "skywars.island-cage-start",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "Mode-specific pre-match island cage countdown and release logic."
                ),
                new MechanicClassification(
                        "skywars.round-elimination",
                        GameMechanicScope.GAMEMODE,
                        pluginId(),
                        "Mode-specific round elimination and win-condition handling."
                )
        );
    }
}
