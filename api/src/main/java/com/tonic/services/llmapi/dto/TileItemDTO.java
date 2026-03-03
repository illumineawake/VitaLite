package com.tonic.services.llmapi.dto;

import com.tonic.data.wrappers.TileItemEx;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.coords.WorldPoint;

/**
 * Data transfer object for ground item information
 */
public class TileItemDTO {

    public static String toJson(TileItemEx item) {
        if (item == null) return "null";

        WorldPoint pos = item.getWorldPoint();
        String[] actions = JsonBuilder.filterNullActions(item.getActions());

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", item.getId());
        json.field("name", item.getName());
        json.field("quantity", item.getQuantity());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.field("isNoted", item.isNoted());
        json.field("gePrice", item.getGePrice());
        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    public static String toJsonBrief(TileItemEx item) {
        if (item == null) return "null";

        WorldPoint pos = item.getWorldPoint();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", item.getId());
        json.field("name", item.getName());
        json.field("quantity", item.getQuantity());
        json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        json.endObject();

        return json.toString();
    }

    /** Alias for use inside Static.invoke() */
    public static String toJsonBriefDirect(TileItemEx item) {
        return toJsonBrief(item);
    }
}
