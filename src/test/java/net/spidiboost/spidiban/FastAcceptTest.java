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
        final FastAccept engine = new FastAccept(List.of("First", "Second", "Third"));
        final List<String> commands = new ArrayList<>();
        final List<Long> clicks = new ArrayList<>();
        public int id() { return id; }
        public boolean root() { return slot == 15; }
        public boolean button(int n, String name) { return n == slot; }
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
        long second = r.now; r.now += 7_000_000_000L; r.prepare(); r.tick();
        assertEquals(second + 7_000_000_000L,r.clicks.getLast()); r.success("Third"); r.tick();assertTrue(r.engine.done());
    }
    @Test void expiredPromptGetsExactlyOneAdditionalClickAfterResponse() {
        var r=new Rig();r.first();r.prepare();r.reply(PROMPT);r.now += DELAY;r.tick();r.tick();
        assertEquals(4,r.clicks.size());r.reply(PROMPT);r.tick();assertEquals(5,r.clicks.size());
        r.reply(PROMPT);r.tick();r.tick();assertEquals(5,r.clicks.size());r.success("Second");r.tick();assertEquals(3,r.commands.size());
    }
    @Test void delayedFirstPromptNotMistakenForRenewal() {
        var r=new Rig();r.first();r.prepare();r.now+=DELAY;r.tick();r.reply(PROMPT);r.tick();
        assertEquals(4,r.clicks.size());r.reply(PROMPT);r.tick();assertEquals(5,r.clicks.size());
    }
    @Test void earlyCloseAndFalseResultsNeverAdvance() {
        var r=new Rig();r.tick();r.prepare();r.tick();r.slot=0;
        r.reply("Bans » Выдан бан First: FP_RW (навсегда)");r.success("First2");
        r.reply("Администратор Other забанил игрока: First по причине 'FP_RW'");
        r.tick();assertEquals(1,r.commands.size());r.now+=12_000_000_000L;r.tick();assertNotNull(r.engine.error());assertEquals(2,r.clicks.size());
    }
    @Test void skippedNickPreservesPreviousDeadline() {
        var r=new Rig();r.first();long first=r.now;r.reply("Игрок Second уже забанен и у вас нету прав на новый бан");r.tick();
        assertEquals("banrw Third",r.commands.getLast());r.prepare();r.now=first+DELAY-1;r.tick();
        assertEquals(3,r.clicks.size());r.now++;r.tick();assertEquals(4,r.clicks.size());
    }
    @Test void noFinalClickInReplacedMenu() {
        var r=new Rig();r.first();r.prepare();r.id++;r.now+=DELAY;r.tick();
        assertNotNull(r.engine.error());assertEquals(3,r.clicks.size());
    }
}
