package network.skypvp.extraction.gameplay;

import java.util.Objects;
import network.skypvp.extraction.engine.BreachEngine;
import network.skypvp.extraction.text.ExtractionTexts;
import network.skypvp.paper.PaperCorePlugin;
import network.skypvp.paper.gui.GuiButtonLibrary;
import network.skypvp.paper.gui.GuiClickContext;
import network.skypvp.paper.gui.GuiManager;
import network.skypvp.paper.gui.GuiMenuBuilder;
import network.skypvp.paper.library.NetworkSoundCue;
import org.bukkit.Material;
import org.bukkit.entity.Player;

public final class BreachLeavePromptService {

    private final PaperCorePlugin core;
    private final BreachEngine engine;

    public BreachLeavePromptService(PaperCorePlugin core, BreachEngine engine) {
        this.core = Objects.requireNonNull(core, "core");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public void openAbandonPrompt(Player player) {
        if (player == null) {
            return;
        }
        GuiManager guiManager = this.core.guiManager();
        if (guiManager == null) {
            this.engine.executeAbandonLeave(player);
            return;
        }
        GuiMenuBuilder menu = GuiMenuBuilder.create(
                ExtractionTexts.plain(player, "extraction.gui.abandon.title"),
                27
        );
        menu.button(
                0,
                GuiButtonLibrary.close(ExtractionTexts.text(player, "extraction.gui.abandon.cancel_a11y")),
                GuiClickContext::close
        );
        menu.button(
                11,
                GuiButtonLibrary.warningAction(
                        Material.TNT,
                        ExtractionTexts.text(player, "extraction.gui.abandon.confirm_button"),
                        lore -> lore
                                .footerStrong(
                                        "<red>",
                                        ExtractionTexts.text(player, "extraction.gui.abandon.warning_footer")
                                )
                                .plain(ExtractionTexts.text(player, "extraction.gui.abandon.lore.body_stays"))
                                .plain(ExtractionTexts.text(player, "extraction.gui.abandon.lore.lootable"))
                                .plain(ExtractionTexts.text(player, "extraction.gui.abandon.lore.extract_saves"))
                ),
                context -> {
                    context.playSound(NetworkSoundCue.UI_BUTTON_FAILURE);
                    context.close();
                    this.engine.executeAbandonLeave(context.viewer());
                }
        );
        menu.button(
                15,
                GuiButtonLibrary.positiveAction(
                        Material.LIME_WOOL,
                        ExtractionTexts.text(player, "extraction.gui.abandon.stay_button"),
                        lore -> lore.plain(ExtractionTexts.text(player, "extraction.gui.abandon.stay_lore"))
                ),
                context -> {
                    context.playSound(NetworkSoundCue.UI_MENU_BACK);
                    context.close();
                }
        );
        guiManager.open(player, menu.build());
        player.sendMessage(ExtractionTexts.miniMessage(player, "extraction.gui.abandon.chat_warning"));
    }
}
