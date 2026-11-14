package net.spidiboost.spidiban;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FastAcceptTest {
    static final long DELAY = 5_010_000_000L;
    static final String PROMPT = "Bans » Ещё раз по «Выдать», чтобы подтвердить: FP_RW (навсегда)";
    static final class Rig implements FastAccept.Menu {
        int id, slot;
        long now;
        String title = "Бан";
        final FastAccept engine;
        Rig() { this(true); }
        Rig(boolean early) { engine = new FastAccept(List.of("First", "Second", "Third"), early); }
        public String title() { return title; }
        final List<String> commands = new ArrayList<>();
        final List<Long> clicks = new ArrayList<>();
        public int id() { return id; }
        public boolean root() { return slot == 15; }
        public boolean button(int n, String name) {
            if (n != slot) return false;
            if (n == 26 && slot == 26) return name.equals("ВЫДАТЬ БАН") || name.equals("ПОДТВЕРДИТЕ");
            return true;
        }
        public String staff() { return "SpidiBoost"; }
        public void click(int n) { assertEquals(slot,n); if (n == 26) clicks.add(now); }
        public void send(String cmd) { commands.add(cmd); }
        void tick() { engine.tick(this,now,DELAY); }
        void reply(String msg) {
            var e = BanEvidence.parse(msg);
            if(e != null) engine.accept(new BanInbox.Delivery(this,e,now),this);
        }
        void success(String nick) { reply("[23:03:13] Администратор SpidiBoost забанил игрока: "+nick+" по причине 'FP_RW'"); }
        void prepare() { for(int n : new int[]{15,1,26}) { id++; slot=n; tick(); } }
        void first() {
            tick(); prepare(); reply(PROMPT); tick();
            assertEquals(List.of(0L,0L),clicks);
            now = 900_000_000L;
            success("First"); tick();
            assertEquals(List.of("banrw First","banrw Second"),commands);
        }
    }
    @Test void threeNicksMenuTimeCountsTowardDelay() {
        var r = new Rig(); r.first(); long first = r.now;
        r.now += 1_000_000_000L; r.prepare(); r.reply(PROMPT);
        r.now = first + DELAY - 1; r.tick(); assertEquals(3,r.clicks.size());
        r.now++; r.tick(); assertEquals(List.of(0L,0L,first+1_000_000_000L,first+DELAY),r.clicks);
        r.success("Second");r.tick();assertEquals("banrw Third",r.commands.getLast());
        long second = r.now; r.now += 7_000_000_000L; r.prepare(); r.reply(PROMPT); r.tick();
        assertEquals(second + 7_000_000_000L,r.clicks.getLast()); r.success("Third"); r.tick();assertTrue(r.engine.done());
    }
    @Test void expiredPromptGetsExactlyOneAdditionalClickAfterResponse() {
        var r=new Rig();r.first();r.prepare();r.reply(PROMPT);r.now += DELAY;r.tick();r.tick();
        assertEquals(4,r.clicks.size());r.reply(PROMPT);r.tick();assertEquals(5,r.clicks.size());
        r.reply(PROMPT);r.tick();r.tick();assertEquals(5,r.clicks.size());r.success("Second");r.tick();assertEquals(3,r.commands.size());
    }
    @Test void delayedPromptAndRepaintDoNotLoseSecondClick() {
        for (boolean early : List.of(false,true)) {
            var r=new Rig(early);r.tick();r.prepare();r.slot=0;r.tick();
            assertNull(r.engine.error());assertEquals(1,r.clicks.size());
            r.now+=250_000_000L;r.reply(PROMPT);r.tick();
            r.id++;r.slot=26;r.tick();r.tick();
            assertNull(r.engine.error());assertEquals(2,r.clicks.size());
        }
    }
    @Test void earlyCloseAndFalseResultsNeverAdvance() {
        var r=new Rig();r.tick();r.prepare();r.reply(PROMPT);r.tick();r.slot=0;
        r.reply("Bans » Выдан бан First: FP_RW (навсегда)");r.success("First2");
        r.reply("Администратор Other забанил игрока: First по причине 'FP_RW'");
        r.tick();assertEquals(1,r.commands.size());r.now+=12_000_000_000L;r.tick();assertNotNull(r.engine.error());assertEquals(2,r.clicks.size());
    }
    @Test void skippedNickPreservesPreviousDeadline() {
        var r=new Rig();r.first();long first=r.now;r.reply("Игрок Second уже забанен и у вас нету прав на новый бан");r.tick();
        assertEquals("banrw Third",r.commands.getLast());r.prepare();r.reply(PROMPT);r.now=first+DELAY-1;r.tick();
        assertEquals(3,r.clicks.size());r.now++;r.tick();assertEquals(4,r.clicks.size());
    }
    @Test void noFinalClickInReplacedMenu() {
        var r=new Rig();r.first();r.prepare();r.reply(PROMPT);r.id++;r.title="Другой игрок";r.slot=0;r.now+=DELAY+12_000_000_000L;r.tick();
        assertNotNull(r.engine.error());assertEquals(3,r.clicks.size());
    }
    @Test void classicWaitsOnlyAfterConfirmedSuccess() {
        var r=new Rig(false); r.tick();r.prepare();
        assertEquals(1,r.clicks.size());
        r.reply("Администратор banana250 замутил EFE17 на 4 часов по причине 'flood'");
        r.tick();assertEquals(1,r.clicks.size());
        r.reply(PROMPT);r.tick();assertEquals(2,r.clicks.size());
        r.reply("Администратор SpidiBoost замутил First на 4 часов по причине 'flood'");
        r.tick();assertEquals(List.of("banrw First"),r.commands);
        r.now=700_000_000L;r.success("First");r.tick();
        assertFalse(r.engine.lockControls(r.now, DELAY));
        r.now+=DELAY-1;r.tick();assertEquals(1,r.commands.size());
        r.now++;r.tick();assertEquals("banrw Second",r.commands.getLast());
        r.prepare();r.reply(PROMPT);r.tick();
        r.reply("Игрок Second уже забанен и у вас нету прав на новый бан .");
        r.tick();assertEquals("banrw Third",r.commands.getLast());
    }

    @Test void exactDuplicateAndThrottleSkipWithoutStartingDelayInBothModes() {
        for(boolean early:List.of(true,false)){
            var r=new Rig(early); r.tick();r.prepare();r.reply(PROMPT);r.tick();
            r.reply("Игрок First1 уже забанен и у вас нету прав на новый бан .");
            r.reply("Bans » Выдан бан First: FP_RW (навсегда)");r.tick();assertEquals(1,r.commands.size());
            r.reply("Игрок First уже забанен и у вас нету прав на новый бан .");r.tick();
            r.prepare();r.reply(PROMPT);r.tick();
            r.reply("[20:17:42] Подождите 4 секунды!");r.tick();assertEquals(List.of("banrw First","banrw Second","banrw Third"),r.commands);
        }
        for(String text:List.of("Подождите ещё 1 секунду.","Подождите 0,5 сек.","Ошибка: Подождите 5 секунд",
                "Слишком быстро! Подождите 5 секунд!"))
            assertEquals(BanEvidence.Kind.COOLDOWN,BanEvidence.parse(text).kind());
        for(String text:List.of("Alex » Подождите 5 секунд","Подождите 5 секунд до телепортации","Администратор banana250 замутил EFE17 на 4 часов по причине 'подождите 5 секунд'"))
            assertNull(BanEvidence.parse(text));
    }

    @Test void missingPlayerSkipsCurrentNickInBothModes() {
        for (boolean early : List.of(true, false)) {
            var r = new Rig(early);
            r.tick();
            r.prepare();
            r.reply("Player does not exist.");
            r.tick();
            assertEquals("banrw Second", r.commands.getLast());
            assertEquals(2, r.commands.size());
        }
    }
}
