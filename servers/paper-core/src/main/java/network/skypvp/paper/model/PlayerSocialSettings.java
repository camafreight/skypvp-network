package network.skypvp.paper.model;

import java.util.UUID;
import network.skypvp.shared.chat.ChatChannel;

public record PlayerSocialSettings(
        UUID playerId,
        boolean chatEnabled,
        boolean blockFriendRequests,
        boolean blockPartyRequests,
        boolean profanityFilterEnabled,
        boolean autoTranslateEnabled,
        ChatChannel activeChatChannel
) {
    public static PlayerSocialSettings defaults(UUID playerId) {
        return new PlayerSocialSettings(playerId, true, false, false, true, false, ChatChannel.ALL);
    }

    public PlayerSocialSettings withChatEnabled(boolean chatEnabled) {
        return new PlayerSocialSettings(
                playerId, chatEnabled, blockFriendRequests, blockPartyRequests,
                profanityFilterEnabled, autoTranslateEnabled, activeChatChannel
        );
    }

    public PlayerSocialSettings withBlockFriendRequests(boolean blockFriendRequests) {
        return new PlayerSocialSettings(
                playerId, chatEnabled, blockFriendRequests, blockPartyRequests,
                profanityFilterEnabled, autoTranslateEnabled, activeChatChannel
        );
    }

    public PlayerSocialSettings withBlockPartyRequests(boolean blockPartyRequests) {
        return new PlayerSocialSettings(
                playerId, chatEnabled, blockFriendRequests, blockPartyRequests,
                profanityFilterEnabled, autoTranslateEnabled, activeChatChannel
        );
    }

    public PlayerSocialSettings withProfanityFilterEnabled(boolean profanityFilterEnabled) {
        return new PlayerSocialSettings(
                playerId, chatEnabled, blockFriendRequests, blockPartyRequests,
                profanityFilterEnabled, autoTranslateEnabled, activeChatChannel
        );
    }

    public PlayerSocialSettings withAutoTranslateEnabled(boolean autoTranslateEnabled) {
        return new PlayerSocialSettings(
                playerId, chatEnabled, blockFriendRequests, blockPartyRequests,
                profanityFilterEnabled, autoTranslateEnabled, activeChatChannel
        );
    }

    public PlayerSocialSettings withActiveChatChannel(ChatChannel activeChatChannel) {
        return new PlayerSocialSettings(
                playerId,
                chatEnabled,
                blockFriendRequests,
                blockPartyRequests,
                profanityFilterEnabled,
                autoTranslateEnabled,
                activeChatChannel == null ? ChatChannel.ALL : activeChatChannel
        );
    }
}
