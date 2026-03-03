package com.tonic.services.llmapi.handlers;

import com.tonic.Static;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.TileItemAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.QuestAPI;
import com.tonic.api.game.VarAPI;
import com.tonic.api.widgets.BankAPI;
import com.tonic.api.widgets.DialogueAPI;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.MakeXAPI;
import com.tonic.data.EquipmentSlot;
import com.tonic.data.wrappers.*;
import com.tonic.queries.*;
import com.tonic.services.GameManager;
import com.tonic.services.llmapi.dto.*;
import com.tonic.services.llmapi.state.BankStateStore;
import com.tonic.services.llmapi.state.RecentMessageStore;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.Player;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Handlers for GET endpoints - reading game state
 */
public class DataHandlers {
    private static final int RECENT_MESSAGE_LIMIT = 4;

    // ==================== PLAYERS ====================

    public String getPlayers() {
        try {
            return Static.invoke(() -> {
                List<PlayerEx> players = new PlayerQuery().collect();
                PlayerEx local = PlayerEx.getLocal();

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", players.size() - 1); // Exclude local
                json.key("players").startArray();

                for (PlayerEx player : players) {
                    if (local != null && player.getPlayer().equals(local.getPlayer())) {
                        continue; // Skip local player
                    }
                    json.rawJson(PlayerDTO.toJsonBrief(player));
                }

                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            e.printStackTrace();
            return JsonBuilder.error(500, "Failed to get players: " + e.getMessage());
        }
    }

    public String getPlayer(int index) {
        try {
            return Static.invoke(() -> {
                PlayerEx player = new PlayerQuery()
                        .keepIf(p -> p.getIndex() == index)
                        .first();

                if (player == null) {
                    return JsonBuilder.error(404, "Player not found with index: " + index);
                }

                return PlayerDTO.toJson(player);
            });
        } catch (Exception e) {
            e.printStackTrace();
            return JsonBuilder.error(500, "Failed to get player: " + e.getMessage());
        }
    }

    // ==================== NPCS ====================

    public String getNpcs() {
        try {
            return Static.invoke(() -> {
                List<NpcEx> npcs = /*new NpcQuery().collect(); */NpcAPI.search().removeIf(n -> n.getName().isEmpty()).collect();

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", npcs.size());
                json.key("npcs").startArray();

                for (NpcEx npc : npcs) {
                    json.rawJson(NpcDTO.toJsonBrief(npc));
                }

                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            e.printStackTrace();
            return JsonBuilder.error(500, "Failed to get NPCs: " + e.getMessage());
        }
    }

    public String getNpc(int index) {
        try {
            return Static.invoke(() -> {
                NpcEx npc = new NpcQuery()
                        .keepIf(n -> n.getIndex() == index)
                        .first();

                if (npc == null) {
                    return JsonBuilder.error(404, "NPC not found with index: " + index);
                }

                return NpcDTO.toJson(npc);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get NPC: " + e.getMessage());
        }
    }

    // ==================== OBJECTS ====================

    public String getObjects() {
        try {
            return Static.invoke(() -> {
                List<TileObjectEx> objects = collectNearbyInteractiveObjects();

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", objects.size());
                json.key("objects").startArray();

                for (TileObjectEx obj : objects) {
                    json.rawJson(TileObjectDTO.toJsonBrief(obj));
                }

                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get objects: " + e.getMessage());
        }
    }

    public String getObject(int id, int x, int y, int plane) {
        try {
            return Static.invoke(() -> {
                TileObjectQuery query = new TileObjectQuery().withId(id);

                // If coordinates provided, filter by location
                if (x >= 0 && y >= 0) {
                    final int fx = x, fy = y, fp = plane >= 0 ? plane : 0;
                    query.keepIf(obj -> {
                        WorldPoint pos = obj.getWorldPoint();
                        return pos.getX() == fx && pos.getY() == fy && pos.getPlane() == fp;
                    });
                }

                TileObjectEx obj = query.first();

                if (obj == null) {
                    return JsonBuilder.error(404, "Object not found with id: " + id);
                }

                return TileObjectDTO.toJson(obj);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get object: " + e.getMessage());
        }
    }

    // ==================== GROUND ITEMS ====================

    public String getGroundItems() {
        try {
            return Static.invoke(() -> {
                List<TileItemEx> items = new TileItemQuery().collect();

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", items.size());
                json.key("items").startArray();

                for (TileItemEx item : items) {
                    json.rawJson(TileItemDTO.toJsonBrief(item));
                }

                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get ground items: " + e.getMessage());
        }
    }

    public String getGroundItem(int id, int x, int y, int plane) {
        try {
            return Static.invoke(() -> {
                TileItemQuery query = new TileItemQuery().withId(id);

                // If coordinates provided, filter by location
                if (x >= 0 && y >= 0) {
                    final int fx = x, fy = y, fp = plane >= 0 ? plane : 0;
                    query.keepIf(item -> {
                        WorldPoint pos = item.getWorldPoint();
                        return pos.getX() == fx && pos.getY() == fy && pos.getPlane() == fp;
                    });
                }

                TileItemEx item = query.first();

                if (item == null) {
                    return JsonBuilder.error(404, "Ground item not found with id: " + id);
                }

                return TileItemDTO.toJson(item);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get ground item: " + e.getMessage());
        }
    }

    // ==================== INVENTORY ====================

    public String getInventory() {
        try {
            return Static.invoke(() -> {
                List<ItemEx> items = InventoryAPI.getItems();

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", items.size());
                json.field("emptySlots", InventoryAPI.getEmptySlots());
                json.field("isFull", InventoryAPI.isFull());
                json.key("items").startArray();

                for (ItemEx item : items) {
                    if (item.getId() > 0) {
                        json.rawJson(ItemDTO.toJson(item));
                    }
                }

                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get inventory: " + e.getMessage());
        }
    }

    public String getInventorySlot(int slot) {
        try {
            return Static.invoke(() -> {
                if (slot < 0 || slot > 27) {
                    return JsonBuilder.error(400, "Invalid slot. Must be 0-27");
                }

                List<ItemEx> items = InventoryAPI.getItems();
                ItemEx item = null;

                for (ItemEx i : items) {
                    if (i.getSlot() == slot && i.getId() > 0) {
                        item = i;
                        break;
                    }
                }

                if (item == null) {
                    return JsonBuilder.error(404, "No item in slot: " + slot);
                }

                return ItemDTO.toJson(item);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get inventory slot: " + e.getMessage());
        }
    }

    // ==================== EQUIPMENT ====================

    public String getEquipment() {
        try {
            return Static.invoke(this::buildEquipmentJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get equipment: " + e.getMessage());
        }
    }

    public String getEquipmentSlot(int slotIdx) {
        try {
            return Static.invoke(() -> buildEquipmentSlotJson(slotIdx));
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get equipment slot: " + e.getMessage());
        }
    }

    // ==================== SKILLS ====================

    public String getSkills() {
        try {
            return Static.invoke(() -> {
                Client client = Static.getClient();

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.key("skills").startArray();

                for (Skill skill : Skill.values()) {
                    int boosted = client.getBoostedSkillLevel(skill);
                    int real = client.getRealSkillLevel(skill);
                    int xp = client.getSkillExperience(skill);
                    json.rawJson(SkillDTO.toJson(skill, boosted, real, xp));
                }

                json.endArray();
                json.field("totalLevel", client.getTotalLevel());
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get skills: " + e.getMessage());
        }
    }

    public String getSkill(String skillName) {
        try {
            return Static.invoke(() -> {
                Client client = Static.getClient();
                Skill skill = null;

                for (Skill s : Skill.values()) {
                    if (s.getName().equalsIgnoreCase(skillName)) {
                        skill = s;
                        break;
                    }
                }

                if (skill == null) {
                    return JsonBuilder.error(404, "Skill not found: " + skillName);
                }

                int boosted = client.getBoostedSkillLevel(skill);
                int real = client.getRealSkillLevel(skill);
                int xp = client.getSkillExperience(skill);
                return SkillDTO.toJson(skill, boosted, real, xp);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get skill: " + e.getMessage());
        }
    }

    // ==================== QUESTS ====================

    public String getCompletedQuests() {
        return getQuestListByState(QuestState.FINISHED);
    }

    public String getUnfinishedQuests() {
        try {
            return Static.invoke(() -> {
                Map<String, QuestState> allQuests = QuestAPI.getQuests();
                int playerQuestPoints = VarAPI.getVarp(VarPlayer.QUEST_POINTS);

                List<Map.Entry<String, QuestState>> filtered = new ArrayList<>();
                if (allQuests != null) {
                    for (Map.Entry<String, QuestState> entry : allQuests.entrySet()) {
                        QuestState state = entry.getValue();
                        if (state == QuestState.NOT_STARTED || state == QuestState.IN_PROGRESS) {
                            filtered.add(entry);
                        }
                    }
                }
                filtered.sort(Comparator.comparing(Map.Entry::getKey));

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", filtered.size());
                json.field("playerQuestPoints", playerQuestPoints);
                json.key("quests").startArray();
                for (Map.Entry<String, QuestState> entry : filtered) {
                    json.startObject();
                    json.field("name", entry.getKey());
                    json.field("state", entry.getValue().name());
                    json.endObject();
                }
                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get unfinished quests: " + e.getMessage());
        }
    }

    // ==================== BANK ====================

    public String getBankItems() {
        try {
            return Static.invoke(this::buildBankItemsJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get bank items: " + e.getMessage());
        }
    }

    // ==================== LOCAL PLAYER ====================

    public String getLocalPlayer() {
        try {
            return Static.invoke(this::buildLocalPlayerJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get local player: " + e.getMessage());
        }
    }

    // ==================== COLLISION ====================

    public String getCollision() {
        try {
            return Static.invoke(() -> {
                Client client = Static.getClient();
                CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();
                int plane = client.getTopLevelWorldView().getPlane();

                if (maps == null || plane < 0 || plane >= maps.length) {
                    return JsonBuilder.error(404, "Collision data not available");
                }

                Player local = client.getLocalPlayer();
                if (local == null) {
                    return JsonBuilder.error(404, "Could not determine base coordinates");
                }

                WorldPoint wp = local.getWorldLocation();
                int baseX = client.getTopLevelWorldView().getBaseX();
                int baseY = client.getTopLevelWorldView().getBaseY();
                WorldPoint basePoint = new WorldPoint(baseX, baseY, wp.getPlane());
                return CollisionDTO.toJson(maps[plane], basePoint.getX(), basePoint.getY(), plane);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get collision: " + e.getMessage());
        }
    }

    public String checkCollision(int worldX, int worldY, int plane) {
        try {
            return Static.invoke(() -> {
                if (worldX < 0 || worldY < 0) {
                    return JsonBuilder.error(400, "Missing x or y parameter");
                }

                Client client = Static.getClient();
                CollisionData[] maps = client.getTopLevelWorldView().getCollisionMaps();

                if (maps == null || plane < 0 || plane >= maps.length) {
                    return JsonBuilder.error(404, "Collision data not available");
                }

                int baseX = client.getTopLevelWorldView().getBaseX();
                int baseY = client.getTopLevelWorldView().getBaseY();

                int localX = worldX - baseX;
                int localY = worldY - baseY;
                return CollisionDTO.checkTile(maps[plane], localX, localY, worldX, worldY, plane);
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to check collision: " + e.getMessage());
        }
    }

    // ==================== DIALOGUE ====================

    public String getDialogue() {
        try {
            return Static.invoke(DialogueDTO::toJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get dialogue: " + e.getMessage());
        }
    }

    // ==================== BATCHED STATE ====================

    /**
     * Get all game state in a single call - optimized for LLM agents.
     * One HTTP request instead of many = much faster.
     */
    public String getFullState() {
        try {
            return Static.invoke(this::buildFullStateJson);
        } catch (Exception e) {
            e.printStackTrace();
            return JsonBuilder.error(500, "Failed to get state: " + e.getMessage());
        }
    }

    private String buildEquipmentJson() {
        List<ItemEx> items = EquipmentAPI.getAll();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("count", items.size());
        json.key("items").startArray();

        for (ItemEx item : items) {
            if (item.getId() > 0) {
                json.rawJson(ItemDTO.toJson(item));
            }
        }

        json.endArray();
        json.endObject();
        return json.toString();
    }

    private String buildEquipmentSlotJson(int slotIdx) {
        EquipmentSlot slot = EquipmentSlot.findBySlot(slotIdx);
        if (slot == null) {
            return JsonBuilder.error(400, "Invalid equipment slot: " + slotIdx);
        }

        ItemEx item = EquipmentAPI.fromSlot(slot);
        if (item == null || item.getId() <= 0) {
            return JsonBuilder.error(404, "No item in equipment slot: " + slotIdx);
        }

        return ItemDTO.toJson(item);
    }

    private String buildLocalPlayerJson() {
        PlayerEx local = PlayerEx.getLocal();
        if (local == null) {
            return JsonBuilder.error(404, "Local player not available");
        }

        return LocalPlayerDTO.toJsonDirect(local);
    }

    private String buildFullStateJson() {
        if (BankAPI.isOpen()) {
            return buildBankingStateJson();
        }
        return buildWorldStateJson();
    }

    private String buildWorldStateJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("mode", "WORLD");

        PlayerEx local = PlayerEx.getLocal();
        if (local != null) {
            json.key("player").rawJson(LocalPlayerDTO.toJsonDirect(local));
            if (isInCombat(local)) {
                NpcEx combatTarget = resolveCombatTarget(local);
                json.key("combatTarget").rawJson(buildCombatTargetJson(combatTarget));
            }
        }

        appendInterfaces(json);
        appendEquipmentState(json);
        appendSkillsState(json);
        appendInventoryState(json);

        List<NpcEx> npcs = new NpcQuery().within(20).collect();
        json.key("npcs").startArray();
        for (NpcEx npc : npcs) {
            json.rawJson(NpcDTO.toJsonBrief(npc));
        }
        json.endArray();

        json.key("npcsTargetingPlayer").startArray();
        if (local != null) {
            List<NpcEx> targetingPlayer = new NpcQuery()
                    .keepIf(npc -> {
                        ActorEx<?> interacting = npc.getInteracting();
                        return interacting != null
                                && interacting.equals(local)
                                && hasAttackAction(npc.getActions());
                    })
                    .collect();
            for (NpcEx npc : targetingPlayer) {
                json.rawJson(NpcDTO.toJsonBrief(npc));
            }
        }
        json.endArray();

        List<TileItemEx> groundItems =/* new TileItemQuery().within(20).collect();*/
        TileItemAPI.search().removeIf(o -> o.getName() == null || "null".equalsIgnoreCase(o.getName()))
                .within(20)
                .collect();
        json.key("ground_items").startArray();
        int itemCount = 0;
        for (TileItemEx item : groundItems) {
            if (itemCount++ >= 15) break;
            json.rawJson(TileItemDTO.toJsonBrief(item));
        }
        json.endArray();

        List<TileObjectEx> objects = collectNearbyInteractiveObjects();
        json.key("objects").startArray();
        int objCount = 0;
        for (TileObjectEx obj : objects) {
            if (objCount++ >= 15) break;
            json.rawJson(TileObjectDTO.toJsonBrief(obj));
        }
        json.endArray();

        json.key("dialogue").rawJson(DialogueDTO.toJson());
        json.fieldRaw("recentMessages", buildRecentMessagesJson());

        json.endObject();
        return json.toString();
    }

    private String buildBankingStateJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("mode", "BANKING");

        PlayerEx local = PlayerEx.getLocal();
        if (local != null) {
            json.key("player").rawJson(buildBankingPlayerSummaryJson(local));
        }

        appendInterfaces(json);
        json.key("bank").rawJson(buildBankItemsJson());
        appendInventoryState(json);
        appendEquipmentState(json);
        appendSkillsState(json);

        json.endObject();
        return json.toString();
    }

    private String buildBankingPlayerSummaryJson(PlayerEx player) {
        Client client = Static.getClient();
        WorldPoint pos = player.getWorldPoint();

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("name", player.getName());
        json.field("combatLevel", player.getCombatLevel());
        if (pos != null) {
            json.fieldRaw("position", JsonBuilder.position(pos.getX(), pos.getY(), pos.getPlane()));
        } else {
            json.fieldNull("position");
        }

        json.key("hitpoints").startObject();
        json.field("current", client.getBoostedSkillLevel(Skill.HITPOINTS));
        json.field("max", client.getRealSkillLevel(Skill.HITPOINTS));
        json.endObject();

        json.key("prayer").startObject();
        json.field("current", client.getBoostedSkillLevel(Skill.PRAYER));
        json.field("max", client.getRealSkillLevel(Skill.PRAYER));
        json.endObject();

        json.field("runEnergy", client.getEnergy() / 100);
        json.endObject();
        return json.toString();
    }

    private String buildBankItemsJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        boolean open = BankAPI.isOpen();
        json.field("open", open);

        if (open) {
            List<ItemEx> items = BankAPI.search().collect();
            BankStateStore.updateFromItems(items);
            BankStateStore.BankSnapshot snapshot = BankStateStore.getSnapshot();

            json.field("source", "LIVE");
            json.field("count", items.size());
            json.key("items").startArray();
            for (ItemEx item : items) {
                if (item.getId() > 0) {
                    json.rawJson(ItemDTO.toJsonBrief(item));
                }
            }
            json.endArray();
            if (snapshot != null) {
                json.field("lastUpdatedTick", snapshot.getLastUpdatedTick());
                json.field("lastUpdatedAtMs", snapshot.getLastUpdatedAtMs());
            }
        } else {
            BankStateStore.BankSnapshot snapshot = BankStateStore.getSnapshot();
            if (snapshot == null || snapshot.getItems().isEmpty()) {
                json.field("source", "NONE");
                json.field("count", 0);
                json.key("items").startArray();
                json.endArray();
                json.field("message", "Bank cache is empty. Open bank once this session to populate cached bank items.");
                json.fieldNull("lastUpdatedTick");
                json.fieldNull("lastUpdatedAtMs");
            } else {
                json.field("source", "CACHE");
                json.field("count", snapshot.getItems().size());
                json.key("items").startArray();
                for (BankStateStore.BankItemSnapshot item : snapshot.getItems()) {
                    json.rawJson(item.toJsonBrief());
                }
                json.endArray();
                json.field("lastUpdatedTick", snapshot.getLastUpdatedTick());
                json.field("lastUpdatedAtMs", snapshot.getLastUpdatedAtMs());
            }
        }

        json.endObject();
        return json.toString();
    }

    private void appendInterfaces(JsonBuilder json) {
        json.key("interfaces").startObject();
        json.field("dialogueOpen", DialogueAPI.dialoguePresent());
        json.field("makeXOpen", MakeXAPI.isOpen());
        json.field("bankOpen", BankAPI.isOpen());
        json.endObject();
    }

    private void appendInventoryState(JsonBuilder json) {
        List<ItemEx> invItems = InventoryAPI.getItems();
        json.key("inventory").startArray();
        for (ItemEx item : invItems) {
            if (item.getId() > 0) {
                json.rawJson(ItemDTO.toJsonBrief(item));
            }
        }
        json.endArray();
        json.field("inv_count", (int) invItems.stream().filter(i -> i.getId() > 0).count());
        json.field("inv_free", InventoryAPI.getEmptySlots());
    }

    private void appendEquipmentState(JsonBuilder json) {
        List<ItemEx> items = EquipmentAPI.getAll();
        json.key("equipment").startArray();
        for (ItemEx item : items) {
            if (item.getId() > 0) {
                json.rawJson(ItemDTO.toJsonBrief(item));
            }
        }
        json.endArray();
    }

    private void appendSkillsState(JsonBuilder json) {
        Client client = Static.getClient();
        json.key("skills").startArray();
        for (Skill skill : Skill.values()) {
            int boosted = client.getBoostedSkillLevel(skill);
            int real = client.getRealSkillLevel(skill);
            json.rawJson(SkillDTO.toJsonBrief(skill, boosted, real));
        }
        json.endArray();
    }

    private String getQuestListByState(QuestState targetState) {
        try {
            return Static.invoke(() -> {
                Map<String, QuestState> allQuests = QuestAPI.getQuests();
                int playerQuestPoints = VarAPI.getVarp(VarPlayer.QUEST_POINTS);

                List<Map.Entry<String, QuestState>> filtered = new ArrayList<>();
                if (allQuests != null) {
                    for (Map.Entry<String, QuestState> entry : allQuests.entrySet()) {
                        if (entry.getValue() == targetState) {
                            filtered.add(entry);
                        }
                    }
                }
                filtered.sort(Comparator.comparing(Map.Entry::getKey));

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", filtered.size());
                json.field("playerQuestPoints", playerQuestPoints);
                json.key("quests").startArray();
                for (Map.Entry<String, QuestState> entry : filtered) {
                    json.startObject();
                    json.field("name", entry.getKey());
                    json.field("state", entry.getValue().name());
                    json.endObject();
                }
                json.endArray();
                json.endObject();
                return json.toString();
            });
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get quests: " + e.getMessage());
        }
    }

    private String buildRecentMessagesJson() {
        List<RecentMessageStore.RecentMessage> messages = RecentMessageStore.getLast(RECENT_MESSAGE_LIMIT);

        JsonBuilder json = new JsonBuilder();
        json.startArray();
        for (RecentMessageStore.RecentMessage message : messages) {
            json.startObject();
            json.field("type", message.getType());
            json.field("sender", message.getSender());
            json.field("text", message.getText());
            json.field("tick", message.getTick());
            json.endObject();
        }
        json.endArray();
        return json.toString();
    }

    private boolean isInCombat(PlayerEx local) {
        List<NpcEx> attackers = new NpcQuery()
                .keepIf(npc -> {
                    ActorEx<?> interacting = npc.getInteracting();
                    return interacting != null
                            && interacting.equals(local)
                            && hasAttackAction(npc.getActions());
                })
                .collect();

        if (!attackers.isEmpty()) {
            return true;
        }

        ActorEx<?> interacting = local.getInteracting();
        return interacting instanceof NpcEx && hasAttackAction(((NpcEx) interacting).getActions());
    }

    private NpcEx resolveCombatTarget(PlayerEx local) {
        ActorEx<?> interacting = local.getInteracting();
        if (interacting instanceof NpcEx) {
            NpcEx target = (NpcEx) interacting;
            if (hasAttackAction(target.getActions())) {
                return target;
            }
        }

        ActorEx<?> inCombatWith = local.getInCombatWith();
        if (inCombatWith instanceof NpcEx) {
            return (NpcEx) inCombatWith;
        }

        return new NpcQuery()
                .keepIf(npc -> {
                    ActorEx<?> npcInteracting = npc.getInteracting();
                    return npcInteracting != null
                            && npcInteracting.equals(local)
                            && hasAttackAction(npc.getActions());
                })
                .nearest();
    }

    private String buildCombatTargetJson(NpcEx target) {
        if (target == null) {
            return "null";
        }

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("index", target.getIndex());
        json.field("id", target.getId());
        json.field("name", target.getName());
        json.field("combatLevel", target.getCombatLevel());
        json.field("isDead", target.isDead());

        WorldPoint position = target.getWorldPoint();
        if (position != null) {
            json.fieldRaw("position", JsonBuilder.position(position.getX(), position.getY(), position.getPlane()));
        } else {
            json.fieldNull("position");
        }

        json.key("health").startObject();
        if (target.healthBarVisible()) {
            int currentHealth = target.getHealth();
            if (currentHealth >= 0) {
                json.field("current", currentHealth);
            } else {
                json.field("current", "??");
            }
        } else {
            json.field("current", "??");
        }
        json.field("max", "??");
        json.endObject();

        json.endObject();
        return json.toString();
    }

    private List<TileObjectEx> collectNearbyInteractiveObjects() {
        return TileObjectAPI.search()
                .removeIf(o -> o.getName() == null || "null".equalsIgnoreCase(o.getName()))
                .within(20)
                .removeIf(o -> {
                    String[] actions = o.getActions();
                    if (actions == null || actions.length == 0) {
                        return true;
                    }
                    for (String action : actions) {
                        if (action != null && !action.trim().isEmpty()) {
                            return false;
                        }
                    }
                    return true;
                })
                .collect();
    }

    private boolean hasAttackAction(String[] actions) {
        if (actions == null) {
            return false;
        }
        for (String action : actions) {
            if (action != null && action.equalsIgnoreCase("Attack")) {
                return true;
            }
        }
        return false;
    }
}
