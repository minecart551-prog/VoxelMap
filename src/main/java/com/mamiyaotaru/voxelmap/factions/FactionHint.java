package com.mamiyaotaru.voxelmap.factions;

import com.mamiyaotaru.voxelmap.VoxelConstants;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * A small hint shown on the fullscreen map, anchored to the top-right corner,
 * showing the result of the latest claim action. Only one hint at a time; a new
 * hint replaces the previous one instantly and it disappears after five seconds.
 */
public final class FactionHint {
    private static final long DISPLAY_TIME_MS = 5000L;

    private static String message;
    private static long hideAfterMs = 0;

    private FactionHint() {}

    public static void show(String message) {
        FactionHint.message = message;
        FactionHint.hideAfterMs = System.currentTimeMillis() + DISPLAY_TIME_MS;
    }

    public static boolean isActive() {
        return message != null && System.currentTimeMillis() < hideAfterMs;
    }

    public static String message() {
        return message;
    }

    public static void render(DrawContext drawContext) {
        if (!isActive()) {
            return;
        }
        // anchored below the world/server name on the top right of the fullscreen map
        TextRenderer font = VoxelConstants.getMinecraft().textRenderer;
        int textWidth = font.getWidth(message());
        int boxWidth = textWidth + 16;
        int boxHeight = 15;
        int x = drawContext.getScaledWindowWidth() - boxWidth - 10;
        int y = 36; // this.top (32) + 4, below the world/server name bar
        drawContext.fill(x, y, x + boxWidth, y + boxHeight, 0xCC101010);
        drawContext.fill(x, y, x + boxWidth, y + 1, 0xCFFFFFFF);
        drawContext.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xCFFFFFFF);
        drawContext.drawTextWithShadow(font, message(), x + 8, y + 3, 16777215);
    }
}
