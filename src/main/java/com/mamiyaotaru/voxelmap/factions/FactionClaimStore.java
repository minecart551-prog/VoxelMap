package com.mamiyaotaru.voxelmap.factions;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;

/**
 * Client side storage of faction claim data synced from the Factions mod, used
 * to draw faction areas on the full screen map.
 *
 * <p>The Factions mod pushes a gzipped NBT snapshot on the
 * {@code voxelmap:faction_claims} payload channel when a player joins and
 * whenever claims change (see ClaimNetworkHandler in the Factions mod). This
 * class only consumes that payload, so VoxelMap still works without the
 * Factions mod installed - no payload is ever sent then and the store stays
 * empty.
 */
public final class FactionClaimStore {
    public static final String CHANNEL = "voxelmap:faction_claims";

    private static volatile Snapshot snapshot = Snapshot.EMPTY;

    private FactionClaimStore() {}

    /**
     * One faction's data relevant to map rendering.
     */
    public static final class FactionArea {
        /** Faction name, never null. */
        public final String name;
        /** Faction description as set by the faction, empty when unset. May contain § formatting codes. */
        public final String description;
        /** Faction color as 0xRRGGBB (white when the faction has no color). */
        public final int color;
        /** Dimension of the faction's home, or null when it has no home. */
        public final String homeDimension;
        public final double homeX;
        public final double homeZ;
        private final Map<String, Set<Long>> claimsByDimension;

        private FactionArea(String name, String description, int color, String homeDimension,
                double homeX, double homeZ, Map<String, Set<Long>> claimsByDimension) {
            this.name = name;
            this.description = description;
            this.color = color;
            this.homeDimension = homeDimension;
            this.homeX = homeX;
            this.homeZ = homeZ;
            this.claimsByDimension = claimsByDimension;
        }

        /**
         * Claimed chunk keys (see {@link #chunkKey}) of this faction in the given
         * dimension, empty when the faction claims nothing there.
         */
        public Set<Long> claimsIn(String dimension) {
            Set<Long> claims = claimsByDimension.get(dimension);
            return claims != null ? claims : Set.of();
        }
    }

    private static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(Map.of());
        final Map<String, List<FactionArea>> byDimension;

        Snapshot(Map<String, List<FactionArea>> byDimension) {
            this.byDimension = byDimension;
        }
    }

    /**
     * @return whether the Factions mod is installed or claim data has been
     *         synced from the server, i.e. whether the faction overlay could
     *         ever show anything.
     */
    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("factions") || hasData();
    }

    private static boolean hasData() {
        return snapshot != Snapshot.EMPTY;
    }

    /**
     * @return the factions that have claims or a home in the given dimension.
     */
    public static List<FactionArea> getAreas(String dimension) {
        List<FactionArea> areas = snapshot.byDimension.get(dimension);
        return areas != null ? areas : List.of();
    }

    /**
     * Reads a gzipped NBT payload sent by the Factions mod and publishes it as
     * the current snapshot.
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
                snapshot = parse(root);
            }
        } catch (Exception e) {
            VoxelConstants.getLogger().warn("Failed to read faction claim data", e);
        }
    }

    /**
     * Forgets all synced data, called when leaving a server.
     */
    public static void clear() {
        snapshot = Snapshot.EMPTY;
    }

    private static Snapshot parse(NbtCompound root) {
        Map<String, List<FactionArea>> byDimension = new HashMap<>();

        NbtList factionList = root.getList("factions", 10);
        for (int i = 0; i < factionList.size(); i++) {
            NbtCompound factionTag = factionList.getCompound(i);

            String name = factionTag.getString("name");
            String description = factionTag.contains("description")
                    ? factionTag.getString("description") : "";
            int color = factionTag.contains("color") ? factionTag.getInt("color") : -1;
            if (color < 0) color = 0xFFFFFF;

            String homeDimension = factionTag.contains("homeLevel")
                    ? factionTag.getString("homeLevel") : null;
            double homeX = factionTag.getDouble("homeX");
            double homeZ = factionTag.getDouble("homeZ");

            Map<String, Set<Long>> claims = new HashMap<>();
            NbtList levelList = factionTag.getList("claims", 10);
            for (int j = 0; j < levelList.size(); j++) {
                NbtCompound levelTag = levelList.getCompound(j);
                String level = levelTag.getString("level");
                if (level == null || level.isEmpty()) continue;

                int[] xs = levelTag.getIntArray("chunkX");
                int[] zs = levelTag.getIntArray("chunkZ");
                Set<Long> chunks = new HashSet<>(Math.max(16, xs.length * 2));
                for (int k = 0; k < xs.length && k < zs.length; k++) {
                    chunks.add(chunkKey(xs[k], zs[k]));
                }
                if (!chunks.isEmpty()) {
                    claims.put(level, chunks);
                }
            }

            if (claims.isEmpty() && homeDimension == null) continue;

            FactionArea area = new FactionArea(name != null ? name : "???",
                    description != null ? description : "", color,
                    homeDimension, homeX, homeZ, claims);
            for (String dimension : claims.keySet()) {
                byDimension.computeIfAbsent(dimension, key -> new ArrayList<>()).add(area);
            }
            if (homeDimension != null && !claims.containsKey(homeDimension)) {
                byDimension.computeIfAbsent(homeDimension, key -> new ArrayList<>()).add(area);
            }
        }

        Map<String, List<FactionArea>> frozen = new HashMap<>();
        for (Map.Entry<String, List<FactionArea>> entry : byDimension.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new Snapshot(Map.copyOf(frozen));
    }

    /**
     * Packs chunk coordinates into a lookup key.
     */
    public static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int chunkX(long key) {
        return (int) (key >> 32);
    }

    public static int chunkZ(long key) {
        return (int) key;
    }
}
