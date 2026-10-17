package net.spidiboost.spidiban;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Automates the two requested /banrw menu routes and retains their delay setting. */
public final class SpidiBanAutomation {
    private static final long STEP_TIMEOUT_TICKS = 20L * 12L;
    private static final double DEFAULT_DELAY_SECONDS = 5.0D;
    private static final String CONFIG_NAME = "sbb.cfg";
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();

    private static State state = State.IDLE;
    private static Route route;
    private static String activeName;
    private static final BanInbox inbox = new BanInbox();
    private static BanInbox.Delivery reply;
    private static String administrator;
    private static long requestedAt;
    private static long ticks;
    private static long stateStartedAt;
    private static long nextCommandAt;
    private static final BanDelay banDelay = new BanDelay(DEFAULT_DELAY_SECONDS);
    private static long confirmationTime;
    private static double delaySeconds = DEFAULT_DELAY_SECONDS;
    private static int handlerBeforeCommand;
    private static boolean configLoaded;
    private static int rootRetries;
    private static final int MAX_ROOT_RETRIES = 3;

    private SpidiBanAutomation() { }

    enum Route {
        FUNPAY("FunPay"),
        CHEATS("Читы");

        final String label;

        Route(String label) {
            this.label = label;
        }
    }

    static int start(String input, Route requestedRoute) {
        Set<String> parsed = new LinkedHashSet<>();
        int rejected = 0;
        for (String line : input.split("\\R")) {
            if (line.trim().startsWith("#")) continue;
            for (String value : line.trim().split("[,;\\s]+")) {
                if (value.isBlank()) continue;
                if (value.matches("[A-Za-z0-9_]{1,16}")) parsed.add(value);
                else rejected++;
            }
        }
        if (parsed.isEmpty()) {
            SpidiBanClient.feedback("Введите хотя бы один корректный ник в spidiban-nicknames.txt.");
            return 0;
        }
        PENDING.clear();
        PENDING.addAll(parsed);
        route = requestedRoute;
        state = State.WAITING_TO_SEND;
        nextCommandAt = ticks;
        banDelay.reset();
        inbox.start();
        reply = null;
        activeName = null;
        rootRetries = 0;
        if (rejected > 0) SpidiBanClient.feedback("Пропущено некорректных никнеймов: " + rejected + ".");
        SpidiBanClient.feedback(route.label + ": в очереди " + PENDING.size() + " ник(а/ов); задержка " + delayDescription() + ".");
        return PENDING.size();
    }

    static void tick(MinecraftClient client) {
        ensureConfigLoaded(client);
        if (client.player == null) {
            if (isActive()) cancel();
            return;
        }
        advance(new ClientMenu(client), System.nanoTime());
    }

    interface Menu {
        boolean open();
        boolean root();
        int id();
        int size();
        String name(int slot);
        String administrator();
        Object connection();
        void close();
        void click(int slot);
        void send(String command);
    }

    private record ClientMenu(MinecraftClient client) implements Menu {
        public boolean open() { return client.currentScreen instanceof HandledScreen<?>; }
        public boolean root() {
            return client.currentScreen instanceof HandledScreen<?> screen && isRootMenu(screen, client.player.currentScreenHandler);
        }
        public int id() { return client.player.currentScreenHandler.syncId; }
        public int size() { return client.player.currentScreenHandler.slots.size(); }
        public String name(int slot) { return itemName(client.player.currentScreenHandler, slot); }
        public String administrator() { return client.player.getGameProfile().getName(); }
        public Object connection() { return client.player.networkHandler.getConnection(); }
        public void close() { if (open()) client.player.closeHandledScreen(); }
        public void click(int slot) { SpidiBanAutomation.click(client, client.player.currentScreenHandler, slot); }
        public void send(String command) { client.player.networkHandler.sendChatCommand(command); }
    }

