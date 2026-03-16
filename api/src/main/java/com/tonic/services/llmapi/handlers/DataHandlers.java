package com.tonic.services.llmapi.handlers;

import com.tonic.Static;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.TileItemAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.GameAPI;
import com.tonic.api.game.QuestAPI;
import com.tonic.api.game.VarAPI;
import com.tonic.api.game.WorldsAPI;
import com.tonic.api.widgets.BankAPI;
import com.tonic.api.widgets.DialogueAPI;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.MakeXAPI;
import com.tonic.api.widgets.ShopAPI;
import com.tonic.api.widgets.WidgetAPI;
import com.tonic.data.EquipmentSlot;
import com.tonic.data.trading.Shop;
import com.tonic.data.wrappers.*;
import com.tonic.queries.*;
import com.tonic.services.llmapi.dto.*;
import com.tonic.services.llmapi.quest.QuestHelperBridge;
import com.tonic.services.llmapi.quest.QuestHelperContextExtractor;
import com.tonic.services.llmapi.state.BankStateStore;
import com.tonic.services.llmapi.state.MakeXStateStore;
import com.tonic.services.llmapi.state.RecentGeOfferStore;
import com.tonic.services.llmapi.state.RecentMessageStore;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Player;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Handlers for GET endpoints - reading game state
 */
public class DataHandlers {
    private static final int RECENT_MESSAGE_LIMIT = 4;
    private final QuestHelperBridge questHelperBridge;
    private final QuestHelperContextExtractor questHelperContextExtractor;

    public DataHandlers(QuestHelperBridge questHelperBridge, QuestHelperContextExtractor questHelperContextExtractor) {
        this.questHelperBridge = questHelperBridge;
        this.questHelperContextExtractor = questHelperContextExtractor;
    }

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

