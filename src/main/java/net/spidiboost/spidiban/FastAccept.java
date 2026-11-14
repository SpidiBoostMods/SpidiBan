package net.spidiboost.spidiban;

import java.util.ArrayDeque;
import java.util.Collection;

final class FastAccept {
    interface Menu {
        int id();
        boolean root();
        String title();
        boolean button(int slot, String name);
        void click(int slot);
        void send(String command);
        String staff();
    }

    private enum Step { NEXT, ROOT, REASON, ISSUE, ARMED, RESULT }
    private static final long TIMEOUT = 12_000_000_000L;
    private final ArrayDeque<String> queue;
    private final boolean prepareEarly;
    private String issueTitle;
    private Step step = Step.NEXT;
    private String nick;
    private int before, issueMenu, retries;
    private long since, requested, lastBan, finalClick;
    private boolean hasBan, primed, renewed, retried;
    private String error;

    FastAccept(Collection<String> names, boolean prepareEarly) {
        queue = new ArrayDeque<>(names);
        this.prepareEarly = prepareEarly;
    }

    boolean done() { return queue.isEmpty() && nick == null; }
    String error() { return error; }
    int remaining() { return queue.size() + (nick == null ? 0 : 1); }
    boolean lockControls(long now, long interval) {
        return prepareEarly || step != Step.NEXT || !hasBan || now - lastBan >= interval - 500_000_000L;
    }
    boolean ownsMenu(int id) { return id >= 0 && step != Step.NEXT && id != before; }

    void accept(BanInbox.Delivery delivery, Menu menu) {
        var e = delivery.evidence();
        long at = delivery.receivedAt();
        if (error != null || nick == null || at - requested < 0 || !e.forPlayer(nick)) return;
        switch (e.kind()) {
            case ISSUED -> {
                if (step != Step.RESULT || at - finalClick < 0 || !e.administrator().equalsIgnoreCase(menu.staff())) return;
                hasBan = true;
                lastBan = at;
            }
            case CONFIRM -> {
                if (step == Step.ARMED) primed = true;
                else if (step == Step.RESULT && at - finalClick >= 0 && !retried) {
                    renewed = true;
                }
                return;
            }
            case MISSING -> { }
            case COOLDOWN -> { if (step != Step.RESULT && step != Step.ROOT) return; }
            case ALREADY_BANNED -> { }
        }
        nick = null;
        step = Step.NEXT;
    }

    void tick(Menu menu, long now, long interval) {
        if (error != null || done()) return;
        switch (step) {
            case NEXT -> {
                if (!prepareEarly && hasBan && now - lastBan < interval) return;
                nick = queue.removeFirst();
                before = menu.id();
                retries = 0;
                requested = now;
                change(Step.ROOT, now);
                menu.send("banrw " + nick);
            }
            case ROOT -> {
                if (menu.id() != before && menu.root() && menu.button(15, "FUNPAY")) {
                    change(Step.REASON, now);
                    menu.click(15);
                } else if (now - since >= TIMEOUT) {
                    if (++retries > 3) stop("не открылось меню");
                    else { since = now; menu.send("banrw " + nick); }
                }
            }
            case REASON -> {
                if (menu.button(1, "FP_RW")) {
                    change(Step.ISSUE, now);
                    menu.click(1);
                } else timeout(now, "не найдена причина FP_RW");
            }
            case ISSUE -> {
                if (menu.button(26, "ВЫДАТЬ БАН")) {
                    issueMenu = menu.id();
                    issueTitle = menu.title();
                    primed = renewed = retried = false;
                    change(Step.ARMED, now);
                    menu.click(26);
                } else timeout(now, "не найдена кнопка выдачи");
            }
            case ARMED -> {
                if (!primed) { timeout(now, "нет запроса на подтверждение FP_RW"); return; }
                if (hasBan && now - lastBan < interval) return;
                if (!confirmButton(menu)) {
                    if (now - since >= TIMEOUT && (!hasBan || now - lastBan - interval >= TIMEOUT))
                        stop("кнопка подтверждения FP_RW не появилась");
                    return;
                }
                finalClick = now;
                change(Step.RESULT, now);
                menu.click(26);
            }
            case RESULT -> {
                if (renewed && confirmButton(menu)) {
                    renewed = false;
                    retried = true;
                    since = now;
                    menu.click(26);
                } else timeout(now, "сервер не подтвердил бан");
            }
        }
    }

    private boolean issueButton(Menu menu) {
        return menu.button(26, "ВЫДАТЬ БАН") && (menu.id() == issueMenu
                || (primed && !issueTitle.isBlank() && issueTitle.equals(menu.title())));
    }

    private boolean confirmButton(Menu menu) {
        return menu.button(26, "ПОДТВЕРДИТЕ")
                || menu.button(26, "ПОДТВЕРДИТЬ")
                || menu.button(26, "ПОДТВЕРДИТЬ БАН");
    }
    private void change(Step next, long at) { step = next; since = at; }
    private void timeout(long now, String reason) { if (now - since >= TIMEOUT) stop(reason); }
    private void stop(String reason) { error = nick + ": " + reason; }
}
