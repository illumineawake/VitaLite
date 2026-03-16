package com.tonic.services.llmapi.handlers;

import com.tonic.Static;
import com.tonic.api.entities.NpcAPI;
import com.tonic.api.entities.PlayerAPI;
import com.tonic.api.entities.TileItemAPI;
import com.tonic.api.entities.TileObjectAPI;
import com.tonic.api.game.ClientScriptAPI;
import com.tonic.api.game.CombatAPI;
import com.tonic.api.game.SceneAPI;
import com.tonic.api.game.WorldsAPI;
import com.tonic.api.widgets.BankAPI;
import com.tonic.api.widgets.DialogueAPI;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.GrandExchangeAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.api.widgets.MakeXAPI;
import com.tonic.api.widgets.ShopAPI;
import com.tonic.api.widgets.WidgetAPI;
import com.tonic.api.threaded.Delays;
import com.tonic.data.AttackStyle;
import com.tonic.data.EquipmentSlot;
import com.tonic.data.GrandExchangeSlot;
import com.tonic.data.locatables.BankLocations;
import com.tonic.data.wrappers.*;
import com.tonic.queries.*;
import com.tonic.services.llmapi.actions.ActionTracker;
import com.tonic.services.llmapi.state.MakeXStateStore;
import com.tonic.services.llmapi.util.JsonBuilder;
import com.tonic.services.llmapi.util.SmartInteractionSupport;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldType;
import net.runelite.http.api.worlds.WorldRegion;