    public String getNpcs(String nameFilter, Integer idFilter) {
        if (nameFilter != null && idFilter != null) {
            return JsonBuilder.error(400, "Provide either name or id, not both");
        }

        try {
            return Static.invoke(() -> {
                NpcQuery query = new NpcQuery();
                if (nameFilter != null && !nameFilter.trim().isEmpty()) {
                    String needle = nameFilter.trim().toLowerCase();
                    query.keepIf(n -> n.getName() != null && n.getName().toLowerCase().contains(needle));
                }
                if (idFilter != null) {
                    query.keepIf(n -> n.getId() == idFilter);
                }

                List<NpcEx> npcs = query
                        .removeIf(n -> n.getName() == null || n.getName().trim().isEmpty())
                        .collect();

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

    public String getObjects(String nameFilter, Integer idFilter) {
        if (nameFilter != null && idFilter != null) {
            return JsonBuilder.error(400, "Provide either name or id, not both");
        }

        try {
            return Static.invoke(() -> {
                List<TileObjectEx> objects = collectNearbyInteractiveObjects(nameFilter, idFilter);

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

    public String getGroundItems(String nameFilter, Integer idFilter) {
        if (nameFilter != null && idFilter != null) {
            return JsonBuilder.error(400, "Provide either name or id, not both");
        }

        try {
            return Static.invoke(() -> {
                TileItemQuery query = new TileItemQuery();
                if (nameFilter != null && !nameFilter.trim().isEmpty()) {
                    String needle = nameFilter.trim().toLowerCase();
                    query.keepIf(item -> item.getName() != null && item.getName().toLowerCase().contains(needle));
                }
                if (idFilter != null) {
                    query.keepIf(item -> item.getId() == idFilter);
                }

                List<TileItemEx> items = query.collect();

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
        return getInventory(null, null);
    }

    public String getInventory(String nameFilter, Integer idFilter) {
        try {
            return Static.invoke(() -> {
                List<ItemEx> items = InventoryAPI.getItems();
                List<ItemEx> filtered = new ArrayList<>();

                for (ItemEx item : items) {
                    if (item == null || item.getId() <= 0) {
                        continue;
                    }
                    if (nameFilter != null) {
                        String name = item.getName();
                        if (name == null || !name.toLowerCase().contains(nameFilter.toLowerCase())) {
                            continue;
                        }
                    }
                    if (idFilter != null && item.getId() != idFilter) {
                        continue;
                    }
                    filtered.add(item);
                }

                JsonBuilder json = new JsonBuilder();
                json.startObject();
                json.field("count", filtered.size());
                json.field("emptySlots", InventoryAPI.getEmptySlots());
                json.field("isFull", InventoryAPI.isFull());
                json.key("items").startArray();

                for (ItemEx item : filtered) {
                    json.rawJson(ItemDTO.toJson(item));
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

    public String getQuestHelperNames() {
        return questHelperBridge.getQuestHelperNamesJson();
    }

    public String getActiveQuestHelperQuest() {
        return questHelperContextExtractor.getActiveQuestJson();
    }

    // ==================== BANK ====================

    public String getBankItems() {
        try {
            return Static.invoke(this::buildBankItemsJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get bank items: " + e.getMessage());
        }
    }

    // ==================== SHOP ====================

    public String getShopStatus() {
        try {
            return Static.invoke(this::buildShopStatusJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get shop status: " + e.getMessage());
        }
    }

    public String getShopItems() {
        try {
            return Static.invoke(this::buildShopItemsJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get shop items: " + e.getMessage());
        }
    }

    // ==================== GRAND EXCHANGE ====================

    public String getGrandExchangeStatus() {
        try {
            return Static.invoke(this::buildGrandExchangeStatusJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get Grand Exchange status: " + e.getMessage());
        }
    }

    public String getGrandExchangeOffers() {
        try {
            return Static.invoke(this::buildGrandExchangeOffersJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get Grand Exchange offers: " + e.getMessage());
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

    public String getMakeXStatus() {
        try {
            return Static.invoke(this::buildMakeXStatusJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get MakeX status: " + e.getMessage());
        }
    }

    public String getWidget(int groupId, int childId, Integer childChildId) {
        try {
            return Static.invoke(() -> buildWidgetJson(groupId, childId, childChildId));
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get widget: " + e.getMessage());
        }
    }

    public String getCurrentWorld() {
        try {
            return Static.invoke(this::buildCurrentWorldJson);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get current world: " + e.getMessage());
        }
    }

    public String searchWidgets(
            Integer groupId,
            Integer parentId,
            String name,
            String text,
            String textContains,
            Integer itemId,
            String action,
            String actionContains,
            boolean visibleOnly
    ) {
        try {
            return Static.invoke(() -> buildWidgetSearchJson(
                    groupId,
                    parentId,
                    name,
                    text,
                    textContains,
                    itemId,
                    action,
                    actionContains,
                    visibleOnly
            ));
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to search widgets: " + e.getMessage());
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
        if (!GameAPI.isLoggedIn()) {
            return buildLoggedOutStateJson();
        }
        if (BankAPI.isOpen()) {
            return buildBankingStateJson();
        }
        if (GrandExchangeAPI.isOpen()) {
            return buildGrandExchangeStateJson();
        }
        if (ShopAPI.isOpen()) {
            return buildShoppingStateJson();
        }
        return buildWorldStateJson();
    }

    private String buildLoggedOutStateJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("gameState", "LOGGED_OUT");
        json.endObject();
        return json.toString();
    }

    private String buildWorldStateJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        appendGameState(json);
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
        appendCurrentQuestState(json);
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
        appendGameState(json);
        json.field("mode", "BANKING");

        PlayerEx local = PlayerEx.getLocal();
        if (local != null) {
            json.key("player").rawJson(buildBankingPlayerSummaryJson(local));
        }

        appendInterfaces(json);
        appendCurrentQuestState(json);
        json.key("bank").rawJson(buildBankItemsJson());
        appendInventoryState(json);
        appendEquipmentState(json);
        appendSkillsState(json);

        json.endObject();
        return json.toString();
    }

    private String buildShoppingStateJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        appendGameState(json);
        json.field("mode", "SHOPPING");

        PlayerEx local = PlayerEx.getLocal();
        if (local != null) {
            json.key("player").rawJson(buildBankingPlayerSummaryJson(local));
        }

        appendInterfaces(json);
        appendCurrentQuestState(json);
        json.key("shop").rawJson(buildShopItemsJson());
        appendInventoryState(json);
        appendEquipmentState(json);
        appendSkillsState(json);

        json.endObject();
        return json.toString();
    }

    private String buildGrandExchangeStateJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        appendGameState(json);
        json.field("mode", "GRAND_EXCHANGE");

        PlayerEx local = PlayerEx.getLocal();
        if (local != null) {
            json.key("player").rawJson(buildBankingPlayerSummaryJson(local));
        }

        appendInterfaces(json);
        appendCurrentQuestState(json);
        json.key("ge").rawJson(buildGrandExchangeStatePayloadJson());
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

    private String buildShopStatusJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();

        boolean open = ShopAPI.isOpen();
        Shop currentShop = open ? Shop.getCurrent() : null;
        int itemCount = open ? InventoryQuery.fromCurrentShop().collect().size() : 0;

        json.field("open", open);
        if (currentShop != null) {
            json.field("shopName", currentShop.name());
            json.field("inventoryId", currentShop.getInventoryId());
        } else {
            json.fieldNull("shopName");
            json.fieldNull("inventoryId");
        }
        json.field("itemCount", itemCount);

        json.endObject();
        return json.toString();
    }

    private String buildShopItemsJson() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();

        boolean open = ShopAPI.isOpen();
        json.field("open", open);
        if (!open) {
            json.field("count", 0);
            json.key("items").startArray();
            json.endArray();
            json.field("message", "Shop is closed. Open a shop to fetch live stock.");
            json.endObject();
            return json.toString();
        }

        Shop currentShop = Shop.getCurrent();
        if (currentShop != null) {
            json.field("shopName", currentShop.name());
            json.field("inventoryId", currentShop.getInventoryId());
        } else {
            json.fieldNull("shopName");
            json.fieldNull("inventoryId");
        }

        List<ItemEx> items = InventoryQuery.fromCurrentShop().collect();
        json.field("count", items.size());
        json.key("items").startArray();
        for (ItemEx item : items) {
            if (item == null || item.getId() <= 0) {
                continue;
            }
            json.rawJson(buildShopItemJson(item));
        }
        json.endArray();

        json.endObject();
        return json.toString();
    }

    private String buildGrandExchangeStatusJson() {
        Client client = Static.getClient();
        GrandExchangeOffer[] offers = Static.invoke(client::getGrandExchangeOffers);

        int offerCount = 0;
        int activeOfferCount = 0;
        int completedOfferCount = 0;
        if (offers != null) {
            for (GrandExchangeOffer offer : offers) {
                if (offer == null || offer.getItemId() <= 0) {
                    continue;
                }
                offerCount++;
                GrandExchangeOfferState state = offer.getState();
                if (state == null) {
                    continue;
                }
                if (state == GrandExchangeOfferState.BOUGHT
                        || state == GrandExchangeOfferState.SOLD
                        || state == GrandExchangeOfferState.CANCELLED_BUY
                        || state == GrandExchangeOfferState.CANCELLED_SELL) {
                    completedOfferCount++;
                } else if (state != GrandExchangeOfferState.EMPTY) {
                    activeOfferCount++;
                }
            }
        }

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("open", GrandExchangeAPI.isOpen());
        json.field("canCollect", GrandExchangeAPI.canCollect());
        json.field("freeSlot", GrandExchangeAPI.freeSlot());
        json.field("offerCount", offerCount);
        json.field("activeOfferCount", activeOfferCount);
        json.field("completedOfferCount", completedOfferCount);
        json.endObject();
        return json.toString();
    }

    private String buildGrandExchangeOffersJson() {
        Client client = Static.getClient();
        GrandExchangeOffer[] offers = Static.invoke(client::getGrandExchangeOffers);

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("open", GrandExchangeAPI.isOpen());
        json.key("offers").startArray();

        int count = 0;
        if (offers != null) {
            for (int i = 0; i < offers.length; i++) {
                GrandExchangeOffer offer = offers[i];
                if (offer == null || offer.getItemId() <= 0) {
                    continue;
                }
                count++;
                json.startObject();
                json.field("slot", i + 1);
                json.field("itemId", offer.getItemId());
                json.field("state", offer.getState() == null ? "UNKNOWN" : offer.getState().name());
                json.field("price", offer.getPrice());
                json.field("totalQuantity", offer.getTotalQuantity());
                json.field("quantitySold", offer.getQuantitySold());
                json.field("spent", offer.getSpent());
                json.endObject();
            }
        }

        json.endArray();
        json.field("count", count);
        json.endObject();
        return json.toString();
    }

    private String buildGrandExchangeStatePayloadJson() {
        Client client = Static.getClient();
        GrandExchangeOffer[] offers = Static.invoke(client::getGrandExchangeOffers);

        int count = 0;
        int activeOfferCount = 0;
        int completedOfferCount = 0;

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("open", GrandExchangeAPI.isOpen());
        json.field("canCollect", GrandExchangeAPI.canCollect());
        json.field("freeSlot", GrandExchangeAPI.freeSlot());
        json.key("offers").startArray();

        if (offers != null) {
            for (int i = 0; i < offers.length; i++) {
                GrandExchangeOffer offer = offers[i];
                if (offer == null || offer.getItemId() <= 0) {
                    continue;
                }

                count++;
                GrandExchangeOfferState state = offer.getState();
                if (state == GrandExchangeOfferState.BOUGHT
                        || state == GrandExchangeOfferState.SOLD
                        || state == GrandExchangeOfferState.CANCELLED_BUY
                        || state == GrandExchangeOfferState.CANCELLED_SELL) {
                    completedOfferCount++;
                } else if (state != GrandExchangeOfferState.EMPTY) {
                    activeOfferCount++;
                }

                json.startObject();
                json.field("slot", i + 1);
                json.field("itemId", offer.getItemId());
                json.field("state", state == null ? "UNKNOWN" : state.name());
                json.field("price", offer.getPrice());
                json.field("totalQuantity", offer.getTotalQuantity());
                json.field("quantitySold", offer.getQuantitySold());
                json.field("spent", offer.getSpent());
                json.endObject();
            }
        }

        json.endArray();
        json.field("count", count);
        json.field("offerCount", count);
        json.field("activeOfferCount", activeOfferCount);
        json.field("completedOfferCount", completedOfferCount);
        json.key("recentResolvedOffers").startArray();
        List<RecentGeOfferStore.RecentGeOfferResolution> recentResolutions = RecentGeOfferStore.getRecent();
        for (RecentGeOfferStore.RecentGeOfferResolution resolution : recentResolutions) {
            json.rawJson(resolution.toJson());
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    private String buildShopItemJson(ItemEx item) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", item.getId());
        json.field("name", item.getName());
        json.field("slot", item.getSlot());
        json.field("quantity", item.getQuantity());
        json.field("shopPrice", item.getShopPrice());
        json.endObject();
        return json.toString();
    }

    private void appendInterfaces(JsonBuilder json) {
        json.key("interfaces").startObject();
        json.field("dialogueOpen", DialogueAPI.dialoguePresent());
        json.field("makeXOpen", MakeXAPI.isOpen());
        json.field("bankOpen", BankAPI.isOpen());
        json.field("shopOpen", ShopAPI.isOpen());
        json.field("geOpen", GrandExchangeAPI.isOpen());
        json.endObject();
    }

    private void appendCurrentQuestState(JsonBuilder json) {
        json.key("currentQuest").rawJson(
                questHelperBridge.toJson(questHelperBridge.getCurrentQuestStateOrInactive())
        );
    }

    private void appendGameState(JsonBuilder json) {
        Client client = Static.getClient();
        GameState state = client == null ? null : client.getGameState();
        json.field("gameState", normalizeGameState(state));
    }

    private String normalizeGameState(GameState state) {
        if (state == null) {
            return "UNKNOWN";
        }
        if (!GameAPI.isLoggedIn()) {
            return "LOGGED_OUT";
        }
        return state.name();
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

    private String buildMakeXStatusJson() {
        boolean open = MakeXAPI.isOpen();
        int selectedAmount = MakeXAPI.getAmount();
        List<ItemEx> options = open ? MakeXAPI.getResultingItems() : new ArrayList<>();
        MakeXStateStore.SelectionSnapshot lastConfirmed = MakeXStateStore.getSelection();
        ItemEx selected = resolveSelectedMakeXProduct(options, lastConfirmed);

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("open", open);
        json.field("ready", open && !options.isEmpty());
        json.field("selectedAmount", selectedAmount);
        json.field("availableCount", options.size());

        if (selected != null) {
            json.key("selectedProduct").rawJson(makeXProductToJson(selected));
        } else {
            json.fieldNull("selectedProduct");
        }

        if (lastConfirmed != null) {
            json.key("lastConfirmed").startObject();
            if (lastConfirmed.getItemId() != null) {
                json.field("itemId", lastConfirmed.getItemId());
            } else {
                json.fieldNull("itemId");
            }
            if (lastConfirmed.getItemName() != null && !lastConfirmed.getItemName().trim().isEmpty()) {
                json.field("itemName", lastConfirmed.getItemName());
            } else {
                json.fieldNull("itemName");
            }
            json.field("amount", lastConfirmed.getAmount());
            json.field("confirmedTick", lastConfirmed.getConfirmedTick());
            json.field("confirmedAtMs", lastConfirmed.getConfirmedAtMs());
            json.endObject();
        } else {
            json.fieldNull("lastConfirmed");
        }

        json.key("products").startArray();
        for (ItemEx option : options) {
            if (option == null || option.getId() <= 0) {
                continue;
            }
            json.rawJson(makeXProductToJson(option));
        }
        json.endArray();

        json.endObject();
        return json.toString();
    }

    private ItemEx resolveSelectedMakeXProduct(List<ItemEx> options, MakeXStateStore.SelectionSnapshot lastConfirmed) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (lastConfirmed != null) {
            Integer itemId = lastConfirmed.getItemId();
            String itemName = lastConfirmed.getItemName();
            for (ItemEx option : options) {
                if (option == null || option.getId() <= 0) {
                    continue;
                }
                if (itemId != null && option.getId() == itemId) {
                    return option;
                }
                if (itemName != null && !itemName.trim().isEmpty() && itemName.equalsIgnoreCase(option.getName())) {
                    return option;
                }
            }
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        return null;
    }

    private String makeXProductToJson(ItemEx product) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("id", product.getId());
        json.field("name", product.getName());
        json.field("slot", product.getSlot());
        json.field("availableQuantity", product.getQuantity());
        json.endObject();
        return json.toString();
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
        return collectNearbyInteractiveObjects(null, null);
    }

    private List<TileObjectEx> collectNearbyInteractiveObjects(String nameFilter, Integer idFilter) {
        var query = TileObjectAPI.search()
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
                });

        if (nameFilter != null && !nameFilter.trim().isEmpty()) {
            String needle = nameFilter.trim().toLowerCase();
            query = query.keepIf(o -> o.getName() != null && o.getName().toLowerCase().contains(needle));
        }
        if (idFilter != null) {
            query = query.keepIf(o -> o.getId() == idFilter);
        }

        return query.collect();
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

    private String buildWidgetJson(int groupId, int childId, Integer childChildId) {
        Widget widget = childChildId == null
                ? WidgetAPI.get(groupId, childId)
                : WidgetAPI.get(groupId, childId, childChildId);
        if (widget == null) {
            return JsonBuilder.error(404, "Widget not found");
        }

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        appendWidgetJson(json, widget, groupId, childId, childChildId);
        json.endObject();
        return json.toString();
    }

    private String buildCurrentWorldJson() {
        World world = WorldsAPI.getCurrentWorld();
        if (world == null) {
            return JsonBuilder.error(404, "Current world unavailable");
        }

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        appendWorldJson(json, world);
        json.endObject();
        return json.toString();
    }

    private String buildWidgetSearchJson(
            Integer groupId,
            Integer parentId,
            String name,
            String text,
            String textContains,
            Integer itemId,
            String action,
            String actionContains,
            boolean visibleOnly
    ) {
        WidgetQuery query = WidgetAPI.search();
        if (visibleOnly) {
            query = query.isVisible();
        }
        if (groupId != null) {
            final int expectedGroupId = groupId;
            query = query.keepIf(widget -> (widget.getId() >> 16) == expectedGroupId);
        }
        if (parentId != null) {
            query = query.withParentId(parentId);
        }
        if (name != null) {
            query = query.withName(name);
        }
        if (text != null) {
            query = query.withText(text);
        }
        if (textContains != null) {
            query = query.withTextContains(textContains);
        }
        if (itemId != null) {
            query = query.withItemId(itemId);
        }
        if (action != null) {
            query = query.withActions(action);
        }
        if (actionContains != null) {
            final String needle = actionContains.toLowerCase();
            query = query.keepIf(widget -> widgetHasActionContaining(widget, needle));
        }

        List<Widget> widgets = query.collect();
        widgets.sort(Comparator
                .comparingInt((Widget widget) -> widget.getId() >> 16)
                .thenComparingInt(widget -> widget.getId() & 0xFFFF)
                .thenComparingInt(Widget::getIndex));

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("count", widgets.size());
        json.key("widgets").startArray();
        for (Widget widget : widgets) {
            json.startObject();
            appendWidgetJson(json, widget, null, null, null);
            json.endObject();
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    private void appendWidgetJson(JsonBuilder json, Widget widget, Integer groupIdOverride, Integer childIdOverride, Integer childChildIdOverride) {
        int widgetId = widget.getId();
        int groupId = groupIdOverride != null ? groupIdOverride : (widgetId >> 16);
        int childId = childIdOverride != null ? childIdOverride : (widgetId & 0xFFFF);
        Integer childChildId = childChildIdOverride != null
                ? childChildIdOverride
                : resolveChildChildId(widget, groupId, childId);

        json.field("widgetId", widgetId);
        json.field("groupId", groupId);
        json.field("childId", childId);
        if (childChildId == null) {
            json.fieldNull("childChildId");
        } else {
            json.field("childChildId", childChildId);
        }
        json.field("index", widget.getIndex());
        json.field("parentId", widget.getParentId());
        json.field("type", widget.getType());
        json.field("visible", WidgetAPI.isVisible(widget));
        json.field("hidden", widget.isHidden());
        if (widget.getText() == null || widget.getText().trim().isEmpty()) {
            json.fieldNull("text");
        } else {
            json.field("text", widget.getText());
        }
        if (widget.getName() == null || widget.getName().trim().isEmpty()) {
            json.fieldNull("name");
        } else {
            json.field("name", widget.getName());
        }
        json.field("itemId", widget.getItemId());
        if (widget.getBounds() == null) {
            json.fieldNull("bounds");
        } else {
            json.key("bounds").startObject();
            json.field("x", widget.getBounds().x);
            json.field("y", widget.getBounds().y);
            json.field("width", widget.getBounds().width);
            json.field("height", widget.getBounds().height);
            json.endObject();
        }
        json.key("actions").startArray();
        String[] actions = widget.getActions();
        if (actions != null) {
            for (String actionValue : actions) {
                if (actionValue != null && !actionValue.trim().isEmpty()) {
                    json.value(actionValue.trim());
                }
            }
        }
        json.endArray();
    }

    private Integer resolveChildChildId(Widget widget, int groupId, int childId) {
        Widget parent = WidgetAPI.get(groupId, childId);
        if (parent == null || parent == widget || parent.getChildren() == null) {
            return null;
        }
        for (Widget child : parent.getChildren()) {
            if (child == widget) {
                return child.getIndex();
            }
        }
        return null;
    }

    private boolean widgetHasActionContaining(Widget widget, String needle) {
        String[] actions = widget.getActions();
        if (actions == null || needle == null || needle.isEmpty()) {
            return false;
        }
        for (String actionValue : actions) {
            if (actionValue != null && actionValue.toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void appendWorldJson(JsonBuilder json, World world) {
        json.field("id", world.getId());
        json.field("members", world.getTypes() != null && world.getTypes().contains(WorldType.MEMBERS));
        json.field("playerCount", world.getPlayers());
        json.field("location", world.getLocation());
        if (world.getRegion() == null) {
            json.fieldNull("region");
        } else {
            json.field("region", world.getRegion().name());
        }
        if (world.getActivity() == null || world.getActivity().trim().isEmpty()) {
            json.fieldNull("activity");
        } else {
            json.field("activity", world.getActivity());
        }
        json.key("types").startArray();
        if (world.getTypes() != null) {
            for (WorldType type : world.getTypes()) {
                if (type != null) {
                    json.value(type.name());
                }
            }
        }
        json.endArray();
    }
}
