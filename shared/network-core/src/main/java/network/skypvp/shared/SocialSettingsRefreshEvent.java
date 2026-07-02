package network.skypvp.shared;

/**
 * Published to {@link NetworkChannels#SOCIAL_SETTINGS_REFRESH} when a player updates social
 * settings on a paper server (social menu or {@code /chat} commands).
 *
 * <p>Subscribers reload that player's row from the database so proxy party/friend checks and
 * other servers do not serve stale cached values.
 *
 * @param playerUuid     Player whose settings changed.
 * @param originServerId Server that published the update (skipped when handling locally).
 * @param timestamp      Epoch milliseconds when the event was published.
 */
public record SocialSettingsRefreshEvent(String playerUuid, String originServerId, long timestamp) {
}
