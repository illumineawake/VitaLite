package com.tonic.services.llmapi.handlers;

import com.tonic.api.game.MovementAPI;
import com.tonic.data.wrappers.PlayerEx;
import com.tonic.services.GameManager;
import com.tonic.services.llmapi.util.JsonBuilder;
import com.tonic.services.pathfinder.model.WalkerPath;
import net.runelite.api.coords.WorldPoint;

/**
 * Handlers for walking and pathfinding endpoints
 */
public class WalkerHandlers {

    // ==================== LOCAL WALKING ====================

    public String walkTo(String body) {
        try {
            WalkRequest request = parseWalkRequest(body);
            if (request == null || request.x < 0 || request.y < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"x\": int, \"y\": int}");
            }

            MovementAPI.walkToWorldPoint(request.x, request.y);

            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("success", true);
            json.field("message", "Walking to local point");
            json.fieldRaw("destination", JsonBuilder.position(request.x, request.y, 0));
            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to walk: " + e.getMessage());
        }
    }

    public String walkRelative(String body) {
        try {
            WalkRequest request = parseWalkRequest(body);
            if (request == null) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"offsetX\": int, \"offsetY\": int}");
            }

            // Parse offsetX and offsetY specifically
            int offsetX = parseIntField(body, "offsetX");
            int offsetY = parseIntField(body, "offsetY");

            MovementAPI.walkRelativeToWorldPoint(offsetX, offsetY);

            PlayerEx local = PlayerEx.getLocal();
            WorldPoint pos = local != null ? local.getWorldPoint() : null;
            int destX = pos != null ? pos.getX() + offsetX : offsetX;
            int destY = pos != null ? pos.getY() + offsetY : offsetY;

            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("success", true);
            json.field("message", "Walking relative to current position");
            json.field("offsetX", offsetX);
            json.field("offsetY", offsetY);
            json.fieldRaw("destination", JsonBuilder.position(destX, destY, pos != null ? pos.getPlane() : 0));
            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to walk relative: " + e.getMessage());
        }
    }

    public String getDestination() {
        try {
            WorldPoint dest = MovementAPI.getDestinationWorldPoint();

            JsonBuilder json = new JsonBuilder();
            json.startObject();

            if (dest != null) {
                json.field("hasDestination", true);
                json.fieldRaw("destination", JsonBuilder.position(dest.getX(), dest.getY(), dest.getPlane()));
            } else {
                json.field("hasDestination", false);
                json.fieldNull("destination");
            }

            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get destination: " + e.getMessage());
        }
    }

    public String isMoving() {
        try {
            boolean moving = MovementAPI.isMoving();

            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("isMoving", moving);
            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to check movement: " + e.getMessage());
        }
    }

    // ==================== LONG DISTANCE WALKER ====================

    public String walkerWalkTo(String body) {
        try {
            WalkRequest request = parseWalkRequest(body);
            if (request == null || request.x < 0 || request.y < 0) {
                return JsonBuilder.error(400, "Invalid request body. Expected {\"x\": int, \"y\": int, \"plane\": int (optional)}");
            }

            int plane = request.plane >= 0 ? request.plane : 0;
            WorldPoint target = new WorldPoint(request.x, request.y, plane);

            // Cancel existing path if any
            WalkerPath existingPath = GameManager.getWalkerPath();
            if (existingPath != null && !existingPath.isDone()) {
                existingPath.cancel();
            }

            // Create new walker path
            WalkerPath path = WalkerPath.get(target);
            GameManager.setWalkerPath(path);

            int stepsRemaining = path.getSteps() != null ? path.getSteps().size() : 0;

            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("success", true);
            json.field("message", "Walker path started");
            json.fieldRaw("destination", JsonBuilder.position(request.x, request.y, plane));
            json.field("stepsRemaining", stepsRemaining);
            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to start walker: " + e.getMessage());
        }
    }

    public String getWalkerStatus() {
        try {
            WalkerPath path = GameManager.getWalkerPath();

            JsonBuilder json = new JsonBuilder();
            json.startObject();

            if (path == null) {
                json.field("active", false);
                json.fieldNull("stepsRemaining");
                json.fieldNull("destination");
                json.field("isDone", true);
                json.field("isCanceled", false);
            } else {
                boolean isDone = path.isDone();
                boolean isCanceled = path.isCanceled();
                int stepsRemaining = path.getSteps() != null ? path.getSteps().size() : 0;

                json.field("active", !isDone && !isCanceled);
                json.field("stepsRemaining", stepsRemaining);
                json.field("isDone", isDone);
                json.field("isCanceled", isCanceled);

                // Get destination from steps if available
                if (path.getSteps() != null && !path.getSteps().isEmpty()) {
                    WorldPoint dest = path.getSteps().get(path.getSteps().size() - 1).getPosition();
                    json.fieldRaw("destination", JsonBuilder.position(dest.getX(), dest.getY(), dest.getPlane()));
                } else {
                    json.fieldNull("destination");
                }
            }

            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to get walker status: " + e.getMessage());
        }
    }

    public String cancelWalker() {
        try {
            WalkerPath path = GameManager.getWalkerPath();

            if (path == null) {
                return JsonBuilder.error(404, "No active walker path to cancel");
            }

            if (path.isDone()) {
                return JsonBuilder.error(400, "Walker path already completed");
            }

            path.cancel();

            return JsonBuilder.success("Walker path canceled");
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to cancel walker: " + e.getMessage());
        }
    }

    public String stepWalker() {
        try {
            WalkerPath path = GameManager.getWalkerPath();

            if (path == null) {
                return JsonBuilder.error(404, "No active walker path");
            }

            if (path.isDone()) {
                return JsonBuilder.error(400, "Walker path already completed");
            }

            if (path.isCanceled()) {
                return JsonBuilder.error(400, "Walker path was canceled");
            }

            boolean hasMoreSteps = path.step();
            int stepsRemaining = path.getSteps() != null ? path.getSteps().size() : 0;

            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("success", true);
            json.field("hasMoreSteps", hasMoreSteps);
            json.field("stepsRemaining", stepsRemaining);
            json.field("isDone", path.isDone());
            json.endObject();
            return json.toString();
        } catch (Exception e) {
            return JsonBuilder.error(500, "Failed to step walker: " + e.getMessage());
        }
    }

    // ==================== HELPER CLASSES AND METHODS ====================

    private static class WalkRequest {
        int x = -1;
        int y = -1;
        int plane = -1;
    }

    private WalkRequest parseWalkRequest(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }

        WalkRequest request = new WalkRequest();

        try {
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
        } catch (Exception e) {
            return null;
        }

        return request;
    }

    private int parseIntField(String body, String field) {
        try {
            String value = extractJsonValue(body, field);
            if (value != null) {
                return Integer.parseInt(value);
            }
        } catch (Exception e) {
            // Return 0 as default
        }
        return 0;
    }

    /**
     * Simple JSON value extractor
     */
    private String extractJsonValue(String json, String key) {
        int keyIndex = json.indexOf("\"" + key + "\"");
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', keyIndex);
        if (colonIndex < 0) {
            return null;
        }

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= json.length()) {
            return null;
        }

        if (json.charAt(valueStart) == '"') {
            int valueEnd = json.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                return null;
            }
            return json.substring(valueStart + 1, valueEnd);
        }

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
