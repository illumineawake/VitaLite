package com.tonic.services.llmapi.actions;

import com.sun.net.httpserver.HttpExchange;
import com.tonic.api.game.MovementAPI;
import com.tonic.api.widgets.BankAPI;
import com.tonic.api.widgets.EquipmentAPI;
import com.tonic.api.widgets.InventoryAPI;
import com.tonic.data.wrappers.ActorEx;
import com.tonic.data.wrappers.ItemEx;
import com.tonic.data.wrappers.NpcEx;
import com.tonic.data.wrappers.PlayerEx;
import com.tonic.queries.NpcQuery;
import com.tonic.queries.PlayerQuery;
import com.tonic.services.GameManager;
import com.tonic.services.llmapi.state.RecentMessageStore;
import com.tonic.services.llmapi.util.JsonBuilder;
import com.tonic.services.pathfinder.model.WalkerPath;
import net.runelite.api.coords.WorldPoint;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class ActionTracker {
    private static final ActionTracker INSTANCE = new ActionTracker();

    private static final int MAX_ACTIONS = 2000;
    private static final int MAX_LIST_LIMIT = 200;
    private static final int EVIDENCE_MESSAGE_LIMIT = 4;
    private static final long ACTION_TTL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long IDEMPOTENCY_TTL_MS = TimeUnit.SECONDS.toMillis(60);
    private static final long WAIT_POLL_MS = 200L;

    private static final long COMBAT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long WALK_LOCAL_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(8);
    private static final long WALKER_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(25);
    private static final long BANK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(12);

    private final AtomicLong sequence = new AtomicLong(1);
    private final Map<String, ActionRecord> actionsById = new ConcurrentHashMap<>();
    private final Deque<String> actionOrder = new ConcurrentLinkedDeque<>();
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Map<String, IdempotencyEntry> idempotencyMap = new ConcurrentHashMap<>();
    private final Set<SseClient> sseClients = new CopyOnWriteArraySet<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean running = true;

    public enum ActionStatus {
        ACCEPTED,
        QUEUED,
        EXECUTING,
        WAITING_CONDITION,
        SUCCEEDED,
        FAILED,
        CANCELED,
        EXPIRED;

        public boolean isTerminal() {
            return this == SUCCEEDED || this == FAILED || this == CANCELED || this == EXPIRED;
        }
    }

    public enum ReasonCode {
        OUT_OF_RANGE,
        ALREADY_IN_COMBAT,
        INVALID_TARGET,
        PATH_BLOCKED,
        MENU_ACTION_UNAVAILABLE,
        TIMEOUT,
        RATE_LIMITED,
        STILL_MOVING,
        INTERFACE_BLOCKED,
        INTERRUPTED,
        IDEMPOTENCY_CONFLICT,
        CANCELED_BY_REQUEST,
        EXPIRED,
        UNKNOWN_ERROR
    }

    @FunctionalInterface
    public interface ActionExecutor {
        String execute();
    }

    public static final class SubmitResult {
        private final int statusCode;
        private final String responseBody;

        private SubmitResult(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getResponseBody() {
            return responseBody;
        }
    }

    private static final class IdempotencyEntry {
        private final String fingerprint;
        private final String actionId;
        private final long createdAtMs;

        private IdempotencyEntry(String fingerprint, String actionId, long createdAtMs) {
            this.fingerprint = fingerprint;
            this.actionId = actionId;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class PreExecutionSnapshot {
        private final int localX;
        private final int localY;
        private final int localPlane;
        private final int walkerSteps;
        private final int inventoryCount;
        private final int equipmentCount;
        private final java.util.Map<Integer, Integer> inventoryQuantities;

        private PreExecutionSnapshot(int localX, int localY, int localPlane, int walkerSteps, int inventoryCount, int equipmentCount, java.util.Map<Integer, Integer> inventoryQuantities) {
            this.localX = localX;
            this.localY = localY;
            this.localPlane = localPlane;
            this.walkerSteps = walkerSteps;
            this.inventoryCount = inventoryCount;
            this.equipmentCount = equipmentCount;
            this.inventoryQuantities = inventoryQuantities;
        }
    }

    private static final class IntentEvaluation {
        private final boolean satisfied;
        private final boolean expired;
        private final ReasonCode reasonCode;
        private final String reasonText;
        private final String intentReason;
        private final boolean retryable;
        private final String evidenceJson;

        private IntentEvaluation(
                boolean satisfied,
                boolean expired,
                ReasonCode reasonCode,
                String reasonText,
                String intentReason,
                boolean retryable,
                String evidenceJson
        ) {
            this.satisfied = satisfied;
            this.expired = expired;
            this.reasonCode = reasonCode;
            this.reasonText = reasonText;
            this.intentReason = intentReason;
            this.retryable = retryable;
            this.evidenceJson = evidenceJson;
        }
    }

    private static final class ActionRecord {
        private final String actionId;
        private final String type;
        private final String endpoint;
        private final String requestPayloadJson;
        private final String targetInfoJson;
        private final String idempotencyKey;
        private final ActionExecutor executor;
        private final long createdAtMs;
        private final long submittedTick;

        private volatile ActionStatus status;
        private volatile String reasonCode;
        private volatile String reasonText;
        private volatile boolean retryable;
        private volatile long startedTick;
        private volatile long resolvedTick;
        private volatile long updatedAtMs;
        private volatile int version;

        private volatile boolean attemptSucceeded;
        private volatile boolean intentSatisfied;
        private volatile String intentReason;
        private volatile String evidenceJson;

        private ActionRecord(
                String actionId,
                String type,
                String endpoint,
                String requestPayloadJson,
                String targetInfoJson,
                String idempotencyKey,
                ActionExecutor executor,
                long createdAtMs,
                long submittedTick
        ) {
            this.actionId = actionId;
            this.type = type;
            this.endpoint = endpoint;
            this.requestPayloadJson = requestPayloadJson;
            this.targetInfoJson = targetInfoJson;
            this.idempotencyKey = idempotencyKey;
            this.executor = executor;
            this.createdAtMs = createdAtMs;
            this.submittedTick = submittedTick;
            this.status = ActionStatus.ACCEPTED;
            this.updatedAtMs = createdAtMs;
            this.version = 1;
            this.startedTick = -1L;
            this.resolvedTick = -1L;
            this.reasonCode = null;
            this.reasonText = null;
            this.retryable = false;
            this.attemptSucceeded = false;
            this.intentSatisfied = false;
            this.intentReason = null;
            this.evidenceJson = "{\"recentMessages\":[],\"stateHints\":{}}";
        }

        private synchronized void setAttemptOutcome(boolean attemptSucceeded) {
            this.attemptSucceeded = attemptSucceeded;
        }

        private synchronized void setIntentOutcome(boolean intentSatisfied, String intentReason, String evidenceJson) {
            this.intentSatisfied = intentSatisfied;
            this.intentReason = intentReason;
            if (evidenceJson != null && !evidenceJson.isEmpty()) {
                this.evidenceJson = evidenceJson;
            }
        }

        private synchronized boolean transition(
                ActionStatus next,
                ReasonCode code,
                String text,
                Boolean retryable,
                boolean setStartedTick,
                boolean setResolvedTick
        ) {
            if (!isValidTransition(this.status, next)) {
                return false;
            }
            this.status = next;
            this.reasonCode = code == null ? null : code.name();
            this.reasonText = text;
            if (retryable != null) {
                this.retryable = retryable;
            }
            long now = System.currentTimeMillis();
            this.updatedAtMs = now;
            if (setStartedTick && this.startedTick < 0) {
                this.startedTick = GameManager.getTickCount();
            }
            if (setResolvedTick) {
                this.resolvedTick = GameManager.getTickCount();
            }
            this.version += 1;
            return true;
        }

        private String toJson() {
            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("actionId", actionId);
            json.field("type", type);
            json.field("status", status.name());
            if (reasonCode != null) {
                json.field("reasonCode", reasonCode);
            } else {
                json.fieldNull("reasonCode");
            }
            if (reasonText != null) {
                json.field("reasonText", reasonText);
            } else {
                json.fieldNull("reasonText");
            }
            json.field("retryable", retryable);
            json.field("attemptSucceeded", attemptSucceeded);
            json.field("intentSatisfied", intentSatisfied);
            if (intentReason != null) {
                json.field("intentReason", intentReason);
            } else {
                json.fieldNull("intentReason");
            }
            json.fieldRaw("evidence", evidenceJson);
            json.field("submittedTick", submittedTick);
            json.field("startedTick", startedTick);
            json.field("resolvedTick", resolvedTick);
            json.field("createdAtMs", createdAtMs);
            json.field("updatedAtMs", updatedAtMs);
            json.field("version", version);
            json.field("endpoint", endpoint);
            if (idempotencyKey != null) {
                json.field("idempotencyKey", idempotencyKey);
            } else {
                json.fieldNull("idempotencyKey");
            }
            json.fieldRaw("targetInfo", targetInfoJson);
            json.fieldRaw("requestPayload", requestPayloadJson);
            json.endObject();
            return json.toString();
        }
    }

    private static final class SseClient {
        private final OutputStream outputStream;

        private SseClient(OutputStream outputStream) {
            this.outputStream = outputStream;
        }
    }

    public static ActionTracker getInstance() {
        return INSTANCE;
    }

    private ActionTracker() {
        worker.submit(this::workerLoop);
        scheduler.scheduleAtFixedRate(this::cleanupOldEntries, 30, 30, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::sendHeartbeat, 10, 10, TimeUnit.SECONDS);
    }

    public SubmitResult submit(
            String type,
            String endpoint,
            String requestBody,
            String idempotencyKey,
            String targetInfoJson,
            ActionExecutor executor
    ) {
        if (!running) {
            return new SubmitResult(503, JsonBuilder.error(503, "Action tracker is not running"));
        }

        String normalizedBody = sanitizePayload(requestBody);
        String payloadJson = toPayloadJson(normalizedBody);
        String normalizedTargetInfo = normalizeTargetInfo(targetInfoJson);
        long now = System.currentTimeMillis();

        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            String key = idempotencyKey.trim();
            String fingerprint = computeFingerprint(type, endpoint, normalizedBody);
            IdempotencyEntry existing = idempotencyMap.get(key);
            if (existing != null && (now - existing.createdAtMs) <= IDEMPOTENCY_TTL_MS) {
                if (!Objects.equals(existing.fingerprint, fingerprint)) {
                    return new SubmitResult(409, buildConflictJson(key));
                }

                ActionRecord existingRecord = actionsById.get(existing.actionId);
                if (existingRecord != null) {
                    return new SubmitResult(202, buildAcceptedJson(existingRecord.actionId, existingRecord.submittedTick));
                }
            }
        }

        String actionId = buildActionId();
        long submittedTick = GameManager.getTickCount();

        ActionRecord record = new ActionRecord(
                actionId,
                type,
                endpoint,
                payloadJson,
                normalizedTargetInfo,
                idempotencyKey,
                executor,
                now,
                submittedTick
        );

        actionsById.put(actionId, record);
        actionOrder.addLast(actionId);

        if (idempotencyKey != null && !idempotencyKey.trim().isEmpty()) {
            idempotencyMap.put(
                    idempotencyKey.trim(),
                    new IdempotencyEntry(computeFingerprint(type, endpoint, normalizedBody), actionId, now)
            );
        }

        publish(record);
        record.transition(ActionStatus.QUEUED, null, null, null, false, false);
        publish(record);

        queue.offer(actionId);
        trimActionBacklog();

        return new SubmitResult(202, buildAcceptedJson(actionId, submittedTick));
    }

    public String getAction(String actionId) {
        ActionRecord record = actionsById.get(actionId);
        if (record == null) {
            return JsonBuilder.error(404, "Action not found: " + actionId);
        }
        return record.toJson();
    }

    public String listActions(int limit, String statusFilter) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        ActionStatus filter = null;
        if (statusFilter != null && !statusFilter.trim().isEmpty()) {
            try {
                filter = ActionStatus.valueOf(statusFilter.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return JsonBuilder.error(400, "Invalid status filter: " + statusFilter);
            }
        }

        List<String> selected = new ArrayList<>();
        Iterator<String> it = actionOrder.descendingIterator();
        while (it.hasNext() && selected.size() < safeLimit) {
            String actionId = it.next();
            ActionRecord record = actionsById.get(actionId);
            if (record == null) {
                continue;
            }
            if (filter != null && record.status != filter) {
                continue;
            }
            selected.add(actionId);
        }

        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("count", selected.size());
        json.key("actions").startArray();
        for (String actionId : selected) {
            ActionRecord record = actionsById.get(actionId);
            if (record != null) {
                json.rawJson(record.toJson());
            }
        }
        json.endArray();
        json.endObject();
        return json.toString();
    }

    public SubmitResult cancel(String actionId) {
        ActionRecord record = actionsById.get(actionId);
        if (record == null) {
            return new SubmitResult(404, JsonBuilder.error(404, "Action not found: " + actionId));
        }

        if (record.status.isTerminal()) {
            return new SubmitResult(409, JsonBuilder.error(409, "Action already terminal: " + record.status.name()));
        }

        if (record.status == ActionStatus.EXECUTING || record.status == ActionStatus.WAITING_CONDITION) {
            return new SubmitResult(409, JsonBuilder.error(409, "Action cannot be canceled in status: " + record.status.name()));
        }

        boolean removed = queue.remove(actionId);
        boolean transitioned = record.transition(
                ActionStatus.CANCELED,
                ReasonCode.CANCELED_BY_REQUEST,
                "Canceled by request",
                false,
                false,
                true
        );
        record.setIntentOutcome(false, "Canceled by request", buildEvidenceJson(record, null));

        if (removed || transitioned) {
            publish(record);
            return new SubmitResult(200, record.toJson());
        }
        return new SubmitResult(409, JsonBuilder.error(409, "Action could not be canceled"));
    }

    public void openStream(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Idempotency-Key");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = exchange.getResponseBody();
        SseClient client = new SseClient(os);
        sseClients.add(client);

        String initial = "event: ready\n" +
                "data: {\"ok\":true}\n\n";
        try {
            os.write(initial.getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            removeSseClient(client);
        }
    }

    public void shutdown() {
        running = false;
        worker.shutdownNow();
        scheduler.shutdownNow();
        for (SseClient client : sseClients) {
            removeSseClient(client);
        }
        sseClients.clear();
    }

    private void workerLoop() {
        while (running) {
            try {
                String actionId = queue.poll(1, TimeUnit.SECONDS);
                if (actionId == null) {
                    continue;
                }

                ActionRecord record = actionsById.get(actionId);
                if (record == null || record.status.isTerminal()) {
                    continue;
                }

                if (!record.transition(ActionStatus.EXECUTING, null, null, null, true, false)) {
                    continue;
                }
                publish(record);

                PreExecutionSnapshot pre = capturePreExecutionSnapshot();

                String result;
                try {
                    result = record.executor.execute();
                } catch (Exception e) {
                    result = JsonBuilder.error(500, "Action execution failed: " + e.getMessage());
                }

                boolean attemptSuccess = isSuccess(result);
                record.setAttemptOutcome(attemptSuccess);

                if (!attemptSuccess) {
                    int code = extractJsonInt(result, "code", 500);
                    String message = extractJsonString(result, "message");
                    ReasonCode mapped = mapReason(code, message);
                    record.setIntentOutcome(false, message, buildEvidenceJson(record, pre));
                    record.transition(ActionStatus.FAILED, mapped, message, isRetryable(mapped), false, true);
                    publish(record);
                    continue;
                }

                IntentEvaluation evaluation;
                if (isCombatIntentAction(record)) {
                    record.transition(ActionStatus.WAITING_CONDITION, null, null, null, false, false);
                    publish(record);
                    evaluation = evaluateCombatIntent(record, pre);
                } else if (isMovementIntentAction(record)) {
                    record.transition(ActionStatus.WAITING_CONDITION, null, null, null, false, false);
                    publish(record);
                    evaluation = evaluateMovementIntent(record, pre);
                } else if (isBankIntentAction(record)) {
                    record.transition(ActionStatus.WAITING_CONDITION, null, null, null, false, false);
                    publish(record);
                    evaluation = evaluateBankIntent(record, pre);
                } else if (isImmediateIntentAction(record)) {
                    evaluation = new IntentEvaluation(
                            true,
                            false,
                            null,
                            null,
                            "Immediate interface intent accepted",
                            false,
                            buildEvidenceJson(record, pre)
                    );
                } else {
                    evaluation = new IntentEvaluation(
                            false,
                            false,
                            ReasonCode.UNKNOWN_ERROR,
                            "Intent evaluator not implemented for action type",
                            "Intent evaluator not implemented for action type",
                            false,
                            buildEvidenceJson(record, pre)
                    );
                }

                record.setIntentOutcome(evaluation.satisfied, evaluation.intentReason, evaluation.evidenceJson);

                if (evaluation.satisfied) {
                    record.transition(ActionStatus.SUCCEEDED, null, null, false, false, true);
                } else {
                    ActionStatus terminalStatus = evaluation.expired ? ActionStatus.EXPIRED : ActionStatus.FAILED;
                    ReasonCode reasonCode = evaluation.reasonCode == null ? ReasonCode.UNKNOWN_ERROR : evaluation.reasonCode;
                    String reasonText = evaluation.reasonText == null ? "Intent not satisfied" : evaluation.reasonText;
                    record.transition(terminalStatus, reasonCode, reasonText, evaluation.retryable, false, true);
                }
                publish(record);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Keep worker alive.
            }
        }
    }

    private PreExecutionSnapshot capturePreExecutionSnapshot() {
        PlayerEx local = PlayerEx.getLocal();
        int x = -1;
        int y = -1;
        int plane = -1;
        if (local != null) {
            WorldPoint wp = local.getWorldPoint();
            if (wp != null) {
                x = wp.getX();
                y = wp.getY();
                plane = wp.getPlane();
            }
        }

        int steps = -1;
        WalkerPath path = GameManager.getWalkerPath();
        if (path != null && path.getSteps() != null) {
            steps = path.getSteps().size();
        }

        List<ItemEx> inventoryItems = InventoryAPI.getItems();
        int inventoryCount = 0;
        java.util.Map<Integer, Integer> quantities = new java.util.HashMap<>();
        for (ItemEx item : inventoryItems) {
            if (item == null || item.getId() <= 0) {
                continue;
            }
            inventoryCount++;
            quantities.put(item.getId(), quantities.getOrDefault(item.getId(), 0) + item.getQuantity());
        }

        int equipmentCount = 0;
        for (ItemEx item : EquipmentAPI.getAll()) {
            if (item != null && item.getId() > 0) {
                equipmentCount++;
            }
        }

        return new PreExecutionSnapshot(x, y, plane, steps, inventoryCount, equipmentCount, quantities);
    }

    private IntentEvaluation evaluateCombatIntent(ActionRecord record, PreExecutionSnapshot pre) {
        long deadline = System.currentTimeMillis() + COMBAT_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            List<RecentMessageStore.RecentMessage> messages = RecentMessageStore.getLast(EVIDENCE_MESSAGE_LIMIT);
            String blockingMessage = findCombatBlockingMessage(messages, record.submittedTick);
            if (blockingMessage != null) {
                return new IntentEvaluation(
                        false,
                        false,
                        ReasonCode.ALREADY_IN_COMBAT,
                        blockingMessage,
                        "Target appears unavailable for combat",
                        false,
                        buildEvidenceJson(record, pre)
                );
            }

            if (isCombatIntentSatisfied(record)) {
                return new IntentEvaluation(
                        true,
                        false,
                        null,
                        null,
                        "Local player engaged with intended combat target",
                        false,
                        buildEvidenceJson(record, pre)
                );
            }

            sleepQuietly(WAIT_POLL_MS);
        }

        return new IntentEvaluation(
                false,
                true,
                ReasonCode.INVALID_TARGET,
                "Combat intent not observed before timeout",
                "Combat target engagement not observed in time",
                true,
                buildEvidenceJson(record, pre)
        );
    }

    private IntentEvaluation evaluateMovementIntent(ActionRecord record, PreExecutionSnapshot pre) {
        long timeoutMs = isWalkerAction(record.type) ? WALKER_TIMEOUT_MS : WALK_LOCAL_TIMEOUT_MS;
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            if (isMovementIntentSatisfied(record, pre)) {
                return new IntentEvaluation(
                        true,
                        false,
                        null,
                        null,
                        "Movement intent reached expected destination/state",
                        false,
                        buildEvidenceJson(record, pre)
                );
            }
            sleepQuietly(WAIT_POLL_MS);
        }

        return new IntentEvaluation(
                false,
                true,
                ReasonCode.PATH_BLOCKED,
                "Movement intent not satisfied before timeout",
                "Destination or movement progression was not confirmed",
                true,
                buildEvidenceJson(record, pre)
        );
    }

    private IntentEvaluation evaluateBankIntent(ActionRecord record, PreExecutionSnapshot pre) {
        long deadline = System.currentTimeMillis() + BANK_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if ("BANK_OPEN".equals(record.type) && BankAPI.isOpen()) {
                return new IntentEvaluation(true, false, null, null, "Bank interface opened", false, buildEvidenceJson(record, pre));
            }
            if ("BANK_CLOSE".equals(record.type) && !BankAPI.isOpen()) {
                return new IntentEvaluation(true, false, null, null, "Bank interface closed", false, buildEvidenceJson(record, pre));
            }
            if ("BANK_DEPOSIT_INVENTORY".equals(record.type) && isInventoryDepositSatisfied(pre)) {
                return new IntentEvaluation(true, false, null, null, "Inventory deposit observed", false, buildEvidenceJson(record, pre));
            }
            if ("BANK_DEPOSIT_EQUIPMENT".equals(record.type) && isEquipmentDepositSatisfied(pre)) {
                return new IntentEvaluation(true, false, null, null, "Equipment deposit observed", false, buildEvidenceJson(record, pre));
            }
            if ("BANK_WITHDRAW".equals(record.type) && isWithdrawSatisfied(record, pre)) {
                return new IntentEvaluation(true, false, null, null, "Withdraw observed in inventory", false, buildEvidenceJson(record, pre));
            }
            sleepQuietly(WAIT_POLL_MS);
        }

        return new IntentEvaluation(
                false,
                true,
                ReasonCode.TIMEOUT,
                "Bank action intent not satisfied before timeout",
                "Bank action did not produce expected inventory/interface state in time",
                true,
                buildEvidenceJson(record, pre)
        );
    }

    private boolean isCombatIntentAction(ActionRecord record) {
        if (!"NPC_INTERACT".equals(record.type) && !"PLAYER_INTERACT".equals(record.type)) {
            return false;
        }
        String actionName = extractJsonString(record.requestPayloadJson, "action");
        return actionName != null && actionName.equalsIgnoreCase("Attack");
    }

    private boolean isMovementIntentAction(ActionRecord record) {
        return "WALK_LOCAL".equals(record.type)
                || "WALK_RELATIVE".equals(record.type)
                || "WALKER_WALK_TO".equals(record.type)
                || "WALKER_STEP".equals(record.type)
                || "WALKER_CANCEL".equals(record.type);
    }

    private boolean isBankIntentAction(ActionRecord record) {
        return "BANK_OPEN".equals(record.type)
                || "BANK_CLOSE".equals(record.type)
                || "BANK_DEPOSIT_INVENTORY".equals(record.type)
                || "BANK_DEPOSIT_EQUIPMENT".equals(record.type)
                || "BANK_WITHDRAW".equals(record.type);
    }

    private boolean isImmediateIntentAction(ActionRecord record) {
        return "MAKE_X_CONFIRM".equals(record.type);
    }

    private boolean isWalkerAction(String type) {
        return "WALKER_WALK_TO".equals(type)
                || "WALKER_STEP".equals(type)
                || "WALKER_CANCEL".equals(type);
    }

    private boolean isCombatIntentSatisfied(ActionRecord record) {
        PlayerEx local = PlayerEx.getLocal();
        if (local == null) {
            return false;
        }

        int expectedNpcIndex = extractJsonInt(record.targetInfoJson, "npcIndex", -1);
        int expectedPlayerIndex = extractJsonInt(record.targetInfoJson, "playerIndex", -1);

        ActorEx<?> localInteracting = local.getInteracting();
        if (localInteracting != null) {
            int interactingIndex = localInteracting.getIndex();
            if (expectedNpcIndex >= 0 && interactingIndex == expectedNpcIndex) {
                return true;
            }
            if (expectedPlayerIndex >= 0 && interactingIndex == expectedPlayerIndex) {
                return true;
            }
        }

        ActorEx<?> inCombatWith = local.getInCombatWith();
        if (inCombatWith != null) {
            int combatIndex = inCombatWith.getIndex();
            if (expectedNpcIndex >= 0 && combatIndex == expectedNpcIndex) {
                return true;
            }
            if (expectedPlayerIndex >= 0 && combatIndex == expectedPlayerIndex) {
                return true;
            }
        }

        if (expectedNpcIndex >= 0) {
            NpcEx npc = new NpcQuery().keepIf(n -> n.getIndex() == expectedNpcIndex).first();
            if (npc != null) {
                ActorEx<?> npcInteracting = npc.getInteracting();
                if (npcInteracting != null && npcInteracting.equals(local)) {
                    return true;
                }
            }
        }

        if (expectedPlayerIndex >= 0) {
            PlayerEx player = new PlayerQuery().keepIf(p -> p.getIndex() == expectedPlayerIndex).first();
            if (player != null) {
                ActorEx<?> playerInteracting = player.getInteracting();
                if (playerInteracting != null && playerInteracting.equals(local)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isInventoryDepositSatisfied(PreExecutionSnapshot pre) {
        if (pre.inventoryCount == 0) {
            return true;
        }
        int nowCount = 0;
        for (ItemEx item : InventoryAPI.getItems()) {
            if (item != null && item.getId() > 0) {
                nowCount++;
            }
        }
        return nowCount < pre.inventoryCount;
    }

    private boolean isEquipmentDepositSatisfied(PreExecutionSnapshot pre) {
        if (pre.equipmentCount == 0) {
            return true;
        }
        int nowCount = 0;
        for (ItemEx item : EquipmentAPI.getAll()) {
            if (item != null && item.getId() > 0) {
                nowCount++;
            }
        }
        return nowCount < pre.equipmentCount;
    }

    private boolean isWithdrawSatisfied(ActionRecord record, PreExecutionSnapshot pre) {
        Integer itemId = parseOptionalJsonInt(record.requestPayloadJson, "itemId");
        String itemName = extractJsonString(record.requestPayloadJson, "itemName");

        List<ItemEx> items = InventoryAPI.getItems();
        if (itemId != null) {
            int before = pre.inventoryQuantities.getOrDefault(itemId, 0);
            int now = 0;
            for (ItemEx item : items) {
                if (item != null && item.getId() == itemId && item.getId() > 0) {
                    now += item.getQuantity();
                }
            }
            return now > before;
        }

        if (itemName != null && !itemName.isEmpty()) {
            for (ItemEx item : items) {
                if (item != null && item.getId() > 0 && itemName.equalsIgnoreCase(item.getName())) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isMovementIntentSatisfied(ActionRecord record, PreExecutionSnapshot pre) {
        PlayerEx local = PlayerEx.getLocal();
        if (local == null) {
            return false;
        }

        WorldPoint localPoint = local.getWorldPoint();
        if (localPoint == null) {
            return false;
        }

        if ("WALK_LOCAL".equals(record.type) || "WALKER_WALK_TO".equals(record.type)) {
            int targetX = extractJsonInt(record.requestPayloadJson, "x", -1);
            int targetY = extractJsonInt(record.requestPayloadJson, "y", -1);
            int targetPlane = extractJsonInt(record.requestPayloadJson, "plane", localPoint.getPlane());
            if (targetX < 0 || targetY < 0) {
                return false;
            }
            int distance = Math.max(Math.abs(localPoint.getX() - targetX), Math.abs(localPoint.getY() - targetY));
            return distance <= 1 && localPoint.getPlane() == targetPlane;
        }

        if ("WALK_RELATIVE".equals(record.type)) {
            if (pre.localX < 0 || pre.localY < 0) {
                return false;
            }
            int distance = Math.max(Math.abs(localPoint.getX() - pre.localX), Math.abs(localPoint.getY() - pre.localY));
            return distance >= 1;
        }

        if ("WALKER_CANCEL".equals(record.type)) {
            WalkerPath path = GameManager.getWalkerPath();
            return path == null || path.isCanceled() || path.isDone();
        }

        if ("WALKER_STEP".equals(record.type)) {
            WalkerPath path = GameManager.getWalkerPath();
            if (path == null) {
                return true;
            }
            int currentSteps = path.getSteps() == null ? 0 : path.getSteps().size();
            return path.isDone() || currentSteps < pre.walkerSteps || MovementAPI.isMoving();
        }

        return false;
    }

    private String findCombatBlockingMessage(List<RecentMessageStore.RecentMessage> messages, long submittedTick) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            RecentMessageStore.RecentMessage message = messages.get(i);
            if (message.getTick() < submittedTick) {
                continue;
            }
            String text = message.getText();
            if (text == null) {
                continue;
            }
            String lower = text.toLowerCase();
            if (lower.contains("someone else is fighting that")
                    || lower.contains("already under attack")
                    || lower.contains("already in combat")) {
                return text;
            }
        }
        return null;
    }

    private String buildEvidenceJson(ActionRecord record, PreExecutionSnapshot pre) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();

        json.key("recentMessages").startArray();
        List<RecentMessageStore.RecentMessage> messages = RecentMessageStore.getLast(EVIDENCE_MESSAGE_LIMIT);
        for (RecentMessageStore.RecentMessage message : messages) {
            json.startObject();
            json.field("tick", message.getTick());
            json.field("type", message.getType());
            json.field("sender", message.getSender());
            json.field("text", message.getText());
            json.endObject();
        }
        json.endArray();

        json.key("stateHints").startObject();
        PlayerEx local = PlayerEx.getLocal();
        if (local != null) {
            WorldPoint localPoint = local.getWorldPoint();
            if (localPoint != null) {
                json.fieldRaw("localPosition", JsonBuilder.position(localPoint.getX(), localPoint.getY(), localPoint.getPlane()));
            } else {
                json.fieldNull("localPosition");
            }
            ActorEx<?> interacting = local.getInteracting();
            if (interacting != null) {
                json.field("localInteractingIndex", interacting.getIndex());
            } else {
                json.fieldNull("localInteractingIndex");
            }
            ActorEx<?> inCombatWith = local.getInCombatWith();
            if (inCombatWith != null) {
                json.field("localCombatWithIndex", inCombatWith.getIndex());
            } else {
                json.fieldNull("localCombatWithIndex");
            }
        } else {
            json.fieldNull("localPosition");
            json.fieldNull("localInteractingIndex");
            json.fieldNull("localCombatWithIndex");
        }

        if (pre != null) {
            json.fieldRaw("preLocalPosition", pre.localX >= 0
                    ? JsonBuilder.position(pre.localX, pre.localY, pre.localPlane)
                    : "null");
            json.field("preWalkerSteps", pre.walkerSteps);
            json.field("preInventoryCount", pre.inventoryCount);
            json.field("preEquipmentCount", pre.equipmentCount);
        } else {
            json.fieldNull("preLocalPosition");
            json.fieldNull("preWalkerSteps");
            json.fieldNull("preInventoryCount");
            json.fieldNull("preEquipmentCount");
        }

        WorldPoint destination = MovementAPI.getDestinationWorldPoint();
        if (destination != null) {
            json.fieldRaw("movementDestination", JsonBuilder.position(destination.getX(), destination.getY(), destination.getPlane()));
        } else {
            json.fieldNull("movementDestination");
        }

        WalkerPath path = GameManager.getWalkerPath();
        if (path != null) {
            json.field("walkerDone", path.isDone());
            json.field("walkerCanceled", path.isCanceled());
            json.field("walkerSteps", path.getSteps() == null ? 0 : path.getSteps().size());
        } else {
            json.fieldNull("walkerDone");
            json.fieldNull("walkerCanceled");
            json.fieldNull("walkerSteps");
        }

        json.endObject();
        json.endObject();
        return json.toString();
    }

    private void cleanupOldEntries() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<String, IdempotencyEntry>> idemIterator = idempotencyMap.entrySet().iterator();
        while (idemIterator.hasNext()) {
            Map.Entry<String, IdempotencyEntry> entry = idemIterator.next();
            if ((now - entry.getValue().createdAtMs) > IDEMPOTENCY_TTL_MS) {
                idemIterator.remove();
            }
        }

        Iterator<String> orderIterator = actionOrder.iterator();
        while (orderIterator.hasNext()) {
            String actionId = orderIterator.next();
            ActionRecord record = actionsById.get(actionId);
            if (record == null) {
                orderIterator.remove();
                continue;
            }
            if ((now - record.createdAtMs) > ACTION_TTL_MS && record.status.isTerminal()) {
                actionsById.remove(actionId);
                orderIterator.remove();
            }
        }
    }

    private void trimActionBacklog() {
        while (actionOrder.size() > MAX_ACTIONS) {
            String oldest = actionOrder.pollFirst();
            if (oldest == null) {
                return;
            }
            actionsById.remove(oldest);
        }
    }

    private void publish(ActionRecord record) {
        String eventType = record.status.isTerminal() ? "action.terminal" : "action.update";
        String payload = "id: " + record.actionId + ":" + record.version + "\n"
                + "event: " + eventType + "\n"
                + "data: " + record.toJson() + "\n\n";

        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        for (SseClient client : sseClients) {
            try {
                client.outputStream.write(bytes);
                client.outputStream.flush();
            } catch (IOException e) {
                removeSseClient(client);
            }
        }
    }

    private void sendHeartbeat() {
        if (sseClients.isEmpty()) {
            return;
        }

        String payload = "event: heartbeat\n"
                + "data: {\"ts\":" + System.currentTimeMillis() + "}\n\n";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        for (SseClient client : sseClients) {
            try {
                client.outputStream.write(bytes);
                client.outputStream.flush();
            } catch (IOException e) {
                removeSseClient(client);
            }
        }
    }

    private void removeSseClient(SseClient client) {
        sseClients.remove(client);
        try {
            client.outputStream.close();
        } catch (IOException ignored) {
        }
    }

    private boolean isSuccess(String json) {
        return json != null && json.contains("\"success\":true");
    }

    private String buildActionId() {
        return "act_" + Long.toHexString(System.currentTimeMillis()) + "_" + sequence.getAndIncrement();
    }

    private String buildAcceptedJson(String actionId, long submittedTick) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("accepted", true);
        json.field("actionId", actionId);
        json.field("submittedTick", submittedTick);
        json.endObject();
        return json.toString();
    }

    private String buildConflictJson(String idempotencyKey) {
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("error", true);
        json.field("code", 409);
        json.field("reasonCode", ReasonCode.IDEMPOTENCY_CONFLICT.name());
        json.field("message", "Idempotency key already used with different request payload");
        json.field("idempotencyKey", idempotencyKey);
        json.endObject();
        return json.toString();
    }

    private String sanitizePayload(String requestBody) {
        if (requestBody == null) {
            return "";
        }
        String trimmed = requestBody.trim();
        if (trimmed.length() > 1000) {
            return trimmed.substring(0, 1000);
        }
        return trimmed;
    }

    private String toPayloadJson(String normalizedBody) {
        if (normalizedBody == null || normalizedBody.isEmpty()) {
            return "{}";
        }
        if ((normalizedBody.startsWith("{") && normalizedBody.endsWith("}"))
                || (normalizedBody.startsWith("[") && normalizedBody.endsWith("]"))) {
            return normalizedBody;
        }
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("raw", normalizedBody);
        json.endObject();
        return json.toString();
    }

    private String normalizeTargetInfo(String targetInfoJson) {
        if (targetInfoJson == null || targetInfoJson.trim().isEmpty()) {
            return "{}";
        }
        String trimmed = targetInfoJson.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        JsonBuilder json = new JsonBuilder();
        json.startObject();
        json.field("raw", trimmed);
        json.endObject();
        return json.toString();
    }

    private String computeFingerprint(String type, String endpoint, String body) {
        return type + "|" + endpoint + "|" + body;
    }

    private static boolean isValidTransition(ActionStatus from, ActionStatus to) {
        if (from == to) {
            return true;
        }
        if (from.isTerminal()) {
            return false;
        }
        switch (from) {
            case ACCEPTED:
                return to == ActionStatus.QUEUED || to == ActionStatus.EXECUTING || to == ActionStatus.CANCELED || to == ActionStatus.EXPIRED;
            case QUEUED:
                return to == ActionStatus.EXECUTING || to == ActionStatus.CANCELED || to == ActionStatus.EXPIRED;
            case EXECUTING:
                return to == ActionStatus.WAITING_CONDITION || to == ActionStatus.SUCCEEDED || to == ActionStatus.FAILED || to == ActionStatus.CANCELED || to == ActionStatus.EXPIRED;
            case WAITING_CONDITION:
                return to == ActionStatus.SUCCEEDED || to == ActionStatus.FAILED || to == ActionStatus.CANCELED || to == ActionStatus.EXPIRED;
            default:
                return false;
        }
    }

    private int extractJsonInt(String json, String key, int defaultValue) {
        String value = extractJsonValue(json, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String extractJsonString(String json, String key) {
        return extractJsonValue(json, key);
    }

    private Integer parseOptionalJsonInt(String json, String key) {
        String value = extractJsonValue(json, key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractJsonValue(String json, String key) {
        if (json == null || json.isEmpty()) {
            return null;
        }

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
            int valueEnd = valueStart + 1;
            while (valueEnd < json.length()) {
                char c = json.charAt(valueEnd);
                if (c == '"' && json.charAt(valueEnd - 1) != '\\') {
                    break;
                }
                valueEnd++;
            }
            if (valueEnd >= json.length()) {
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

        return json.substring(valueStart, valueEnd);
    }

    private ReasonCode mapReason(int code, String message) {
        String msg = message == null ? "" : message.toLowerCase();

        if (msg.contains("someone else is fighting that")
                || msg.contains("under attack")
                || msg.contains("already in combat")) {
            return ReasonCode.ALREADY_IN_COMBAT;
        }
        if (msg.contains("out of range") || msg.contains("too far")) {
            return ReasonCode.OUT_OF_RANGE;
        }
        if (msg.contains("path") && msg.contains("block")) {
            return ReasonCode.PATH_BLOCKED;
        }
        if (msg.contains("moving")) {
            return ReasonCode.STILL_MOVING;
        }
        if (msg.contains("dialogue") || msg.contains("interface") || msg.contains("widget")) {
            return ReasonCode.INTERFACE_BLOCKED;
        }
        if (msg.contains("interrupt")) {
            return ReasonCode.INTERRUPTED;
        }
        if (msg.contains("rate") && msg.contains("limit")) {
            return ReasonCode.RATE_LIMITED;
        }
        if (msg.contains("bank is not open") || msg.contains("bank") && msg.contains("open")) {
            return ReasonCode.INTERFACE_BLOCKED;
        }
        if (msg.contains("item not found in bank")) {
            return ReasonCode.INVALID_TARGET;
        }

        if (code == 404) {
            return ReasonCode.INVALID_TARGET;
        }
        if (code == 400) {
            return ReasonCode.MENU_ACTION_UNAVAILABLE;
        }
        if (code == 408) {
            return ReasonCode.TIMEOUT;
        }
        if (code == 429) {
            return ReasonCode.RATE_LIMITED;
        }

        return ReasonCode.UNKNOWN_ERROR;
    }

    private boolean isRetryable(ReasonCode code) {
        return code == ReasonCode.TIMEOUT
                || code == ReasonCode.RATE_LIMITED
                || code == ReasonCode.PATH_BLOCKED
                || code == ReasonCode.STILL_MOVING
                || code == ReasonCode.INTERRUPTED
                || code == ReasonCode.UNKNOWN_ERROR;
    }

    private void sleepQuietly(long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
