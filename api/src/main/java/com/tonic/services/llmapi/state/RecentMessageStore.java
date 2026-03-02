package com.tonic.services.llmapi.state;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class RecentMessageStore {
    private static final int MAX_MESSAGES = 50;
    private static final Deque<RecentMessage> RECENT_MESSAGES = new ArrayDeque<>();

    private RecentMessageStore() {
    }

    public static void recordSystemMessage(String type, String text, String sender, long tick) {
        String trimmedText = text == null ? "" : text.trim();
        if (trimmedText.isEmpty()) {
            return;
        }

        synchronized (RECENT_MESSAGES) {
            RECENT_MESSAGES.addLast(new RecentMessage(
                    type == null ? "UNKNOWN" : type,
                    sender == null ? "" : sender,
                    trimmedText,
                    tick
            ));

            while (RECENT_MESSAGES.size() > MAX_MESSAGES) {
                RECENT_MESSAGES.removeFirst();
            }
        }
    }

    public static void clear() {
        synchronized (RECENT_MESSAGES) {
            RECENT_MESSAGES.clear();
        }
    }

    public static List<RecentMessage> getLast(int count) {
        synchronized (RECENT_MESSAGES) {
            if (count <= 0 || RECENT_MESSAGES.isEmpty()) {
                return new ArrayList<>();
            }

            List<RecentMessage> snapshot = new ArrayList<>(RECENT_MESSAGES);
            int fromIndex = Math.max(0, snapshot.size() - count);
            return new ArrayList<>(snapshot.subList(fromIndex, snapshot.size()));
        }
    }

    public static final class RecentMessage {
        private final String type;
        private final String sender;
        private final String text;
        private final long tick;

        public RecentMessage(String type, String sender, String text, long tick) {
            this.type = type;
            this.sender = sender;
            this.text = text;
            this.tick = tick;
        }

        public String getType() {
            return type;
        }

        public String getSender() {
            return sender;
        }

        public String getText() {
            return text;
        }

        public long getTick() {
            return tick;
        }
    }
}
