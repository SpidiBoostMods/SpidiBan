package net.spidiboost.spidiban;

import java.util.regex.Pattern;

record BanEvidence(Kind kind, String player, String administrator) {
    enum Kind { ISSUED, ALREADY_BANNED, MISSING }

    private static final String NICK = "([A-Za-z0-9_]{1,16})";
    private static final String PREFIX = "^(?:\\[[^]\\r\\n]{1,80}]\\s*)*";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern ISSUED = Pattern.compile(PREFIX + "Администратор\\s+" + NICK
            + "\\s+(?:временно\\s+)?забанил\\s+игрока\\s+" + NICK
            + "\\s+(?:(?:на\\s+.+?|навсегда)\\s+)?по\\s+причине\\s+['\"«].+['\"»]\\s*[.!]?$", FLAGS);
    private static final Pattern ALREADY = Pattern.compile(PREFIX + "Игрок\\s+" + NICK
            + "\\s+уже\\s+забанен\\s+и\\s+у\\s+вас\\s+нету\\s+прав\\s+на\\s+новый\\s+бан[.!]?$", FLAGS);
    private static final Pattern MISSING = Pattern.compile(PREFIX + "(?:PLAYER DOES NOT EXIST|Ошибка:\\s*Игрок не найден)[.!]?$", FLAGS);

    static BanEvidence parse(String message) {
        if (message == null || message.length() > 16384) return null;
        String clean = message.replaceAll("(?i)§[0-9A-FK-ORX]", "")
                .replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]", "")
                .replaceAll("(?U)\\s+", " ").trim();
        var issued = ISSUED.matcher(clean);
        if (issued.matches()) return new BanEvidence(Kind.ISSUED, issued.group(2), issued.group(1));
        var already = ALREADY.matcher(clean);
        if (already.matches()) return new BanEvidence(Kind.ALREADY_BANNED, already.group(1), "");
        if (MISSING.matcher(clean).matches()) return new BanEvidence(Kind.MISSING, "", "");
        return null;
    }

    boolean forPlayer(String nickname) {
        return player.isEmpty() || player.equalsIgnoreCase(nickname);
    }
}
