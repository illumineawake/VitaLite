package com.tonic.services.llmapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tonic.services.llmapi.actions.ActionTracker;
import com.tonic.services.llmapi.handlers.ActionHandlers;
import com.tonic.services.llmapi.handlers.DataHandlers;
import com.tonic.services.llmapi.handlers.WalkerHandlers;
import com.tonic.services.llmapi.util.JsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP REST API server for LLM/AI agent integration.
 * Enables external AI systems to query game state and perform interactions.
 *
 * Start with: LLMApiServer.getInstance().start()
 * Stop with: LLMApiServer.getInstance().stop()
 */
public class LLMApiServer {
    private static LLMApiServer instance;

    private HttpServer server;
    private int port = 8788;
    private volatile boolean running = false;

    private final DataHandlers dataHandlers;
    private final ActionHandlers actionHandlers;
    private final WalkerHandlers walkerHandlers;
    private final ActionTracker actionTracker;

    private LLMApiServer() {
        dataHandlers = new DataHandlers();
        actionHandlers = new ActionHandlers();
        walkerHandlers = new WalkerHandlers();
        actionTracker = ActionTracker.getInstance();
    }

    public static LLMApiServer getInstance() {
        if (instance == null) {
            instance = new LLMApiServer();
        }
        return instance;
    }

    public void start() throws IOException {
        start(port);
    }

    public void start(int port) throws IOException {
        if (running) {
            return;
        }
        this.port = port;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // Root/documentation endpoint
        server.createContext("/", this::handleRoot);

        // Player endpoints
        server.createContext("/players", this::handlePlayers);

        // NPC endpoints
        server.createContext("/npcs", this::handleNpcs);

        // Object endpoints
        server.createContext("/objects", this::handleObjects);

        // Ground item endpoints
        server.createContext("/ground-items", this::handleGroundItems);

        // Inventory endpoints
        server.createContext("/inventory", this::handleInventory);

        // Equipment endpoints
        server.createContext("/equipment", this::handleEquipment);

        // Skill endpoints
        server.createContext("/skills", this::handleSkills);

        // Local player endpoint
        server.createContext("/local-player", this::handleLocalPlayer);

        // Batched state endpoint (for LLM agents - one call gets everything)
        server.createContext("/state", this::handleState);

        // Health endpoint
        server.createContext("/health", this::handleHealth);

        // Collision endpoints
        server.createContext("/collision", this::handleCollision);

        // Walk endpoints
        server.createContext("/walk", this::handleWalk);

        // Walker endpoints
        server.createContext("/walker", this::handleWalker);

        // Dialogue endpoints
        server.createContext("/dialogue", this::handleDialogue);
        server.createContext("/actions", this::handleActions);

        server.start();
        running = true;
        System.out.println("[LLMApiServer] Started on http://127.0.0.1:" + port);
    }