    static void advance(Menu menu, long now) {
        ticks++;
        if (state == State.IDLE) return;
        if (inbox.overflowed()) {
            fail("слишком много ответов сервера; результат последнего бана не подтверждён");
            return;
        }
        for (BanInbox.Delivery message; (message = inbox.poll()) != null;) {
            if (message.connection() == menu.connection()) acceptEvidence(message);
        }
        if (reply != null) {
            if (reply.evidence().kind() == BanEvidence.Kind.ISSUED) {
                banDelay.complete(reply.receivedAt());
                SpidiBanClient.LOGGER.info("Server confirmed ban: administrator={}, target={}", administrator, activeName);
                SpidiBanClient.feedback("Бан для " + activeName + " подтверждён сервером.");
            } else {
                String reason = reply.evidence().kind() == BanEvidence.Kind.ALREADY_BANNED
                        ? "уже забанен или нет прав" : "игрок не существует";
                SpidiBanClient.feedback(activeName + ": " + reason + "; пропускаю без новой задержки.");
                SpidiBanClient.LOGGER.info("Server rejected ban: target={}, result={}; cooldown unchanged", activeName, reply.evidence().kind());
            }
            if (menu.id() != handlerBeforeCommand) menu.close();
            reply = null;
            activeName = null;
            sendNext(menu, now);
            return;
        }
        if (state == State.WAITING_TO_SEND) {
            if (ticks >= nextCommandAt) sendNext(menu, now);
            return;
        }

        if (state == State.WAITING_FOR_RESULT) {
            if (timedOut()) fail("сервер не подтвердил бан для " + activeName + "; повторной выдачи не будет");
            return;
        }

        if (!menu.open()) {
            if (timedOut()) retryOrFail(menu);
            return;
        }
        if (menu.size() < 27) {
            if (timedOut()) fail("в меню недостаточно слотов");
            return;
        }

        if (state == State.WAITING_FOR_ROOT) {
            if (menu.id() != handlerBeforeCommand && menu.root()) {
                int slot = route == Route.FUNPAY ? 15 : 13;
                String name = route == Route.FUNPAY ? "FUNPAY" : "ЧИТЫ И МОДЫ";
                if (menu.name(slot).equals(normalized(name))) {
                    menu.click(slot);
                    state = State.WAITING_FOR_REASON;
                    stateStartedAt = ticks;
                } else if (timedOut()) fail("не найден пункт " + name);
            } else if (timedOut()) retryOrFail(menu);
        } else if (state == State.WAITING_FOR_REASON) {
            int slot = route == Route.FUNPAY ? 1 : 0;
            String name = route == Route.FUNPAY ? "FP_RW" : "Читы";
            if (menu.name(slot).equals(normalized(name))) {
                menu.click(slot);
                state = route == Route.FUNPAY ? State.WAITING_FOR_ISSUE : State.WAITING_FOR_SILENT_BAN;
                stateStartedAt = ticks;
            } else if (timedOut()) fail("не найден пункт " + name);
        } else if (state == State.WAITING_FOR_SILENT_BAN) {
            if (menu.name(24).equals(normalized("Тихий бан: выкл")) && menu.name(26).equals("ВЫДАТЬ БАН")) {
                menu.click(24);
                state = State.WAITING_FOR_ISSUE;
                stateStartedAt = ticks;
            } else if (timedOut()) fail("не найдены Тихий бан: выкл или ВЫДАТЬ БАН");
        } else if (state == State.WAITING_FOR_ISSUE) {
            if (menu.name(26).equals("ВЫДАТЬ БАН")) {
                if (!banDelay.ready(now)) return;
                confirmationTime = now;
                administrator = menu.administrator();
                state = State.WAITING_FOR_RESULT;
                stateStartedAt = ticks;
                menu.click(26);
                if (reply == null && state == State.WAITING_FOR_RESULT) menu.click(26);
            } else if (banDelay.ready(now) && timedOut()) fail("не найдена кнопка ВЫДАТЬ БАН");
        }
    }

    public static boolean isActive() {
        return state != State.IDLE;
    }

    public static boolean shouldLockControls() {
        if (state == State.IDLE) return false;
        if (state != State.WAITING_TO_SEND) return true;
        if (PENDING.isEmpty()) return false;
        return ticks >= nextCommandAt - 10L;
    }

    static void cancel() {
        if (state == State.IDLE) {
            SpidiBanClient.feedback("Очередь не запущена.");
            return;
        }
        int remaining = PENDING.size() + (activeName == null ? 0 : 1);
        PENDING.clear();
        activeName = null;
        inbox.stop();
        reply = null;
        route = null;
        rootRetries = 0;
        state = State.IDLE;
        SpidiBanClient.feedback("Очередь отменена. Не обработано: " + remaining + ".");
    }

    public static void captureServerMessage(Object connection, String message) {
        inbox.capture(connection, message, System.nanoTime());
    }

    static void capture(Object connection, String message, long now) {
        inbox.capture(connection, message, now);
    }

    private static void acceptEvidence(BanInbox.Delivery message) {
        if (activeName == null || message.receivedAt() - requestedAt < 0) return;
        BanEvidence evidence = message.evidence();
        if (!evidence.forPlayer(activeName)) return;
        if (evidence.kind() == BanEvidence.Kind.ISSUED) {
            if (state != State.WAITING_FOR_RESULT || message.receivedAt() - confirmationTime < 0
                    || !evidence.administrator().equalsIgnoreCase(administrator) || reply != null) return;
        }
        reply = message;
    }

