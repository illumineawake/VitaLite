package com.tonic.services.llmapi.state;

import com.tonic.data.wrappers.ItemEx;
import com.tonic.services.GameManager;
import com.tonic.services.llmapi.util.JsonBuilder;

import java.util.ArrayList;
import java.util.List;

public final class BankStateStore {
    private static final Object LOCK = new Object();
    private static BankSnapshot lastSnapshot;

    private BankStateStore() {
    }

    public static void clear() {
        synchronized (LOCK) {
            lastSnapshot = null;
        }
    }

    public static void updateFromItems(List<ItemEx> items) {
        List<BankItemSnapshot> snapshots = new ArrayList<>();
        if (items != null) {
            for (ItemEx item : items) {
                if (item == null || item.getId() <= 0) {
                    continue;
                }
                snapshots.add(new BankItemSnapshot(
                        item.getId(),
                        item.getName(),
                        item.getSlot(),
                        item.getQuantity()
                ));
            }
        }

        synchronized (LOCK) {
            lastSnapshot = new BankSnapshot(
                    snapshots,
                    GameManager.getTickCount(),
                    System.currentTimeMillis()
            );
        }
    }

    public static BankSnapshot getSnapshot() {
        synchronized (LOCK) {
            if (lastSnapshot == null) {
                return null;
            }
            return lastSnapshot.copy();
        }
    }

    public static final class BankSnapshot {
        private final List<BankItemSnapshot> items;
        private final long lastUpdatedTick;
        private final long lastUpdatedAtMs;

        private BankSnapshot(List<BankItemSnapshot> items, long lastUpdatedTick, long lastUpdatedAtMs) {
            this.items = items;
            this.lastUpdatedTick = lastUpdatedTick;
            this.lastUpdatedAtMs = lastUpdatedAtMs;
        }

        public List<BankItemSnapshot> getItems() {
            return items;
        }

        public long getLastUpdatedTick() {
            return lastUpdatedTick;
        }

        public long getLastUpdatedAtMs() {
            return lastUpdatedAtMs;
        }

        public BankSnapshot copy() {
            List<BankItemSnapshot> copied = new ArrayList<>();
            for (BankItemSnapshot item : items) {
                copied.add(item.copy());
            }
            return new BankSnapshot(copied, lastUpdatedTick, lastUpdatedAtMs);
        }
    }

    public static final class BankItemSnapshot {
        private final int id;
        private final String name;
        private final int slot;
        private final int quantity;

        private BankItemSnapshot(int id, String name, int slot, int quantity) {
            this.id = id;
            this.name = name;
            this.slot = slot;
            this.quantity = quantity;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public int getSlot() {
            return slot;
        }

        public int getQuantity() {
            return quantity;
        }

        public String toJsonBrief() {
            JsonBuilder json = new JsonBuilder();
            json.startObject();
            json.field("id", id);
            json.field("name", name);
            json.field("slot", slot);
            json.field("quantity", quantity);
            json.endObject();
            return json.toString();
        }

        public BankItemSnapshot copy() {
            return new BankItemSnapshot(id, name, slot, quantity);
        }
    }
}
