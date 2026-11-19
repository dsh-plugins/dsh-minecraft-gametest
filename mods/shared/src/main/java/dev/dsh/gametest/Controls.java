package dev.dsh.gametest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

final class Controls {
    private final Minecraft client;
    private final GameUi ui;

    Controls(Minecraft client, GameUi ui) {
        this.client = client;
        this.ui = ui;
    }

    GameBridge.GameTask key(Arguments args) {
        String name = args.text("key").toLowerCase(Locale.ROOT).replace('_', '.');
        if (name.startsWith("kp.")) name = "keypad." + name.substring(3);
        int code;
        try {
            var key = InputConstants.getKey("key.keyboard." + name);
            if (key == InputConstants.UNKNOWN) throw new IllegalArgumentException();
            code = key.getValue();
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown keyboard key " + args.text("key"));
        }
        int ticks = args.integer("ticks", 1);
        var event = new KeyEvent(code, 0, modifiers(args.integer("modifiers", 0)));
        long window = client.getWindow().handle();
        client.keyboardHandler.keyPress(window, InputConstants.PRESS, event);
        return new GameBridge.GameTask() {
            int remaining = ticks;

            @Override
            public ToolResult tick() {
                return --remaining <= 0 ? ToolResult.ok(new JsonObject()) : null;
            }

            @Override
            public void close() {
                client.keyboardHandler.keyPress(window, InputConstants.RELEASE, event);
            }
        };
    }

    GameBridge.GameTask clickControl(Arguments args) {
        GameUi.Layout layout = ui.layout(args.text("control_type"));
        if (!args.text("screen").equals(layout.metadata().get("screen").getAsString())
            || !args.text("layout").equals(layout.metadata().get("layout").getAsString())) {
            throw new IllegalStateException("The screen or control list changed. Take a new numbered screenshot.");
        }
        int id = args.integer("id");
        if (id > layout.controls().size()) throw new IllegalArgumentException("Unknown UI control " + id);
        GameUi.Control control = layout.controls().get(id - 1);
        if (args.has("slot_signature") && (control.contents() == null
            || !args.text("slot_signature").equals(control.contents().get("slot_signature").getAsString()))) {
            throw new IllegalStateException("The slot contents changed. Read the current container or take a new numbered screenshot.");
        }
        var mouse = mouseButton(args.integer("button", 0));
        clickScreen(layout.screen(), mouse, control.x() + control.width() / 2.0, control.y() + control.height() / 2.0, null);
        return GameBridge.after(2, control.json());
    }

    GameBridge.GameTask mouse(Arguments args) {
        Screen screen = Platform.screen(client);
        String action = args.text("action");
        var mouse = mouseButton(args.integer("button", 0));
        if (action.equals("scroll")) {
            if (!args.has("scroll")) throw new IllegalArgumentException("scroll is required");
            if (screen == null) client.mouseHandler.onScroll(client.getWindow().handle(), 0, args.number("scroll"));
            else {
                requirePosition(screen, args, "x", "y");
                screen.mouseScrolled(args.number("x"), args.number("y"), 0, args.number("scroll"));
            }
        } else if (screen == null) {
            if (!action.equals("click")) throw new IllegalStateException("Mouse dragging requires an open screen");
            long window = client.getWindow().handle();
            client.mouseHandler.onButton(window, mouse, InputConstants.PRESS);
            return new GameBridge.GameTask() {
                @Override
                public ToolResult tick() { return ToolResult.ok(new JsonObject()); }
                @Override
                public void close() { client.mouseHandler.onButton(window, mouse, InputConstants.RELEASE); }
            };
        } else {
            requirePosition(screen, args, "x", "y");
            if (action.equals("drag")) requirePosition(screen, args, "to_x", "to_y");
            clickScreen(screen, mouse, args.number("x"), args.number("y"), action.equals("drag") ? args : null);
        }
        return GameBridge.after(2, new JsonObject());
    }

    private static int modifiers(int portable) {
        int result = 0;
        if ((portable & 1) != 0) result |= InputConstants.MOD_SHIFT;
        if ((portable & 2) != 0) result |= InputConstants.MOD_CONTROL;
        if ((portable & 4) != 0) result |= InputConstants.MOD_ALT;
        if ((portable & 8) != 0) result |= InputConstants.MOD_SUPER;
        return result;
    }

    private static MouseButtonInfo mouseButton(int button) {
        int code = switch (button) {
            case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
            case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
            default -> InputConstants.MOUSE_BUTTON_LEFT;
        };
        return new MouseButtonInfo(code, 0);
    }

    private static void requirePosition(Screen screen, Arguments args, String xKey, String yKey) {
        if (!args.has(xKey) || !args.has(yKey)) throw new IllegalArgumentException(xKey + " and " + yKey + " are required");
        double x = args.number(xKey), y = args.number(yKey);
        if (x < 0 || x >= screen.width || y < 0 || y >= screen.height) {
            throw new IllegalArgumentException("Mouse coordinates are outside the screen");
        }
    }

    private void clickScreen(Screen screen, MouseButtonInfo mouse, double x, double y, Arguments drag) {
        var event = new MouseButtonEvent(x, y, mouse);
        screen.mouseMoved(x, y);
        screen.mouseClicked(event, false);
        if (Platform.screen(client) != screen) return;
        if (drag != null) {
            double toX = drag.number("to_x"), toY = drag.number("to_y");
            var end = new MouseButtonEvent(toX, toY, mouse);
            screen.mouseDragged(end, toX - x, toY - y);
            screen.mouseReleased(end);
        } else screen.mouseReleased(event);
    }
}
