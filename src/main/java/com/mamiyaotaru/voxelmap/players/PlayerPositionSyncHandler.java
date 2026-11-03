package com.mamiyaotaru.voxelmap.players;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Broadcasts the player position snapshot from {@link PlayerPositionPacket}.
 *
 * <p>Positions change constantly, so the check runs on a short timer, but the
 * snapshot is only actually sent when it differs from the last one sent. An
 * idle server where nobody is moving costs nothing at all.
 */
public final class PlayerPositionSyncHandler {
    /** Half a second: frequent enough for smooth markers, cheap on the wire. */
    private static final int INTERVAL_TICKS = 10;

    /**
     * Positions are compared at quarter block precision. It is far finer than a
     * map can show, and keeps floating point noise from turning into a resend.
     */
    private static final double POSITION_PRECISION = 4.0;

    private static volatile String lastSignature;

    private PlayerPositionSyncHandler() {}

    public static void register() {
        // Fresh joins get their snapshot immediately rather than waiting for the timer
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                PlayerPositionPacket.sendTo(handler.player, PlayerPositionPacket.build(server)));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % INTERVAL_TICKS != 0) return;
            broadcastIfChanged(server);
        });
    }

    /**
     * Send the current snapshot to everyone right away, so a /pv hide or show
     * takes effect without waiting for the next timed broadcast.
     */
    public static void broadcastNow(MinecraftServer server) {
        NbtCompound root = PlayerPositionPacket.build(server);
        lastSignature = signature(root);
        sendToAll(server, root);
    }

    private static void broadcastIfChanged(MinecraftServer server) {
        NbtCompound root = PlayerPositionPacket.build(server);
        String signature = signature(root);
        // Nobody moved, nobody joined or left and nobody hid: skip the compress and send
        if (signature.equals(lastSignature)) return;

        lastSignature = signature;
        sendToAll(server, root);
    }

    private static void sendToAll(MinecraftServer server, NbtCompound root) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            PlayerPositionPacket.sendTo(player, root);
        }
    }

    /**
     * A cheap stand-in for the whole snapshot: two snapshots with equal
     * signatures describe the same players in the same places.
     */
    private static String signature(NbtCompound root) {
        NbtList playerList = root.getList("players", NbtElement.COMPOUND_TYPE);
        StringBuilder signature = new StringBuilder(playerList.size() * 64);

        for (int i = 0; i < playerList.size(); i++) {
            NbtCompound playerTag = playerList.getCompound(i);
            signature.append(playerTag.getString("uuid")).append(';')
                    .append((long) Math.round(playerTag.getDouble("x") * POSITION_PRECISION)).append(';')
                    .append((long) Math.round(playerTag.getDouble("y") * POSITION_PRECISION)).append(';')
                    .append((long) Math.round(playerTag.getDouble("z") * POSITION_PRECISION)).append(';')
                    .append(playerTag.getString("dim")).append(';')
                    .append(playerTag.getBoolean("hidden")).append('\n');
        }

        return signature.toString();
    }
}
