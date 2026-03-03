package com.tonic.services.llmapi.dto;

import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.CollisionData;

/**
 * Data transfer object for collision map information
 */
public class CollisionDTO {

    public static String toJson(CollisionData collisionData, int baseX, int baseY, int plane) {
        if (collisionData == null) {
            return JsonBuilder.error(404, "Collision data not available");
        }

        int[][] flags = collisionData.getFlags();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("baseX", baseX);
        json.field("baseY", baseY);
        json.field("plane", plane);
        json.field("width", flags.length);
        json.field("height", flags.length > 0 ? flags[0].length : 0);
        json.fieldArray("flags", flags);
        json.endObject();

        return json.toString();
    }

    /**
     * Check if a specific tile is walkable
     */
    public static String checkTile(CollisionData collisionData, int localX, int localY, int worldX, int worldY, int plane) {
        if (collisionData == null) {
            return JsonBuilder.error(404, "Collision data not available");
        }

        int[][] flags = collisionData.getFlags();
        if (localX < 0 || localX >= flags.length || localY < 0 || localY >= flags[0].length) {
            return JsonBuilder.error(400, "Coordinates out of bounds");
        }

        int flag = flags[localX][localY];
        boolean blocked = (flag & 0x100) != 0; // BLOCK_MOVEMENT flag
        boolean blockedNorth = (flag & 0x2) != 0;
        boolean blockedSouth = (flag & 0x20) != 0;
        boolean blockedEast = (flag & 0x8) != 0;
        boolean blockedWest = (flag & 0x80) != 0;

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.fieldRaw("position", JsonBuilder.position(worldX, worldY, plane));
        json.field("flag", flag);
        json.field("isBlocked", blocked);
        json.field("blockedNorth", blockedNorth);
        json.field("blockedSouth", blockedSouth);
        json.field("blockedEast", blockedEast);
        json.field("blockedWest", blockedWest);
        json.endObject();

        return json.toString();
    }
}
