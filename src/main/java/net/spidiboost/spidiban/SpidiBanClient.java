package net.spidiboost.spidiban;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Entry point for the standalone SpidiBan client mod. */
public final class SpidiBanClient implements ClientModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("SpidiBan");
    private static final String FILE_NAME = "spidiban-nicknames.txt";
    private static final String FILE_TEMPLATE = "# Вставьте никнеймы ниже.\\n"
            + "# Разделители: пробел, запятая или точка с запятой.\\n"
            + "# Сохраните файл, затем используйте /sb accept (FunPay) или /sb cheats (Читы).\\n"
            + "# Задержка между банами: /sb delay 6\\n\\n";

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("sb")
                    .executes(context -> openEditor())
                    .then(ClientCommandManager.literal("accept").executes(context -> accept(SpidiBanAutomation.Route.FUNPAY)))
                    .then(ClientCommandManager.literal("cheats").executes(context -> accept(SpidiBanAutomation.Route.CHEATS)))
                    .then(ClientCommandManager.literal("cancel").executes(context -> cancel()))
                    .then(delayCommand()));
            dispatcher.register(ClientCommandManager.literal("spidiban")
                    .executes(context -> openEditor())
                    .then(ClientCommandManager.literal("accept").executes(context -> accept(SpidiBanAutomation.Route.FUNPAY)))
                    .then(ClientCommandManager.literal("cheats").executes(context -> accept(SpidiBanAutomation.Route.CHEATS)))
                    .then(ClientCommandManager.literal("cancel").executes(context -> cancel()))
                    .then(delayCommand()));
            dispatcher.register(ClientCommandManager.literal("spidiboost")
                    .executes(context -> openEditor())
                    .then(ClientCommandManager.literal("accept").executes(context -> accept(SpidiBanAutomation.Route.FUNPAY)))
                    .then(ClientCommandManager.literal("cheats").executes(context -> accept(SpidiBanAutomation.Route.CHEATS)))
                    .then(ClientCommandManager.literal("cancel").executes(context -> cancel()))
                    .then(delayCommand()));
            LOGGER.info("Registered local commands: /sb, /spidiban and /spidiboost");
        });
        ClientTickEvents.END_CLIENT_TICK.register(SpidiBanAutomation::tick);
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(c -> SpidiBanAutomation.cancel());
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                SpidiBanAutomation.onIncomingMessage(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                SpidiBanAutomation.onIncomingMessage(message.getString()));
    }

    private static int openEditor() {
        MinecraftClient client = MinecraftClient.getInstance();
        Path file = nicknamesFile(client);
        try {
            if (Files.notExists(file)) Files.writeString(file, FILE_TEMPLATE, StandardCharsets.UTF_8);
            CompletableFuture.runAsync(() -> openInTextEditor(file));
            feedback("Открыт " + file.getFileName() + ". Сохраните его и введите /sb accept.");
        } catch (IOException e) {
            LOGGER.error("Could not create SpidiBan nickname file", e);
            feedback("Не удалось создать " + FILE_NAME + ".");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> delayCommand() {
        return ClientCommandManager.literal("delay")
                .executes(context -> showDelay())
                .then(ClientCommandManager.argument("seconds", DoubleArgumentType.doubleArg(0.0D, 3600.0D))
                        .executes(context -> setDelay(DoubleArgumentType.getDouble(context, "seconds"))));
    }

    private static int accept(SpidiBanAutomation.Route route) {
        Path file = nicknamesFile(MinecraftClient.getInstance());
        try {
            if (Files.notExists(file)) {
                feedback("Сначала откройте файл через /sb.");
                return Command.SINGLE_SUCCESS;
            }
            SpidiBanAutomation.start(Files.readString(file, StandardCharsets.UTF_8), route);
        } catch (IOException e) {
            LOGGER.error("Could not read SpidiBan nickname file", e);
            feedback("Не удалось прочитать " + FILE_NAME + ".");
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int showDelay() {
        feedback("Задержка между банами: " + SpidiBanAutomation.delayDescription() + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int setDelay(double seconds) {
        SpidiBanAutomation.setDelaySeconds(seconds);
        feedback("Задержка между банами установлена: " + SpidiBanAutomation.delayDescription() + ".");
        return Command.SINGLE_SUCCESS;
    }

    private static int cancel() {
        SpidiBanAutomation.cancel();
        return Command.SINGLE_SUCCESS;
    }

    /** Handles only the exact aliases and their accept subcommand before network transmission. */
    public static boolean tryHandleInput(String input) {
        if (net.spidiboost.shist.ShistClient.handleInput(input)) return true;
        String command = input == null ? "" : input.trim();
        if (command.startsWith("/")) command = command.substring(1);
        String[] parts = command.split("\\s+", 2);
        boolean alias = parts.length > 0 && (parts[0].equalsIgnoreCase("sb")
                || parts[0].equalsIgnoreCase("spidiban")
                || parts[0].equalsIgnoreCase("spidiboost"));
        if (!alias) return false;
        if (parts.length == 1) {
            openEditor();
            return true;
        }
        String action = parts[1].trim();
        if (action.equalsIgnoreCase("accept")) accept(SpidiBanAutomation.Route.FUNPAY);
        else if (action.equalsIgnoreCase("cheats")) accept(SpidiBanAutomation.Route.CHEATS);
        else if (action.equalsIgnoreCase("cancel")) cancel();
        else if (action.equalsIgnoreCase("delay")) showDelay();
        else if (action.regionMatches(true, 0, "delay ", 0, 6)) {
            try {
                setDelay(Double.parseDouble(action.substring(6).trim()));
            } catch (NumberFormatException exception) {
                feedback("Задержка должна быть числом от 0 до 3600 секунд.");
            }
        } else return false;
        return true;
    }

    private static Path nicknamesFile(MinecraftClient client) {
        return client.runDirectory.toPath().resolve(FILE_NAME);
    }

    private static void openInTextEditor(Path file) {
        try {
            if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")) {
                new ProcessBuilder("open", "-a", "TextEdit", file.toString()).start();
                LOGGER.info("Opened SpidiBan nickname file in TextEdit: {}", file);
                return;
            }
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                LOGGER.warn("Desktop file opening is unavailable; file is at {}", file);
                return;
            }
            Desktop.getDesktop().open(file.toFile());
        } catch (IOException | SecurityException e) {
            LOGGER.error("Could not open SpidiBan nickname file in the default editor", e);
        }
    }

    static void feedback(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[SpidiBan] ").formatted(Formatting.DARK_AQUA)
                    .append(Text.literal(message)), false);
        }
    }

}