    static void setDelaySeconds(double requestedSeconds) {
        ensureConfigLoaded(MinecraftClient.getInstance());
        BanDelay.validate(requestedSeconds);
        delaySeconds = requestedSeconds;
        banDelay.set(delaySeconds);
        saveConfig(MinecraftClient.getInstance());
    }

    static String delayDescription() {
        ensureConfigLoaded(MinecraftClient.getInstance());
        return BanDelay.describe(delaySeconds);
    }

    private static void sendNext(Menu menu, long now) {
        activeName = PENDING.poll();
        reply = null;
        if (activeName == null) {
            state = State.IDLE;
            inbox.stop();
            SpidiBanClient.feedback("Очередь завершена.");
            return;
        }
        handlerBeforeCommand = menu.id();
        requestedAt = now;
        administrator = menu.administrator();
        rootRetries = 0;
        state = State.WAITING_FOR_ROOT;
        stateStartedAt = ticks;
        menu.send("banrw " + activeName);
    }

    private static void retryOrFail(Menu menu) {
        if (rootRetries >= MAX_ROOT_RETRIES) {
            fail("не дождались нужного меню после " + MAX_ROOT_RETRIES + " повторов для " + activeName);
            return;
        }
        rootRetries++;
        SpidiBanClient.feedback("Меню не появилось для " + activeName + "; повтор " + rootRetries + "/" + MAX_ROOT_RETRIES + ".");
        handlerBeforeCommand = menu.id();
        stateStartedAt = ticks;
        menu.send("banrw " + activeName);
    }

    private static boolean isRootMenu(HandledScreen<?> screen, ScreenHandler handler) {
        return normalized(screen.getTitle().getString()).contains("БАН") && handler.slots.size() >= 27;
    }

    private static String itemName(ScreenHandler handler, int slot) {
        ItemStack stack = handler.getSlot(slot).getStack();
        return stack.isEmpty() ? "" : normalized(stack.getName().getString());
    }

    private static String normalized(String value) {
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static void click(MinecraftClient client, ScreenHandler handler, int slot) {
        if (client.interactionManager != null) {
            client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
        }
    }

    private static boolean timedOut() {
        return ticks - stateStartedAt > STEP_TIMEOUT_TICKS;
    }

    private static void ensureConfigLoaded(MinecraftClient client) {
        if (configLoaded) return;
        configLoaded = true;
        Path file = configFile(client);
        try {
            if (Files.exists(file)) {
                // Read configurations written by older builds with literal \\n.
                for (String line : Files.readString(file, StandardCharsets.UTF_8)
                        .replace("\\n", "\n").split("\\R")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("delaySeconds=")) {
                        delaySeconds = BanDelay.parse(trimmed.substring("delaySeconds=".length()));
                        banDelay.set(delaySeconds);
                    }
                }
            } else {
                saveConfig(client);
            }
        } catch (IOException | IllegalArgumentException exception) {
            SpidiBanClient.LOGGER.warn("Could not read {}. Using {} seconds.", CONFIG_NAME, DEFAULT_DELAY_SECONDS, exception);
            delaySeconds = DEFAULT_DELAY_SECONDS;
            banDelay.set(delaySeconds);
        }
    }

    private static void saveConfig(MinecraftClient client) {
        try {
            Path file = configFile(client);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "# SpidiBan delay between completed bans, in seconds.\n"
                    + "delaySeconds=" + delaySeconds + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            SpidiBanClient.LOGGER.warn("Could not save {}", CONFIG_NAME, exception);
        }
    }

    private static Path configFile(MinecraftClient client) {
        return client.runDirectory.toPath().resolve("config").resolve(CONFIG_NAME);
    }

    private static void fail(String reason) {
        SpidiBanClient.LOGGER.warn("Ban queue stopped: target={}, state={}, pending={}, reason={}", activeName, state, PENDING.size(), reason);
        SpidiBanClient.feedback("Остановлено: " + reason + ". Осталось в очереди: " + PENDING.size() + ".");
        PENDING.clear();
        activeName = null;
        inbox.stop();
        reply = null;
        rootRetries = 0;
        route = null;
        state = State.IDLE;
    }

    private enum State {
        IDLE, WAITING_TO_SEND, WAITING_FOR_ROOT, WAITING_FOR_REASON, WAITING_FOR_SILENT_BAN, WAITING_FOR_ISSUE, WAITING_FOR_RESULT
    }
}
