package network.skypvp.paper.nms.impl;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/** Reflective access to package-private {@link PlayerList} lookup maps. */
final class PlayerListAccess {

    private static final Field PLAYERS_BY_UUID;
    private static final Field PLAYERS_BY_NAME;

    static {
        try {
            PLAYERS_BY_UUID = PlayerList.class.getDeclaredField("playersByUUID");
            PLAYERS_BY_UUID.setAccessible(true);
            PLAYERS_BY_NAME = PlayerList.class.getDeclaredField("playersByName");
            PLAYERS_BY_NAME.setAccessible(true);
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private PlayerListAccess() {
    }

    static void register(PlayerList playerList, ServerPlayer player) {
        playersByUuid(playerList).put(player.getUUID(), player);
        playersByName(playerList).put(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT), player);
    }

    static boolean unregister(PlayerList playerList, ServerPlayer player) {
        playersByName(playerList).remove(player.getScoreboardName().toLowerCase(java.util.Locale.ROOT));
        if (playersByUuid(playerList).get(player.getUUID()) == player) {
            playersByUuid(playerList).remove(player.getUUID());
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ServerPlayer> playersByUuid(PlayerList playerList) {
        try {
            return (Map<UUID, ServerPlayer>) PLAYERS_BY_UUID.get(playerList);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ServerPlayer> playersByName(PlayerList playerList) {
        try {
            return (Map<String, ServerPlayer>) PLAYERS_BY_NAME.get(playerList);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
