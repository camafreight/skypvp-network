package network.skypvp.paper.nms.impl;

import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import network.skypvp.paper.nms.HeadlessPlayerSpec;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.inventory.ItemStack;

/**
 * Low-level spawn/despawn helpers that mirror the essential parts of {@link PlayerList#placeNewPlayer} /
 * {@link PlayerList#remove} without firing join/quit events or writing join broadcast messages.
 */
final class HeadlessPlayerPlacement {

    private HeadlessPlayerPlacement() {
    }

    /** Variant 2: fresh {@link ServerPlayer} copy. */
    static ServerPlayer spawn(MinecraftServer server, PlayerList playerList, HeadlessPlayerSpec spec, Runnable forcedKickHandler) {
        Location location = spec.location();
        if (location == null || location.getWorld() == null) {
            return null;
        }
        if (!Bukkit.isOwnedByCurrentRegion(location)) {
            return null;
        }
        if (playerList.getPlayer(spec.id()) != null) {
            return null;
        }

        GameProfile profile = buildProfile(spec);
        ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        player.isRealPlayer = true;
        player.loginTime = System.currentTimeMillis();
        player.setPos(location.getX(), location.getY(), location.getZ());
        player.setRot(location.getYaw(), location.getPitch());
        player.setYHeadRot(location.getYaw());
        player.setGameMode(GameType.SURVIVAL);

        applyHealth(player, spec.health(), spec.maxHealth());
        applyInventory(player, spec.contents());

        attachInertConnection(server, player, forcedKickHandler);
        insertIntoServer(server, playerList, player, level, true);
        ensureCombatReady(player);
        return player;
    }

    /**
     * Variant 3: revive and reinsert the same {@link ServerPlayer} object after vanilla quit removal, then swap its
     * network handler for an inert fake connection.
     */
    static boolean hang(MinecraftServer server, PlayerList playerList, ServerPlayer player, Runnable forcedKickHandler) {
        if (player == null) {
            return false;
        }
        Location location = player.getBukkitEntity().getLocation();
        if (location.getWorld() == null || !Bukkit.isOwnedByCurrentRegion(location)) {
            return false;
        }

        if (player.isRemoved()) {
            player.unsetRemoved();
        }

        attachInertConnection(server, player, forcedKickHandler);

        ServerLevel level = player.level();
        if (level == null) {
            level = ((CraftWorld) location.getWorld()).getHandle();
            player.setServerLevel(level);
        }

        boolean reinserted = playerList.getPlayer(player.getUUID()) != player;
        insertIntoServer(server, playerList, player, level, reinserted);
        ensureCombatReady(player);
        return true;
    }

