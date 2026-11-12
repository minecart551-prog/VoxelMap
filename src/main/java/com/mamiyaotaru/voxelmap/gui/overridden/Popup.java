package com.mamiyaotaru.voxelmap.gui.overridden;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import com.mamiyaotaru.voxelmap.util.OpenGL;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.util.ArrayList;

public class Popup {
    private static final float TEXT_SCALE = 0.8F;
    private static final int PANEL_GRAY = 15;
    private static final int PANEL_ALPHA = 190;
    private static final int EXTRA_WIDTH = 10;
    private static final int LINE_GRAY = 150;
    private static final int HOVER_ALPHA = 46; // ~18% white hover wash over the option row
    final TextRenderer fontRendererObj;
    int x;
    int y;
    final PopupEntry[] entries;
    int w;
    final int h;
    public final int clickedX;
    public final int clickedY;
    public final int clickedDirectX;
    public final int clickedDirectY;
    boolean shouldClose;
    final PopupGuiScreen parentGui;
    private static final int PADDING = 2;
    final int entryHeight = 13;
    final int lineGap = 2;
    final int textTopOffset = 3;

    private int rowTop(int t) {
        return this.y + PADDING + t * (this.entryHeight + this.lineGap);
    }

    public Popup(int x, int y, int directX, int directY, ArrayList<PopupEntry> entries, PopupGuiScreen parentGui) {
        this.fontRendererObj = VoxelConstants.getMinecraft().textRenderer;
        this.parentGui = parentGui;
        this.clickedX = x;
        this.clickedY = y;
        this.clickedDirectX = directX;
        this.clickedDirectY = directY;
        this.x = x - 1;
        this.y = y - 1;
        this.entries = new PopupEntry[entries.size()];
        entries.toArray(this.entries);
        // bottom padding is 1: the box ends one pixel higher than it used to
        this.h = PADDING + 1 + this.entries.length * this.entryHeight + Math.max(0, this.entries.length - 1) * this.lineGap;

        for (PopupEntry entry : this.entries) {
            int entryWidth = (int) Math.ceil(this.fontRendererObj.getWidth(entry.name) * TEXT_SCALE);
            if (entryWidth > this.w) {
                this.w = entryWidth;
            }
        }

        this.w += PADDING * 2 + EXTRA_WIDTH;
        if (x + this.w > parentGui.width) {
            this.x = x - this.w + 2;
        }

        if (y + this.h > parentGui.height) {
            this.y = y - this.h + 2;
        }

    }

    public boolean clickedMe(double mouseX, double mouseY) {
        return clickedMe(mouseX, mouseY, true);
    }

    public boolean releasedMe(double mouseX, double mouseY) {
        return clickedMe(mouseX, mouseY, false);
    }

    /** Fires entries: press for causesClose=true rows, release for causesClose=false rows. */
    private boolean clickedMe(double mouseX, double mouseY, boolean firingOnPress) {
        boolean clicked = mouseX > this.x && mouseX < (this.x + this.w) && mouseY > this.y && mouseY < (this.y + this.h);
        if (clicked) {
            for (int t = 0; t < this.entries.length; ++t) {
                if (this.entries[t].enabled) {
                    int rowTop = this.rowTop(t);
                    boolean entryClicked = mouseX >= this.x && mouseX <= (this.x + this.w) && mouseY >= rowTop && mouseY <= (rowTop + this.entryHeight);
                    // entries that close the popup fire on press; toggles that keep it
                    // open cycle on release instead, so one physical click fires once
                    if (entryClicked && (firingOnPress == this.entries[t].causesClose)) {
                        this.shouldClose = this.entries[t].causesClose;
                        this.parentGui.popupAction(this, this.entries[t].action);
                    }
                }
            }
        }

        return clicked;
    }

    /** Replaces the label of the entry with the given action, e.g. to cycle option text. */
    public void setEntryName(int action, String newName) {
        for (PopupEntry entry : this.entries) {
            if (entry.action == action) {
                entry.name = newName;
            }
        }

    }

    public boolean overMe(int x, int y) {
        return x > this.x && x < this.x + this.w && y > this.y && y < this.y + this.h;
    }

    public boolean shouldClose() {
        return this.shouldClose;
    }

