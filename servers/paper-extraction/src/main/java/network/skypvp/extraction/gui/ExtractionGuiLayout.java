package network.skypvp.extraction.gui;

import java.util.List;
import network.skypvp.paper.gui.GuiLayoutLibrary.Browser54Spacious;
import network.skypvp.paper.gui.GuiLayoutLibrary.NetworkNavigator54;

/** Standard 54-slot extraction hub/browser chrome (custom close/back icons, tools between). */
public final class ExtractionGuiLayout {

    public static final int SIZE = 54;
    public static final int CLOSE_SLOT = NetworkNavigator54.CLOSE_SLOT;
    public static final int BACK_SLOT = NetworkNavigator54.BACK_SLOT;
    public static final int HEADER_SLOT = Browser54Spacious.HEADER_SLOT;
    public static final int FILTER_SLOT = 3;
    public static final int SORT_SLOT = 5;
    public static final int WALLET_SLOT = 6;

    public static final List<Integer> PAGE_SLOTS = Browser54Spacious.PAGE_SLOTS;
    public static final int PREVIOUS_PAGE_SLOT = Browser54Spacious.PREVIOUS_SLOT;
    public static final int NEXT_PAGE_SLOT = Browser54Spacious.NEXT_SLOT;

    /** Four-up hub actions on the second content row. */
    public static final List<Integer> HUB_ACTION_SLOTS = List.of(19, 21, 23, 25);

    public static final int WORKSTATION_INPUT_SLOT = 22;
    public static final int WORKSTATION_ACTION_SLOT = 31;

    private ExtractionGuiLayout() {
    }

    public static void backOrClose(network.skypvp.paper.gui.GuiClickContext context) {
        if (!context.back()) {
            context.close();
        }
    }
}
