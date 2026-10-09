package net.spidiboost.shist;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class ShistClient implements ClientModInitializer {
    public void onInitializeClient() {
        ShistSession.init();
        ShistSession.debug("client initialized version=" + ShistSession.VERSION);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> registerCommands(dispatcher));
        if (Boolean.getBoolean("spidiban.shist.smoke")) RuntimeSmoke.register();
    }
    static void registerCommands(com.mojang.brigadier.CommandDispatcher<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> dispatcher) {
            for (String alias : new String[]{"sb", "spidiban", "spidiboost"}) {
                var shist = ClientCommandManager.literal("shist");
                for (String action : new String[]{"status", "cancel", "retry"}) {
                    shist.then(ClientCommandManager.literal(action).executes(ctx -> {
                        ShistSession.control(action, 0); return 1;
                    }));
                }
                shist.then(ClientCommandManager.literal("speed").then(ClientCommandManager.argument("commands_per_second",
                        IntegerArgumentType.integer(1, 1000)).executes(ctx -> {
                            ShistSession.control("speed", IntegerArgumentType.getInteger(ctx, "commands_per_second")); return 1;
                        })));
                var name = ClientCommandManager.argument("nickname", StringArgumentType.word()).suggests((ctx, builder) -> {
                    var network = MinecraftClient.getInstance().getNetworkHandler();
                    if (network != null) for (var entry : network.getPlayerList()) {
                        String nick = entry.getProfile().getName();
                        if (nick.toLowerCase(java.util.Locale.ROOT).startsWith(builder.getRemainingLowerCase())) builder.suggest(nick);
                    }
                    return builder.buildFuture();
                });
                for (String mode : new String[]{"ban", "mute"}) {
                    name.then(ClientCommandManager.literal(mode).executes(ctx -> {
                        ShistSession.start(nickname(ctx), mode, 10, false); return 1;
                    }).then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 10000))
                            .suggests((ctx, builder) -> {
                                for (String count : new String[]{"10", "100", "1000", "10000"})
                                    if (count.startsWith(builder.getRemaining())) builder.suggest(count, Text.literal("Количество записей (необязательно)"));
                                return builder.buildFuture();
                            }).executes(ctx -> {
                                ShistSession.start(nickname(ctx), mode,
                                        IntegerArgumentType.getInteger(ctx, "count"), true); return 1;
                            })));
                }
                // A real player may be named status/cancel/retry/speed. Keep both
                // the one-word control and nickname + ban/mute branches working.
                var nicknameNode = name.build();
                for (var node : shist.getArguments()) {
                    node.addChild(nicknameNode.getChild("ban"));
                    node.addChild(nicknameNode.getChild("mute"));
                }
                // Brigadier merges this child into the existing root, preserving accept/cheats/delay.
                dispatcher.register(ClientCommandManager.literal(alias).then(shist.then(name)));
            }
    }
    private static String nickname(com.mojang.brigadier.context.CommandContext<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> context) {
        try { return StringArgumentType.getString(context, "nickname"); }
        catch (IllegalArgumentException error) { return context.getNodes().get(2).getNode().getName(); }
    }
    public static boolean handleInput(String input) {
        try {
            ShistCommand command = ShistCommand.parse(input);
            if (command == null) return false;
            if (command.action() != null) ShistSession.control(command.action(), command.speed());
            else ShistSession.start(command.nickname(), command.mode(), command.count() == null ? 10 : command.count(), command.count() != null);
        } catch (IllegalArgumentException error) { feedback(error.getMessage()); }
        return true;
    }
    static void feedback(String message) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal("[SpidiBan] " + message), false);
    }
}
