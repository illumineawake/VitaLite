package com.tonic.services.llmapi.dto;

import com.tonic.Static;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.game.CombatAPI;
import com.tonic.api.game.MovementAPI;
import com.tonic.data.AttackStyle;
import com.tonic.data.wrappers.ActorEx;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.data.wrappers.PlayerEx;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/**
 * Data transfer object for local player information
 */
public class LocalPlayerDTO {

    public static String toJson(PlayerEx player) {
        if (player == null) return "null";

        Client client = Static.getClient();
        WorldPoint pos = player.getWorldPoint();
        WorldPoint dest = MovementAPI.getDestinationWorldPoint();
        Actor interacting = player.getActor().getInteracting();
        ActorEx<?> interactingEx = player.getInteracting();

        // Get hitpoints and prayer from client
        int currentHp = Static.invoke(() -> client.getBoostedSkillLevel(Skill.HITPOINTS));
        int maxHp = Static.invoke(() -> client.getRealSkillLevel(Skill.HITPOINTS));
        int currentPrayer = Static.invoke(() -> client.getBoostedSkillLevel(Skill.PRAYER));
        int maxPrayer = Static.invoke(() -> client.getRealSkillLevel(Skill.PRAYER));
        int runEnergy = Static.invoke(() -> client.getEnergy()) / 100; // Energy is 0-10000

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("name", player.getName());
        json.field("combatLevel", player.getCombatLevel());
        writeCombatStyle(json, CombatAPI.getAttackStyle());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));

        // Hitpoints
        json.key("hitpoints").startObject();
        json.field("current", currentHp);
        json.field("max", maxHp);
        json.endObject();

        // Prayer
        json.key("prayer").startObject();
        json.field("current", currentPrayer);
        json.field("max", maxPrayer);
        json.endObject();

        json.field("runEnergy", runEnergy);
        json.field("isRunEnabled", MovementAPI.isRunEnabled());
        json.field("isMoving", MovementAPI.isMoving());
        json.field("isIdle", player.isIdle());
        json.field("inCombat", Static.invoke(() -> isInCombatDirect(player)));
        json.field("animation", player.getActor().getAnimation());

        if (interacting != null) {
            json.field("interacting", interacting.getName());
            if (interactingEx != null) {
                json.field("interactingIndex", interactingEx.getIndex());
            } else {
                json.fieldNull("interactingIndex");
            }
        } else {
            json.fieldNull("interacting");
            json.fieldNull("interactingIndex");
        }

        if (dest != null) {
            json.fieldRaw("destination", JsonBuilder.position(dest.getX(), dest.getY(), dest.getPlane()));
        } else {
            json.fieldNull("destination");
        }

        json.endObject();

        return json.toString();
    }

    /**
     * Version for use inside Static.invoke() - no nested invoke calls
     */
    public static String toJsonDirect(PlayerEx player) {
        if (player == null) return "null";

        Client client = Static.getClient();
        WorldPoint pos = player.getWorldPoint();
        WorldPoint dest = MovementAPI.getDestinationWorldPoint();
        Actor interacting = player.getActor().getInteracting();
        ActorEx<?> interactingEx = player.getInteracting();

        // Direct access - already inside invoke
        int currentHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        int maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        int currentPrayer = client.getBoostedSkillLevel(Skill.PRAYER);
        int maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        int runEnergy = client.getEnergy() / 100;

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("name", player.getName());
        json.field("combatLevel", player.getCombatLevel());
        writeCombatStyle(json, CombatAPI.getAttackStyle());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));

        json.key("hitpoints").startObject();
        json.field("current", currentHp);
        json.field("max", maxHp);
        json.endObject();

        json.key("prayer").startObject();
        json.field("current", currentPrayer);
        json.field("max", maxPrayer);
        json.endObject();

        json.field("runEnergy", runEnergy);
        json.field("isRunEnabled", MovementAPI.isRunEnabled());
        json.field("isMoving", MovementAPI.isMoving());
        json.field("isIdle", player.isIdle());
        json.field("inCombat", isInCombatDirect(player));
        json.field("animation", player.getActor().getAnimation());

        if (interacting != null) {
            json.field("interacting", interacting.getName());
            if (interactingEx != null) {
                json.field("interactingIndex", interactingEx.getIndex());
            } else {
                json.fieldNull("interactingIndex");
            }
        } else {
            json.fieldNull("interacting");
            json.fieldNull("interactingIndex");
        }

        if (dest != null) {
            json.fieldRaw("destination", JsonBuilder.position(dest.getX(), dest.getY(), dest.getPlane()));
        } else {
            json.fieldNull("destination");
        }

        json.endObject();
        return json.toString();
    }

    private static boolean isInCombatDirect(PlayerEx player) {
        List<NpcEx> attackingNpcs = NpcAPI.search()
                .interactingWith(player)
                .withAction("Attack")
                .collect();
        if (!attackingNpcs.isEmpty()) {
            return true;
        }

        var interacting = player.getInteracting();
        if (interacting instanceof NpcEx) {
            NpcEx targetNpc = (NpcEx) interacting;
            String[] actions = targetNpc.getActions();
            if (actions != null) {
                for (String action : actions) {
                    if (action != null && action.equalsIgnoreCase("Attack")) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static void writeCombatStyle(JsonBuilder json, AttackStyle style) {
        AttackStyle resolved = style == null ? AttackStyle.UNKNOWN : style;
        json.key("combatStyle").startObject();
        json.field("index", resolved.getIndex());
        json.field("name", resolved.name());
        json.endObject();
    }
}
