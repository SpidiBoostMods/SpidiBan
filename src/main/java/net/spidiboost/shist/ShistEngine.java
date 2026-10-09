package net.spidiboost.shist;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minecraft-independent protocol state machine. All calls belong to one client thread. */
public final class ShistEngine {
    public interface Sink {
        void send(String command);
        void log(String message);
        void history(String line);
        void feedback(String message);
        void complete(Result result);
        default void interrupted(Result result) { }
    }
    public record Result(List<String> candidates, List<String> accepted, List<String> unresolved, List<String> unclearHistory,
                         Map<String, String> decisions, int entries, int expected, boolean historyIncomplete) { }
    private enum Phase { IDLE, HISTORY, CHECKS }
    private enum Status { QUEUED, PENDING, ACCEPTED, REJECTED, UNRESOLVED }
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern HEADER = Pattern.compile("^история персонала\\s+([A-Za-z0-9_]{1,16})(?:\\s|$)", FLAGS);
    private static final Pattern LIMIT = Pattern.compile("лимит\\s*:\\s*(\\d+)", FLAGS);
    private static final Pattern DATE = Pattern.compile("^[-—–]+\\s*\\[\\d{4}-\\d{2}-\\d{2}[^]]*]\\s*[-—–]*$");
    private static final Pattern ENTRY = Pattern.compile("^([A-Za-z0-9_]{1,16})\\s+был\\s+(забанен|заткнут)(?:\\s|$)", FLAGS);
    private static final Pattern REVERSAL = Pattern.compile("^([A-Za-z0-9_]{1,16})\\s+was (?:unbanned|unmuted) by\\s+.+", FLAGS);
    private static final Pattern STATUS = Pattern.compile("\\[\\s*(активный|ист[её]к|снят|неактивный)\\s*]\\s*$", FLAGS);
    private static final Pattern TARGET = Pattern.compile("^(?:цель|target)\\s+(?:\\[([A-Za-z0-9_]{1,16})]|([A-Za-z0-9_]{1,16})(?=\\s+(?:забан|затк|замуч|замут|не |is |has )))", FLAGS);
    private static final Pattern NAMED_NEGATIVE = Pattern.compile("^([A-Za-z0-9_]{1,16})\\s+(?:is not (?:banned|muted)|не (?:забанен|заткнут|замучен|замутен))", FLAGS);
    private static final Pattern REASON = Pattern.compile("^(?:по причине|причина|reason)\\s*:\\s*(.*)$", FLAGS);
    private static final Pattern ENDING = Pattern.compile("^(?:окончание\\s+(?:бана|мута|мьюта)|(?:ban|mute) (?:expires|ends)|(?:banned|muted) until|expires)\\s*:\\s*(.*)$", FLAGS);
    private static final Pattern PERMANENT = Pattern.compile("(?:навсегда|permanent)\\s*:\\s*(yes|no|да|нет|true|false)(?=\\W|$)", FLAGS);
    private static final Pattern FOREVER_VALUE = Pattern.compile("(?:^|\\()\\s*(?:навсегда|permanent|forever)(?:\\s|\\)|$)", FLAGS);
    private static final Pattern TIME = Pattern.compile("^\\[\\d{1,2}:\\d{2}:\\d{2}]\\s*");
    private static final Pattern FORMAT = Pattern.compile("§[0-9a-fk-orx]", FLAGS);
    private static final Pattern EXCLUDED = Pattern.compile("попытк[а-яё]*взлом|чсп|пиар[а-яё]*чит|некорректн[а-яё]*(?:никнейм|ник)|(?:нек+|nek+)(?:ник(?:нейм)?|nick(?:name)?|nik)");

