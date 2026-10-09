package net.spidiboost.shist;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

/** Client-thread adapter with batched background file writing. */
public final class ShistSession {
    public static final String VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("spidiban").orElseThrow().getMetadata().getVersion().getFriendlyString();
    private static ShistEngine engine;
    private static Path directory, run;
    private static final AtomicInteger runCounter = new AtomicInteger();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SpidiShist-files"); thread.setDaemon(true); return thread;
    });
    private static final ConcurrentLinkedQueue<String> LOG = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> HISTORY = new ConcurrentLinkedQueue<>();
    private static volatile IOException logError;
    private static long lastFlush, lastHeartbeat;
    private static Object connection;
    private ShistSession() { }

    public static void init() {
        if (engine != null) return;
        directory = MinecraftClient.getInstance().runDirectory.toPath().resolve("spidiban-shist");
        engine = new ShistEngine(new ShistEngine.Sink() {
            public void send(String command) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.getNetworkHandler() == null) throw new IllegalStateException("Disconnected before command send");
                client.getNetworkHandler().sendChatCommand(command);
            }
            public void log(String message) { debug(message); }
            public void history(String line) { HISTORY.add(line); }
            public void feedback(String message) { ShistClient.feedback(message); }
            public void complete(ShistEngine.Result result) { finish(result, true); }
            public void interrupted(ShistEngine.Result result) { finish(result, false); }
        }, () -> System.nanoTime() / 1_000_000);
        ClientTickEvents.END_CLIENT_TICK.register(ShistSession::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (engine.active()) engine.abort("Клиент закрывается; сохранён промежуточный результат.");
            flush(); IO.shutdown();
            try { IO.awaitTermination(3, TimeUnit.SECONDS); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
    }
    public static void debug(String message) {
        LOG.add(LocalDateTime.now() + " [" + Thread.currentThread().getName() + "] " + message);
    }
    public static void start(String name, String mode, int count, boolean explicitCount) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.getNetworkHandler() == null) {
            ShistClient.feedback("Нужно подключиться к серверу."); return;
        }
        if (!engine.active()) {
            flush();
            run = directory.resolve(LocalDateTime.now().toString().replace(':', '-') + "-" + runCounter.incrementAndGet());
            connection = client.getNetworkHandler();
            debug("VERSION=" + VERSION + " SESSION=" + run.getFileName());
        }
        engine.start(name, mode, explicitCount ? count : null);
    }
    public static void start(String name, String mode, int count) { start(name, mode, count, true); }
    public static void accept(String raw) {
        if (engine == null || !engine.active()) return;
        if (!MinecraftClient.getInstance().isOnThread()) throw new IllegalStateException("Packet capture must be on client thread");
        try { engine.accept(raw); }
        catch (RuntimeException error) {
            debug("PACKET ERROR " + error + " " + java.util.Arrays.toString(error.getStackTrace()));
            engine.abort("Ошибка разбора ответа: " + error.getClass().getSimpleName() + ". Смотри журнал.");
            flush();
        }
    }
    public static void control(String action, int speed) {
        switch (action) {
            case "status" -> ShistClient.feedback(engine.status());
            case "cancel" -> { engine.abort("Сбор остановлен. Подтверждённые решения сохранены в журнале."); flush(); }
            case "retry" -> { connection = MinecraftClient.getInstance().getNetworkHandler(); engine.retry(); }
            case "speed" -> engine.speed(speed);
            default -> throw new IllegalArgumentException(action);
        }
    }
    private static void tick(MinecraftClient client) {
        if (engine.active()) {
            if (client.getNetworkHandler() != connection || client.player == null) {
                engine.abort("Соединение изменилось. Непроверенные ники: /sb shist retry после подключения.");
            } else {
                try { engine.tick(); }
                catch (Exception error) {
                    debug("ERROR " + error + " " + java.util.Arrays.toString(error.getStackTrace()));
                    engine.abort("Проверка прервана: " + error.getClass().getSimpleName() + ". Смотри журнал.");
                }
            }
            long now = System.nanoTime() / 1_000_000;
            if (now - lastHeartbeat >= 2000) { lastHeartbeat = now; debug("STATE " + engine.status()); }
        }
        long now = System.nanoTime() / 1_000_000;
        if (now - lastFlush >= 250) { lastFlush = now; flush(); }
        if (logError != null) {
            ShistClient.feedback("Ошибка записи журнала: " + logError.getMessage()); logError = null;
        }
    }
    private static void flush() {
        if (LOG.isEmpty() && HISTORY.isEmpty()) return;
        StringBuilder log = new StringBuilder(), history = new StringBuilder();
        String line;
        while ((line = LOG.poll()) != null) log.append(line).append('\n');
        while ((line = HISTORY.poll()) != null) history.append(line).append('\n');
        Path destination = run == null ? directory : run;
        IO.execute(() -> {
            try {
                Files.createDirectories(destination);
                if (!log.isEmpty()) append(destination.resolve("debug.log"), log.toString());
                if (!history.isEmpty()) append(destination.resolve("history.txt"), history.toString());
            } catch (IOException error) { logError = error; }
        });
    }
    private static void append(Path path, String value) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) { writer.write(value); }
    }
    private static void finish(ShistEngine.Result result, boolean copy) {
        String names = String.join(" ", result.accepted());
        Path destination = run;
        flush();
        IO.execute(() -> {
            try {
                Files.createDirectories(destination);
                Files.writeString(destination.resolve("candidates.txt"), String.join(" ", result.candidates()), StandardCharsets.UTF_8);
                Files.writeString(destination.resolve("result.txt"), names, StandardCharsets.UTF_8);
                Files.writeString(destination.resolve("unresolved.txt"), String.join(" ", result.unresolved()), StandardCharsets.UTF_8);
                Files.writeString(destination.resolve("history-unresolved.txt"), String.join(" ", result.unclearHistory()), StandardCharsets.UTF_8);
                StringBuilder report = new StringBuilder("nickname\tdecision\n");
                result.decisions().forEach((name, decision) -> report.append(name).append('\t')
                        .append(decision.replace('\n', ' ').replace('\t', ' ')).append('\n'));
                Files.writeString(destination.resolve("checks.tsv"), report.toString(), StandardCharsets.UTF_8);
            } catch (IOException error) { logError = error; }
        });
        if (!copy) return;
        try {
            MinecraftClient.getInstance().keyboard.setClipboard(names);
            boolean copied = names.equals(MinecraftClient.getInstance().keyboard.getClipboard());
            debug("CLIPBOARD verified=" + copied + " length=" + names.length());
            if (!copied) throw new IllegalStateException("Clipboard readback mismatch");
            String suffix = result.unresolved().isEmpty() ? "" : " Непроверенных: " + result.unresolved().size() + "; /sb shist retry.";
            if (result.historyIncomplete()) suffix += " История короче лимита: " + result.entries() + "/" + result.expected() + ".";
            if (!result.unclearHistory().isEmpty()) suffix += " Записей без однозначного статуса: " + result.unclearHistory().size() + "; смотри history-unresolved.txt.";
            ShistClient.feedback("В буфере " + result.accepted().size() + " подтверждённых ников." + suffix);
        } catch (Exception error) {
            debug("CLIPBOARD ERROR " + error);
            ShistClient.feedback("Результат сохранён в " + destination.resolve("result.txt") + "; ошибка буфера: " + error.getMessage());
        }
        flush();
    }
}
