package com.tonic.services.llmapi.dto;

import com.tonic.data.wrappers.TileObjectEx;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.coords.WorldPoint;

/**
 * Data transfer object for tile object information
 */
public class TileObjectDTO {

    public static String toJson(TileObjectEx obj) {
        if (obj == null) return "null";

        WorldPoint pos = obj.getWorldPoint();
        String[] actions = JsonBuilder.filterNullActions(obj.getActions());

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", obj.getId());
        json.field("name", obj.getName());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.field("isReachable", obj.isReachable());
        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    public static String toJsonBrief(TileObjectEx obj) {
        if (obj == null) return "null";

        WorldPoint pos = obj.getWorldPoint();
        String[] actions = JsonBuilder.filterNullActions(obj.getActions());

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", obj.getId());
        json.field("name", obj.getName());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    /** Alias for use inside Static.invoke() */
    public static String toJsonBriefDirect(TileObjectEx obj) {
        return toJsonBrief(obj);
    }
}
