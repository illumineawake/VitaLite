package com.tonic.services.llmapi.dto;

import com.tonic.data.wrappers.NpcEx;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.Actor;
import net.runelite.api.coords.WorldPoint;

/**
 * Data transfer object for NPC information
 */
public class NpcDTO {

    public static String toJson(NpcEx npc) {
        if (npc == null) return "null";

        WorldPoint pos = npc.getWorldPoint();
        Actor interacting = npc.getActor().getInteracting();
        String[] actions = JsonBuilder.filterNullActions(npc.getActions());
        int health = npc.getHealth();
        int combatLevel = npc.getCombatLevel();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("index", npc.getIndex());
        json.field("id", npc.getId());
        json.field("name", npc.getName());
        json.field("combatLevel", combatLevel);
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));

        // Health info
        json.key("health").startObject();
        json.field("current", health);
        json.field("max", combatLevel > 0 ? combatLevel : -1); // Approximate, actual max varies
        json.endObject();

        json.field("isMoving", !npc.isIdle());
        json.field("animation", npc.getActor().getAnimation());
        json.field("isDead", npc.isDead());

        if (interacting != null) {
            json.field("interacting", interacting.getName());
        } else {
            json.fieldNull("interacting");
        }

        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    public static String toJsonBrief(NpcEx npc) {
        if (npc == null) return "null";

        WorldPoint pos = npc.getWorldPoint();
        Actor interacting = npc.getActor().getInteracting();
        String[] actions = JsonBuilder.filterNullActions(npc.getActions());

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("index", npc.getIndex());
        json.field("id", npc.getId());
        json.field("name", npc.getName());
        json.field("combatLevel", npc.getCombatLevel());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.field("hasTarget", interacting != null);
        json.field("isDead", npc.isDead());
        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    /** Alias for use inside Static.invoke() */
    public static String toJsonBriefDirect(NpcEx npc) {
        return toJsonBrief(npc);
    }
}