import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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

    public String smartInteractNpc(int index, String body) {
        try {
            NpcEx npc = SmartInteractionSupport.resolveNpc(index);
            if (npc == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "NPC not found with index: " + index);
            }

            ActionRequest request = parseActionRequest(body);
            if (request == null || (request.actionIndex < 0 && request.actionName == null)) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int or string}");
            }

            if (!SmartInteractionSupport.hasAction(npc.getActions(), request.actionIndex, request.actionName)) {
                return reasonedError(400, ActionTracker.ReasonCode.MENU_ACTION_UNAVAILABLE, "Menu action unavailable for NPC: " + npc.getName());
            }

            WorldPoint approachTile = null;
            boolean walkRequired = !SmartInteractionSupport.canInteract(npc);
            if (walkRequired) {
                approachTile = SmartInteractionSupport.getApproachTile(npc);
                if (approachTile == null) {
                    return reasonedError(400, ActionTracker.ReasonCode.PATH_BLOCKED, "No valid approach tile found for NPC: " + npc.getName());
                }
                SmartInteractionSupport.startWalker(approachTile);
            }

            return smartActionAccepted("Smart NPC interaction prepared", walkRequired ? "APPROACHING" : "READY_TO_INTERACT", walkRequired, approachTile);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to prepare smart NPC interaction: " + e.getMessage());
        }
    }

    public String smartUseItemOnNpc(int index, String body) {
        try {
            NpcEx npc = SmartInteractionSupport.resolveNpc(index);
            if (npc == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "NPC not found with index: " + index);
            }

            int itemId = parseItemId(body);
            if (itemId < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int}");
            }

            if (SmartInteractionSupport.requireInventoryItem(itemId) == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Item not found in inventory with id: " + itemId);
            }

            WorldPoint approachTile = null;
            boolean walkRequired = !SmartInteractionSupport.canInteract(npc);
            if (walkRequired) {
                approachTile = SmartInteractionSupport.getApproachTile(npc);
                if (approachTile == null) {
                    return reasonedError(400, ActionTracker.ReasonCode.PATH_BLOCKED, "No valid approach tile found for NPC: " + npc.getName());
                }
                SmartInteractionSupport.startWalker(approachTile);
            }

            return smartActionAccepted("Smart use-item on NPC prepared", walkRequired ? "APPROACHING" : "READY_TO_INTERACT", walkRequired, approachTile);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to prepare smart item-on-NPC interaction: " + e.getMessage());
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

    public String smartInteractObject(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);
            if (request == null || request.x < 0 || request.y < 0 || request.plane < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int|string, \"x\": int, \"y\": int, \"plane\": int}");
            }
            if (request.actionIndex < 0 && request.actionName == null) {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            WorldPoint reference = new WorldPoint(request.x, request.y, request.plane);
            TileObjectEx object = SmartInteractionSupport.resolveObject(id, reference);
            if (object == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Object not found with id: " + id);
            }
            if (!SmartInteractionSupport.hasAction(object.getActions(), request.actionIndex, request.actionName)) {
                return reasonedError(400, ActionTracker.ReasonCode.MENU_ACTION_UNAVAILABLE, "Menu action unavailable for object: " + object.getName());
            }

            WorldPoint approachTile = null;
            boolean walkRequired = !SmartInteractionSupport.canInteract(object);
            if (walkRequired) {
                approachTile = SmartInteractionSupport.getApproachTile(object);
                if (approachTile == null) {
                    return reasonedError(400, ActionTracker.ReasonCode.PATH_BLOCKED, "No valid approach tile found for object: " + object.getName());
                }
                SmartInteractionSupport.startWalker(approachTile);
            }

            return smartActionAccepted("Smart object interaction prepared", walkRequired ? "APPROACHING" : "READY_TO_INTERACT", walkRequired, approachTile);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to prepare smart object interaction: " + e.getMessage());
        }
    }

    public String smartUseItemOnObject(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);
            if (request == null || request.itemId < 0 || request.x < 0 || request.y < 0 || request.plane < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int, \"x\": int, \"y\": int, \"plane\": int}");
            }

            if (SmartInteractionSupport.requireInventoryItem(request.itemId) == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Item not found in inventory with id: " + request.itemId);
            }

            WorldPoint reference = new WorldPoint(request.x, request.y, request.plane);
            TileObjectEx object = SmartInteractionSupport.resolveObject(id, reference);
            if (object == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Object not found with id: " + id);
            }

            WorldPoint approachTile = null;
            boolean walkRequired = !SmartInteractionSupport.canInteract(object);
            if (walkRequired) {
                approachTile = SmartInteractionSupport.getApproachTile(object);
                if (approachTile == null) {
                    return reasonedError(400, ActionTracker.ReasonCode.PATH_BLOCKED, "No valid approach tile found for object: " + object.getName());
                }
                SmartInteractionSupport.startWalker(approachTile);
            }

            return smartActionAccepted("Smart item-on-object interaction prepared", walkRequired ? "APPROACHING" : "READY_TO_INTERACT", walkRequired, approachTile);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to prepare smart item-on-object interaction: " + e.getMessage());
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

    public String smartInteractGroundItem(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);
            if (request == null || request.x < 0 || request.y < 0 || request.plane < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"action\": int|string, \"x\": int, \"y\": int, \"plane\": int}");
            }
            if (request.actionIndex < 0 && request.actionName == null) {
                return JsonBuilder.error(400, "Missing action parameter");
            }

            WorldPoint reference = new WorldPoint(request.x, request.y, request.plane);
            TileItemEx groundItem = SmartInteractionSupport.resolveGroundItem(id, reference);
            if (groundItem == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Ground item not found with id: " + id);
            }

            WorldPoint approachTile = null;
            boolean walkRequired = !SmartInteractionSupport.canInteract(groundItem);
            if (walkRequired) {
                approachTile = SmartInteractionSupport.getApproachTile(groundItem);
                if (approachTile == null) {
                    return reasonedError(400, ActionTracker.ReasonCode.PATH_BLOCKED, "No valid approach tile found for ground item: " + groundItem.getName());
                }
                SmartInteractionSupport.startWalker(approachTile);
            }

            return smartActionAccepted("Smart ground item interaction prepared", walkRequired ? "APPROACHING" : "READY_TO_INTERACT", walkRequired, approachTile);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to prepare smart ground item interaction: " + e.getMessage());
        }
    }

    public String smartUseItemOnGroundItem(int id, String body) {
        try {
            ObjectRequest request = parseObjectRequest(body);
            if (request == null || request.itemId < 0 || request.x < 0 || request.y < 0 || request.plane < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int, \"x\": int, \"y\": int, \"plane\": int}");
            }

            if (SmartInteractionSupport.requireInventoryItem(request.itemId) == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Item not found in inventory with id: " + request.itemId);
            }

            WorldPoint reference = new WorldPoint(request.x, request.y, request.plane);
            TileItemEx groundItem = SmartInteractionSupport.resolveGroundItem(id, reference);
            if (groundItem == null) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Ground item not found with id: " + id);
            }

            WorldPoint approachTile = null;
            boolean walkRequired = !SmartInteractionSupport.canInteract(groundItem);
            if (walkRequired) {
                approachTile = SmartInteractionSupport.getApproachTile(groundItem);
                if (approachTile == null) {
                    return reasonedError(400, ActionTracker.ReasonCode.PATH_BLOCKED, "No valid approach tile found for ground item: " + groundItem.getName());
                }
                SmartInteractionSupport.startWalker(approachTile);
            }

            return smartActionAccepted("Smart item-on-ground-item interaction prepared", walkRequired ? "APPROACHING" : "READY_TO_INTERACT", walkRequired, approachTile);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to prepare smart item-on-ground-item interaction: " + e.getMessage());
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

            MakeXStateStore.update(itemId, itemName, amount);
            return JsonBuilder.success("MakeX confirmed");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to confirm MakeX: " + e.getMessage());
        }
    }

    public String clickWidget(String body) {
        try {
            WidgetRequest request = parseWidgetRequest(body);
            if (request == null || request.groupId < 0 || request.childId < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"groupId\": int, \"childId\": int, \"childChildId\"?: int}");
            }

            Widget widget = request.childChildId >= 0
                    ? WidgetAPI.get(request.groupId, request.childId, request.childChildId)
                    : WidgetAPI.get(request.groupId, request.childId);
            if (widget == null || !WidgetAPI.isVisible(widget)) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Widget not found or not visible");
            }

            if (!dispatchWidgetClick(widget)) {
                return reasonedError(500, ActionTracker.ReasonCode.INTERFACE_BLOCKED, "Failed to dispatch widget click");
            }

            return JsonBuilder.success("Widget click dispatched");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to click widget: " + e.getMessage());
        }
    }

    public String hopWorld(String body) {
        try {
            Integer worldId = parseWorldId(body);
            if (worldId == null || worldId <= 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"worldId\": int}");
            }

            World world = new WorldQuery().withId(worldId).first();
            if (world == null) {
                return JsonBuilder.error(404, "World not found with id: " + worldId);
            }

            WorldsAPI.hop(world).execute();
            return worldHopAccepted("World hop submitted", world);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to hop world: " + e.getMessage());
        }
    }

    public String hopRandomSameCountryWorld() {
        try {
            World current = WorldsAPI.getCurrentWorld();
            if (current == null) {
                return JsonBuilder.error(404, "Current world unavailable");
            }

            WorldQuery query = WorldsAPI.createDefaultQuery(false);
            WorldRegion region = current.getRegion();
            if (region != null) {
                query = query.withRegion(region);
            } else {
                final int location = current.getLocation();
                query = query.keepIf(world -> world.getLocation() == location);
            }

            if (current.getTypes() != null && current.getTypes().contains(WorldType.MEMBERS)) {
                query = query.isP2p();
            } else {
                query = query.isF2p();
            }

            World target = query.random();
            if (target == null) {
                return JsonBuilder.error(404, "No matching safe world found for current country and membership");
            }

            WorldsAPI.hop(target).execute();
            return worldHopAccepted("Random same-country world hop submitted", target);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to hop random same-country world: " + e.getMessage());
        }
    }

    public String interactWidget(String body) {
        try {
            WidgetRequest request = parseWidgetRequest(body);
            ActionRequest action = parseActionRequest(body);
            if (request == null || request.groupId < 0 || request.childId < 0 || action == null || (action.actionIndex < 0 && action.actionName == null)) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"groupId\": int, \"childId\": int, \"childChildId\"?: int, \"action\": int|string}");
            }

            Widget widget = request.childChildId >= 0
                    ? WidgetAPI.get(request.groupId, request.childId, request.childChildId)
                    : WidgetAPI.get(request.groupId, request.childId);
            if (widget == null || !WidgetAPI.isVisible(widget)) {
                return reasonedError(404, ActionTracker.ReasonCode.INVALID_TARGET, "Widget not found or not visible");
            }

            if (action.actionIndex >= 0) {
                String[] actions = widget.getActions();
                if (actions == null || action.actionIndex < 1 || action.actionIndex > actions.length || actions[action.actionIndex - 1] == null || actions[action.actionIndex - 1].trim().isEmpty()) {
                    return reasonedError(400, ActionTracker.ReasonCode.MENU_ACTION_UNAVAILABLE, "Widget action index unavailable");
                }
                WidgetAPI.interact(widget, action.actionIndex);
            } else {
                if (!SmartInteractionSupport.hasAction(widget.getActions(), -1, action.actionName)) {
                    return reasonedError(400, ActionTracker.ReasonCode.MENU_ACTION_UNAVAILABLE, "Widget action unavailable");
                }
                WidgetAPI.interact(widget, action.actionName);
            }
            return JsonBuilder.success("Widget interaction submitted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to interact with widget: " + e.getMessage());
        }
    }

    // ==================== SHOP ====================

    public String buyShopItem(String body) {
        try {
            if (!ShopAPI.isOpen()) {
                return JsonBuilder.error(400, "Shop is not open");
            }

            Integer amount = parseAmount(body);
            if (amount == null || amount <= 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected amount > 0");
            }

            Integer itemId = parseOptionalItemId(body);
            String itemName = parseOptionalItemName(body);
            if (itemId == null && (itemName == null || itemName.trim().isEmpty())) {
                return JsonBuilder.error(400, "Invalid request body. Expected itemId or itemName");
            }

            ItemEx shopItem;
            if (itemId != null) {
                shopItem = ShopAPI.getShopItem(itemId);
            } else {
                shopItem = findShopItemByName(itemName);
            }

            if (shopItem == null) {
                return JsonBuilder.error(404, "Item not found in current shop");
            }

            ShopAPI.buyX(shopItem.getId(), amount);
            return JsonBuilder.success("Buy submitted for " + shopItem.getName() + " x" + amount);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to buy from shop: " + e.getMessage());
        }
    }

    public String sellShopItem(String body) {
        try {
            if (!ShopAPI.isOpen()) {
                return JsonBuilder.error(400, "Shop is not open");
            }

            Integer amount = parseAmount(body);
            if (amount == null || amount <= 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected amount > 0");
            }

            Integer itemId = parseOptionalItemId(body);
            String itemName = parseOptionalItemName(body);
            if (itemId == null && (itemName == null || itemName.trim().isEmpty())) {
                return JsonBuilder.error(400, "Invalid request body. Expected itemId or itemName");
            }

            ItemEx inventoryItem;
            if (itemId != null) {
                inventoryItem = InventoryAPI.getItem(itemId);
            } else {
                inventoryItem = findInventoryItemByName(itemName);
            }

            if (inventoryItem == null || inventoryItem.getId() <= 0) {
                return JsonBuilder.error(404, "Item not found in inventory");
            }

            ShopAPI.sellX(inventoryItem.getId(), amount);
            return JsonBuilder.success("Sell submitted for " + inventoryItem.getName() + " x" + amount);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to sell to shop: " + e.getMessage());
        }
    }

    public String closeShop() {
        try {
            if (!ShopAPI.isOpen()) {
                return JsonBuilder.success("Shop already closed");
            }

            ShopAPI.close();
            for (int i = 0; i < 8; i++) {
                if (!ShopAPI.isOpen()) {
                    return JsonBuilder.success("Closed shop");
                }
                Thread.sleep(120L);
            }
            return JsonBuilder.error(500, "Shop close not confirmed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonBuilder.error(500, "Shop close interrupted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to close shop: " + e.getMessage());
        }
    }

    // ==================== GRAND EXCHANGE ====================

    public String getGrandExchangeOpen() {
        try {
            if (GrandExchangeAPI.isOpen()) {
                return JsonBuilder.success("Grand Exchange already open");
            }

            final long deadline = System.currentTimeMillis() + 10000L;
            while (System.currentTimeMillis() < deadline) {
                if (GrandExchangeAPI.isOpen()) {
                    return JsonBuilder.success("Opened Grand Exchange");
                }
                if (!tryOpenNearbyGrandExchange()) {
                    return JsonBuilder.error(404, "No nearby Grand Exchange Clerk in line of sight");
                }
                Thread.sleep(200L);
            }

            return JsonBuilder.error(408, "Timed out opening Grand Exchange");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonBuilder.error(500, "Grand Exchange open interrupted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to open Grand Exchange: " + e.getMessage());
        }
    }

    public String closeGrandExchange() {
        try {
            if (!GrandExchangeAPI.isOpen()) {
                return JsonBuilder.success("Grand Exchange already closed");
            }

            GrandExchangeAPI.close();
            for (int i = 0; i < 8; i++) {
                if (!GrandExchangeAPI.isOpen()) {
                    return JsonBuilder.success("Closed Grand Exchange");
                }
                Thread.sleep(120L);
            }
            return JsonBuilder.error(500, "Grand Exchange close not confirmed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonBuilder.error(500, "Grand Exchange close interrupted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to close Grand Exchange: " + e.getMessage());
        }
    }

    public String collectGrandExchangeAll() {
        try {
            if (!GrandExchangeAPI.isOpen()) {
                return JsonBuilder.error(400, "Grand Exchange is not open");
            }
            GrandExchangeAPI.collectAll();
            Delays.tick();
            return JsonBuilder.success("Collect-all submitted");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to collect from Grand Exchange: " + e.getMessage());
        }
    }

    public String buyGrandExchangeAuto(String body) {
        try {
            if (!GrandExchangeAPI.isOpen()) {
                return geBuyFailure(
                        400,
                        "GE_INTERFACE_CLOSED",
                        "Grand Exchange is not open",
                        -1,
                        -1,
                        -1,
                        0,
                        0,
                        0,
                        0,
                        0,
                        "UNKNOWN",
                        0
                );
            }

            Integer itemId = parseOptionalItemId(body);
            Integer amount = parseAmount(body);
            if (itemId == null || itemId <= 0 || amount == null || amount <= 0) {
                return JsonBuilder.error(
                        400,
                        "Invalid request body. Expected {\"itemId\": int > 0, \"amount\": int > 0, \"noted\"?: bool, \"maxRetries\"?: int, \"waitTicksPerAttempt\"?: int, \"initialFivePercentSteps\"?: int}"
                );
            }

            boolean noted = parseNoted(body, false);
            int maxRetries = parseOptionalInt(body, "maxRetries", 6, 0, 40);
            int waitTicksPerAttempt = parseOptionalInt(body, "waitTicksPerAttempt", 6, 1, 80);
            int initialFivePercentSteps = parseOptionalInt(body, "initialFivePercentSteps", 1, 1, 20);

            int slotNumber = GrandExchangeAPI.freeSlot();
            if (slotNumber == -1) {
                return geBuyFailure(
                        409,
                        "GE_SLOT_UNAVAILABLE",
                        "No free Grand Exchange slot available",
                        itemId,
                        amount,
                        -1,
                        0,
                        maxRetries,
                        waitTicksPerAttempt,
                        initialFivePercentSteps,
                        initialFivePercentSteps,
                        "UNKNOWN",
                        0
                );
            }

            GrandExchangeSlot slot = GrandExchangeSlot.getBySlot(slotNumber);
            if (slot == null) {
                return geBuyFailure(
                        500,
                        "GE_SLOT_UNAVAILABLE",
                        "Failed to resolve Grand Exchange slot",
                        itemId,
                        amount,
                        slotNumber,
                        0,
                        maxRetries,
                        waitTicksPerAttempt,
                        initialFivePercentSteps,
                        initialFivePercentSteps,
                        "UNKNOWN",
                        0
                );
            }

            int fivePercentSteps = initialFivePercentSteps;
            int retriesUsed = 0;
            boolean completed = false;
            int attemptsUsed = 0;
            String lastOfferState = "UNKNOWN";
            int lastQuantitySold = 0;

            while (retriesUsed <= maxRetries) {
                attemptsUsed = retriesUsed + 1;
                if (!GrandExchangeAPI.isOpen()) {
                    return geBuyFailure(
                            409,
                            "GE_INTERFACE_CLOSED",
                            "Grand Exchange closed during buy flow",
                            itemId,
                            amount,
                            slotNumber,
                            attemptsUsed,
                            maxRetries,
                            waitTicksPerAttempt,
                            initialFivePercentSteps,
                            fivePercentSteps,
                            lastOfferState,
                            lastQuantitySold
                    );
                }

                int startedSlot = GrandExchangeAPI.startBuyOfferPercentage(itemId, amount, fivePercentSteps, slotNumber);
                if (startedSlot == -1) {
                    return geBuyFailure(
                            500,
                            "GE_OFFER_START_FAILED",
                            "Failed to start Grand Exchange buy offer",
                            itemId,
                            amount,
                            slotNumber,
                            attemptsUsed,
                            maxRetries,
                            waitTicksPerAttempt,
                            initialFivePercentSteps,
                            fivePercentSteps,
                            lastOfferState,
                            lastQuantitySold
                    );
                }

                GeOfferSnapshot startedSnapshot = snapshotGeOffer(slotNumber);
                lastOfferState = startedSnapshot.state;
                lastQuantitySold = startedSnapshot.quantitySold;

                int remainingTicks = waitTicksPerAttempt;
                while (remainingTicks-- > 0) {
                    if (!GrandExchangeAPI.isOpen()) {
                        return geBuyFailure(
                                409,
                                "GE_INTERFACE_CLOSED",
                                "Grand Exchange closed while waiting for buy offer completion",
                                itemId,
                                amount,
                                slotNumber,
                                attemptsUsed,
                                maxRetries,
                                waitTicksPerAttempt,
                                initialFivePercentSteps,
                                fivePercentSteps,
                                lastOfferState,
                                lastQuantitySold
                        );
                    }

                    GrandExchangeAPI.bypassHighOfferWarning();
                    GeOfferSnapshot tickSnapshot = snapshotGeOffer(slotNumber);
                    lastOfferState = tickSnapshot.state;
                    lastQuantitySold = tickSnapshot.quantitySold;
                    if (slot.isDone()) {
                        completed = true;
                        break;
                    }
                    Delays.tick();
                }

                if (completed) {
                    break;
                }

                GrandExchangeAPI.cancel(slot);
                GrandExchangeAPI.collectFromSlot(slotNumber);
                Delays.tick();
                retriesUsed++;
                fivePercentSteps++;
            }

            ClientScriptAPI.closeNumericInputDialogue();
            Delays.tick();

            if (!completed) {
                int finalFivePercentSteps = Math.max(initialFivePercentSteps, fivePercentSteps - 1);
                String reasonCode = lastQuantitySold > 0 ? "GE_OFFER_NOT_FILLED" : "GE_PRICE_TOO_LOW";
                String reasonText = lastQuantitySold > 0
                        ? "Grand Exchange offer was only partially filled"
                        : "Grand Exchange offer did not fill at attempted prices";
                return geBuyFailure(
                        408,
                        reasonCode,
                        reasonText
                                + " after "
                                + attemptsUsed
                                + " attempt(s) (last price step: +"
                                + (finalFivePercentSteps * 5)
                                + "%)",
                        itemId,
                        amount,
                        slotNumber,
                        attemptsUsed,
                        maxRetries,
                        waitTicksPerAttempt,
                        initialFivePercentSteps,
                        finalFivePercentSteps,
                        lastOfferState,
                        lastQuantitySold
                );
            }

            GrandExchangeAPI.collectFromSlot(slotNumber, noted, amount);
            Delays.tick();

            return geBuySuccess(
                    "Grand Exchange buy completed for itemId "
                            + itemId
                            + " x"
                            + amount
                            + " after "
                            + attemptsUsed
                            + " attempt(s)",
                    itemId,
                    amount,
                    slotNumber,
                    attemptsUsed,
                    maxRetries,
                    waitTicksPerAttempt,
                    initialFivePercentSteps,
                    fivePercentSteps,
                    lastOfferState,
                    lastQuantitySold
            );
        } catch (Exception e) {
            return geBuyFailure(
                    500,
                    "GE_OFFER_START_FAILED",
                    "Failed to execute Grand Exchange buy: " + e.getMessage(),
                    -1,
                    -1,
                    -1,
                    0,
                    0,
                    0,
                    0,
                    0,
                    "UNKNOWN",
                    0
            );
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

    public String depositAllOfItem(String body) {
        try {
            if (!BankAPI.isOpen()) {
                return JsonBuilder.error(400, "Bank is not open");
            }

            Integer itemId = parseOptionalItemId(body);
            if (itemId == null || itemId <= 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"itemId\": int > 0}");
            }

            ItemEx inventoryItem = InventoryAPI.getItem(itemId);
            if (inventoryItem == null || inventoryItem.getId() <= 0) {
                return JsonBuilder.error(404, "Item not found in inventory: " + itemId);
            }

            BankAPI.depositAction(inventoryItem.getId(), -1, inventoryItem.getSlot());
            return JsonBuilder.success("Deposit-all submitted for itemId " + itemId);
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to deposit inventory item: " + e.getMessage());
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

    private boolean tryOpenNearbyGrandExchange() {
        NpcEx clerk = new NpcQuery()
                .withNameContains("Grand Exchange Clerk")
                .keepIf(n -> SceneAPI.losTileNextTo(n.getWorldPoint()) != null)
                .nearest();
        if (clerk == null) {
            return false;
        }

        NpcAPI.interact(clerk, 2);
        return true;
    }

    private ItemEx findShopItemByName(String itemName) {
        if (itemName == null || itemName.trim().isEmpty()) {
            return null;
        }

        List<ItemEx> stock = InventoryQuery.fromCurrentShop().collect();
        for (ItemEx item : stock) {
            if (item != null && item.getId() > 0 && itemName.equalsIgnoreCase(item.getName())) {
                return item;
            }
        }

        ItemEx containsMatch = ShopAPI.getShopItem(itemName);
        if (containsMatch != null && containsMatch.getId() > 0) {
            return containsMatch;
        }
        return null;
    }

    private ItemEx findInventoryItemByName(String itemName) {
        if (itemName == null || itemName.trim().isEmpty()) {
            return null;
        }

        for (ItemEx item : InventoryAPI.getItems()) {
            if (item != null && item.getId() > 0 && itemName.equalsIgnoreCase(item.getName())) {
                return item;
            }
        }

        ItemEx containsMatch = InventoryAPI.getItem(itemName);
        if (containsMatch != null && containsMatch.getId() > 0) {
            return containsMatch;
        }
        return null;
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

    private static class WidgetRequest {
        int groupId = -1;
        int childId = -1;
        int childChildId = -1;
    }

    private static class GeOfferSnapshot {
        final String state;
        final int quantitySold;

        private GeOfferSnapshot(String state, int quantitySold) {
            this.state = state;
            this.quantitySold = quantitySold;
        }
    }

    private String smartActionAccepted(String message, String phase, boolean walkRequired, WorldPoint approachTile) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("success", true);
        json.field("message", message);
        json.field("phase", phase);
        json.field("walkRequired", walkRequired);
        if (approachTile != null) {
            json.fieldRaw("approachTile", JsonBuilder.position(approachTile.getX(), approachTile.getY(), approachTile.getPlane()));
        } else {
            json.fieldNull("approachTile");
        }
        json.endObject();
        return json.toString();
    }

    private String worldHopAccepted(String message, World world) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("success", true);
        json.field("message", message);
        json.field("worldId", world.getId());
        json.field("members", world.getTypes() != null && world.getTypes().contains(WorldType.MEMBERS));
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
        json.endObject();
        return json.toString();
    }

    private String reasonedError(int code, ActionTracker.ReasonCode reasonCode, String message) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("error", true);
        json.field("code", code);
        json.field("reasonCode", reasonCode.name());
        json.field("message", message);
        json.endObject();
        return json.toString();
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

    private Integer parseWorldId(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            String value = extractJsonValue(body, "worldId");
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

    private WidgetRequest parseWidgetRequest(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            WidgetRequest request = new WidgetRequest();
            String groupId = extractJsonValue(body, "groupId");
            String childId = extractJsonValue(body, "childId");
            String childChildId = extractJsonValue(body, "childChildId");
            if (groupId != null) {
                request.groupId = Integer.parseInt(groupId);
            }
            if (childId != null) {
                request.childId = Integer.parseInt(childId);
            }
            if (childChildId != null) {
                request.childChildId = Integer.parseInt(childChildId);
            }
            return request;
        } catch (Exception e) {
            return null;
        }
    }

    private int parseOptionalInt(String body, String key, int defaultValue, int minInclusive, int maxInclusive) {
        if (body == null || body.isEmpty()) {
            return defaultValue;
        }
        try {
            String value = extractJsonValue(body, key);
            if (value == null || value.trim().isEmpty()) {
                return defaultValue;
            }
            int parsed = Integer.parseInt(value);
            if (parsed < minInclusive) {
                return minInclusive;
            }
            if (parsed > maxInclusive) {
                return maxInclusive;
            }
            return parsed;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private boolean dispatchWidgetClick(Widget widget) {
        Rectangle bounds = widget.getBounds();
        if (bounds == null || bounds.width <= 0 || bounds.height <= 0) {
            return false;
        }

        Canvas canvas = Static.getRuneLite().getGameApplet().getCanvas();
        if (canvas == null) {
            return false;
        }

        int x = bounds.x + ThreadLocalRandom.current().nextInt(bounds.width);
        int y = bounds.y + ThreadLocalRandom.current().nextInt(bounds.height);
        long when = System.currentTimeMillis();

        MouseEvent pressEvent = new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, when, 0, x, y, 1, false, MouseEvent.BUTTON1);
        MouseEvent releaseEvent = new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, when + 50L, 0, x, y, 1, false, MouseEvent.BUTTON1);
        MouseEvent clickEvent = new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, when + 50L, 0, x, y, 1, false, MouseEvent.BUTTON1);

        CountDownLatch latch = new CountDownLatch(1);
        java.awt.EventQueue.invokeLater(() -> {
            try {
                canvas.dispatchEvent(pressEvent);
                canvas.dispatchEvent(releaseEvent);
                canvas.dispatchEvent(clickEvent);
            } finally {
                latch.countDown();
            }
        });

        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private GeOfferSnapshot snapshotGeOffer(int slotNumber) {
        if (slotNumber < 1 || slotNumber > 8) {
            return new GeOfferSnapshot("UNKNOWN", 0);
        }

        try {
            Client client = Static.getClient();
            GrandExchangeOffer[] offers = Static.invoke(client::getGrandExchangeOffers);
            if (offers == null || offers.length < slotNumber) {
                return new GeOfferSnapshot("UNKNOWN", 0);
            }

            GrandExchangeOffer offer = offers[slotNumber - 1];
            if (offer == null) {
                return new GeOfferSnapshot(GrandExchangeOfferState.EMPTY.name(), 0);
            }

            GrandExchangeOfferState state = offer.getState();
            return new GeOfferSnapshot(state == null ? "UNKNOWN" : state.name(), offer.getQuantitySold());
        } catch (Exception e) {
            return new GeOfferSnapshot("UNKNOWN", 0);
        }
    }

    private String geBuyFailure(
            int code,
            String reasonCode,
            String message,
            int itemId,
            int amount,
            int slot,
            int attemptsUsed,
            int maxRetries,
            int waitTicksPerAttempt,
            int initialFivePercentSteps,
            int finalFivePercentSteps,
            String finalOfferState,
            int quantitySold
    ) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("error", true);
        json.field("code", code);
        json.field("reasonCode", reasonCode);
        json.field("message", message);
        json.field("itemId", itemId);
        json.field("amount", amount);
        json.field("slot", slot);
        json.field("attemptsUsed", attemptsUsed);
        json.field("maxRetries", maxRetries);
        json.field("waitTicksPerAttempt", waitTicksPerAttempt);
        json.field("initialFivePercentSteps", initialFivePercentSteps);
        json.field("finalFivePercentSteps", finalFivePercentSteps);
        json.field("finalOfferState", finalOfferState == null ? "UNKNOWN" : finalOfferState);
        json.field("quantitySold", quantitySold);
        json.endObject();
        return json.toString();
    }

    private String geBuySuccess(
            String message,
            int itemId,
            int amount,
            int slot,
            int attemptsUsed,
            int maxRetries,
            int waitTicksPerAttempt,
            int initialFivePercentSteps,
            int finalFivePercentSteps,
            String finalOfferState,
            int quantitySold
    ) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("success", true);
        json.field("message", message);
        json.field("itemId", itemId);
        json.field("amount", amount);
        json.field("slot", slot);
        json.field("attemptsUsed", attemptsUsed);
        json.field("maxRetries", maxRetries);
        json.field("waitTicksPerAttempt", waitTicksPerAttempt);
        json.field("initialFivePercentSteps", initialFivePercentSteps);
        json.field("finalFivePercentSteps", finalFivePercentSteps);
        json.field("finalOfferState", finalOfferState == null ? "UNKNOWN" : finalOfferState);
        json.field("quantitySold", quantitySold);
        json.endObject();
        return json.toString();
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
