package net.spidiboost.shist;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.*;

/** Deterministic server simulator, regression replay, and generated stress tests. */
public final class ShistProtocolTest {
    private static int tests;
    public static void main(String[] args) throws Exception {
        test("optional count and all aliases", () -> {
            for (String alias : List.of("sb", "spidiban", "spidiboost", "SB")) {
                eq(ShistCommand.parse("/"+alias+" shist SpidiBoost BAN").count(), null);
                eq(ShistCommand.parse("/"+alias+" shist SpidiBoost mute 10000").count(), 10000);
            }
        });
        test("existing commands stay owned by original mod", () -> {
            for (String text : List.of("/sb accept", "/sb cheats", "/sb delay 1", "/checkban ABC", "hello")) eq(ShistCommand.parse(text), null);
        });
        test("malformed commands", () -> {
            for (String text : List.of("/sb shist", "/sb shist Nick", "/sb shist Nick ban 0", "/sb shist Nick ban -1",
                    "/sb shist Nick ban 10001", "/sb shist Nick ban 2147483648", "/sb shist Nick nope",
                    "/sb shist Nick mute abc", "/sb shist bad-name ban", "/sb shist AAAAAAAAAAAAAAAAA ban",
                    "/sb shist Nick ban 10 junk", "/sb shist speed 0", "/sb shist speed 1001")) {
                expectError(() -> ShistCommand.parse(text));
            }
        });
        test("controls", () -> {
            for (String action : List.of("status", "retry", "cancel")) eq(ShistCommand.parse("/sb shist " + action).action(), action);
            eq(ShistCommand.parse("/sb shist speed 1000").speed(), 1000);
        });
        test("real-server burst regression: one request at a time even at speed 1000", () -> {
            Harness h = new Harness("mute",40); h.engine.speed(1000);
            for(int i=0;i<40;i++) h.entry("Nick"+i,true);
            h.beginChecks(); eq(h.checkSends(),1);
            h.advance(50); eq(h.checkSends(),1); // No answer yet: never flood the connection.
            h.responder=command->h.reply(command.split(" ")[1],"flood",false);
            h.reply("Nick0","flood",false); h.drain();
            eq(h.checkSends(),40); eq(h.result.unresolved(),List.of());
            for(int i=1;i<h.checkTimes.size();i++)
                yes(h.checkTimes.get(i)-h.checkTimes.get(i-1)>=125,"unsafe check burst");
        });
        test("controls do not reserve real player nicknames", () -> {
            for(String nick:List.of("status","cancel","retry","speed")) {
                eq(ShistCommand.parse("/sb shist "+nick+" ban").nickname(),nick);
                eq(ShistCommand.parse("/sb shist "+nick+" mute 10").nickname(),nick);
            }
        });
        test("timestamps formatting Unicode spaces", () -> {
            eq(ShistEngine.clean(" §a[18:02:03] §6[18:02:04]\u00a0Цель\u202f[Nick]\u200b "), "Цель [Nick]");
        });
        test("Unicode reason separators", () -> {
            yes(ShistEngine.excludedReason("Попытка\u2003взлома"),"Unicode space");
        });
        test("status-looking words inside reason are not the record status", () -> {
            Harness h=new Harness("ban",1); h.feed("Nick0 был забанен куратором SpidiBoost\nПо причине: 'написал [Активный]' [Истек]");
            h.beginChecks(); h.drain(); eq(h.result.candidates(),List.of());
        });
        test("not permanent ending is not forever", () -> {
            Harness h=one("ban"); h.feed("Цель [Nick0] забанена:\nПричина: Читы\nОкончание бана: не навсегда\nIP бан: no, навсегда: no");
            h.drain(); eq(h.result.accepted(),List.of());
        });
        test("all excluded spelling and case variants", () -> {
            for (String reason : List.of("попытка взлома", "ПОПЫТКА ВЗЛОМА", "чсп", "ЧСП",
                    "пиар читов", "Некорректный никнейм", "некорректный ник", "некк ник", "нек ник",
                    "nekk nick", "nek nick", "nek.nick", "nek. nick.", "nekk.nick.", "NEKK__NICK",
                    "Попытка-взлома // rec", "Пиар. читов by Moderator")) {
                yes(ShistEngine.excludedReason(reason), reason);
            }
            for (String reason : List.of("Читы", "оскорбление администрации by barrabulka", "Уклон от проверки",
                    "Отказ от прохождения проверки", "флуд", "Лив с проверки")) yes(!ShistEngine.excludedReason(reason), reason);
        });
        test("ban active and forever and exclusion", () -> {
            Harness h = new Harness("ban", 4);
            h.entry("Good", true); h.entry("Expired", false); h.entry("Temp", true); h.entry("Excluded", true); h.beginChecks();
            h.reply("Good", "Читы", true); h.reply("Temp", "Читы", false); h.reply("Excluded", "Попытка взлома", true);
            h.drain(); eq(h.result.accepted(), List.of("Good")); eq(h.result.candidates(), List.of("Good", "Temp", "Excluded"));
        });
        test("mute ignores exclusion list", () -> {
            Harness h = new Harness("mute", 2); h.entry("Good", true); h.entry("Temp", true); h.beginChecks();
            h.reply("Good", "Попытка взлома", true); h.reply("Temp", "ЧСП", false); h.drain(); eq(h.result.accepted(), List.of("Good"));
        });
        test("ten nicks and duplicated full responses never skip next nick", () -> {
            Harness h = new Harness("ban", 10);
            for (int i=0;i<10;i++) { h.entry("Nick"+i, true); h.entry("Nick"+i, true); }
            h.beginChecks();
            for (int i=9;i>=0;i--) { h.reply("Nick"+i,"Читы",true); h.reply("Nick"+i,"Читы",true); }
            h.drain(); eq(h.result.accepted(), names(10)); eq(h.checkSends(), 10); eq(h.result.entries(), 10);
        });
        test("temporary ending cannot capture forever no from later field", () -> {
            Harness h = one("ban"); h.reply("Nick0","Читы",false); h.drain(); eq(h.result.accepted(), List.of());
        });
        test("permanent words in reason or player chat are not expiry", () -> {
            Harness h = one("ban"); h.reply("Nick0", "навсегда: yes — ложное слово в причине", false);
            h.feed("Other » навсегда: yes"); h.drain(); eq(h.result.accepted(),List.of());
        });
        test("punishment broadcasts do not contaminate wrapped reason", () -> {
            Harness h = one("ban"); h.feed("Цель [Nick0] забанена:\nПричина: Читы\nАдминистратор Other замутил Somebody по причине 'ЧСП'\nЗабанен: сегодня\nОкончание бана: навсегда (навсегда)\nIP бан: no, навсегда: yes");
            h.drain(); eq(h.result.accepted(),List.of("Nick0"));
        });
        test("history missing status is reported separately", () -> {
            Harness h=new Harness("ban",1); h.feed("Nick0 был забанен куратором SpidiBoost\nПо причине: Читы");
            h.beginChecks(); h.drain(); eq(h.result.candidates(),List.of()); eq(h.result.unclearHistory(),List.of("Nick0"));
        });
        test("contradictory history status cannot be accepted", () -> {
            Harness h=new Harness("ban",1); h.feed("Nick0 был забанен куратором SpidiBoost\nПо причине: Читы [Активный]\nПо причине: Читы [Истек]\nОкончание в 10 дней");
            h.beginChecks(); h.drain(); eq(h.result.candidates(),List.of()); eq(h.result.unclearHistory(),List.of("Nick0"));
        });
        test("fractional tick send rates are bounded", () -> {
            Harness h=new Harness("ban",100); h.engine.speed(21);
            for(int i=0;i<100;i++) h.entry("Nick"+i,true);
            h.responder=command->h.reply(command.split(" ")[1],"Читы",true);
            h.beginChecks(); int initial=h.checkSends();
            for(int i=0;i<20;i++) h.advance(50);
            yes(h.checkSends()-initial<=21,"rate exceeded: "+h.checkSends()); h.drain(); eq(h.result.accepted(),names(100));
        });
        test("wrong punishment type cannot satisfy a pending check", () -> {
            Harness h = one("ban"); h.feed("Цель [Nick0] заткнута:\nПричина: Читы\nОкончание мута: навсегда (навсегда)");
            h.reply("Nick0","Читы",false); h.drain(); eq(h.result.accepted(),List.of());
        });
        test("history with unban reversal counts without becoming candidate", () -> {
            Harness h = new Harness("ban",2); h.entry("Nick0",true); h.feed("-- [2026-09-24 12:00] --\nOther was unbanned by ReallyWorld.");
            h.beginChecks(); h.reply("Nick0","Читы",true); h.drain(); eq(h.result.entries(),2); eq(h.result.accepted(),List.of("Nick0"));
        });
        test("history burst pauses below timeout retain all records", () -> {
            Harness h = new Harness("ban",2); h.entry("A",true); h.advance(1000); h.entry("B",true);
            h.beginChecks(); h.reply("A","Читы",true); h.reply("B","Читы",true); h.drain(); eq(h.result.accepted(),List.of("A","B"));
        });
        test("Cyrillic uppercase target", () -> {
            Harness h = one("ban"); h.feed("ЦЕЛЬ [Nick0] ЗАБАНЕНА:\nПРИЧИНА: Читы\nОКОНЧАНИЕ БАНА: навсегда (навсегда)\nIP бан: no, навсегда: yes");
            h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("case insensitive exact nickname matching", () -> {
            Harness h = one("ban"); h.reply("nICK0","Читы",true); h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("unbracketed header", () -> {
            Harness h = one("ban"); h.feed("Цель Nick0 забанена:\nПричина: Читы\nОкончание бана: навсегда (навсегда)");
            h.advance(200); h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("mute ends without IP footer", () -> {
            Harness h = one("mute"); h.feed("Цель [Nick0] заткнута:\nПричина: флуд\nОкончание мута: навсегда (навсегда)");
            h.advance(200); h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("real English mute ending without flags", () -> {
            Harness h=one("mute");
            h.feed("Target [Nick0] is muted:\nMuted by: KatAN1tA\nReason: flood\nMuted on: 2026-10-08 09:57\nMuted until: forever (навсегда)\nMuted on server grief, server scope: grief");
            h.advance(200); h.drain(); eq(h.result.accepted(),List.of("Nick0")); eq(h.checkSends(),1);
        });
        test("English ban metadata cannot become an excluded reason", () -> {
            Harness h=one("ban");
            h.feed("Target [Nick0] is banned:\nReason: Cheats\nBanned by: nekk_nick\nBanned on: today\nBanned until: forever (forever)\nIP ban: no, permanent: yes");
            h.drain(); eq(h.result.accepted(),List.of("Nick0"));
        });
        test("unsolicited future response is ignored until its own request", () -> {
            Harness h=new Harness("ban",2); h.entry("A",true); h.entry("B",true); h.beginChecks();
            h.feed("Target [B] is banned:\nReason: Cheats\nExpires: forever\nPermanent: yes");
            eq(h.checkSends(),1); h.reply("A","Читы",false); h.advance(150);
            h.reply("B","Читы",false); h.drain(); eq(h.result.accepted(),List.of()); eq(h.checkSends(),2);
        });
        test("slow replies cannot cause overlapping requests", () -> {
            Harness h=new Harness("ban",2); h.entry("A",true); h.entry("B",true); h.beginChecks();
            h.advance(1000); eq(h.checkSends(),1); h.reply("A","Читы",true);
            h.advance(50); eq(h.checkSends(),2); h.reply("B","Читы",true); h.drain(); eq(h.result.accepted(),List.of("A","B"));
        });
        test("flags without ending", () -> {
            Harness h = one("ban"); h.feed("Цель [Nick0] забанена:\nПричина: Читы\nIP бан: no, навсегда: yes");
            h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("missing ban reason remains unresolved", () -> {
            Harness h = one("ban"); h.feed("Цель [Nick0] забанена:\nОкончание бана: навсегда (навсегда)\nIP бан: no, навсегда: yes");
            h.drain(); eq(h.result.unresolved(), List.of("Nick0")); eq(h.result.accepted(), List.of()); eq(h.checkSends(), 3);
        });
        test("conflicting expiry and permanent flag retries", () -> {
            Harness h = one("ban"); h.feed("Цель [Nick0] забанена:\nПричина: Читы\nОкончание бана: завтра\nIP бан: no, навсегда: yes");
            h.drain(); eq(h.result.unresolved(), List.of("Nick0")); eq(h.result.accepted(), List.of());
        });
        test("reason wrapped over packets", () -> {
            Harness h = one("ban"); h.feed("Цель [Nick0] забанена:\nПричина: Попытка\nвзлома\nОкончание бана: навсегда (навсегда)\nIP бан: no, навсегда: yes");
            h.drain(); eq(h.result.accepted(), List.of());
        });
        test("stranger check cannot contaminate current target", () -> {
            Harness h = one("ban"); h.reply("Other","Читы",true); h.reply("Nick0","Читы",false);
            h.drain(); eq(h.result.accepted(), List.of());
        });
        test("empty expired-only history completes without checks", () -> {
            Harness h = new Harness("ban", 2); h.entry("A",false); h.entry("B",false); h.beginChecks();
            eq(h.result.accepted(), List.of()); eq(h.checkSends(), 0);
        });
        test("last active entry without expiry line is retained", () -> {
            Harness h = new Harness("ban", 1); h.feed("Nick0 был забанен куратором SpidiBoost\nПо причине: Читы [Активный]");
            h.beginChecks(); h.reply("Nick0","Читы",true); h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("expired entries without expiry line and mixed punishment modes", () -> {
            Harness h = new Harness("ban", 3);
            h.feed("A был забанен куратором SpidiBoost\nПо причине: Читы [Истёк]\nB был заткнут куратором SpidiBoost\nПо причине: флуд [Активный]\nC был забанен куратором SpidiBoost\nПо причине: Читы [Активный]");
            h.beginChecks(); h.reply("C","Читы",true); h.drain(); eq(h.result.candidates(), List.of("C"));
        });
        test("unrelated busy chat does not postpone completion", () -> {
            Harness h = new Harness("ban", 1); h.entry("Nick0",true);
            for (int i=0;i<20;i++) { h.feed("Other » hello"); h.advance(50); }
            h.reply("Nick0","Читы",true); h.drain(); eq(h.result.accepted(), List.of("Nick0"));
        });
        test("history shorter than requested is reported", () -> {
            Harness h = new Harness("ban", 100); h.entry("Nick0",true); h.beginChecks(); h.reply("Nick0","Читы",true);
            h.drain(); yes(h.result.historyIncomplete(),"must flag incomplete history");
        });
        test("wrong owner header ignored", () -> {
            Harness h = new Harness(); h.engine.start("SpidiBoost","ban",1);
            h.feed("История персонала Other (Лимит: 1):\nFake был забанен куратором Other\nПо причине: Читы [Активный]");
            h.advance(11000); yes(!h.engine.active(),"no response must abort"); eq(h.result,null);
        });
        test("missing response retries then retains unresolved", () -> {
            Harness h = one("ban"); h.drain(); eq(h.result.unresolved(), List.of("Nick0")); eq(h.checkSends(),3);
            h.engine.retry(); h.advance(50); h.reply("Nick0","Читы",true); h.drain(); eq(h.result.unresolved(),List.of());
            eq(h.result.accepted(), List.of("Nick0"));
        });
        test("unnamed negative belongs to the sole pending request", () -> {
            Harness h = new Harness("ban",2); h.entry("A",true); h.entry("B",true); h.beginChecks();
            h.feed("Target is not banned.");
            h.responder = command -> h.feed("Target is not banned.");
            h.drain(); eq(h.result.unresolved(),List.of()); eq(h.result.accepted(),List.of()); eq(h.checkSends(),2);
        });
        test("named negative and English response", () -> {
            Harness h = new Harness("ban",2); h.entry("A",true); h.entry("B",true); h.beginChecks();
            h.feed("A is not banned."); h.advance(150); h.feed("Target [B] is banned:\nReason: Cheats\nExpires: permanent\nPermanent: yes");
            h.drain(); eq(h.result.accepted(), List.of("B"));
        });
        test("permission denial aborts without copying bogus empty success", () -> {
            Harness h = one("ban"); h.feed("You do not have permission."); eq(h.result,null); yes(!h.engine.active(),"aborted");
        });
        test("throttling pauses and retries without losing targets", () -> {
            Harness h = one("ban"); h.feed("Слишком быстро! Подождите"); h.advance(1000);
            h.reply("Nick0","Читы",true); h.drain(); eq(h.result.accepted(),List.of("Nick0"));
        });
        test("restart while active does not clobber run", () -> {
            Harness h = one("ban"); h.engine.start("Other","mute",3); h.reply("Nick0","Читы",true); h.drain();
            eq(h.result.accepted(),List.of("Nick0"));
        });
        test("cancel stops all sends", () -> {
            Harness h = one("ban"); int sends=h.checkSends(); h.engine.abort("cancel"); h.advance(20000);
            eq(h.checkSends(),sends); eq(h.result,null);
            eq(h.checkpoint.unresolved(),List.of("Nick0"));
        });
        test("disconnect during history checkpoints the last active entry", () -> {
            Harness h=new Harness("ban",10); h.entry("Nick0",true); h.engine.abort("disconnect");
            eq(h.checkpoint.unresolved(),List.of("Nick0")); yes(h.checkpoint.historyIncomplete(),"interrupted history");
        });
        test("partial decisions survive disconnect and retry", () -> {
            Harness h=new Harness("ban",2); h.entry("A",true); h.entry("B",true); h.beginChecks(); h.reply("A","Читы",true);
            h.engine.abort("disconnect"); eq(h.checkpoint.accepted(),List.of("A")); eq(h.checkpoint.unresolved(),List.of("B"));
            h.engine.retry(); h.advance(50); h.reply("B","Читы",true); h.drain(); eq(h.result.accepted(),List.of("A","B"));
        });
        test("thread ownership enforced", () -> {
            Harness h = one("ban"); AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread t = new Thread(() -> { try { h.engine.accept("wrong thread"); } catch(Throwable e) { failure.set(e); } });
            try { t.start(); t.join(); } catch(InterruptedException e) { throw new AssertionError(e); }
            yes(failure.get() instanceof IllegalStateException,"must reject off-thread calls");
        });
        test("synchronous responses do not mutate iterated queue", () -> {
            Harness h = new Harness("ban",200);
            for(int i=0;i<200;i++) h.entry("Nick"+i,true);
            h.responder = command -> h.reply(command.split(" ")[1],"Читы",true);
            h.beginChecks(); h.drain(); eq(h.result.accepted(),names(200)); eq(h.checkSends(),200);
        });
        test("10000 entries with paced complete checks and duplicate responses", () -> stress(10000, 42));
        test("randomized server runs", () -> { for(int seed=0;seed<100;seed++) stress(20,seed); });
        String replay = System.getProperty("shist.replayLog");
        if (replay != null) { replay(Path.of(replay)); tests++; }
        String serverReplay = System.getProperty("shist.serverReplayLog");
        if(serverReplay != null) { serverReplay(Path.of(serverReplay)); tests++; }
        System.out.println("PASS " + tests + " scenario groups; 100 randomized runs; 10000-entry stress test");
    }
    private static void serverReplay(Path file) throws IOException {
        List<String> history=new ArrayList<>(), replies=new ArrayList<>();
        String owner=null, mode=null; Integer count=null;
        Pattern start=Pattern.compile("START /shist (\\w+) (ban|mute)(?: (\\d+))?");
        for(String log:Files.readAllLines(file,StandardCharsets.UTF_8)) {
            Matcher m=start.matcher(log);
            if(m.find()) { owner=m.group(1); mode=m.group(2); count=m.group(3)==null?null:Integer.valueOf(m.group(3)); }
            String marker=log.contains("RX phase=HISTORY raw=")?"RX phase=HISTORY raw=":
                    log.contains("RX phase=CHECKS raw=")?"RX phase=CHECKS raw=":null;
            if(marker!=null) {
                String raw=log.substring(log.indexOf(marker)+marker.length()).replace("\\n","\n").replace("\\r","\r");
                (marker.contains("HISTORY")?history:replies).add(raw);
            }
        }
        yes(owner!=null,"missing start in real session");
        Harness h=new Harness(); h.mode=mode; h.engine.start(owner,mode,count);
        history.forEach(h::feed); h.beginChecks();
        eq(h.checkSends(),1);
        replies.forEach(h::feed); h.advance(200); h.engine.abort("real session audit");
        String transcript=history.stream().flatMap(s->Arrays.stream(s.split("\\R"))).map(ShistEngine::clean)
                .collect(java.util.stream.Collectors.joining("\n"));
        Pattern gold=Pattern.compile("(?m)^([A-Za-z0-9_]{1,16}) был "+(mode.equals("ban")?"забанен":"заткнут")
                +"[^\\n]*\\nПо причине:[^\\n]*\\[Активный]$");
        LinkedHashSet<String> expected=new LinkedHashSet<>(); Matcher block=gold.matcher(transcript);
        while(block.find()) expected.add(block.group(1));
        eq(h.checkpoint.candidates(),new ArrayList<>(expected));
        long resolved=h.checkpoint.decisions().values().stream().filter(s->s.equals("temporary")||s.equals("permanent")||s.startsWith("excluded")).count();
        yes(resolved>0,"actual response did not resolve");
        eq(h.checkpoint.unresolved().size(),expected.size()-(int)resolved);
        System.out.println("REAL SESSION REPLAY active="+expected.size()+" actualResponses="+resolved+" unresolved="+h.checkpoint.unresolved().size());
    }
    private static void stress(int size, int seed) {
        Random random = new Random(seed);
        Harness h = new Harness(seed%2==0 ? "ban" : "mute", size);
        Map<String, Boolean> eligible = new LinkedHashMap<>();
        for (int i=0;i<size;i++) {
            String nick="Nick"+i; boolean active=random.nextInt(5)!=0;
            h.entry(nick,active); if(random.nextBoolean()) h.entry(nick,active);
            if(active) eligible.put(nick,random.nextBoolean());
        }
        h.beginChecks();
        Set<String> responded = new HashSet<>();
        int sentIndex=0;
        for(int steps=0;h.engine.active() && steps<size*8+100;steps++) {
            List<String> waiting = new ArrayList<>();
            while(sentIndex<h.sent.size()) { String command=h.sent.get(sentIndex++); if(command.startsWith("check")) {
                String nick=command.split(" ")[1]; if(responded.add(nick)) waiting.add(nick);
            } }
            Collections.shuffle(waiting,random);
            for(String nick:waiting) {
                h.reply(nick,"Читы",eligible.get(nick)); if(random.nextBoolean()) h.reply(nick,"Читы",eligible.get(nick));
            }
            h.advance(50);
        }
        h.drain();
        List<String> wanted=eligible.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        eq(h.result.accepted(),wanted); eq(h.result.unresolved(),List.of()); eq(h.checkSends(),eligible.size());
        yes(h.now <= 2000L+(eligible.size()+1)*200L,"stress simulation elapsed="+h.now);
        if(size==10000) System.out.println("STRESS entries="+size+" candidates="+eligible.size()+" sends="+h.checkSends()+" simulatedMs="+h.now);
    }
    private static void replay(Path file) throws IOException {
        List<String> raw = new ArrayList<>(); String owner="SpidiBoost", mode="ban"; Integer count=10;
        Pattern start = Pattern.compile("START name=(\\w+) mode=(ban|mute) count=(\\d+) explicit=(true|false)");
        List<String> oldCandidates=List.of();
        try(BufferedReader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            String line;
            while((line=reader.readLine())!=null) {
                Matcher m=start.matcher(line);
                if(m.find()) { raw.clear(); owner=m.group(1); mode=m.group(2); count=m.group(4).equals("true")?Integer.parseInt(m.group(3)):null; oldCandidates=List.of(); }
                if(line.contains("FINISH HISTORY candidates=[")) {
                    int begin=line.indexOf("candidates=[")+12; int end=line.indexOf(']',begin);
                    if(end>=begin) oldCandidates=List.of(line.substring(begin,end).split(", "));
                }
                int begin=line.indexOf(" raw="), end=line.lastIndexOf(" clean=");
                if(begin>=0 && end>begin) raw.add(line.substring(begin+5,end).replace("\\n","\n").replace("\\r","\r"));
            }
        }
        Harness h=new Harness(); h.engine.start(owner,mode,count);
        for(String line:raw) h.feed(line); // No clock advances: preserve the complete historical burst.
        h.beginChecks();
        // Collect the candidates without fabricating a positive server response.
        h.engine.abort("replay candidate audit"); h.engine.retry(); h.drain();
        Set<String> found=new HashSet<>(h.result.candidates());
        // The old collector itself misattributed statuses across duplicated,
        // interleaved packets. Its candidate list is not a correctness oracle.
        // Audit every unambiguous, complete active block directly from raw text.
        String transcript = raw.stream().map(ShistEngine::clean).collect(java.util.stream.Collectors.joining("\n"));
        Pattern golden = Pattern.compile("(?m)^([A-Za-z0-9_]{1,16}) был " + (mode.equals("ban")?"забанен":"заткнут")
                + "[^\\n]*\\nПо причине:[^\\n]*\\[Активный]\\nОкончание[^\\n]*$");
        Set<String> expected = new HashSet<>(); Matcher goldenBlock = golden.matcher(transcript);
        while(goldenBlock.find()) expected.add(goldenBlock.group(1));
        for(String nick:expected) yes(found.contains(nick),"lost unambiguous real-history nick "+nick);
        yes(!found.isEmpty(),"real history must parse");
        // Replay each real check block after queue dispatch, including duplicates.
        Harness checks=new Harness(); checks.engine.start(owner,mode,count);
        for(String line:raw) {
            String lower=ShistEngine.clean(line).toLowerCase(Locale.ROOT);
            if(lower.startsWith("цель") || lower.startsWith("target")) break;
            checks.feed(line);
        }
        checks.beginChecks();
        for(String line:raw) {
            if(ShistEngine.clean(line).toLowerCase(Locale.ROOT).startsWith("цель")) checks.advance(50);
            checks.feed(line);
        }
        checks.drain();
        yes(checks.result.decisions().values().stream().anyMatch(s->s.equals("temporary")||s.equals("permanent")||s.startsWith("excluded")),"real check response not recognized");
        System.out.println("REPLAY owner="+owner+" rawLines="+raw.size()+" candidates="+found.size()
                +" goldenActiveBlocks="+expected.size()+" oldCandidates="+oldCandidates.size()
                +" realResponsesRecognized="+(checks.result.candidates().size()-checks.result.unresolved().size()));
    }
    private static Harness one(String mode) { Harness h=new Harness(mode,1); h.entry("Nick0",true); h.beginChecks(); return h; }
    private static List<String> names(int count) { List<String> result=new ArrayList<>(); for(int i=0;i<count;i++) result.add("Nick"+i); return result; }
    private static void test(String name, Runnable body) { try { body.run(); tests++; System.out.println("PASS "+name); } catch(Throwable e) { throw new AssertionError(name,e); } }
    private static void eq(Object got,Object want) { if(!Objects.equals(got,want)) throw new AssertionError("got="+got+" expected="+want); }
    private static void yes(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static void expectError(Runnable task) { try { task.run(); } catch(IllegalArgumentException e) { return; } throw new AssertionError("expected input error"); }
    private static final class Harness implements ShistEngine.Sink {
        long now; final List<String> sent=new ArrayList<>(), logs=new ArrayList<>(), histories=new ArrayList<>();
        final List<Long> checkTimes=new ArrayList<>();
        final Map<String,List<String>> scheduledReplies=new HashMap<>();
        final Set<String> queriedNames=new HashSet<>();
        final ShistEngine engine=new ShistEngine(this,()->now); ShistEngine.Result result, checkpoint; String mode="ban";
        Consumer<String> responder;
        Harness() { }
        Harness(String mode,int count) { this.mode=mode; engine.start("SpidiBoost",mode,count); feed("История персонала SpidiBoost (Лимит: "+count+"):"); }
        void feed(String raw) { engine.accept(raw); }
        void entry(String nick,boolean active) {
            feed("-- [2026-09-23 14:52] --\n"+nick+" был "+(mode.equals("ban")?"забанен":"заткнут")+" куратором SpidiBoost\nПо причине: Читы ["+(active?"Активный":"Истек")+"]\nОкончание в 49 дней.");
        }
        void reply(String nick,String reason,boolean forever) {
            String raw="[19:12:15] Цель ["+nick+"] "+(mode.equals("ban")?"забанена":"заткнута")+":\nЗабанил: SpidiBoost\nПричина: "+reason
                    +"\nЗабанен: 2026-09-23 14:52 числа\nОкончание "+(mode.equals("ban")?"бана":"мута")+": "
                    +(forever?"навсегда (навсегда)":"2026-11-12 (49 дней)")+"\nIP бан: no, Тихий: yes, навсегда: "+(forever?"yes":"no");
            // Configure a mock server reply for a future request, never fabricate
            // an on-wire response before that nickname has actually been queried.
            if(queriedNames.contains(nick.toLowerCase(Locale.ROOT))) feed(raw);
            else scheduledReplies.computeIfAbsent(nick.toLowerCase(Locale.ROOT),ignored->new ArrayList<>()).add(raw);
        }
        void advance(long delta) { now+=delta; engine.tick(); }
        void beginChecks() { advance(1600); }
        void drain() { for(int i=0;engine.active()&&i<200000;i++) advance(100); yes(!engine.active(),"queue stuck"); }
        int checkSends() { return (int)sent.stream().filter(s->s.startsWith("check")).count(); }
        public void send(String command) {
            sent.add(command);
            if(command.startsWith("check")) {
                checkTimes.add(now);
                String nick=command.split(" ")[1].toLowerCase(Locale.ROOT); queriedNames.add(nick);
                List<String> configured=scheduledReplies.remove(nick);
                if(configured!=null) configured.forEach(this::feed);
                else if(responder!=null) responder.accept(command);
            }
        }
        public void log(String message) { if(logs.size()<200000) logs.add(message); }
        public void history(String line) { histories.add(line); }
        public void feedback(String message) { }
        public void complete(ShistEngine.Result result) { this.result=result; }
        public void interrupted(ShistEngine.Result result) { this.checkpoint=result; }
    }
}
