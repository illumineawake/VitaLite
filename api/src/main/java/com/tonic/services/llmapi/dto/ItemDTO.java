package com.tonic.services.llmapi.dto;

import com.tonic.data.wrappers.ItemEx;
import com.tonic.services.llmapi.util.JsonBuilder;

/**
 * Data transfer object for inventory/equipment item information
 */
public class ItemDTO {

    public static String toJson(ItemEx item) {
        if (item == null) return "null";

        String[] actions = JsonBuilder.filterNullActions(item.getActions());

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", item.getId());
        json.field("name", item.getName());
        json.field("slot", item.getSlot());
        json.field("quantity", item.getQuantity());
        json.field("isNoted", item.isNoted());
        json.field("isPlaceholder", item.isPlaceholder());
        json.field("isTradeable", item.isTradeable());
        json.field("gePrice", item.getGePrice());
        json.field("highAlchValue", item.getHighAlchValue());
        json.fieldArray("actions", actions);
        json.endObject();

        return json.toString();
    }

    public static String toJsonBrief(ItemEx item) {
        if (item == null) return "null";

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", item.getId());
        json.field("name", item.getName());
        json.field("slot", item.getSlot());
        json.field("quantity", item.getQuantity());
        json.endObject();

        return json.toString();
    }

    /** Alias for use inside Static.invoke() */
    public static String toJsonBriefDirect(ItemEx item) {
        return toJsonBrief(item);
    }
}
