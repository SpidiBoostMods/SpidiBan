package net.spidiboost.shist;

import java.util.Locale;

/** Null means another mod owns the input. Malformed local commands never reach the server. */
public record ShistCommand(String nickname, String mode, Integer count, String action, int speed) {
    public static final String USAGE = "/sb shist <ник> <ban|mute> [количество: 1–10000]";
    public static ShistCommand parse(String input) {
        if (input == null) return null;
        String text = input.strip();
        if (text.startsWith("/")) text = text.substring(1);
        String[] parts = text.split("\\s+");
        if (parts.length < 2 || !(parts[0].equalsIgnoreCase("sb") || parts[0].equalsIgnoreCase("spidiban")
                || parts[0].equalsIgnoreCase("spidiboost")) || !parts[1].equalsIgnoreCase("shist")) return null;
        if (parts.length == 3 && (parts[2].equalsIgnoreCase("status") || parts[2].equalsIgnoreCase("cancel")
                || parts[2].equalsIgnoreCase("retry"))) return new ShistCommand(null, null, null, parts[2].toLowerCase(Locale.ROOT), 0);
        if (parts.length == 4 && parts[2].equalsIgnoreCase("speed")
                && !parts[3].equalsIgnoreCase("ban") && !parts[3].equalsIgnoreCase("mute")) {
            return new ShistCommand(null, null, null, "speed", integer(parts[3], 1, 1000));
        }
        if ((parts.length != 4 && parts.length != 5) || !ShistEngine.validName(parts[2])
                || !(parts[3].equalsIgnoreCase("ban") || parts[3].equalsIgnoreCase("mute"))) throw new IllegalArgumentException(USAGE);
        return new ShistCommand(parts[2], parts[3].toLowerCase(Locale.ROOT),
                parts.length == 5 ? integer(parts[4], 1, 10000) : null, null, 0);
    }
    private static int integer(String value, int min, int max) {
        try { int result = Integer.parseInt(value); if (result >= min && result <= max) return result; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Число должно быть " + min + "–" + max + ". " + USAGE);
    }
}
