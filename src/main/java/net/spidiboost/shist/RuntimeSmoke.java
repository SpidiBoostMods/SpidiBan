package net.spidiboost.shist;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/** Opt-in development smoke run: no server login or commands, automatically exits its own client. */
final class RuntimeSmoke {
    private static int ticks;
    private static boolean finished;
    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (finished || ++ticks < 100 || client.currentScreen == null) return;
            finished = true;
            Path report = client.runDirectory.toPath().resolve("shist-smoke-result.txt");
            try {
                List<String> evidence = new ArrayList<>();
                Class<?> network = net.minecraft.client.network.ClientPlayNetworkHandler.class;
                require(Arrays.stream(network.getDeclaredMethods()).anyMatch(m -> m.getName().contains("spidiban$game")), "packet mixin not applied");
                evidence.add("PASS packet mixin applied to ClientPlayNetworkHandler");
                CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
                for (String alias : List.of("sb", "spidiban", "spidiboost")) {
                    dispatcher.register(ClientCommandManager.literal(alias).executes(ctx -> 7)
                            .then(ClientCommandManager.literal("accept").executes(ctx -> 8))
                            .then(ClientCommandManager.literal("cheats").executes(ctx -> 9))
                            .then(ClientCommandManager.literal("delay").executes(ctx -> 10)));
                }
                ShistClient.registerCommands(dispatcher);
                for (String alias : List.of("sb", "spidiban", "spidiboost")) {
                    require(dispatcher.execute(alias, null) == 7, "root changed");
                    require(dispatcher.execute(alias+" accept",null)==8,"accept changed");
                    require(dispatcher.execute(alias+" cheats",null)==9,"cheats changed");
                    require(dispatcher.execute(alias+" delay",null)==10,"delay changed");
                    for (String command : List.of(alias+" shist SpidiBoost ban", alias+" shist SpidiBoost mute 10")) {
                        var parse = dispatcher.parse(command,null);
                        require(!parse.getReader().canRead() && parse.getContext().getCommand()!=null,"incomplete parse: "+command);
                    }
                    for (String nick : List.of("status", "cancel", "retry", "speed")) {
                        var parse = dispatcher.parse(alias+" shist "+nick+" ban 10",null);
                        require(!parse.getReader().canRead() && parse.getContext().getCommand()!=null,"reserved nickname collision: "+nick);
                    }
                    var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse(alias+" shist SpidiBoost ",null)).get();
                    var words=suggestions.getList().stream().map(s->s.getText()).toList();
                    require(words.contains("ban") && words.contains("mute"),"missing mode suggestions");
                    var counts=dispatcher.getCompletionSuggestions(dispatcher.parse(alias+" shist SpidiBoost ban ",null)).get();
                    require(counts.getList().stream().anyMatch(s->s.getText().equals("10")),"missing count suggestion");
                }
                evidence.add("PASS all aliases, root/accept/cheats/delay preserved, optional count, ban/mute/count TAB");
                String clipboardBefore=client.keyboard.getClipboard();
                try {
                    String sample="SpidiShistClipboard_A SpidiShistClipboard_B";
                    client.keyboard.setClipboard(sample);
                    require(sample.equals(client.keyboard.getClipboard()),"GLFW clipboard readback mismatch");
                    sample=java.util.stream.IntStream.range(0,10000).mapToObj(i->"SmokeNick"+i)
                            .collect(java.util.stream.Collectors.joining(" "));
                    client.keyboard.setClipboard(sample);
                    require(sample.equals(client.keyboard.getClipboard()),"large clipboard readback mismatch");
                } finally { client.keyboard.setClipboard(clipboardBefore); }
                evidence.add("PASS native macOS GLFW clipboard readback including 10000 names; previous contents restored");
                Files.writeString(report,String.join("\n",evidence)+"\n",StandardCharsets.UTF_8);
                System.out.println("SHIST_SMOKE_PASS " + report);
            } catch(Throwable error) {
                error.printStackTrace();
                try { Files.writeString(report,"FAIL "+error+"\n",StandardCharsets.UTF_8); }
                catch(Exception ignored) { }
            } finally { client.scheduleStop(); }
        });
    }
    private static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
