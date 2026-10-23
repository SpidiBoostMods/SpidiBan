package net.spidiboost.shist;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.spidiboost.spidiban.SpidiBanClient;
import java.nio.file.*;
import java.util.*;

/** Isolated title-screen integration. No real server, bans, clipboard changes or user instance. */
public final class NativeShistProbe implements ClientModInitializer {
    private int ticks;private boolean finished;
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Fixture implements ShistEngine.Sink {
        long now;int clears;final List<String> sent=new ArrayList<>(),history=new ArrayList<>();ShistEngine.Result result,partial;
        final ShistEngine engine=new ShistEngine(this,()->now);
        public void send(String command){sent.add(command);}
        public void log(String message){}
        public void history(String line){history.add(line);}
        public void feedback(String message){}
        public void complete(ShistEngine.Result value){result=value;}
        public void interrupted(ShistEngine.Result value){partial=value;}
        public void clearChat(){clears++;ShistSession.requestChatClear();}
        void start(String mode,int count){engine.start("QAStaff",mode,count);engine.accept("История персонала QAStaff (Лимит: "+count+"):");for(int i=0;i<count;i++)engine.accept("-- [2026-10-13 12:00] --\nQA"+i+" был "+(mode.equals("ban")?"забанен":"заткнут")+" куратором QAStaff\nПо причине: Читы [Активный]\nОкончание в 1 день");}
        void advance(long delta){now+=delta;engine.tick();}
        void reply(int i,String mode,Text reason){engine.accept(StyledMessages.formatted(Text.empty().append(Text.literal("Цель [QA"+i+"] "+(mode.equals("ban")?"забанена":"заткнута")+":\nПричина: ").formatted(Formatting.GOLD)).append(reason).append(Text.literal("\nОкончание "+(mode.equals("ban")?"бана":"мута")+": навсегда\nIP бан: no, навсегда: yes").formatted(Formatting.GOLD))));}
    }
    @Override public void onInitializeClient(){ClientTickEvents.END_CLIENT_TICK.register(client->{
        if(finished||++ticks<40||!(client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen)||client.getOverlay()!=null)return;finished=true;
        Path report=client.runDirectory.toPath().resolve("native-shist-results.txt");var evidence=new ArrayList<String>();Object original=null;
        try{
            var nicknameMethod=SpidiBanClient.class.getDeclaredMethod("nicknamesFile",MinecraftClient.class);nicknameMethod.setAccessible(true);
            Path nicknamePath=(Path)nicknameMethod.invoke(null,client);
            require(nicknamePath.equals(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize().resolve("spidiban-nicknames.txt")),"nickname file outside Fabric config directory");
            require(Files.isRegularFile(nicknamePath)&&Files.readString(nicknamePath).contains("\n# Разделители"),"nickname template missing or malformed");
            evidence.add("PASS native client creates nickname file in Fabric config directory with real line breaks");
            var field=ShistSession.class.getDeclaredField("engine");field.setAccessible(true);original=field.get(null);
            require(Arrays.stream(net.minecraft.client.network.ClientPlayNetworkHandler.class.getDeclaredMethods()).anyMatch(m->m.getName().contains("spidiban$game")),"packet mixin not applied");
            var dispatcher=new CommandDispatcher<FabricClientCommandSource>();SpidiBanClient.registerCommands(dispatcher);ShistClient.registerCommands(dispatcher);
            for(String alias:List.of("sb","spidiban","spidiboost")){
                var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse(alias+" ",null)).get().getList().stream().map(s->s.getText()).toList();
                require(suggestions.containsAll(List.of("accept","cheats","delay","stop","shist")),"root commands/TAB changed");
            }
            for(String alias:List.of("sb","spidiban","spidiboost"))for(String phase:List.of("history","checks"))for(boolean fallback:List.of(false,true)){
                var test=new Fixture();test.start("ban",2);if(phase.equals("checks")){test.advance(1600);test.reply(0,"ban",Text.literal("Читы").formatted(Formatting.GOLD));}
                field.set(null,test.engine);int sent=test.sent.size();
                if(fallback)require(SpidiBanClient.tryHandleInput(" /"+alias.toUpperCase(Locale.ROOT)+" STOP "),"fallback did not own stop");else require(dispatcher.execute(alias+" stop",null)==1,"dispatcher stop failed");
                test.advance(20000);require(!test.engine.active()&&test.sent.size()==sent&&test.partial!=null,"stop left active sends");
                if(phase.equals("checks"))require(test.partial.accepted().equals(List.of("QA0"))&&test.partial.unresolved().equals(List.of("QA1")),"stop lost partial decisions");
            }
            evidence.add("PASS all three aliases: Brigadier + uppercase/slash fallback stop history/checks, preserve partial results, zero sends after stop");
            var automation=net.spidiboost.spidiban.SpidiBanAutomation.class;
            var state=automation.getDeclaredField("state");state.setAccessible(true);
            var queue=automation.getDeclaredField("PENDING");queue.setAccessible(true);
            for(String alias:List.of("sb","spidiban","spidiboost")){
                var test=new Fixture();test.start("ban",1);field.set(null,test.engine);
                @SuppressWarnings({"rawtypes","unchecked"}) Object waiting=Enum.valueOf((Class)state.getType(),"WAITING_TO_SEND");state.set(null,waiting);
                @SuppressWarnings("unchecked") var pending=(Deque<String>)queue.get(null);pending.add("QAQueued");
                dispatcher.execute(alias+" stop",null);
                require(!test.engine.active()&&!net.spidiboost.spidiban.SpidiBanAutomation.isActive()&&pending.isEmpty(),"stop did not clear both queues");
            }
            evidence.add("PASS all aliases stop active ban-menu automation and shist together, clear queued commands, unlock controls");
            for(String mode:List.of("ban","mute")){
                var hud=client.inGameHud.getChatHud();hud.addToMessageHistory("/qa-saved-command");hud.addMessage(Text.literal("QA old chat"));
                var test=new Fixture();test.start(mode,20);require(test.clears==1,"20 history cleanup");ShistSession.clearPendingChat();
                require(hud.getMessageHistory().contains("/qa-saved-command"),"cleanup deleted typed command history");
                var messages=hud.getClass().getDeclaredField("messages");messages.setAccessible(true);require(((List<?>)messages.get(hud)).isEmpty(),"native ChatHud did not clear");
                test.advance(1600);for(int i=0;i<20;i++){
                    Text reason=i==0?Text.literal("Лив с проверки").formatted(Formatting.AQUA):i==1?Text.literal("FP_RW").formatted(Formatting.GOLD):Text.literal("Читы").formatted(Formatting.GOLD);
                    test.reply(i,mode,reason);test.advance(150);
                }
                require(test.result!=null&&test.clears==2&&test.history.size()>=60,"cleanup lost queue/history");ShistSession.clearPendingChat();
                require(test.result.accepted().size()==(mode.equals("ban")?18:20),"native styled reasons misclassified");
                evidence.add("PASS "+mode+": native Text/inherited RGB styles -> parser, real ChatHud.clear(false), saved input/history/20 decisions retained");
            }
            Text label=Text.literal("[18:26:29] Причина: ").formatted(Formatting.AQUA).append(Text.literal("Читы").formatted(Formatting.GOLD));
            require(!ReasonColors.hasAqua(StyledMessages.formatted(label),false),"aqua label excluded gold reason");
            Text parent=Text.empty().formatted(Formatting.AQUA).append(Text.literal("Причина: Лив\nс проверки"));
            require(ReasonColors.hasAqua(StyledMessages.formatted(parent).split("\n")[0],false),"inherited parent RGB lost");
            evidence.add("PASS aqua reason only; timestamps/label ignored; inherited and newline Text colors retained");
            Files.writeString(report,String.join("\n",evidence)+"\n");
        }catch(Throwable error){error.printStackTrace();try{Files.writeString(report,"FAIL "+error+"\n");}catch(Exception ignored){}}
        finally{try{if(original!=null){var f=ShistSession.class.getDeclaredField("engine");f.setAccessible(true);f.set(null,original);}}catch(Exception ignored){}client.scheduleStop();}
    });}
}