    public void stop() {
        if (!running || server == null) {
            return;
        }
        server.stop(0);
        running = false;
        actionTracker.shutdown();
        System.out.println("[LLMApiServer] Stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    // ==================== ROUTE HANDLERS ====================

    private void handleRoot(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            sendJson(exchange, 200, getDocumentation());
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Endpoint not found: " + path));
        }
    }

    private void handlePlayers(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if (path.equals("/players")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getPlayers());
            }
        } else if (path.matches("/players/\\d+")) {
            int index = extractPathInt(path, "/players/");
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getPlayer(index));
            }
        } else if (path.matches("/players/\\d+/interact")) {
            int index = extractPathInt(path, "/players/", "/interact");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "PLAYER_INTERACT",
                        body,
                        "{\"playerIndex\":" + index + "}",
                        () -> actionHandlers.interactPlayer(index, body)
                );
            }
        } else if (path.matches("/players/\\d+/use-item")) {
            int index = extractPathInt(path, "/players/", "/use-item");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "PLAYER_USE_ITEM",
                        body,
                        "{\"playerIndex\":" + index + "}",
                        () -> actionHandlers.useItemOnPlayer(index, body)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown players endpoint"));
        }
    }

    private void handleNpcs(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/npcs")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getNpcs());
            }
        } else if (path.matches("/npcs/\\d+")) {
            int index = extractPathInt(path, "/npcs/");
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getNpc(index));
            }
        } else if (path.matches("/npcs/\\d+/interact")) {
            int index = extractPathInt(path, "/npcs/", "/interact");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "NPC_INTERACT",
                        body,
                        "{\"npcIndex\":" + index + "}",
                        () -> actionHandlers.interactNpc(index, body)
                );
            }
        } else if (path.matches("/npcs/\\d+/use-item")) {
            int index = extractPathInt(path, "/npcs/", "/use-item");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "NPC_USE_ITEM",
                        body,
                        "{\"npcIndex\":" + index + "}",
                        () -> actionHandlers.useItemOnNpc(index, body)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown npcs endpoint"));
        }
    }

    private void handleObjects(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = parseQuery(exchange);

        if (path.equals("/objects")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getObjects());
            }
        } else if (path.matches("/objects/\\d+")) {
            int id = extractPathInt(path, "/objects/");
            if (checkGet(exchange)) {
                int x = parseIntParam(params, "x", -1);
                int y = parseIntParam(params, "y", -1);
                int plane = parseIntParam(params, "plane", -1);
                sendJson(exchange, 200, dataHandlers.getObject(id, x, y, plane));
            }
        } else if (path.matches("/objects/\\d+/interact")) {
            int id = extractPathInt(path, "/objects/", "/interact");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "OBJECT_INTERACT",
                        body,
                        "{\"objectId\":" + id + "}",
                        () -> actionHandlers.interactObject(id, body)
                );
            }
        } else if (path.matches("/objects/\\d+/use-item")) {
            int id = extractPathInt(path, "/objects/", "/use-item");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "OBJECT_USE_ITEM",
                        body,
                        "{\"objectId\":" + id + "}",
                        () -> actionHandlers.useItemOnObject(id, body)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown objects endpoint"));
        }
    }

    private void handleGroundItems(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = parseQuery(exchange);

        if (path.equals("/ground-items")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getGroundItems());
            }
        } else if (path.matches("/ground-items/\\d+")) {
            int id = extractPathInt(path, "/ground-items/");
            if (checkGet(exchange)) {
                int x = parseIntParam(params, "x", -1);
                int y = parseIntParam(params, "y", -1);
                int plane = parseIntParam(params, "plane", -1);
                sendJson(exchange, 200, dataHandlers.getGroundItem(id, x, y, plane));
            }
        } else if (path.matches("/ground-items/\\d+/interact")) {
            int id = extractPathInt(path, "/ground-items/", "/interact");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "GROUND_ITEM_INTERACT",
                        body,
                        "{\"groundItemId\":" + id + "}",
                        () -> actionHandlers.interactGroundItem(id, body)
                );
            }
        } else if (path.matches("/ground-items/\\d+/use-item")) {
            int id = extractPathInt(path, "/ground-items/", "/use-item");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "GROUND_ITEM_USE_ITEM",
                        body,
                        "{\"groundItemId\":" + id + "}",
                        () -> actionHandlers.useItemOnGroundItem(id, body)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown ground-items endpoint"));
        }
    }

    private void handleInventory(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/inventory")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getInventory());
            }
        } else if (path.matches("/inventory/\\d+")) {
            int slot = extractPathInt(path, "/inventory/");
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getInventorySlot(slot));
            }
        } else if (path.matches("/inventory/\\d+/interact")) {
            int slot = extractPathInt(path, "/inventory/", "/interact");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "INVENTORY_INTERACT",
                        body,
                        "{\"slot\":" + slot + "}",
                        () -> actionHandlers.interactInventory(slot, body)
                );
            }
        } else if (path.matches("/inventory/\\d+/use-on-item")) {
            int slot = extractPathInt(path, "/inventory/", "/use-on-item");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "INVENTORY_USE_ON_ITEM",
                        body,
                        "{\"slot\":" + slot + "}",
                        () -> actionHandlers.useOnInventoryItem(slot, body)
                );
            }
        } else if (path.matches("/inventory/\\d+/drop")) {
            int slot = extractPathInt(path, "/inventory/", "/drop");
            if (checkPost(exchange)) {
                submitTrackedAction(
                        exchange,
                        "INVENTORY_DROP",
                        "{}",
                        "{\"slot\":" + slot + "}",
                        () -> actionHandlers.dropInventoryItem(slot)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown inventory endpoint"));
        }
    }

    private void handleEquipment(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/equipment")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getEquipment());
            }
        } else if (path.matches("/equipment/\\d+")) {
            int slot = extractPathInt(path, "/equipment/");
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getEquipmentSlot(slot));
            }
        } else if (path.matches("/equipment/\\d+/interact")) {
            int slot = extractPathInt(path, "/equipment/", "/interact");
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "EQUIPMENT_INTERACT",
                        body,
                        "{\"slot\":" + slot + "}",
                        () -> actionHandlers.interactEquipment(slot, body)
                );
            }
        } else if (path.matches("/equipment/\\d+/unequip")) {
            int slot = extractPathInt(path, "/equipment/", "/unequip");
            if (checkPost(exchange)) {
                submitTrackedAction(
                        exchange,
                        "EQUIPMENT_UNEQUIP",
                        "{}",
                        "{\"slot\":" + slot + "}",
                        () -> actionHandlers.unequipItem(slot)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown equipment endpoint"));
        }
    }

    private void handleSkills(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/skills")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getSkills());
            }
        } else if (path.matches("/skills/[a-zA-Z]+")) {
            String skillName = path.substring("/skills/".length());
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getSkill(skillName));
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown skills endpoint"));
        }
    }

    private void handleLocalPlayer(HttpExchange exchange) throws IOException {
        if (checkGet(exchange)) {
            sendJson(exchange, 200, dataHandlers.getLocalPlayer());
        }
    }

    private void handleState(HttpExchange exchange) throws IOException {
        if (checkGet(exchange)) {
            sendJson(exchange, 200, dataHandlers.getFullState());
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (checkGet(exchange)) {
            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("running", running);
            json.field("port", port);
            json.endObject();
            sendJson(exchange, 200, json.toString());
        }
    }

    private void handleCollision(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = parseQuery(exchange);

        if (path.equals("/collision")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getCollision());
            }
        } else if (path.equals("/collision/check")) {
            if (checkGet(exchange)) {
                int x = parseIntParam(params, "x", -1);
                int y = parseIntParam(params, "y", -1);
                int plane = parseIntParam(params, "plane", 0);
                sendJson(exchange, 200, dataHandlers.checkCollision(x, y, plane));
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown collision endpoint"));
        }
    }

    private void handleWalk(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/walk")) {
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "WALK_LOCAL",
                        body,
                        "{}",
                        () -> walkerHandlers.walkTo(body)
                );
            }
        } else if (path.equals("/walk/relative")) {
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "WALK_RELATIVE",
                        body,
                        "{}",
                        () -> walkerHandlers.walkRelative(body)
                );
            }
        } else if (path.equals("/walk/destination")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, walkerHandlers.getDestination());
            }
        } else if (path.equals("/walk/is-moving")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, walkerHandlers.isMoving());
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown walk endpoint"));
        }
    }

    private void handleWalker(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/walker/walk-to")) {
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "WALKER_WALK_TO",
                        body,
                        "{}",
                        () -> walkerHandlers.walkerWalkTo(body)
                );
            }
        } else if (path.equals("/walker/status")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, walkerHandlers.getWalkerStatus());
            }
        } else if (path.equals("/walker/cancel")) {
            if (checkPost(exchange)) {
                submitTrackedAction(
                        exchange,
                        "WALKER_CANCEL",
                        "{}",
                        "{}",
                        walkerHandlers::cancelWalker
                );
            }
        } else if (path.equals("/walker/step")) {
            if (checkPost(exchange)) {
                submitTrackedAction(
                        exchange,
                        "WALKER_STEP",
                        "{}",
                        "{}",
                        walkerHandlers::stepWalker
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown walker endpoint"));
        }
    }

    private void handleDialogue(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if (path.equals("/dialogue")) {
            if (checkGet(exchange)) {
                sendJson(exchange, 200, dataHandlers.getDialogue());
            }
        } else if (path.equals("/dialogue/continue")) {
            if (checkPost(exchange)) {
                submitTrackedAction(
                        exchange,
                        "DIALOGUE_CONTINUE",
                        "{}",
                        "{}",
                        actionHandlers::continueDialogue
                );
            }
        } else if (path.equals("/dialogue/select")) {
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "DIALOGUE_SELECT",
                        body,
                        "{}",
                        () -> actionHandlers.selectDialogueOption(body)
                );
            }
        } else if (path.equals("/dialogue/enter-number")) {
            if (checkPost(exchange)) {
                String body = readBody(exchange);
                submitTrackedAction(
                        exchange,
                        "DIALOGUE_ENTER_NUMBER",
                        body,
                        "{}",
                        () -> actionHandlers.enterDialogueNumber(body)
                );
            }
        } else {
            sendJson(exchange, 404, JsonBuilder.error(404, "Unknown dialogue endpoint"));
        }
    }

    private void handleActions(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = parseQuery(exchange);

        if (path.equals("/actions/stream")) {
            if (checkGet(exchange)) {
                actionTracker.openStream(exchange);
            }
            return;
        }

        if (path.equals("/actions")) {
            if (checkGet(exchange)) {
                int limit = parseIntParam(params, "limit", 20);
                String status = params.get("status");
                sendJson(exchange, 200, actionTracker.listActions(limit, status));
            }
            return;
        }

        if (path.matches("/actions/[^/]+/cancel")) {
            String actionId = extractPathString(path, "/actions/", "/cancel");
            if (checkPost(exchange)) {
                ActionTracker.SubmitResult cancelResult = actionTracker.cancel(actionId);
                sendJson(exchange, cancelResult.getStatusCode(), cancelResult.getResponseBody());
            }
            return;
        }

        if (path.matches("/actions/[^/]+")) {
            String actionId = extractPathString(path, "/actions/");
            if (checkGet(exchange)) {
                String response = actionTracker.getAction(actionId);
                int statusCode = response.contains("\"error\":true") ? 404 : 200;
                sendJson(exchange, statusCode, response);
            }
            return;
        }

        sendJson(exchange, 404, JsonBuilder.error(404, "Unknown actions endpoint"));
    }

    // ==================== UTILITIES ====================

    private boolean checkGet(HttpExchange exchange) throws IOException {
        if (checkOptions(exchange)) {
            return false;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            sendJson(exchange, 405, JsonBuilder.error(405, "Method not allowed. Use GET."));
            return false;
        }
        return true;
    }

    private boolean checkPost(HttpExchange exchange) throws IOException {
        if (checkOptions(exchange)) {
            return false;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJson(exchange, 405, JsonBuilder.error(405, "Method not allowed. Use POST."));
            return false;
        }
        return true;
    }

    private boolean checkOptions(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            return false;
        }

        sendJson(exchange, 200, "{}");
        return true;
    }

    private void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, String> parseQuery(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isEmpty()) {
            return params;
        }
        for (String param : query.split("&")) {
            String[] kv = param.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private int parseIntParam(Map<String, String> params, String key, int defaultValue) {
        String value = params.get(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int extractPathInt(String path, String prefix) {
        String numStr = path.substring(prefix.length());
        int endIdx = numStr.indexOf('/');
        if (endIdx > 0) {
            numStr = numStr.substring(0, endIdx);
        }
        return Integer.parseInt(numStr);
    }

    private int extractPathInt(String path, String prefix, String suffix) {
        String numStr = path.substring(prefix.length());
        int endIdx = numStr.indexOf(suffix);
        if (endIdx > 0) {
            numStr = numStr.substring(0, endIdx);
        }
        return Integer.parseInt(numStr);
    }

    private String extractPathString(String path, String prefix) {
        String value = path.substring(prefix.length());
        int endIdx = value.indexOf('/');
        if (endIdx > 0) {
            value = value.substring(0, endIdx);
        }
        return value;
    }

    private String extractPathString(String path, String prefix, String suffix) {
        String value = path.substring(prefix.length());
        int endIdx = value.indexOf(suffix);
        if (endIdx > 0) {
            value = value.substring(0, endIdx);
        }
        return value;
    }

    private void submitTrackedAction(
            HttpExchange exchange,
            String type,
            String body,
            String targetInfoJson,
            ActionTracker.ActionExecutor executor
    ) throws IOException {
        String idempotencyKey = exchange.getRequestHeaders().getFirst("Idempotency-Key");
        String requestBody = body == null ? "{}" : body;
        ActionTracker.SubmitResult result = actionTracker.submit(
                type,
                exchange.getRequestURI().getPath(),
                requestBody,
                idempotencyKey,
                targetInfoJson,
                executor
        );
        sendJson(exchange, result.getStatusCode(), result.getResponseBody());
    }

    // ==================== DOCUMENTATION ====================

    private String getDocumentation() {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("name", "VitaLite LLM API");
        json.field("version", "1.0");
        json.field("description", "REST API for LLM/AI agent integration with game client");

        json.key("endpoints").startObject();

        // Players
        json.key("players").startObject();
        json.field("GET /players", "List all nearby players (excludes local player)");
        json.field("GET /players/{index}", "Detailed info for specific player by index");
        json.field("POST /players/{index}/interact", "Interact with player. Body: {\"action\": int|string}");
        json.field("POST /players/{index}/use-item", "Use inventory item on player. Body: {\"itemId\": int}");
        json.endObject();

        // NPCs
        json.key("npcs").startObject();
        json.field("GET /npcs", "List all nearby NPCs");
        json.field("GET /npcs/{index}", "Detailed info for specific NPC by index");
        json.field("POST /npcs/{index}/interact", "Submit NPC interaction. Returns {accepted, actionId, submittedTick}. Body: {\"action\": int|string}");
        json.field("POST /npcs/{index}/use-item", "Submit use-item on NPC. Returns {accepted, actionId, submittedTick}. Body: {\"itemId\": int}");
        json.endObject();

        // Objects
        json.key("objects").startObject();
        json.field("GET /objects", "List all nearby tile objects");
        json.field("GET /objects/{id}?x=&y=&plane=", "Detailed info for specific object");
        json.field("POST /objects/{id}/interact", "Interact with object. Body: {\"action\": int|string, \"x\": int, \"y\": int, \"plane\": int}");
        json.field("POST /objects/{id}/use-item", "Use inventory item on object. Body: {\"itemId\": int, \"x\": int, \"y\": int, \"plane\": int}");
        json.endObject();

        // Ground Items
        json.key("ground_items").startObject();
        json.field("GET /ground-items", "List all nearby ground items");
        json.field("GET /ground-items/{id}?x=&y=&plane=", "Detailed info for specific ground item");
        json.field("POST /ground-items/{id}/interact", "Interact with ground item. Body: {\"action\": int|string, \"x\": int, \"y\": int, \"plane\": int}");
        json.field("POST /ground-items/{id}/use-item", "Use inventory item on ground item");
        json.endObject();

        // Inventory
        json.key("inventory").startObject();
        json.field("GET /inventory", "List all inventory items");
        json.field("GET /inventory/{slot}", "Detailed info for item in slot (0-27)");
        json.field("POST /inventory/{slot}/interact", "Use item. Body: {\"action\": int|string}");
        json.field("POST /inventory/{slot}/use-on-item", "Use on another item. Body: {\"targetSlot\": int}");
        json.field("POST /inventory/{slot}/drop", "Drop item");
        json.endObject();

        // Equipment
        json.key("equipment").startObject();
        json.field("GET /equipment", "List all equipped items");
        json.field("GET /equipment/{slot}", "Detailed info for equipment slot");
        json.field("POST /equipment/{slot}/interact", "Interact with equipped item. Body: {\"action\": int|string}");
        json.field("POST /equipment/{slot}/unequip", "Unequip item");
        json.endObject();

        // Skills
        json.key("skills").startObject();
        json.field("GET /skills", "All skill levels (boosted/real/xp)");
        json.field("GET /skills/{name}", "Specific skill info (e.g., /skills/attack)");
        json.endObject();

        // Local Player
        json.key("local_player").startObject();
        json.field("GET /local-player", "Full local player state (hp, prayer, energy, position, inCombat, etc.)");
        json.endObject();

        // State
        json.key("state").startObject();
        json.field("GET /state", "Batched snapshot: player, combatTarget, npcs (with hasTarget), npcsTargetingPlayer, ground_items, inventory, objects, dialogue, recentMessages");
        json.endObject();

        // Action tracking
        json.key("actions").startObject();
        json.field("GET /actions/{actionId}", "Get detailed status for a submitted action (includes attemptSucceeded, intentSatisfied, intentReason, evidence)");
        json.field("GET /actions?limit=&status=", "List recent actions (optional status filter)");
        json.field("POST /actions/{actionId}/cancel", "Cancel a queued action");
        json.field("GET /actions/stream", "SSE stream of action.update/action.terminal events");
        json.endObject();

        // Health
        json.key("health").startObject();
        json.field("GET /health", "Server health/status (running, port)");
        json.endObject();

        // Collision
        json.key("collision").startObject();
        json.field("GET /collision", "Local collision data grid (104x104)");
        json.field("GET /collision/check?x=&y=&plane=", "Check if specific world tile is walkable");
        json.endObject();

        // Walk
        json.key("walk").startObject();
        json.field("POST /walk", "Local walk. Body: {\"x\": int, \"y\": int}");
        json.field("POST /walk/relative", "Walk relative to player. Body: {\"offsetX\": int, \"offsetY\": int}");
        json.field("GET /walk/destination", "Current walk destination");
        json.field("GET /walk/is-moving", "Check if player is moving");
        json.endObject();

        // Walker
        json.key("walker").startObject();
        json.field("POST /walker/walk-to", "Long distance walk. Body: {\"x\": int, \"y\": int, \"plane\": int}");
        json.field("GET /walker/status", "Current walker status (destination, steps remaining)");
        json.field("POST /walker/cancel", "Cancel current walk path");
        json.field("POST /walker/step", "Execute a single walker step (for manual stepping)");
        json.endObject();

        // Dialogue
        json.key("dialogue").startObject();
        json.field("GET /dialogue", "Get current dialogue state (text, options, type)");
        json.field("POST /dialogue/continue", "Continue/click through dialogue");
        json.field("POST /dialogue/select", "Select dialogue option. Body: {\"option\": int|string}");
        json.field("POST /dialogue/enter-number", "Enter number in dialogue. Body: {\"value\": int}");
        json.endObject();

        json.endObject(); // endpoints

        // Equipment slot reference
        json.key("equipment_slots").startObject();
        json.field("0", "HEAD");
        json.field("1", "CAPE");
        json.field("2", "AMULET");
        json.field("3", "WEAPON");
        json.field("4", "BODY");
        json.field("5", "SHIELD");
        json.field("7", "LEGS");
        json.field("9", "GLOVES");
        json.field("10", "BOOTS");
        json.field("12", "RING");
        json.field("13", "AMMO");
        json.endObject();

        json.endObject();
        return json.toString();
    }
}
