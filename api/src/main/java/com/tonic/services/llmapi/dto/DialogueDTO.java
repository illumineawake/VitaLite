package com.tonic.services.llmapi.dto;

import com.tonic.api.widgets.DialogueAPI;
import com.tonic.services.llmapi.util.JsonBuilder;

import java.util.List;

/**
 * Data transfer object for dialogue information
 */
public class DialogueDTO {

    public static String toJson() {
        boolean present = DialogueAPI.dialoguePresent();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("present", present);

        if (present) {
            String header = DialogueAPI.getDialogueHeader();
            String text = DialogueAPI.getDialogueText();
            List<String> options = DialogueAPI.getOptions();

            json.field("header", header);
            json.field("text", text);

            // Determine dialogue type
            String type = "unknown";
            boolean canContinue = false;
            boolean hasOptions = false;

            if ("Select an Option".equals(header) || (options != null && !options.isEmpty())) {
                type = "options";
                hasOptions = true;
            } else if (text != null && !text.isEmpty()) {
                type = "text";
                canContinue = true;
            }

            json.field("type", type);
            json.field("canContinue", canContinue);
            json.field("hasOptions", hasOptions);

            if (hasOptions && options != null) {
                json.key("options").startArray();
                for (int i = 0; i < options.size(); i++) {
                    json.startObject();
                    json.field("index", i);
                    json.field("text", options.get(i));
                    json.endObject();
                }
                json.endArray();
            } else {
                json.fieldNull("options");
            }
        } else {
            json.fieldNull("header");
            json.fieldNull("text");
            json.fieldNull("type");
            json.field("canContinue", false);
            json.field("hasOptions", false);
            json.fieldNull("options");
        }

        json.endObject();
        return json.toString();
    }
}