    private static void addBorder(BufferBuilder vertexBuffer, int x, int y, int w, int h) {
        // top and bottom edge
        vertexBuffer.vertex(x + 1, y + 1, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w - 1, y + 1, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w - 1, y, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + 1, y, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + 1, y + h, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w - 1, y + h, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w - 1, y + h - 1, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + 1, y + h - 1, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        // left and right edge (same BL -> BR -> TR -> TL winding as every other quad)
        vertexBuffer.vertex(x, y + h, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + 1, y + h, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + 1, y, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x, y, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w - 1, y + h, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w, y + h, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w, y, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
        vertexBuffer.vertex(x + w - 1, y, 0.0).color(LINE_GRAY, LINE_GRAY, LINE_GRAY, 255).next();
    }

    public void drawPopup(DrawContext drawContext, int mouseX, int mouseY) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder vertexBuffer = tessellator.getBuffer();
        OpenGL.glDisable(OpenGL.GL11_GL_DEPTH_TEST);
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        OpenGL.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        OpenGL.glEnable(OpenGL.GL11_GL_BLEND);
        OpenGL.glBlendFunc(OpenGL.GL11_GL_SRC_ALPHA, OpenGL.GL11_GL_ONE_MINUS_SRC_ALPHA);
        vertexBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        vertexBuffer.vertex(this.x, this.y + this.h, 0.0).color(PANEL_GRAY, PANEL_GRAY, PANEL_GRAY, PANEL_ALPHA).next();
        vertexBuffer.vertex(this.x + this.w, this.y + this.h, 0.0).color(PANEL_GRAY, PANEL_GRAY, PANEL_GRAY, PANEL_ALPHA).next();
        vertexBuffer.vertex(this.x + this.w, this.y, 0.0).color(PANEL_GRAY, PANEL_GRAY, PANEL_GRAY, PANEL_ALPHA).next();
        vertexBuffer.vertex(this.x, this.y, 0.0).color(PANEL_GRAY, PANEL_GRAY, PANEL_GRAY, PANEL_ALPHA).next();
        // thin separation line between each option row, flush with the box edges;
        // the last row has none, so the box ends where the last option ends
        for (int t = 1; t < this.entries.length; ++t) {
            int rowTop = this.rowTop(t);
            int lineTop = rowTop - this.lineGap;
            int lineBottom = lineTop + 1;
            int divider = this.entries[t].dividerColor;
            int r = (divider == 0 ? LINE_GRAY : divider >> 16 & 255);
            int g = (divider == 0 ? LINE_GRAY : divider >> 8 & 255);
            int b = (divider == 0 ? LINE_GRAY : divider & 255);
            vertexBuffer.vertex(this.x, lineBottom, 0.0).color(r, g, b, 255).next();
            vertexBuffer.vertex(this.x + this.w, lineBottom, 0.0).color(r, g, b, 255).next();
            vertexBuffer.vertex(this.x + this.w, lineTop, 0.0).color(r, g, b, 255).next();
            vertexBuffer.vertex(this.x, lineTop, 0.0).color(r, g, b, 255).next();
        }
        // hovered option gets the background of its section washed instead of the text color changing
        int hoveredRow = -1;
        for (int t = 0; t < this.entries.length; ++t) {
            if (this.entries[t].enabled
                    && mouseX >= this.x && mouseX <= this.x + this.w
                    && mouseY >= this.rowTop(t) && mouseY <= this.rowTop(t) + this.entryHeight) {
                hoveredRow = t;
                break;
            }
        }
        if (hoveredRow != -1) {
            int rowTop = this.rowTop(hoveredRow);
            // 1px above the row as well, like every other section; the bottom
            // section also picks up the pad pixel below its row up to the border
            int washTop = Math.max(this.y + 1, rowTop - 1);
            int washBottom = rowTop + this.entryHeight + (hoveredRow == this.entries.length - 1 ? 1 : 0);
            vertexBuffer.vertex(this.x, washBottom, 0.0).color(255, 255, 255, HOVER_ALPHA).next();
            vertexBuffer.vertex(this.x + this.w, washBottom, 0.0).color(255, 255, 255, HOVER_ALPHA).next();
            vertexBuffer.vertex(this.x + this.w, washTop, 0.0).color(255, 255, 255, HOVER_ALPHA).next();
            vertexBuffer.vertex(this.x, washTop, 0.0).color(255, 255, 255, HOVER_ALPHA).next();
        }
        // 1px surrounding border in the same color as the separators
        addBorder(vertexBuffer, this.x, this.y, this.w, this.h);

        tessellator.draw();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        OpenGL.glDisable(OpenGL.GL11_GL_BLEND);

        MatrixStack matrices = drawContext.getMatrices();
        for (int t = 0; t < this.entries.length; ++t) {
            int rowTop = this.rowTop(t);
            int color = this.entries[t].enabled ? 16777215 : 10526880;
            float drawX = (rowTop + this.textTopOffset) / TEXT_SCALE;
            float scaledTextWidth = this.fontRendererObj.getWidth(this.entries[t].name) * TEXT_SCALE;
            float drawTextX = (this.x + (this.w - scaledTextWidth) / 2.0F) / TEXT_SCALE;
            matrices.push();
            matrices.scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
            drawContext.drawTextWithShadow(this.fontRendererObj, this.entries[t].name, (int) drawTextX, (int) drawX, color);
            matrices.pop();
        }

    }

    public static class PopupEntry {
        public String name;
        public final int action;
        final boolean causesClose;
        final boolean enabled;
        /** Divider line drawn above this entry; 0 uses the default white/gray divider. */
        public final int dividerColor;

        public PopupEntry(String name, int action, boolean causesClose, boolean enabled) {
            this(name, action, causesClose, enabled, 0);
        }

        public PopupEntry(String name, int action, boolean causesClose, boolean enabled, int dividerColor) {
            this.name = name;
            this.action = action;
            this.causesClose = causesClose;
            this.enabled = enabled;
            this.dividerColor = dividerColor;
        }
    }
}
