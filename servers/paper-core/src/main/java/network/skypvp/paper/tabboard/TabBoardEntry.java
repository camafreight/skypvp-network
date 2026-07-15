package network.skypvp.paper.tabboard;

import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/** One fake (or real) row in a viewer's tab list. */
public record TabBoardEntry(
        UUID profileId,
        String profileName,
        Component tabDisplayName,
        int latencyMs,
        boolean listed,
        String textureValue,
        String textureSignature
) {
    public TabBoardEntry {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(profileName, "profileName");
        tabDisplayName = tabDisplayName == null ? Component.empty() : tabDisplayName;
    }

    public TabBoardEntry(
            UUID profileId,
            String profileName,
            Component tabDisplayName,
            int latencyMs,
            boolean listed
    ) {
        this(profileId, profileName, tabDisplayName, latencyMs, listed, null, null);
    }

    public TabBoardEntry withTexture(TabBoardSkins.Texture texture) {
        if (texture == null || !texture.present()) {
            return this;
        }
        return new TabBoardEntry(
                profileId,
                profileName,
                tabDisplayName,
                latencyMs,
                listed,
                texture.value(),
                texture.signature()
        );
    }

    public boolean hasTexture() {
        return textureValue != null && !textureValue.isBlank();
    }
}