    private final Sink sink;
    private final LongSupplier clock;
    private Phase phase = Phase.IDLE;
    private String owner, mode, date = "";
    // Real server silently drops bursts and can disconnect without a throttle message.
    // Only an acknowledged request (or its timeout) opens the next slot.
    public static final int MAX_CHECK_RATE = 8;
    private int expected, entries, rate = MAX_CHECK_RATE, sendCount;
    private long startAt, lastHistoryAt, nextSendAt, pauseUntil, lastProgressAt;
    private long lastCheckSentAt = -1000000;
    private double latency = 250;
    private boolean headerSeen, historyIncomplete;
    private HistoryEntry entry;
    private final Set<String> records = new HashSet<>();
    private final LinkedHashSet<String> unclearHistory = new LinkedHashSet<>();
    private final LinkedHashMap<String, Check> checks = new LinkedHashMap<>();
    private final ArrayDeque<Check> queue = new ArrayDeque<>();
    private final LinkedHashMap<String, Check> pending = new LinkedHashMap<>();
    private Check response;
    private boolean readingReason;
    private final Thread thread = Thread.currentThread();

    public ShistEngine(Sink sink, LongSupplier clock) { this.sink = sink; this.clock = clock; }
    private void assertThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Shist state used outside client thread");
    }
    public boolean active() { return phase != Phase.IDLE; }
    public void speed(int value) {
        assertThread();
        if (value < 1 || value > 1000) throw new IllegalArgumentException("speed must be 1..1000");
        rate = Math.min(value, MAX_CHECK_RATE);
        nextSendAt = Math.max(nextSendAt, lastCheckSentAt + sendIntervalMs());
        sink.feedback("Скорость проверок: до " + rate + " команд/сек, по одному ответу."
                + (value > MAX_CHECK_RATE ? " Залпы отключены: сервер пропускает ответы и отключает соединение." : ""));
    }
    public void start(String owner, String mode, Integer count) {
        assertThread();
        if (!validName(owner) || !(mode.equals("ban") || mode.equals("mute"))
                || (count != null && (count < 1 || count > 10000))) throw new IllegalArgumentException("invalid shist command");
        if (active()) { sink.feedback("Сбор уже идёт. /sb shist status; остановить: /sb shist cancel"); return; }
        this.owner = owner; this.mode = mode; expected = count == null ? 0 : count;
        entries = 0; date = ""; headerSeen = false; historyIncomplete = false;
        records.clear(); unclearHistory.clear(); checks.clear(); queue.clear(); pending.clear(); response = null; entry = null;
        latency = 250; sendCount = 0; pauseUntil = 0;
        startAt = lastHistoryAt = lastProgressAt = clock.getAsLong();
        phase = Phase.HISTORY;
        String command = "shist " + owner + " " + mode + (count == null ? "" : " " + count);
        sink.log("START /" + command + " rate=" + rate);
        sink.feedback("Собираю /" + command + "…");
        sink.send(command);
    }
    public void accept(String raw) {
        assertThread();
        if (!active() || raw == null) return;
        sink.log("RX phase=" + phase + " raw=" + raw.replace("\r", "\\r").replace("\n", "\\n"));
        // Packet newlines are semantic lines, not visual chat wrapping.
        for (String part : raw.split("\\R", -1)) {
            String line = clean(part);
            if (line.isEmpty()) continue;
            if (phase == Phase.HISTORY) acceptHistory(line);
            else if (phase == Phase.CHECKS) acceptCheck(line);
        }
    }
    private void acceptHistory(String line) {
        Matcher header = HEADER.matcher(line);
        if (header.find()) {
            if (!header.group(1).equalsIgnoreCase(owner)) return;
            headerSeen = true;
            Matcher limit = LIMIT.matcher(line);
            if (limit.find()) expected = Math.min(10000, Integer.parseInt(limit.group(1)));
            recordHistory(line);
            sink.log("HISTORY HEADER expected=" + expected);
            return;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.matches(".*(?:нет|не найдено) (?:истории|записей|наказаний).*") && !line.contains("»")) {
            headerSeen = true; expected = 0; recordHistory(line); finishHistory(); return;
        }
        if (!headerSeen) {
            if (isPermissionError(lower)) abort("Сервер отклонил /shist: " + line);
            return;
        }
        if (DATE.matcher(line).matches()) {
            finishEntry(); date = line; recordHistory(line); return;
        }
        if (REVERSAL.matcher(line).matches()) {
            finishEntry();
            if (records.add(date + "\n" + line.toLowerCase(Locale.ROOT))) entries++;
            recordHistory(line); return;
        }
        Matcher match = ENTRY.matcher(line);
        if (match.find()) {
            finishEntry();
            boolean matchesMode = mode.equals("ban") == match.group(2).equalsIgnoreCase("забанен");
            entry = new HistoryEntry(match.group(1), date, matchesMode);
            entry.lines.add(line); recordHistory(line); return;
        }
        Matcher status = STATUS.matcher(line);
        if (entry != null && (REASON.matcher(line).matches() || status.find()
                || lower.startsWith("окончание"))) {
            entry.lines.add(line);
            Matcher marker = STATUS.matcher(line);
            if (marker.find()) {
                boolean active = marker.group(1).equalsIgnoreCase("активный");
                if (entry.statusSeen && entry.active != active) entry.ambiguous = true;
                entry.active = active; entry.statusSeen = true;
            }
            recordHistory(line);
            if (lower.startsWith("окончание")) finishEntry();
        } else if (entry != null && entry.statusSeen && entry.lines.getLast().startsWith("По причине:")
                && !line.contains("»")) {
            // Actual wrapped reason packets are retained; unrelated chat never extends the wait.
            sink.log("HISTORY OTHER " + line);
        }
    }
    private void recordHistory(String line) { lastHistoryAt = clock.getAsLong(); sink.history(line); }
    private void finishEntry() {
        if (entry == null) return;
        String signature = entry.date + "\n" + String.join("\n", entry.lines).toLowerCase(Locale.ROOT);
        if (records.add(signature)) {
            entries++;
            if (entry.matchesMode && (!entry.statusSeen || entry.ambiguous)) unclearHistory.add(entry.name);
            if (entry.matchesMode && entry.active && !entry.ambiguous) {
                checks.computeIfAbsent(key(entry.name), ignored -> new Check(entry.name));
            }
            sink.log("HISTORY ENTRY nick=" + entry.name + " active=" + entry.active
                    + " statusSeen=" + entry.statusSeen + " entries=" + entries + " candidates=" + checks.size());
        } else sink.log("HISTORY DUPLICATE nick=" + entry.name);
        entry = null;
    }
    private void finishHistory() {
        if (phase != Phase.HISTORY) return;
        finishEntry();
        historyIncomplete = expected > 0 && entries < expected;
        sink.log("HISTORY END entries=" + entries + "/" + expected + " candidates=" + checks.size()
                + " incomplete=" + historyIncomplete);
        phase = Phase.CHECKS;
        queue.addAll(checks.values()); nextSendAt = Math.max(clock.getAsLong(), lastCheckSentAt + sendIntervalMs());
        sink.feedback("История: " + entries + " записей, " + checks.size() + " активных ников. Проверяю…");
        if (checks.isEmpty()) complete();
    }
    private void acceptCheck(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        Matcher header = TARGET.matcher(line);
        Matcher namedNegative = NAMED_NEGATIVE.matcher(line);
        boolean hasHeader = header.find();
        boolean hasNamedNegative = namedNegative.find() && !namedNegative.group(1).equalsIgnoreCase("target");
        if (hasHeader || hasNamedNegative) {
            String name = hasHeader ? (header.group(1) == null ? header.group(2) : header.group(1))
                    : namedNegative.group(1);
            response = pending.get(key(name)); readingReason = false;
            if ((mode.equals("ban") && (lower.contains("заткнут") || lower.contains("muted") || lower.contains("замучен")))
                    || (mode.equals("mute") && (lower.contains("забанен") || lower.contains("banned")))) response = null;
            sink.log("CHECK HEADER nick=" + name + " pending=" + (response != null));
            if (response != null) {
                response.lastLineAt = clock.getAsLong(); response.lines.add(line);
                if (isNegative(lower)) resolve(response, false, "not punished");
            }
            return;
        }
        if (isPermissionError(lower)) { abort("Сервер отклонил проверки: " + line); return; }
        if (isThrottle(lower)) {
            pauseUntil = clock.getAsLong() + 1500;
            rate = Math.max(1, rate / 2);
            for (Check check : pending.values()) check.sentAt = pauseUntil;
            sink.log("THROTTLE rate=" + rate + " inFlightLimit=1 line=" + line);
            return;
        }
        if (isNegative(lower) && (lower.startsWith("цель") || lower.startsWith("target"))) {
            response = null; readingReason = false;
            if (pending.size() == 1) resolve(pending.values().iterator().next(), false, "not punished");
            else sink.log("AMBIGUOUS NEGATIVE pending=" + pending.size() + "; retry serially");
            return;
        }
        if (response == null || response.status != Status.PENDING) return;
        Matcher reason = REASON.matcher(line);
        Matcher ending = ENDING.matcher(line);
        Matcher permanent = PERMANENT.matcher(line);
        boolean reasonLine = reason.matches(), endingLine = ending.matches();
        boolean flagsLine = !reasonLine && !endingLine && (lower.startsWith("ip ") || lower.startsWith("навсегда:")
                || lower.startsWith("permanent:") || lower.startsWith("тихий:")) && permanent.find();
        if (reasonLine) { response.reason = reason.group(1); response.reasonSeen = true; readingReason = true; }
        else if (endingLine) {
            String value = ending.group(1).toLowerCase(Locale.ROOT);
            response.endingSeen = !value.isBlank();
            response.forever = FOREVER_VALUE.matcher(value).find();
            readingReason = false;
        } else if (flagsLine) {
            boolean flag = Set.of("yes", "да", "true").contains(permanent.group(1).toLowerCase(Locale.ROOT));
            // A contradictory response must be retried, never silently classified.
            if (response.endingSeen && response.forever != flag) {
                response.conflict = true; sink.log("CHECK CONFLICT nick=" + response.name);
            } else if (!response.endingSeen) response.forever = flag;
            response.flagsSeen = true; readingReason = false;
        } else if (lower.startsWith("забанен:") || lower.startsWith("замучен:")
                || lower.startsWith("заткнут:") || lower.startsWith("забанил:")
                || lower.startsWith("замутил:") || lower.startsWith("заткнул:")
                || lower.startsWith("banned by:") || lower.startsWith("muted by:")
                || lower.startsWith("banned on:") || lower.startsWith("muted on:")
                || lower.startsWith("banned on server ") || lower.startsWith("muted on server ")) readingReason = false;
        else if (readingReason && !line.contains("»") && !line.contains("->") && !line.startsWith("[")
                && !lower.startsWith("администратор ") && !lower.startsWith("куратор ") && !lower.startsWith("модератор ")
                && !lower.startsWith("ac ") && !lower.startsWith("rwac ") && !lower.startsWith("история ")) {
            response.reason += " " + line;
        } else return;
        response.lines.add(line); response.lastLineAt = clock.getAsLong();
        sink.log("CHECK FIELD nick=" + response.name + " reason=" + response.reasonSeen + " ending="
                + response.endingSeen + " flags=" + response.flagsSeen + " line=" + line);
        if (flagsLine && ready(response)) finishResponse(response);
    }
    private boolean ready(Check check) {
        return !check.conflict && (check.endingSeen || check.flagsSeen)
                && (mode.equals("mute") || (check.reasonSeen && !check.reason.isBlank()));
    }
    private void finishResponse(Check check) {
        boolean excluded = mode.equals("ban") && excludedReason(check.reason);
        resolve(check, check.forever && !excluded, excluded ? "excluded reason: " + check.reason
                : check.forever ? "permanent" : "temporary");
    }
    private void resolve(Check check, boolean accepted, String decision) {
        if (check.status != Status.PENDING) return;
        check.status = accepted ? Status.ACCEPTED : Status.REJECTED; check.decision = decision;
        pending.remove(key(check.name));
        double sample = Math.max(1, clock.getAsLong() - check.sentAt);
        latency = latency * .8 + sample * .2;
        sink.log("CHECK END nick=" + check.name + " decision=" + decision + " attempts=" + check.attempts
                + " pending=" + pending.size() + " elapsedMs=" + (long)sample);
        if (response == check) { response = null; readingReason = false; }
    }
    public void tick() {
        assertThread();
        if (!active()) return;
        long now = clock.getAsLong();
        if (phase == Phase.HISTORY) {
            if (!headerSeen && now - startAt >= 10000) { abort("Нет ответа /shist за 10 секунд. Смотри журнал."); return; }
            long quiet = expected > 0 && entries < expected ? 1500 : 750;
            if (headerSeen && now - lastHistoryAt >= quiet) finishHistory();
        }
        if (phase != Phase.CHECKS) return;
        for (Check check : new ArrayList<>(pending.values())) {
            if (ready(check) && now - check.lastLineAt >= 150) finishResponse(check);
            else if (now - check.sentAt >= timeoutMs()) {
                pending.remove(key(check.name));
                if (response == check) { response = null; readingReason = false; }
                check.status = Status.UNRESOLVED; check.decision = "missing/incomplete/conflicting response";
                sink.log("CHECK TIMEOUT nick=" + check.name + " attempt=" + check.attempts + " lines=" + check.lines);
            }
        }
        if (queue.isEmpty() && pending.isEmpty()) {
            for (Check check : checks.values()) {
                if (check.status == Status.UNRESOLVED && check.attempts < 3) {
                    check.status = Status.QUEUED; queue.add(check);
                }
            }
            if (queue.isEmpty()) { complete(); return; }
            sink.log("SERIAL RETRY count=" + queue.size());
        }
        if (now < pauseUntil) return;
        if (pending.isEmpty() && !queue.isEmpty() && now >= nextSendAt) {
            Check check = queue.removeFirst();
            if (check.status != Status.QUEUED) throw new IllegalStateException("Non-queued check in send queue");
            check.reset(); check.status = Status.PENDING; check.attempts++; check.sentAt = check.lastLineAt = now;
            pending.put(key(check.name), check); sendCount++;
            String command = (mode.equals("ban") ? "checkban " : "checkmute ") + check.name;
            sink.log("SEND CHECK /" + command + " attempt=" + check.attempts + " pending=" + pending.size()
                    + " gapMs=" + (now-lastCheckSentAt) + " minGapMs=" + sendIntervalMs() + " rate=" + rate);
            // State is set before dispatch: even a synchronous response cannot corrupt the queue.
            lastCheckSentAt = now; nextSendAt = now + sendIntervalMs();
            sink.send(command);
        }
        if (now - lastProgressAt >= 2000) {
            lastProgressAt = now; sink.feedback(status());
            sink.log("PROGRESS " + status() + " sends=" + sendCount + " latencyMs=" + (long)latency);
        }
    }
    private long sendIntervalMs() { return (1000L + rate - 1) / rate; }
    private long timeoutMs() { return Math.max(1500, Math.min(8000, (long)(latency * 5 + 500))); }
    public String status() {
        int done = 0, accepted = 0;
        for (Check check : checks.values()) {
            if (check.status == Status.ACCEPTED || check.status == Status.REJECTED) done++;
            if (check.status == Status.ACCEPTED) accepted++;
        }
        return phase == Phase.HISTORY ? "Сбор истории: " + entries + "/" + (expected == 0 ? "?" : expected)
                : "Проверено " + done + "/" + checks.size() + "; вечных: " + accepted + "; в работе: " + pending.size();
    }
    public void retry() {
        assertThread();
        if (active()) { sink.feedback("Проверка уже идёт."); return; }
        queue.clear(); pending.clear(); response = null;
        for (Check check : checks.values()) if (check.status == Status.UNRESOLVED) {
            check.attempts = 0; check.status = Status.QUEUED; queue.add(check);
        }
        if (queue.isEmpty()) { sink.feedback("Непроверенных ников нет."); return; }
        phase = Phase.CHECKS; nextSendAt = Math.max(clock.getAsLong(), lastCheckSentAt + sendIntervalMs()); pauseUntil = 0;
        sink.log("MANUAL RETRY count=" + queue.size());
    }
    public void abort(String message) {
        assertThread();
        if (!active()) return;
        if (phase == Phase.HISTORY) { finishEntry(); historyIncomplete = true; }
        for (Check check : checks.values()) if (check.status == Status.QUEUED || check.status == Status.PENDING) {
            check.status = Status.UNRESOLVED; check.decision = "interrupted";
        }
        phase = Phase.IDLE; pending.clear(); queue.clear(); response = null;
        sink.log("ABORT " + message); sink.interrupted(snapshot()); sink.feedback(message);
    }
    private Result snapshot() {
        List<String> candidates = new ArrayList<>(), accepted = new ArrayList<>(), unresolved = new ArrayList<>();
        Map<String, String> decisions = new LinkedHashMap<>();
        for (Check check : checks.values()) {
            candidates.add(check.name); decisions.put(check.name, check.decision);
            if (check.status == Status.ACCEPTED) accepted.add(check.name);
            if (check.status == Status.UNRESOLVED) unresolved.add(check.name);
        }
        return new Result(List.copyOf(candidates), List.copyOf(accepted), List.copyOf(unresolved), List.copyOf(unclearHistory),
                Collections.unmodifiableMap(decisions), entries, expected, historyIncomplete);
    }
    private void complete() {
        phase = Phase.IDLE; response = null;
        Result result = snapshot();
        sink.log("COMPLETE entries=" + entries + " checked=" + (checks.size()-result.unresolved().size())
                + " accepted=" + result.accepted().size() + " unresolved=" + result.unresolved().size() + " durationMs=" + (clock.getAsLong()-startAt));
        sink.complete(result);
    }
    public static String clean(String text) {
        String result = FORMAT.matcher(text).replaceAll("").replace('\u00a0', ' ').replace('\u202f', ' ')
                .replace("\u200b", "").replace("\ufeff", "").strip();
        while (TIME.matcher(result).find()) result = TIME.matcher(result).replaceFirst("").strip();
        return result;
    }
    public static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_]{1,16}"); }
    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
    public static boolean excludedReason(String reason) {
        String lower = clean(reason).toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("(?U)[\\s\\p{P}\\p{S}]+", "");
        return EXCLUDED.matcher(compact).find();
    }
    private static boolean isNegative(String lower) {
        return lower.contains("не забанен") || lower.contains("не заткнут") || lower.contains("не замучен")
                || lower.contains("не замутен") || lower.contains("not banned") || lower.contains("not muted");
    }
    private static boolean isPermissionError(String lower) {
        return !lower.contains("»") && (lower.startsWith("нет прав") || lower.startsWith("недостаточно прав")
                || lower.startsWith("you do not have permission") || lower.startsWith("unknown command")
                || lower.startsWith("неизвестная команда"));
    }
    private static boolean isThrottle(String lower) {
        return !lower.contains("»") && (lower.startsWith("слишком быстро") || lower.startsWith("подождите")
                || lower.startsWith("too many commands") || lower.startsWith("please wait"));
    }
    private static final class HistoryEntry {
        final String name, date; final boolean matchesMode;
        boolean active, statusSeen, ambiguous; final List<String> lines = new ArrayList<>();
        HistoryEntry(String name, String date, boolean matchesMode) { this.name=name; this.date=date; this.matchesMode=matchesMode; }
    }
    private static final class Check {
        final String name; Status status = Status.QUEUED;
        int attempts; long sentAt, lastLineAt; boolean reasonSeen, endingSeen, flagsSeen, forever, conflict;
        String reason="", decision="not checked"; final List<String> lines = new ArrayList<>();
        Check(String name) { this.name = name; }
        void reset() { reasonSeen=endingSeen=flagsSeen=forever=conflict=false; reason=""; lines.clear(); }
    }
}
