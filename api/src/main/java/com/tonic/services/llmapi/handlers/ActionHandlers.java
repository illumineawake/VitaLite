package com.tonic.services.llmapi.handlers;

import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.PlayerAPI;
import com.tonic.api.entities.TileItemAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.CombatAPI;
import com.tonic.api.game.SceneAPI;
import com.tonic.api.widgets.BankAPI;
import com.tonic.api.widgets.DialogueAPI;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.MakeXAPI;
import com.tonic.data.AttackStyle;
import com.tonic.data.EquipmentSlot;
import com.tonic.data.locatables.BankLocations;
import com.tonic.data.wrappers.*;
import com.tonic.queries.*;
import com.tonic.services.llmapi.util.JsonBuilder;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/**
 * Handlers for POST endpoints - performing interactions
 */
public class ActionHandlers {

    // ==================== PLAYER INTERACTIONS ====================

    public String interactPlayer(int index, String body) {
        try {
            PlayerEx player = new PlayerQuery()
                    .keepIf(p -> p.getIndex() == index)
                    .first();

            if (player == null) {
                return JsonBuilder.error(404, "Player not found with index: " + index);
            }

            // Parse action from body
            ActionRequest request = parseActionRequest(body);
            if (request == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int or string}");
            }

            if (request.actionIndex >= 0) {
                PlayerAPI.interact(player, request.actionIndex);
            } else if (request.actionName != null) {
                PlayerAPI.interact(player, request.actionName);
            } else {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            return JsonBuilder.success("Interacted with player: " + player.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with player: " + e.getMessage());
        }
    }

    public String useItemOnPlayer(int index, String body) {
        try {
            PlayerEx player = new PlayerQuery()
                    .keepIf(p -> p.getIndex() == index)
                    .first();

            if (player == null) {
                return JsonBuilder.error(404, "Player not found with index: " + index);
            }

            // Parse itemId from body
            int itemId = parseItemId(body);
            if (itemId < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int}");
            }

            ItemEx item = InventoryAPI.getItem(itemId);
            if (item == null) {
                return JsonBuilder.error(404, "Item not found in inventory with id: " + itemId);
            }

            InventoryAPI.useOn(item, player);
            return JsonBuilder.success("Used item on player: " + player.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to use item on player: " + e.getMessage());
        }
    }

    // ==================== NPC INTERACTIONS ====================

    public String interactNpc(int index, String body) {
        try {
            NpcEx npc = new NpcQuery()
                    .keepIf(n -> n.getIndex() == index)
                    .first();

            if (npc == null) {
                return JsonBuilder.error(404, "NPC not found with index: " + index);
            }

            ActionRequest request = parseActionRequest(body);
            if (request == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int or string}");
            }

            if (request.actionIndex >= 0) {
                NpcAPI.interact(npc, request.actionIndex);
            } else if (request.actionName != null) {
                NpcAPI.interact(npc, request.actionName);
            } else {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            return JsonBuilder.success("Interacted with NPC: " + npc.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with NPC: " + e.getMessage());
        }
    }

    public String useItemOnNpc(int index, String body) {
        try {
            NpcEx npc = new NpcQuery()
                    .keepIf(n -> n.getIndex() == index)
                    .first();

            if (npc == null) {
                return JsonBuilder.error(404, "NPC not found with index: " + index);
            }

            int itemId = parseItemId(body);
            if (itemId < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int}");
            }

            ItemEx item = InventoryAPI.getItem(itemId);
            if (item == null) {
                return JsonBuilder.error(404, "Item not found in inventory with id: " + itemId);
            }

            InventoryAPI.useOn(item, npc);
            return JsonBuilder.success("Used item on NPC: " + npc.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to use item on NPC: " + e.getMessage());
        }
    }

    // ==================== OBJECT INTERACTIONS ====================

    public String interactObject(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);

            TileObjectQuery query = new TileObjectQuery().withId(id);

            // If coordinates provided, filter by location
            if (request != null && request.x >= 0 && request.y >= 0) {
                final int fx = request.x, fy = request.y, fp = request.plane >= 0 ? request.plane : 0;
                query = query.keepIf(obj -> {
                    WorldPoint pos = obj.getWorldPoint();
                    return pos.getX() == fx && pos.getY() == fy && pos.getPlane() == fp;
                });
            }

            TileObjectEx obj = query.first();

            if (obj == null) {
                return JsonBuilder.error(404, "Object not found with id: " + id);
            }

            if (request == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int or string}");
            }

            if (request.actionIndex >= 0) {
                TileObjectAPI.interact(obj, request.actionIndex);
            } else if (request.actionName != null) {
                TileObjectAPI.interact(obj, request.actionName);
            } else {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            return JsonBuilder.success("Interacted with object: " + obj.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with object: " + e.getMessage());
        }
    }

    public String useItemOnObject(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);
            if (request == null || request.itemId < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int, \"x\": int, \"y\": int, \"plane\": int}");
            }

