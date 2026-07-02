package network.skypvp.shared.chat;

public record ChatRenderContext(
        String playerName,
        String message,
        String channelLabel,
        String targetName
) {
    public ChatRenderContext(String playerName, String message, String channelLabel) {
        this(playerName, message, channelLabel, "");
    }

    public ChatRenderContext {
        playerName = playerName == null ? "Player" : playerName;
        message = message == null ? "" : message;
        channelLabel = channelLabel == null ? "" : channelLabel;
        targetName = targetName == null ? "" : targetName;
    }
}
