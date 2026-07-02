package network.skypvp.shared.chat;

public record ChatFormatProfile(
        String id,
        ChatFormatScope scope,
        ChatFormatFlags flags
) {
    public ChatFormatProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("format id is required");
        }
        if (scope == null) {
            scope = ChatFormatScope.RANK;
        }
        if (flags == null) {
            flags = ChatFormatFlags.EMPTY;
        }
    }
}
