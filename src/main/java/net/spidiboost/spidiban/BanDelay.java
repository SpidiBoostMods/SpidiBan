package net.spidiboost.spidiban;

import java.math.BigDecimal;

final class BanDelay {
    private long previous;
    private boolean completed;
    private long interval;

    BanDelay(double seconds) {
        set(seconds);
    }

    static double parse(String input) {
        String value = input.trim().replace(',', '.');
        if (!value.matches("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) throw new IllegalArgumentException();
        double seconds = Double.parseDouble(value);
        validate(seconds);
        return seconds;
    }

    static void validate(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0 || seconds > 3600) throw new IllegalArgumentException();
    }

    void set(double seconds) {
        validate(seconds);
        interval = (long) Math.ceil(seconds * 1_000_000_000D) + 10_000_000L;
    }

    void reset() {
        completed = false;
    }

    void complete(long confirmationTime) {
        previous = confirmationTime;
        completed = true;
    }

    boolean ready(long now) {
        return !completed || now - previous >= interval;
    }

    static String describe(double seconds) {
        return BigDecimal.valueOf(seconds).stripTrailingZeros().toPlainString() + " сек";
    }
}
