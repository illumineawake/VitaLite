package com.tonic.services.llmapi.dto;

import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.Skill;

/**
 * Data transfer object for skill information
 */
public class SkillDTO {

    public static String toJson(Skill skill, int boostedLevel, int realLevel, int experience) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("name", skill.getName());
        json.field("boostedLevel", boostedLevel);
        json.field("realLevel", realLevel);
        json.field("experience", experience);
        json.endObject();

        return json.toString();
    }

    public static String toJsonBrief(Skill skill, int boostedLevel, int realLevel) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("name", skill.getName());
        json.field("boostedLevel", boostedLevel);
        json.field("realLevel", realLevel);
        json.endObject();

        return json.toString();
    }
}
