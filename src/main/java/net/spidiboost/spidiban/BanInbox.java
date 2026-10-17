package net.spidiboost.spidiban;

import java.util.ArrayDeque;

final class BanInbox {
    record Delivery(Object connection, BanEvidence evidence, long receivedAt) { }

    private final ArrayDeque<Delivery> messages = new ArrayDeque<>();
    private boolean enabled;
    private boolean overflow;

    synchronized void start() {
        messages.clear();
        overflow = false;
        enabled = true;
    }

    synchronized void stop() {
        enabled = false;
        messages.clear();
        overflow = false;
    }

    synchronized void capture(Object connection, String message, long now) {
        if (!enabled) return;
        BanEvidence evidence = BanEvidence.parse(message);
        if (evidence == null) return;
        if (messages.size() >= 256) {
            overflow = true;
            return;
        }
        messages.add(new Delivery(connection, evidence, now));
    }

    synchronized Delivery poll() { return messages.poll(); }
    synchronized boolean overflowed() { return overflow; }
}
