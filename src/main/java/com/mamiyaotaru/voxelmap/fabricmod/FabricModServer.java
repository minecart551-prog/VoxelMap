package com.mamiyaotaru.voxelmap.fabricmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mamiyaotaru.voxelmap.players.PlayerPositionSyncHandler;
import com.mamiyaotaru.voxelmap.players.PlayerVisibility;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Server side of VoxelMap: the player position broadcast that lets every
 * client draw a head for every player, and the /pv command that controls it.
 *
 * <p>Registered under the "main" entrypoint rather than "server" so /pv also
 * works in singleplayer, where the integrated server shares this process. It
 * touches nothing under {@code net.minecraft.client}, so it is safe on a
 * dedicated server where those classes do not exist.
 */
public class FabricModServer implements ModInitializer {
    @Override
    public void onInitialize() {
        PlayerPositionSyncHandler.register();
        CommandRegistrationCallback.EVENT.register(FabricModServer::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            CommandManager.RegistrationEnvironment environment) {
        LiteralCommandNode<ServerCommandSource> playerVisibility =
                CommandManager.literal("pv").build();
        playerVisibility.addChild(CommandManager.literal("hide")
                .executes(context -> setMapVisibility(context, true)).build());
        playerVisibility.addChild(CommandManager.literal("show")
                .executes(context -> setMapVisibility(context, false)).build());
        dispatcher.getRoot().addChild(playerVisibility);
    }

    private static int setMapVisibility(CommandContext<ServerCommandSource> context, boolean hidden) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;

        PlayerVisibility.setHidden(source.getServer(), player.getUuid(), hidden);
        // Push the new snapshot so every client stops or starts drawing this player at once
        PlayerPositionSyncHandler.broadcastNow(source.getServer());

        source.sendFeedback(() -> Text.literal(hidden
                ? "You are now hidden from other players' maps and world markers."
                : "You are now visible on other players' maps and world markers."), false);
        return 1;
    }
}
