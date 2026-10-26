package com.mamiyaotaru.voxelmap.util;

import java.util.Locale;

/** Wire format for optional item-backed waypoint icons. */
public final class ItemWaypointIcon {
    public static final String PREFIX = "server_waypoint_item/";

    private ItemWaypointIcon() {
    }

    public static String encode(String itemId) {
        return PREFIX + itemId.toLowerCase(Locale.ROOT);
    }

    public static String decode(String imageSuffix) {
        if (imageSuffix == null || !imageSuffix.startsWith(PREFIX)) {
            return null;
        }
        String itemId = imageSuffix.substring(PREFIX.length());
        int separator = itemId.indexOf(':');
        return separator > 0 && separator < itemId.length() - 1 ? itemId : null;
    }
}