package com.mamiyaotaru.voxelmap.players;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;

/**
 * Snapshot of every player on the server, pushed by the factions mod.
 *
 * <p>Vanilla entity tracking only tells the client about players nearby, but
 * the map wants to show everyone no matter the distance, so the server
 * broadcasts positions on {@link #CHANNEL}. Only the most recent snapshot is
 * kept, and it is dropped on disconnect.
 */
public final class PlayerPositionStore {
    public static final String CHANNEL_NAMESPACE = "voxelmap";
    public static final String CHANNEL_PATH = "player_positions";
    public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_PATH;

    private static volatile List<PlayerEntry> entries = List.of();

    private PlayerPositionStore() {}

    /**
     * One player as reported by the server.
     */
    public static final class PlayerEntry {
        public final UUID uuid;
        public final String name;
        public final double x;
        public final double y;
        public final double z;
        /** Dimension id, e.g. "minecraft:overworld". */
        public final String dimension;
        /** True when the player ran /pv hide and should not be drawn. */
        public final boolean hidden;

        PlayerEntry(UUID uuid, String name, double x, double y, double z, String dimension, boolean hidden) {
            this.uuid = uuid;
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimension = dimension;
            this.hidden = hidden;
        }
    }

    /**
     * Reads the gzipped snapshot pushed by the Factions mod on
     * {@link #CHANNEL} and replaces the current one.
     */
    public static void handlePayload(PacketByteBuf buffer) {
        try {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);

            NbtCompound root;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes));
                    DataInputStream in = new DataInputStream(gzip)) {
                root = NbtIo.read(in);
            }

            if (root != null) {
                update(root);
            }
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Failed to read player position data", e);
        }
    }

    /**
     * Parses the gzipped snapshot payload:
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
    public static void update(NbtCompound root) {
        NbtList playerList = root.getList("players", NbtElement.COMPOUND_TYPE);
        List<PlayerEntry> parsed = new ArrayList<>(playerList.size());

        for (int i = 0; i < playerList.size(); i++) {
            NbtCompound playerTag = playerList.getCompound(i);
            try {
                parsed.add(new PlayerEntry(
                        UUID.fromString(playerTag.getString("uuid")),
                        playerTag.getString("name"),
                        playerTag.getDouble("x"),
                        playerTag.getDouble("y"),
                        playerTag.getDouble("z"),
                        playerTag.getString("dim"),
                        playerTag.contains("hidden") && playerTag.getBoolean("hidden")));
            } catch (IllegalArgumentException ignored) {
                // Malformed entry; skip it instead of dropping the whole snapshot
            }
        }

        entries = List.copyOf(parsed);
    }

    public static List<PlayerEntry> getEntries() {
        return entries;
    }

    /**
     * The most recent entry for a player, or null when the server hasn't
     * reported them (server without the mod, or a snapshot not received yet).
     */
    public static PlayerEntry get(UUID uuid) {
        for (PlayerEntry entry : entries) {
            if (entry.uuid.equals(uuid)) return entry;
        }
        return null;
    }

    /**
     * Whether the player asked to be hidden. Used to keep clients that never
     * received this player's entry consistent with the /pv state.
     */
    public static boolean isHidden(UUID uuid) {
        PlayerEntry entry = get(uuid);
        return entry != null && entry.hidden;
    }

    public static void clear() {
        entries = List.of();
    }
}
