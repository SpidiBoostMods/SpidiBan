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
        long now = 1_000_000_000L;

        Menu(MinecraftClient client) { this.client = client; }
        public boolean open() { return open; }
        public boolean root() { return root; }
        public int id() { return id; }
        public int size() { return handler == null ? 0 : handler.slots.size(); }
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
        skipped.tick();
        SpidiBanAutomation.onIncomingMessage("PLAYER DOES NOT EXIST");
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
        SpidiBanAutomation.setDelaySeconds(10.01);
        var loaded = SpidiBanAutomation.class.getDeclaredField("configLoaded");
        loaded.setAccessible(true);
        loaded.setBoolean(null, false);
        require(SpidiBanAutomation.delayDescription().equals("10.01 сек"), "saved delay did not reload");
        SpidiBanAutomation.setDelaySeconds(5);
        return "PASS accept/cheats: actual Minecraft container slots, immediate next banrw/reason, two confirmation clicks only at previous confirmation + delay + 10ms; no cooldown timeout; cancellation; all aliases decimal/comma delay";
    }
}
