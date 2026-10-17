package net.spidiboost.spidiban;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NativeBanProbe {
    public static int hiddenPackets;
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Menu implements SpidiBanAutomation.Menu {
        final MinecraftClient client;
        final List<String> sent = new ArrayList<>();
        final List<Integer> clicks = new ArrayList<>();
        GenericContainerScreenHandler handler;
        boolean open, root;
        int id;
        final Object connection;
        long now = 1_000_000_000L;

        Menu(MinecraftClient client) { this(client, new Object()); }
        Menu(MinecraftClient client, Object connection) { this.client = client; this.connection = connection; }
        public boolean open() { return open; }
        public boolean root() { return root; }
        public int id() { return id; }
        public int size() { return handler == null ? 0 : handler.slots.size(); }
        public String administrator() { return "LiveStaff"; }
        public Object connection() { return connection; }
        public void close() { open = false; }
        public String name(int slot) { return handler.getSlot(slot).getStack().getName().getString().toUpperCase(Locale.ROOT); }
        public void click(int slot) { clicks.add(slot); }
        public void send(String command) { sent.add(command); }
        void show(boolean root) {
            handler = GenericContainerScreenHandler.createGeneric9x3(++id, new net.minecraft.entity.player.PlayerInventory(null));
            this.root = root;
            open = true;
        }
        void item(int slot, String name) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
            handler.getSlot(slot).setStack(stack);
        }
        void tick() { SpidiBanAutomation.advance(this, now); }
        void reply(String text) { SpidiBanAutomation.capture(connection, text, now); }
        void success(String player) { reply("[Silent] Администратор LiveStaff временно забанил игрока " + player + " на 50 дней по причине 'Читы'"); }
        void prepare(SpidiBanAutomation.Route route) {
            show(true);
            item(route == SpidiBanAutomation.Route.FUNPAY ? 15 : 13, route == SpidiBanAutomation.Route.FUNPAY ? "FUNPAY" : "ЧИТЫ И МОДЫ");
            tick();
            show(false);
            item(route == SpidiBanAutomation.Route.FUNPAY ? 1 : 0, route == SpidiBanAutomation.Route.FUNPAY ? "FP_RW" : "Читы");
            tick();
            show(false);
            item(26, "ВЫДАТЬ БАН");
            if (route == SpidiBanAutomation.Route.CHEATS) {
                item(24, "Тихий бан: выкл");
                tick();
            }
        }
    }

    public static String run(MinecraftClient client) throws Exception {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        SpidiBanClient.registerCommands(dispatcher);
        for (String alias : List.of("sb", "spidiban", "spidiboost")) {
            require(dispatcher.execute(alias + " delay 5.10", null) == 1, "decimal dispatcher");
            require(SpidiBanAutomation.delayDescription().equals("5.1 сек"), "decimal seconds");
            require(dispatcher.execute(alias + " delay 10,01", null) == 1, "comma dispatcher");
            require(SpidiBanAutomation.delayDescription().equals("10.01 сек"), "comma seconds");
            SpidiBanClient.tryHandleInput("/" + alias + " delay 0,0001");
            require(SpidiBanAutomation.delayDescription().equals("0.0001 сек"), "fallback precision");
            require(dispatcher.execute(alias + " delay NaN", null) == 0, "NaN accepted");
        }
        for (var route : SpidiBanAutomation.Route.values()) {
            for (double seconds : new double[]{0, .001, 5, 5.1, 3600}) {
                SpidiBanAutomation.setDelaySeconds(seconds);
                SpidiBanAutomation.start("First Second Third", route);
                Menu menu = new Menu(client);
                menu.tick();
                require(menu.sent.equals(List.of("banrw First")), "first command delayed");
                menu.prepare(route);
                menu.tick();
                require(menu.clicks.stream().filter(s -> s == 26).count() == 2, "first double confirm");
                long first = menu.now;
                menu.open = false;
                menu.tick();
                require(menu.sent.size() == 1, "closed menu falsely counted as ban");
                menu.success("First");
                menu.tick();
                require(menu.sent.equals(List.of("banrw First", "banrw Second")), "next menu delayed");
                menu.prepare(route);
                require(menu.clicks.contains(route == SpidiBanAutomation.Route.FUNPAY ? 1 : 0), "reason not selected");
                long interval = (long)Math.ceil(seconds * 1_000_000_000D) + 10_000_000L;
                menu.now = first + interval - 1;
                for (int i = 0; i < 300; i++) menu.tick();
                require(SpidiBanAutomation.isActive(), "cooldown triggered menu timeout");
                require(menu.clicks.stream().filter(s -> s == 26).count() == 2, "confirmed before deadline");
                menu.now++;
                menu.tick();
                require(menu.clicks.stream().filter(s -> s == 26).count() == 4, "not confirmed at deadline");
                require(menu.clicks.stream().filter(s -> s == 24).count() == (route == SpidiBanAutomation.Route.CHEATS ? 2 : 0), "silent switch repeated");
                menu.open = false;
                menu.success("Second");
                menu.tick();
                require(menu.sent.size() == 3, "third menu delayed");
                SpidiBanAutomation.cancel();
                menu.now += interval;
                menu.tick();
                require(menu.sent.size() == 3, "sent after cancellation");
            }
        }
        SpidiBanAutomation.setDelaySeconds(5);
        SpidiBanAutomation.start("First Missing Third", SpidiBanAutomation.Route.FUNPAY);
        Menu skipped = new Menu(client);
        skipped.tick();
        skipped.prepare(SpidiBanAutomation.Route.FUNPAY);
        skipped.tick();
        skipped.open = false;
        skipped.success("First");
        skipped.tick();
        skipped.reply("PLAYER DOES NOT EXIST");
        skipped.tick();
        require(skipped.sent.equals(List.of("banrw First", "banrw Missing", "banrw Third")), "missing nick stalled queue");
        skipped.prepare(SpidiBanAutomation.Route.FUNPAY);
        skipped.now += 5_009_999_999L;
        skipped.tick();
        require(skipped.clicks.stream().filter(s -> s == 26).count() == 2, "skip bypassed cooldown");
        skipped.now++;
        skipped.tick();
        require(skipped.clicks.stream().filter(s -> s == 26).count() == 4, "skip restarted cooldown");
        SpidiBanAutomation.cancel();
        for (var route : SpidiBanAutomation.Route.values()) {
            SpidiBanAutomation.start("Frolok_q67 Frolok_q68 Next", route);
            Menu rejected = new Menu(client);
            rejected.tick();
            rejected.prepare(route);
            rejected.tick();
            rejected.reply("Игрок Frolok_q67 уже забанен и у вас нету прав на новый бан");
            rejected.tick();
            rejected.prepare(route);
            rejected.tick();
            require(rejected.clicks.stream().filter(s -> s == 26).count() == 4, "rejection added cooldown");
            rejected.open = false;
            rejected.reply("Bans » Выдан бан Frolok_q68. FP_RW (навсегда)");
            rejected.tick();
            require(rejected.sent.size() == 2, "Bans mod feedback counted as evidence");
            rejected.reply("Игрок Frolok_q67 уже забанен и у вас нету прав на новый бан");
            rejected.success("Frolok_q680");
            rejected.reply("Администратор OtherStaff временно забанил игрока Frolok_q68 на 50 дней по причине 'Читы'");
            SpidiBanAutomation.capture(new Object(), "Администратор LiveStaff временно забанил игрока Frolok_q68 на 50 дней по причине 'Читы'", rejected.now);
            rejected.tick();
            require(rejected.sent.size() == 2, "foreign/stale confirmation accepted");
            rejected.reply("Игрок Frolok_q68 уже забанен и у вас нету прав на новый бан");
            rejected.tick();
            rejected.prepare(route);
            rejected.tick();
            require(rejected.clicks.stream().filter(s -> s == 26).count() == 6, "second rejected nick added delay");
            SpidiBanAutomation.cancel();
        }
        SpidiBanAutomation.start("Unknown Never", SpidiBanAutomation.Route.FUNPAY);
        Menu absent = new Menu(client);
        absent.tick();
        absent.prepare(SpidiBanAutomation.Route.FUNPAY);
        absent.tick();
        absent.open = false;
        for (int i = 0; i < 250; i++) absent.tick();
        require(!SpidiBanAutomation.isActive() && absent.sent.size() == 1, "unconfirmed ban retried or advanced");
        var wire = new net.minecraft.network.ClientConnection(net.minecraft.network.NetworkSide.CLIENTBOUND);
        var network = new io.netty.channel.embedded.EmbeddedChannel(wire);
        try {
            for (int variant = 0; variant < 3; variant++) {
            SpidiBanAutomation.start("Hidden Next", SpidiBanAutomation.Route.FUNPAY);
            Menu hidden = new Menu(client, wire);
            hidden.now = System.nanoTime();
            hidden.tick();
            hidden.prepare(SpidiBanAutomation.Route.FUNPAY);
            hidden.tick();
            Text broadcast = Text.literal("[Silent] Администратор LiveStaff временно забанил игрока Hidden на 50 дней по причине 'Читы'");
            var raw = new net.minecraft.text.Decoration("%s", List.of(net.minecraft.text.Decoration.Parameter.CONTENT), net.minecraft.text.Style.EMPTY);
            var rawType = new net.minecraft.network.message.MessageType.Parameters(
                    net.minecraft.registry.entry.RegistryEntry.of(new net.minecraft.network.message.MessageType(raw, raw)), Text.literal("Server"), java.util.Optional.empty());
            var playerChat = new net.minecraft.network.message.MessageType.Parameters(
                    net.minecraft.registry.entry.RegistryEntry.of(new net.minecraft.network.message.MessageType(
                            net.minecraft.network.message.MessageType.CHAT_TEXT_DECORATION, raw)), Text.literal("Impostor"), java.util.Optional.empty());
            network.writeInbound(new net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket(broadcast, playerChat));
            hidden.now = System.nanoTime();
            hidden.tick();
            require(hidden.sent.size() == 1, "player chat spoof confirmed a ban");
            var direct = new net.minecraft.network.packet.s2c.play.GameMessageS2CPacket(broadcast, false);
            network.writeInbound(switch (variant) {
                case 0 -> direct;
                case 1 -> new net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket(broadcast, rawType);
                default -> new net.minecraft.network.packet.s2c.play.BundleS2CPacket(List.of(direct));
            });
            hidden.now = System.nanoTime();
            hidden.tick();
            require(hiddenPackets == (variant + 1) * 2 && hidden.sent.size() == 2,
                    "hidden raw packet missed: variant=" + variant + ", hidden=" + hiddenPackets + ", sent=" + hidden.sent);
            SpidiBanAutomation.cancel();
            }
        } finally { network.finishAndReleaseAll(); }
        SpidiBanAutomation.setDelaySeconds(10.01);
        var loaded = SpidiBanAutomation.class.getDeclaredField("configLoaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
        require(SpidiBanAutomation.delayDescription().equals("10.01 сек"), "saved delay did not reload");
        SpidiBanAutomation.setDelaySeconds(5);
        return "PASS accept/cheats: server-confirmed bans only; exact administrator/victim; Frolok_q67/q68 duplicate rejection without cooldown; closed menus/Bans feedback/foreign connections ignored; unknown result stops without reban; actual ClientConnection + Netty system/profileless/bundle packets cancelled by another mixin still captured; player-chat spoof rejected; decimal delays and immediate next menus preserved";
    }
}