    /**
     * Removes a headless body from the world and the player list. Every step is exception-isolated: a hung (Variant 3)
     * body's entity scheduler was already retired at the original quit, so {@code retireScheduler()} throws — that
     * must NEVER abort this method, because the vanilla duplicate-login flow polls the uuid slot and the reconnecting
     * owner is stuck (ReadTimeout) until the slot frees.
     *
     * @return true when the body was torn down on this thread; false when the body is still live in a region this
     *         thread does not own — the caller MUST re-dispatch onto the body's owning region instead. Running the
     *         teardown off-region makes {@code removePlayerImmediately} fail ({@code getCurrentWorldData()} is null
     *         on the global/login thread) while the player-list/connection unregistration still proceeds; Folia's
     *         next {@code tickConnections} then trips on the half-removed connection (null ticket pos NPE) and
     *         HALTS THE ENTIRE SERVER. This exact sequence took down the extraction pod.
     */
    static boolean manualDespawn(PlayerList playerList, ServerPlayer player) {
        MinecraftServer server = playerList.getServer();

        ServerLevel bodyLevel = player.level();
        if (bodyLevel != null && !player.isRemoved() && !Bukkit.isOwnedByCurrentRegion(player.getBukkitEntity())) {
            Bukkit.getLogger().warning("[Headless] Refusing off-region despawn for " + player.getUUID()
                    + " (body live in '" + bodyLevel.getWorld().getName() + "') — re-dispatching to its region.");
            return false;
        }

        try {
            ServerLevel level = player.level();
            if (level != null) {
                level.removePlayerImmediately(player, net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
            }
        } catch (RuntimeException exception) {
            Bukkit.getLogger().warning("[Headless] World removal failed for " + player.getUUID() + ": " + exception.getMessage());
        }
        try {
            player.retireScheduler();
        } catch (RuntimeException ignored) {
            // Already retired at the original quit for hung bodies.
        }
        try {
            player.getAdvancements().stopListening();
        } catch (RuntimeException ignored) {
        }

        // Free the uuid slot unconditionally — this is what the duplicate-login wait polls.
        playerList.players.remove(player);
        playerList.players.removeIf(existing -> existing.getUUID().equals(player.getUUID()));
        boolean unregistered = PlayerListAccess.unregister(playerList, player);

        if (unregistered) {
            try {
                server.notificationManager().playerLeft(player);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            server.getCustomBossEvents().onPlayerDisconnect(player);
        } catch (RuntimeException ignored) {
        }

        try {
            var removePacket = new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(
                    List.of(player.getUUID()));
            CraftPlayer removedCraft = player.getBukkitEntity();
            for (ServerPlayer other : playerList.getPlayers()) {
                CraftPlayer otherCraft = other.getBukkitEntity();
                if (otherCraft.canSee(removedCraft)) {
                    other.connection.send(removePacket);
                } else {
                    otherCraft.onEntityRemove(player);
                }
            }
        } catch (RuntimeException ignored) {
        }

        try {
            server.invalidateStatus();
        } catch (RuntimeException ignored) {
        }
        try {
            if (!player.isRemoved()) {
                player.setRemoved(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER);
            }
        } catch (RuntimeException ignored) {
        }
        return true;
    }

    private static void attachInertConnection(MinecraftServer server, ServerPlayer player, Runnable forcedKickHandler) {
        FakeConnection connection = new FakeConnection();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(player.getGameProfile(), false);
        InertHeadlessPacketListener listener = new InertHeadlessPacketListener(server, connection, player, cookie, forcedKickHandler);
        listener.processedDisconnect = false;
        connection.setupInboundProtocol(
                GameProtocols.SERVERBOUND_TEMPLATE.bind(
                        RegistryFriendlyByteBuf.decorator(server.registryAccess()),
                        listener),
                listener);
    }

    private static void insertIntoServer(
            MinecraftServer server,
            PlayerList playerList,
            ServerPlayer player,
            ServerLevel level,
            boolean announceTab) {
        server.services().nameToIdCache().add(player.nameAndId());

        player.isRealPlayer = true;
        if (player.loginTime <= 0L) {
            player.loginTime = System.currentTimeMillis();
        }

        if (!playerList.players.contains(player)) {
            playerList.players.add(player);
        }
        PlayerListAccess.register(playerList, player);

        player.suppressTrackerForLogin = true;
        playerList.sendLevelInfo(player, level);
        if (!level.players().contains(player)) {
            level.addNewPlayer(player);
        }
        player.initInventoryMenu();
        playerList.sendPlayerPermissionLevel(player);

        if (announceTab) {
            announceToTabList(playerList, player);
        }

        player.suppressTrackerForLogin = false;
        level.getChunkSource().chunkMap.addEntity(player);
        player.sentListPacket = true;

        server.getCustomBossEvents().onPlayerConnect(player);
        server.notificationManager().playerJoined(player);
    }

    private static GameProfile buildProfile(HeadlessPlayerSpec spec) {
        GameProfile profile = new GameProfile(spec.id(), spec.name());
        if (spec.texturesValue() != null && !spec.texturesValue().isBlank()) {
            profile.properties().put(
                    "textures",
                    new Property("textures", spec.texturesValue(), spec.texturesSignature()));
        }
        return profile;
    }

    private static void applyHealth(ServerPlayer player, double health, double maxHealth) {
        AttributeInstance attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute != null) {
            attribute.setBaseValue(Math.max(1.0D, maxHealth));
        }
        float capped = (float) Math.max(0.0D, Math.min(health, Math.max(1.0D, maxHealth)));
        player.setHealth(capped);
    }

    private static void applyInventory(ServerPlayer player, ItemStack[] contents) {
        if (contents == null) {
            return;
        }
        player.getBukkitEntity().getInventory().setContents(cloneContents(contents));
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] cloned = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            cloned[i] = stack == null ? null : stack.clone();
        }
        return cloned;
    }

    private static void announceToTabList(PlayerList playerList, ServerPlayer joining) {
        CraftPlayer joiningCraft = joining.getBukkitEntity();
        ClientboundPlayerInfoUpdatePacket initPacket =
                ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(joining));

        List<ServerPlayer> revealTargets = Lists.newArrayListWithExpectedSize(Math.max(0, playerList.getPlayers().size() - 1));
        for (ServerPlayer other : playerList.getPlayers()) {
            if (other == joining) {
                continue;
            }
            CraftPlayer otherCraft = other.getBukkitEntity();
            if (!otherCraft.canSee(joiningCraft)) {
                continue;
            }
            if (otherCraft.isListed(joiningCraft)) {
                other.connection.send(initPacket);
            } else {
                other.connection.send(ClientboundPlayerInfoUpdatePacket.createSinglePlayerInitializing(joining, false));
            }
            if (joiningCraft.canSee(otherCraft)) {
                revealTargets.add(other);
            }
        }

        if (!revealTargets.isEmpty()) {
            joining.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(revealTargets, joining));
        }
        joining.sentListPacket = true;
    }

    static void ensureCombatReady(ServerPlayer player) {
        CraftPlayer craft = player.getBukkitEntity();
        craft.setInvulnerable(false);
        craft.setNoDamageTicks(0);
        if (craft.getGameMode() != GameMode.SURVIVAL && craft.getGameMode() != GameMode.ADVENTURE) {
            player.setGameMode(GameType.SURVIVAL);
        }
        if (player.connection instanceof InertHeadlessPacketListener inert) {
            inert.processedDisconnect = false;
        }
    }
}
