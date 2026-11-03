package com.mamiyaotaru.voxelmap.players;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Per player "hidden from maps" flag, stored in the player's own data files.
 *
 * <p>Kept out of any mod's records: the flag lives in a small NBT file next to
 * the vanilla player data ({@code <uuid>.voxelmap.dat} in the world's
 * playerdata folder), so it survives rejoins and server restarts without being
 * tied to anything else.
 */
public final class PlayerVisibility {
    private static final Logger LOGGER = LogManager.getLogger("VoxelMap");
    private static final String NBT_KEY = "Hidden";
    private static final Map<UUID, Boolean> CACHE = new ConcurrentHashMap<>();

    private PlayerVisibility() {}

    /**
     * Whether the player has asked to be hidden from other players' maps and
     * world markers. Players who never ran the command count as visible.
     */
    public static boolean isHidden(MinecraftServer server, UUID playerId) {
        return CACHE.computeIfAbsent(playerId, id -> read(server, id));
    }

    public static void setHidden(MinecraftServer server, UUID playerId, boolean hidden) {
        if (isHidden(server, playerId) == hidden) return;
        CACHE.put(playerId, hidden);
        write(server, playerId, hidden);
    }

    private static boolean read(MinecraftServer server, UUID playerId) {
        Path file = fileFor(server, playerId);
        if (!Files.exists(file)) return false;

        try {
            NbtCompound nbt = NbtIo.readCompressed(file.toFile());
            return nbt != null && nbt.getBoolean(NBT_KEY);
        } catch (IOException e) {
            LOGGER.warn("Could not read map visibility for " + playerId, e);
            return false;
        }
    }

    private static void write(MinecraftServer server, UUID playerId, boolean hidden) {
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(NBT_KEY, hidden);

        Path file = fileFor(server, playerId);
        try {
            Files.createDirectories(file.getParent());
            NbtIo.writeCompressed(nbt, file.toFile());
        } catch (IOException e) {
            LOGGER.warn("Could not save map visibility for " + playerId, e);
        }
    }

    private static Path fileFor(MinecraftServer server, UUID playerId) {
        return server.getSavePath(WorldSavePath.PLAYERDATA).resolve(playerId + ".voxelmap.dat");
    }
}
