package com.mamiyaotaru.voxelmap.players;

import java.util.List;
import java.util.UUID;

import org.joml.Matrix4f;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.GameVariableAccessShim;
import com.mamiyaotaru.voxelmap.util.OpenGL;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.DefaultSkinHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
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
    /** World units across at {@link #SIZE_REFERENCE_DISTANCE}, similar in size to a waypoint icon. */
    private static final float HEAD_SIZE = 0.5F;
    /**
     * The distance the icon's size is locked to. Perspective shrinks anything with
     * distance, so the icon is grown by the same factor to keep it the same size on
     * screen no matter how far away the player is.
     */
    private static final double SIZE_REFERENCE_DISTANCE = 20.0;
    /** Offset above the feet, so the icon sits low, over the player's own head. */
    private static final double HEAD_HEIGHT = 1.0;

    // Face and hat layer of a 64x64 skin, in normalized texture coordinates
    private static final float FACE_U0 = 8.0F / 64.0F;
    private static final float FACE_U1 = 16.0F / 64.0F;
    private static final float FACE_V0 = 8.0F / 64.0F;
    private static final float FACE_V1 = 16.0F / 64.0F;
    private static final float HAT_U0 = 40.0F / 64.0F;
    private static final float HAT_U1 = 48.0F / 64.0F;

    /**
     * Alpha of the "through walls" copy of the head. Heads are drawn fully opaque,
     * including the copy that shows through terrain.
     */
    private static final float SEE_THROUGH_ALPHA = 1.0F;

    /**
     * How close the camera has to be pointed at a player before their name shows.
     * The icon is a constant size on screen at any range, so a constant angle is a
     * constant patch of screen. Five degrees matches the waypoint label.
     */
    private static final double LOOK_ANGLE_DEGREES = 5.0;
    private static final double LOOK_AT_COSINE = Math.cos(Math.toRadians(LOOK_ANGLE_DEGREES));

    /** Label line height in font pixels, used to size the text against the head. */
    private static final float TEXT_HEIGHT = 9.0F;
    /** Blank space between the head and the name, in font pixels. */
    private static final float LABEL_GAP = 2.0F;
    /** Text height as a fraction of the head's on-screen height. */
    private static final float LABEL_HEIGHT_RATIO = 0.8F;

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

        // Heads behind the camera can never be seen, so they are dropped before any
        // matrix work. In open terrain that is roughly half of them, and it is the
        // only culling allowed here: heads have to stay visible at any distance.
        double yaw = Math.toRadians(client.getEntityRenderDispatcher().camera.getYaw());
        double pitch = Math.toRadians(client.getEntityRenderDispatcher().camera.getPitch());
        double forwardX = -Math.sin(yaw) * Math.cos(pitch);
        double forwardY = -Math.sin(pitch);
        double forwardZ = Math.cos(yaw) * Math.cos(pitch);

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
            double x = position.x - renderPosX;
            double y = position.y + HEAD_HEIGHT - renderPosY;
            double z = position.z - renderPosZ;
            double toward = x * forwardX + y * forwardY + z * forwardZ;
            // Behind the camera plane: dot product with the view direction, and a
            // head there would project inside out as well as being invisible
            if (toward <= 0.0) continue;

            double distance = Math.sqrt(x * x + y * y + z * z);
            // Named only when the camera is actually pointed at them
            String name = distance > 1.0E-4 && toward / distance >= LOOK_AT_COSINE ? entry.name : null;

            renderHead(matrixStack, client, entry.uuid, name, x, y, z, distance, withDepth, withoutDepth);
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

    private static void renderHead(MatrixStack matrixStack, MinecraftClient client, UUID uuid, String name,
            double x, double y, double z, double distance, boolean withDepth, boolean withoutDepth) {
        Identifier skin = skinFor(client, uuid);
        if (skin == null) return;

        matrixStack.push();
        matrixStack.translate(x, y, z);
        matrixStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(
                -client.getEntityRenderDispatcher().camera.getYaw()));
        matrixStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(
                client.getEntityRenderDispatcher().camera.getPitch()));

        // Named only in the through-walls pass: that pass always runs, and drawing
        // it there keeps the name readable no matter what is in front of the player
        if (name != null && withoutDepth) {
            renderLabel(matrixStack, client, name, distance);
        }

        matrixStack.push();
        // Constant size on screen: perspective divides projected size by distance, so
        // growing the icon by that same distance cancels it out. Floored at one block so
        // a head right on top of the camera does not collapse to nothing.
        float scale = (float) (HEAD_SIZE * Math.max(distance, 1.0) / SIZE_REFERENCE_DISTANCE);
        matrixStack.scale(-scale, -scale, -scale);
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
        matrixStack.pop();
    }

    /**
     * The player's name, floating just above their head while the camera is
     * pointed at them.
     *
     * <p>Same shape as the waypoint label: a backdrop quad for readability and
     * the text itself in the through-walls layer. The head and the label are both
     * scaled with distance, so locking the text to a fraction of the head's height
     * keeps the two in proportion on screen at every range.
     */
    private static void renderLabel(MatrixStack matrixStack, MinecraftClient client, String name, double distance) {
        TextRenderer textRenderer = client.textRenderer;
        if (textRenderer == null || name.isEmpty()) return;

        float headScale = (float) (HEAD_SIZE * Math.max(distance, 1.0) / SIZE_REFERENCE_DISTANCE);
        float labelScale = headScale * LABEL_HEIGHT_RATIO / TEXT_HEIGHT;
        // The head's top edge in label units; both scales grow with distance, so
        // this ratio is the same up close as it is a thousand blocks away
        float headTop = -0.5F * headScale / labelScale;
        float textTop = headTop - LABEL_GAP - TEXT_HEIGHT;

        matrixStack.push();
        matrixStack.scale(-labelScale, -labelScale, -labelScale);
        Matrix4f matrix4f = matrixStack.peek().getPositionMatrix();

        int halfWidth = textRenderer.getWidth(name) / 2;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder vertexBuffer = tessellator.getBuffer();

        // Backdrop, so the name stays legible against bright sky or snow
        OpenGL.glDisable(OpenGL.GL11_GL_DEPTH_TEST);
        OpenGL.glDepthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        vertexBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        vertexBuffer.vertex(matrix4f, -halfWidth - 2, textTop + TEXT_HEIGHT, 0.0F).color(0.0F, 0.0F, 0.0F, 0.5F).next();
        vertexBuffer.vertex(matrix4f, halfWidth + 2, textTop + TEXT_HEIGHT, 0.0F).color(0.0F, 0.0F, 0.0F, 0.5F).next();
        vertexBuffer.vertex(matrix4f, halfWidth + 2, textTop - 2, 0.0F).color(0.0F, 0.0F, 0.0F, 0.5F).next();
        vertexBuffer.vertex(matrix4f, -halfWidth - 2, textTop - 2, 0.0F).color(0.0F, 0.0F, 0.0F, 0.5F).next();
        tessellator.draw();

        VertexConsumerProvider.Immediate vertexConsumerProvider = client.getBufferBuilders().getEntityVertexConsumers();
        textRenderer.draw(Text.literal(name), -halfWidth, textTop, 0xFFFFFF, false, matrix4f,
                vertexConsumerProvider, TextRenderer.TextLayerType.SEE_THROUGH, 0, 15728880);
        vertexConsumerProvider.draw();

        OpenGL.glEnable(OpenGL.GL11_GL_BLEND);
        OpenGL.glDepthMask(false);
        matrixStack.pop();
    }

    /**
     * Face first, then the hat layer over it, both written into one buffer and
     * drawn with a single call. The hat is a transparent overlay so it has to be
     * submitted after the face, but nothing about that requires a separate draw:
     * sharing one {@code begin}/{@code draw} halves the draw calls per head, which
     * is the main per-frame cost of this renderer.
     */
    private static void drawHeadLayers(BufferBuilder vertexBuffer, Tessellator tessellator, Matrix4f matrix4f, float alpha) {
        vertexBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        drawQuad(vertexBuffer, matrix4f, FACE_U0, FACE_V0, FACE_U1, FACE_V1, alpha);
        drawQuad(vertexBuffer, matrix4f, HAT_U0, FACE_V0, HAT_U1, FACE_V1, alpha);
        tessellator.draw();
    }

    /** Appends one quad to the buffer that is already being built. */
    private static void drawQuad(BufferBuilder vertexBuffer, Matrix4f matrix4f,
            float u0, float v0, float u1, float v1, float alpha) {
        float half = 0.5F;
        vertexBuffer.vertex(matrix4f, -half, -half, 0.0F).texture(u0, v0).color(1.0F, 1.0F, 1.0F, alpha).next();
        vertexBuffer.vertex(matrix4f, -half, half, 0.0F).texture(u0, v1).color(1.0F, 1.0F, 1.0F, alpha).next();
        vertexBuffer.vertex(matrix4f, half, half, 0.0F).texture(u1, v1).color(1.0F, 1.0F, 1.0F, alpha).next();
        vertexBuffer.vertex(matrix4f, half, -half, 0.0F).texture(u1, v0).color(1.0F, 1.0F, 1.0F, alpha).next();
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
