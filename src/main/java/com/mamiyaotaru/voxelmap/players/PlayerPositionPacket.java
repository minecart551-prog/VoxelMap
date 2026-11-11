package com.mamiyaotaru.voxelmap.players;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server side of the player position sync, so every client can draw a head for
 * every player.
 *
 * <p>Vanilla only tracks entities near a player, so a client cannot know where
 * someone on the far side of the world is. The server pushes everyone's
 * position to every client on a timer instead, and VoxelMap draws it.
 *
 * <p>Deliberately free of any client side classes: this runs on dedicated
 * servers, where {@code net.minecraft.client.*} does not exist.
 */
public final class PlayerPositionPacket {
    private static final Logger LOGGER = LogManager.getLogger("VoxelMap");

    /**
     * Same channel the client reads. The namespace and path come from the
     * client store so the two sides cannot drift apart.
     */
    public static final Identifier CHANNEL =
            new Identifier(PlayerPositionStore.CHANNEL_NAMESPACE, PlayerPositionStore.CHANNEL_PATH);

    private PlayerPositionPacket() {}

    /**
     * Builds the current snapshot of every online player:
     * <pre>
     * {
     *   "players": [
     *     {
     *       "uuid": String, "name": String,
     *       "x": double, "y": double, "z": double,
     *       "dim": String, "hidden": bool
     *     }
     *   ]
     * }
     * </pre>
     */
    public static NbtCompound build(MinecraftServer server) {
        NbtList playerList = new NbtList();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            NbtCompound playerTag = new NbtCompound();

            playerTag.putString("uuid", player.getUuidAsString());
            playerTag.putString("name", player.getName().getString());
            playerTag.putDouble("x", player.getX());
            playerTag.putDouble("y", player.getY());
            playerTag.putDouble("z", player.getZ());
            playerTag.putString("dim", player.getWorld().getRegistryKey().getValue().toString());
            playerTag.putBoolean("hidden", PlayerVisibility.isHidden(server, player.getUuid()));

            playerList.add(playerTag);
        }

        NbtCompound root = new NbtCompound();
        root.put("players", playerList);
        return root;
    }

    public static void sendTo(ServerPlayerEntity player, NbtCompound root) {
        byte[] payload = compress(root);
        if (payload == null) return;

        send(player, payload);
        logFirstSend(root);
    }

    /**
     * Compresses once and reuses those bytes for every recipient.
     *
     * <p>Compressing inside the per-player loop made the cost of a broadcast grow
     * linearly with the player count: the payload is identical for everyone, so at
     * 100 players a single snapshot cost ~9 ms of server thread instead of ~0.1 ms,
     * and at 200 it exceeded a whole tick. Now it is compressed exactly once.
     */
    public static void sendToAll(MinecraftServer server, NbtCompound root) {
        byte[] payload = compress(root);
        if (payload == null) return;

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            send(player, payload);
        }
        logFirstSend(root);
    }

    /**
     * Deliberately not gated on {@code ServerPlayNetworking.canSend()}: the client
     * reads this channel through a mixin on ClientPlayNetworkHandler instead of a
     * Fabric receiver, so it never declares the channel and canSend() would always
     * be false, which silently dropped every snapshot. Sent unconditionally, exactly
     * like the factions claim sync; clients without VoxelMap just ignore it.
     *
     * @return the gzipped snapshot, or null when it could not be written
     */
    private static byte[] compress(NbtCompound root) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                NbtIo.write(root, out);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            LOGGER.warn("Could not send player positions", e);
            return null;
        }
    }

    private static void send(ServerPlayerEntity player, byte[] payload) {
        // A fresh wrapper per player: CustomPayloadS2CPacket copies out of the buffer
        // when the packet is written, consuming its read position, so one shared
        // wrapper would hand an empty payload to everyone after the first. The bytes
        // underneath are only ever read, so they are safe to share across wrappers.
        PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(payload));
        ServerPlayNetworking.send(player, CHANNEL, buf);
    }

    /** One line per server run, so it is obvious in the log that sync is alive. */
    private static volatile boolean loggedOnce;

    private static void logFirstSend(NbtCompound root) {
        if (loggedOnce) return;
        loggedOnce = true;
        int players = root.getList("players", NbtElement.COMPOUND_TYPE).size();
        LOGGER.info("VoxelMap player position sync active: broadcasting " + players + " player(s) to every client");
    }
}
