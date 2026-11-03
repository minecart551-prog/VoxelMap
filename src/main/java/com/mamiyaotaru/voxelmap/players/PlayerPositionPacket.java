package com.mamiyaotaru.voxelmap.players;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
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
        if (!ServerPlayNetworking.canSend(player, CHANNEL)) return;

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                NbtIo.write(root, out);
            }
            PacketByteBuf buf = new PacketByteBuf(Unpooled.wrappedBuffer(bytes.toByteArray()));
            ServerPlayNetworking.send(player, CHANNEL, buf);
        } catch (IOException e) {
            LOGGER.warn("Could not send player positions", e);
        }
    }
}
