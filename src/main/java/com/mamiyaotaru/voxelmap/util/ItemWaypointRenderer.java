package com.mamiyaotaru.voxelmap.util;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Resolves and draws item IDs attached to waypoints by integrations. */
public final class ItemWaypointRenderer {
    private ItemWaypointRenderer() {
    }

    public static ItemStack resolve(Waypoint waypoint) {
        if (waypoint.itemIconId == null) {
            return ItemStack.EMPTY;
        }
        Identifier id = Identifier.tryParse(waypoint.itemIconId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = Registries.ITEM.getOrEmpty(id).orElse(Items.AIR);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static void draw(DrawContext context, ItemStack stack, float centerX, float centerY, float size) {
        if (stack.isEmpty()) {
            return;
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        OpenGL.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        context.getMatrices().push();
        context.getMatrices().translate(centerX - size / 2.0F, centerY - size / 2.0F, 200.0F);
        float scale = size / 16.0F;
        context.getMatrices().scale(scale, scale, 1.0F);
        context.drawItem(stack, 0, 0);
        context.getMatrices().pop();
    }
}