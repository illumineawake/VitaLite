package com.tonic.services.llmapi.dto;

import com.tonic.data.wrappers.PlayerEx;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;

/**
 * Data transfer object for player information
 */
public class PlayerDTO {

    public static String toJson(PlayerEx player) {
        if (player == null) return "null";

        WorldPoint pos = player.getWorldPoint();
        Actor interacting = player.getActor().getInteracting();
        String[] actions = JsonBuilder.filterNullActions(player.getActions());

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("index", player.getIndex());
        json.field("name", player.getName());
        json.field("combatLevel", player.getCombatLevel());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.field("isMoving", !player.isIdle());
        json.field("animation", player.getActor().getAnimation());

        if (interacting != null) {
            json.field("interacting", interacting.getName());
        } else {
            json.fieldNull("interacting");
        }

        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    public static String toJsonBrief(PlayerEx player) {
        if (player == null) return "null";

        WorldPoint pos = player.getWorldPoint();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("index", player.getIndex());
        json.field("name", player.getName());
        json.field("combatLevel", player.getCombatLevel());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.endObject();

        return json.toString();
    }
}