            TileObjectQuery query = new TileObjectQuery().withId(id);

            if (request.x >= 0 && request.y >= 0) {
                final int fx = request.x, fy = request.y, fp = request.plane >= 0 ? request.plane : 0;
                query = query.keepIf(obj -> {
                    WorldPoint pos = obj.getWorldPoint();
                    return pos.getX() == fx && pos.getY() == fy && pos.getPlane() == fp;
                });
            }

            TileObjectEx obj = query.first();

            if (obj == null) {
                return JsonBuilder.error(404, "Object not found with id: " + id);
            }

            ItemEx item = InventoryAPI.getItem(request.itemId);
            if (item == null) {
                return JsonBuilder.error(404, "Item not found in inventory with id: " + request.itemId);
            }

            InventoryAPI.useOn(item, obj);
            return JsonBuilder.success("Used item on object: " + obj.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to use item on object: " + e.getMessage());
        }
    }

    // ==================== GROUND ITEM INTERACTIONS ====================

    public String interactGroundItem(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);

            TileItemQuery query = new TileItemQuery().withId(id);

            if (request != null && request.x >= 0 && request.y >= 0) {
                final int fx = request.x, fy = request.y, fp = request.plane >= 0 ? request.plane : 0;
                query = query.keepIf(item -> {
                    WorldPoint pos = item.getWorldPoint();
                    return pos.getX() == fx && pos.getY() == fy && pos.getPlane() == fp;
                });
            }

            TileItemEx item = query.first();

            if (item == null) {
                return JsonBuilder.error(404, "Ground item not found with id: " + id);
            }

            TileItemAPI.interact(item, 2);

            return JsonBuilder.success("Interacted with ground item: " + item.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with ground item: " + e.getMessage());
        }
    }

    public String useItemOnGroundItem(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);
            if (request == null || request.itemId < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int, \"x\": int, \"y\": int, \"plane\": int}");
            }

            TileItemQuery query = new TileItemQuery().withId(id);

            if (request.x >= 0 && request.y >= 0) {
                final int fx = request.x, fy = request.y, fp = request.plane >= 0 ? request.plane : 0;
                query = query.keepIf(item -> {
                    WorldPoint pos = item.getWorldPoint();
                    return pos.getX() == fx && pos.getY() == fy && pos.getPlane() == fp;
                });
            }

            TileItemEx groundItem = query.first();

            if (groundItem == null) {
                return JsonBuilder.error(404, "Ground item not found with id: " + id);
            }

            ItemEx item = InventoryAPI.getItem(request.itemId);
            if (item == null) {
                return JsonBuilder.error(404, "Item not found in inventory with id: " + request.itemId);
            }

            InventoryAPI.useOn(item, groundItem);
            return JsonBuilder.success("Used item on ground item: " + groundItem.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to use item on ground item: " + e.getMessage());
        }
    }

    // ==================== INVENTORY INTERACTIONS ====================

    public String interactInventory(int slot, String body) {
        try {
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

            ActionRequest request = parseActionRequest(body);
            if (request == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int or string}");
            }

            if (request.actionIndex >= 0) {
                InventoryAPI.interact(item, request.actionIndex);
            } else if (request.actionName != null) {
                InventoryAPI.interact(item, request.actionName);
            } else {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            return JsonBuilder.success("Interacted with inventory item: " + item.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with inventory item: " + e.getMessage());
        }
    }

    public String useOnInventoryItem(int slot, String body) {
        try {
            if (slot < 0 || slot > 27) {
                return JsonBuilder.error(400, "Invalid slot. Must be 0-27");
            }

            int targetSlot = parseTargetSlot(body);
            if (targetSlot < 0 || targetSlot > 27) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"targetSlot\": int (0-27)}");
            }

            List<ItemEx> items = InventoryAPI.getItems();
            ItemEx sourceItem = null;
            ItemEx targetItem = null;

            for (ItemEx i : items) {
                if (i.getSlot() == slot && i.getId() > 0) {
                    sourceItem = i;
                }
                if (i.getSlot() == targetSlot && i.getId() > 0) {
                    targetItem = i;
                }
            }

            if (sourceItem == null) {
                return JsonBuilder.error(404, "No item in source slot: " + slot);
            }

            if (targetItem == null) {
                return JsonBuilder.error(404, "No item in target slot: " + targetSlot);
            }

            InventoryAPI.useOn(sourceItem, targetItem);
            return JsonBuilder.success("Used " + sourceItem.getName() + " on " + targetItem.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to use item on item: " + e.getMessage());
        }
    }

    public String dropInventoryItem(int slot) {
        try {
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

            InventoryAPI.interact(item, "drop");
            return JsonBuilder.success("Dropped item: " + item.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to drop item: " + e.getMessage());
        }
    }

    // ==================== EQUIPMENT INTERACTIONS ====================

    public String interactEquipment(int slotIdx, String body) {
        try {
            EquipmentSlot slot = EquipmentSlot.findBySlot(slotIdx);
            if (slot == null) {
                return JsonBuilder.error(400, "Invalid equipment slot: " + slotIdx);
            }

            ItemEx item = EquipmentAPI.fromSlot(slot);
            if (item == null || item.getId() <= 0) {
                return JsonBuilder.error(404, "No item in equipment slot: " + slotIdx);
            }

            ActionRequest request = parseActionRequest(body);
            if (request == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int or string}");
            }

            if (request.actionIndex >= 0) {
                EquipmentAPI.interact(item, request.actionIndex);
            } else if (request.actionName != null) {
                EquipmentAPI.interact(item, request.actionName);
            } else {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            return JsonBuilder.success("Interacted with equipped item: " + item.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with equipment: " + e.getMessage());
        }
    }

    public String unequipItem(int slotIdx) {
        try {
            EquipmentSlot slot = EquipmentSlot.findBySlot(slotIdx);
            if (slot == null) {
                return JsonBuilder.error(400, "Invalid equipment slot: " + slotIdx);
            }

            ItemEx item = EquipmentAPI.fromSlot(slot);
            if (item == null || item.getId() <= 0) {
                return JsonBuilder.error(404, "No item in equipment slot: " + slotIdx);
            }

            EquipmentAPI.unEquip(slot);
            return JsonBuilder.success("Unequipped item: " + item.getName());
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to unequip item: " + e.getMessage());
        }
    }

    // ==================== DIALOGUE INTERACTIONS ====================

    public String continueDialogue() {
        try {
            if (!DialogueAPI.dialoguePresent()) {
                return JsonBuilder.error(404, "No dialogue present");
            }

            boolean success = DialogueAPI.continueDialogue();

            if (success) {
                return JsonBuilder.success("Continued dialogue");
            } else {
                return JsonBuilder.error(400, "Unable to continue dialogue (may require option selection)");
            }
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to continue dialogue: " + e.getMessage());
        }
    }

    public String selectDialogueOption(String body) {
        try {
            if (!DialogueAPI.dialoguePresent()) {
                return JsonBuilder.error(404, "No dialogue present");
            }

            // Parse option from body - can be index or text
            String optionValue = extractJsonValue(body, "option");
            if (optionValue == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"option\": int or string}");
            }

            // Try to parse as integer (index)
            try {
                int optionIndex = Integer.parseInt(optionValue);
                DialogueAPI.selectOption(optionIndex);
                return JsonBuilder.success("Selected dialogue option by index: " + optionIndex);
            } catch (NumberFormatException e) {
                // It's a string - search by text
                boolean success = DialogueAPI.selectOption(optionValue);
                if (success) {
                    return JsonBuilder.success("Selected dialogue option: " + optionValue);
                } else {
                    return JsonBuilder.error(404, "Option not found: " + optionValue);
                }
            }
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to select dialogue option: " + e.getMessage());
        }
    }

    public String enterDialogueNumber(String body) {
        try {
            String valueStr = extractJsonValue(body, "value");
            if (valueStr == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"value\": int}");
            }

            int value;
            try {
                value = Integer.parseInt(valueStr);
            } catch (NumberFormatException e) {
                return JsonBuilder.error(400, "Invalid value. Must be an integer.");
            }

            DialogueAPI.resumeNumericDialogue(value);
            return JsonBuilder.success("Entered numeric value: " + value);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to enter numeric value: " + e.getMessage());
        }
    }

    // ==================== MAKEX ====================

    public String makeXConfirm(String body) {
        try {
            Integer amount = parseAmount(body);
            if (amount == null || amount <= 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"amount\": int > 0, \"itemId\"?: int, \"itemName\"?: string}");
            }
            if (!MakeXAPI.isOpen()) {
                return JsonBuilder.error(400, "MakeX interface is not open");
            }

            MakeXAPI.setAmount(amount);

            Integer itemId = parseOptionalItemId(body);
            String itemName = parseOptionalItemName(body);
            boolean confirmed;
            if (itemId != null) {
                confirmed = MakeXAPI.confirm(itemId);
            } else if (itemName != null && !itemName.trim().isEmpty()) {
                confirmed = MakeXAPI.confirm(itemName);
            } else {
                return JsonBuilder.error(400, "Invalid request body. Provide either itemId or itemName");
            }

            if (!confirmed) {
                return JsonBuilder.error(404, "Requested MakeX option not found");
            }

            return JsonBuilder.success("MakeX confirmed");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to confirm MakeX: " + e.getMessage());
        }
    }

    // ==================== BANK ====================

    public String openBank() {
        try {
            if (BankAPI.isOpen()) {
                return JsonBuilder.success("Bank already open");
            }

            final long timeoutMs = 12000L;
            final long deadline = System.currentTimeMillis() + timeoutMs;
            long nextPathAt = 0L;

            while (System.currentTimeMillis() < deadline) {
                if (BankAPI.isOpen()) {
                    return JsonBuilder.success("Opened bank");
                }

                if (!tryOpenNearbyBank()) {
                    long now = System.currentTimeMillis();
                    if (now >= nextPathAt) {
                        BankLocations.walkToNearest();
                        nextPathAt = now + 1800L;
                    }
                }

                Thread.sleep(200L);
            }

            return JsonBuilder.error(408, "Timed out opening bank");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonBuilder.error(500, "Bank open interrupted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to open bank: " + e.getMessage());
        }
    }

    public String closeBank() {
        try {
            if (!BankAPI.isOpen()) {
                return JsonBuilder.success("Bank already closed");
            }

            BankAPI.close();
            for (int i = 0; i < 8; i++) {
                if (!BankAPI.isOpen()) {
                    return JsonBuilder.success("Closed bank");
                }
                Thread.sleep(120L);
            }
            return JsonBuilder.error(500, "Bank close not confirmed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonBuilder.error(500, "Bank close interrupted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to close bank: " + e.getMessage());
        }
    }

    public String depositInventory() {
        try {
            if (!BankAPI.isOpen()) {
                return JsonBuilder.error(400, "Bank is not open");
            }
            BankAPI.depositAll();
            return JsonBuilder.success("Deposit inventory submitted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to deposit inventory: " + e.getMessage());
        }
    }

    public String depositEquipment() {
        try {
            if (!BankAPI.isOpen()) {
                return JsonBuilder.error(400, "Bank is not open");
            }
            BankAPI.depositEquipment();
            return JsonBuilder.success("Deposit equipment submitted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to deposit equipment: " + e.getMessage());
        }
    }

    public String withdrawBankItem(String body) {
        try {
            if (!BankAPI.isOpen()) {
                return JsonBuilder.error(400, "Bank is not open");
            }

            Integer amount = parseAmount(body);
            if (amount == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected amount");
            }
            boolean noted = parseNoted(body, false);

            Integer itemId = parseOptionalItemId(body);
            String itemName = parseOptionalItemName(body);
            if (itemId == null && (itemName == null || itemName.trim().isEmpty())) {
                return JsonBuilder.error(400, "Invalid request body. Expected itemId or itemName");
            }

            if (itemId != null) {
                if (!BankAPI.contains(itemId)) {
                    return JsonBuilder.error(404, "Item not found in bank: " + itemId);
                }
                BankAPI.withdraw(itemId, amount, noted);
                return JsonBuilder.success("Withdraw submitted for itemId " + itemId);
            }

            if (!BankAPI.contains(itemName)) {
                return JsonBuilder.error(404, "Item not found in bank: " + itemName);
            }
            BankAPI.withdraw(itemName, amount, noted);
            return JsonBuilder.success("Withdraw submitted for itemName " + itemName);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to withdraw bank item: " + e.getMessage());
        }
    }

    private boolean tryOpenNearbyBank() {
        NpcEx banker = new NpcQuery()
                .withNameContains("Banker")
                .keepIf(n -> SceneAPI.losTileNextTo(n.getWorldPoint()) != null)
                .nearest();
        if (banker != null) {
            NpcAPI.interact(banker, 2);
            return true;
        }

        TileObjectEx bank = new TileObjectQuery()
                .withNamesContains("Bank booth", "Bank chest")
                .sortNearest()
                .first();
        if (bank != null && SceneAPI.losTileNextTo(bank.getWorldPoint()) != null) {
            if (bank.getName() != null && bank.getName().contains("Bank booth")) {
                TileObjectAPI.interact(bank, 1);
            } else {
                TileObjectAPI.interact(bank, 0);
            }
            return true;
        }

        return false;
    }

    // ==================== COMBAT ====================

    public String setCombatStyle(String body) {
        try {
            Integer styleIndex = parseStyleIndex(body);
            if (styleIndex == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"styleIndex\": int}");
            }

            AttackStyle targetStyle = AttackStyle.fromIndex(styleIndex);
            if (targetStyle == AttackStyle.UNKNOWN) {
                return JsonBuilder.error(400, "Invalid styleIndex: " + styleIndex);
            }

            CombatAPI.setAttackStyle(targetStyle);

            for (int i = 0; i < 8; i++) {
                AttackStyle current = CombatAPI.getAttackStyle();
                if (current != AttackStyle.UNKNOWN && current.getIndex() == styleIndex) {
                    return JsonBuilder.success("Set combat style to index " + styleIndex + " (" + current.name() + ")");
                }
                Thread.sleep(120L);
            }

            AttackStyle current = CombatAPI.getAttackStyle();
            return JsonBuilder.error(500, "Combat style not applied. Current style: " + current.name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonBuilder.error(500, "Combat style update interrupted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to set combat style: " + e.getMessage());
        }
    }

    // ==================== HELPER CLASSES AND METHODS ====================

    private static class ActionRequest {
        int actionIndex = -1;
        String actionName = null;
    }

    private static class ObjectRequest {
        int actionIndex = -1;
        String actionName = null;
        int x = -1;
        int y = -1;
        int plane = -1;
        int itemId = -1;
    }

    /**
     * Parse action from JSON body
     * Expected format: {"action": int or string}
     */
    private ActionRequest parseActionRequest(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        ActionRequest request = new ActionRequest();

        try {
            // Simple JSON parsing without external dependencies
            String actionValue = extractJsonValue(body, "action");
            if (actionValue != null) {
                // Try to parse as integer first
                try {
                    request.actionIndex = Integer.parseInt(actionValue);
                } catch (NumberFormatException e) {
                    // It's a string action
                    request.actionName = actionValue;
                }
            }
        } catch (Exception e) {
            return null;
        }

        return request;
    }

    /**
     * Parse object request with coordinates from JSON body
     * Expected format: {"action": int/string, "x": int, "y": int, "plane": int, "itemId": int}
     */
    private ObjectRequest parseObjectRequest(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        ObjectRequest request = new ObjectRequest();

        try {
            String actionValue = extractJsonValue(body, "action");
            if (actionValue != null) {
                try {
                    request.actionIndex = Integer.parseInt(actionValue);
                } catch (NumberFormatException e) {
                    request.actionName = actionValue;
                }
            }

            String xValue = extractJsonValue(body, "x");
            if (xValue != null) {
                request.x = Integer.parseInt(xValue);
            }

            String yValue = extractJsonValue(body, "y");
            if (yValue != null) {
                request.y = Integer.parseInt(yValue);
            }

            String planeValue = extractJsonValue(body, "plane");
            if (planeValue != null) {
                request.plane = Integer.parseInt(planeValue);
            }

            String itemIdValue = extractJsonValue(body, "itemId");
            if (itemIdValue != null) {
                request.itemId = Integer.parseInt(itemIdValue);
            }
        } catch (Exception e) {
            return null;
        }

        return request;
    }

    /**
     * Parse itemId from JSON body
     * Expected format: {"itemId": int}
     */
    private int parseItemId(String body) {
        if (body == null || body.isEmpty()) {
            return -1;
        }

        try {
            String value = extractJsonValue(body, "itemId");
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            return -1;
        }

        return -1;
    }

    /**
     * Parse targetSlot from JSON body
     * Expected format: {"targetSlot": int}
     */
    private int parseTargetSlot(String body) {
        if (body == null || body.isEmpty()) {
            return -1;
        }

        try {
            String value = extractJsonValue(body, "targetSlot");
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            return -1;
        }

        return -1;
    }

    private Integer parseStyleIndex(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            String value = extractJsonValue(body, "styleIndex");
            if (value == null) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseAmount(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            String value = extractJsonValue(body, "amount");
            if (value == null) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseOptionalItemId(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            String value = extractJsonValue(body, "itemId");
            if (value == null || value.trim().isEmpty()) {
                return null;
            }
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String parseOptionalItemName(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String value = extractJsonValue(body, "itemName");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value;
    }

    private boolean parseNoted(String body, boolean defaultValue) {
        if (body == null || body.isEmpty()) {
            return defaultValue;
        }
        String value = extractJsonValue(body, "noted");
        if (value == null) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(value);
    }

    /**
     * Simple JSON value extractor
     * Handles both string and numeric values
     */
    private String extractJsonValue(String json, String key) {
        // Look for "key": or "key" :
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex);
        if (colonIndex < 0) {
            return null;
        }

        // Find the value start
        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) {
            return null;
        }

        // Check if it's a string value
        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                return null;
            }
            return json.substring(valueStart + 1, valueEnd);
        }

        // It's a numeric or boolean value
        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) {
                break;
            }
            valueEnd++;
        }

        return json.substring(valueStart, valueEnd).trim();
    }
}
