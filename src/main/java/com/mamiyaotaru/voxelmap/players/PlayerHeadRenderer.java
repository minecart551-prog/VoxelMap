package com.mamiyaotaru.voxelmap.players;

import java.util.List;
import java.util.UUID;

import org.joml.Matrix4f;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.GameVariableAccessShim;
import com.mamiyaotaru.voxelmap.util.OpenGL;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

/**
 * Draws the head of every other player as a billboard above them, the same way
 * waypoint icons are drawn.
 *
 * <p>Positions come from {@link PlayerPositionStore}, which the Factions mod
 * keeps updated for every player on the server, so players far beyond vanilla
 * entity tracking are still marked. When the player is tracked locally their
 * live position is used instead, which moves smoothly between the server's
 * periodic updates.
 */
public final class PlayerHeadRenderer {
    /** World units across; similar in size to a waypoint icon. */
    private static final float HEAD_SIZE = 0.5F;
    /** Offset above the feet, so the head sits over the player's own head. */
    private static final double HEAD_HEIGHT = 1.85;

    // Face and hat layer of a 64x64 skin, in normalized texture coordinates
    private static final float FACE_U0 = 8.0F / 64.0F;
    private static final float FACE_U1 = 16.0F / 64.0F;
    private static final float FACE_V0 = 8.0F / 64.0F;
    private static final float FACE_V1 = 16.0F / 64.0F;
    private static final float HAT_U0 = 40.0F / 64.0F;
    private static final float HAT_U1 = 48.0F / 64.0F;

    /** Alpha of the "through walls" copy of the head, matching waypoint icons. */
    private static final float SEE_THROUGH_ALPHA = 0.3F;

    private PlayerHeadRenderer() {}

    public static void renderPlayerHeads(float partialTicks, MatrixStack matrixStack, boolean withDepth, boolean withoutDepth) {
        MinecraftClient client = VoxelConstants.getMinecraft();
        if (client.player == null || client.world == null || client.options.hudHidden) return;

        List<PlayerPositionStore.PlayerEntry> entries = PlayerPositionStore.getEntries();
        if (entries.isEmpty()) return;

        String currentDimension = client.world.getRegistryKey().getValue().toString();
        double renderPosX = GameVariableAccessShim.xCoordDouble();
        double renderPosY = GameVariableAccessShim.yCoordDouble();
        double renderPosZ = GameVariableAccessShim.zCoordDouble();

        OpenGL.glEnable(OpenGL.GL11_GL_CULL_FACE);
        OpenGL.glEnable(OpenGL.GL11_GL_BLEND);
        // Same blend setup as the waypoint signs pass, which also runs from the
        // hand render hook, so the two agree on how alpha is blended
        OpenGL.glBlendFuncSeparate(OpenGL.GL11_GL_SRC_ALPHA, OpenGL.GL11_GL_ONE_MINUS_SRC_ALPHA,
                OpenGL.GL11_GL_ONE_MINUS_SRC_ALPHA, OpenGL.GL11_GL_ONE_MINUS_SRC_ALPHA);

        for (PlayerPositionStore.PlayerEntry entry : entries) {
            // Hidden players are drawn nowhere: not on the map, not in the world,
            // and not as radar contacts
            if (entry.hidden) continue;
            // Heads only belong in the dimension they are standing in
            if (!entry.dimension.equals(currentDimension)) continue;
            if (entry.uuid.equals(client.player.getUuid())) continue;

            Vec3d position = livePosition(client.world, entry, partialTicks);
            renderHead(matrixStack, client, entry.uuid, position.x - renderPosX,
                    position.y + HEAD_HEIGHT - renderPosY, position.z - renderPosZ, withDepth, withoutDepth);
        }

        OpenGL.glDepthMask(true);
        OpenGL.glEnable(OpenGL.GL11_GL_DEPTH_TEST);
        OpenGL.glDisable(OpenGL.GL11_GL_BLEND);
        OpenGL.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * The tracked entity's interpolated position when this client can see the
     * player, otherwise the last position the server reported.
     */
    private static Vec3d livePosition(ClientWorld world, PlayerPositionStore.PlayerEntry entry, float partialTicks) {
        for (PlayerEntity player : world.getPlayers()) {
            if (player.getUuid().equals(entry.uuid)) {
                return player.getLerpedPos(partialTicks);
            }
        }
        return new Vec3d(entry.x, entry.y, entry.z);
    }

    private static void renderHead(MatrixStack matrixStack, MinecraftClient client, UUID uuid,
            double x, double y, double z, boolean withDepth, boolean withoutDepth) {
        Identifier skin = skinFor(client, uuid);
        if (skin == null) return;

        matrixStack.push();
        matrixStack.translate(x, y, z);
        matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(
                -client.getEntityRenderDispatcher().camera.getYaw()));
        matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(
                client.getEntityRenderDispatcher().camera.getPitch()));
        matrixStack.scale(-HEAD_SIZE, -HEAD_SIZE, -HEAD_SIZE);
        Matrix4f matrix4f = matrixStack.peek().getPositionMatrix();

        RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        RenderSystem.setShaderTexture(0, skin);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder vertexBuffer = tessellator.getBuffer();

        if (withDepth) {
            OpenGL.glEnable(OpenGL.GL11_GL_DEPTH_TEST);
            OpenGL.glDepthMask(true);
            drawHeadLayers(vertexBuffer, tessellator, matrix4f, 1.0F);
        }

        if (withoutDepth) {
            OpenGL.glDisable(OpenGL.GL11_GL_DEPTH_TEST);
            OpenGL.glDepthMask(false);
            drawHeadLayers(vertexBuffer, tessellator, matrix4f, SEE_THROUGH_ALPHA);
        }

        matrixStack.pop();
    }

    /**
     * Face first, then the hat layer over it. The hat is a transparent overlay,
     * so it only needs to be drawn on top of the face.
     */
    private static void drawHeadLayers(BufferBuilder vertexBuffer, Tessellator tessellator, Matrix4f matrix4f, float alpha) {
        drawQuad(vertexBuffer, tessellator, matrix4f, FACE_U0, FACE_V0, FACE_U1, FACE_V1, alpha);
        drawQuad(vertexBuffer, tessellator, matrix4f, HAT_U0, FACE_V0, HAT_U1, FACE_V1, alpha);
    }

    private static void drawQuad(BufferBuilder vertexBuffer, Tessellator tessellator, Matrix4f matrix4f,
            float u0, float v0, float u1, float v1, float alpha) {
        float half = 0.5F;
        vertexBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        vertexBuffer.vertex(matrix4f, -half, -half, 0.0F).texture(u0, v0).color(1.0F, 1.0F, 1.0F, alpha).next();
        vertexBuffer.vertex(matrix4f, -half, half, 0.0F).texture(u0, v1).color(1.0F, 1.0F, 1.0F, alpha).next();
        vertexBuffer.vertex(matrix4f, half, half, 0.0F).texture(u1, v1).color(1.0F, 1.0F, 1.0F, alpha).next();
        vertexBuffer.vertex(matrix4f, half, -half, 0.0F).texture(u1, v0).color(1.0F, 1.0F, 1.0F, alpha).next();
        tessellator.draw();
    }

    /**
     * The player's skin, falling back to their default skin when this client has
     * no entry for them yet.
     */
    private static Identifier skinFor(MinecraftClient client, UUID uuid) {
        ClientPlayNetworkHandler networkHandler = client.getNetworkHandler();
        if (networkHandler != null) {
            PlayerListEntry entry = networkHandler.getPlayerListEntry(uuid);
            if (entry != null) {
                return entry.getSkinTexture();
            }
        }
        return DefaultSkinHelper.getTexture(uuid);
    }
}
