package dev.dsh.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractStringWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

final class GameUi {
    private final Minecraft client;
    private Capture capture;

    GameUi(Minecraft client) { this.client = client; }

    static String screenId(Screen screen) {
        return screen == null ? "none" : screen.getClass().getName();
    }

    Layout layout(String group) {
        Screen screen = Platform.screen(client);
        if (screen == null) throw new IllegalStateException("Open a screen before using numbered controls");
        if (group.equals("slot") && !(screen instanceof AbstractContainerScreen<?>)) {
            throw new IllegalStateException("The current screen has no inventory slots");
        }
        var controls = new ArrayList<Control>();
        if (!group.equals("slot")) widgets(screen, controls, screen.width, screen.height);
        if (!group.equals("widget") && screen instanceof AbstractContainerScreen<?> container) {
            for (var slot : container.getMenu().slots) {
                if (!slot.isActive()) continue;
                controls.add(new Control(controls.size() + 1, "slot", "", slot.index,
                    container.leftPos + slot.x, container.topPos + slot.y, 16, 16, slotContents(slot)));
            }
        }
        var metadata = new JsonObject();
        metadata.addProperty("screen", screenId(screen));
        metadata.addProperty("title", screen.getTitle().getString());
        metadata.addProperty("control_type", group);
        var rows = new JsonArray();
        for (Control control : controls) rows.add(control.json());
        metadata.add("controls", rows);
        // Positions can change with GUI scale. IDs are tied to the ordered controls.
        var identity = new JsonObject();
        identity.addProperty("screen", screenId(screen));
        identity.addProperty("title", screen.getTitle().getString());
        identity.addProperty("group", group);
        var identities = new JsonArray();
        for (Control control : controls) {
            identities.add(control.kind + ":" + control.slot + ":" + control.label);
        }
        identity.add("controls", identities);
        metadata.addProperty("layout", digest(identity.toString()));
        return new Layout(screen, controls, metadata);
    }

    GameBridge.GameTask assertSlot(Arguments args) {
        int index = args.integer("slot");
        return new GameBridge.GameTask() {
            int remaining = args.integer("wait_ticks", 40);

            @Override
            public ToolResult tick() {
                String mismatch = checkSlot(args, index);
                if (mismatch == null) {
                    var screen = (AbstractContainerScreen<?>) Platform.screen(client);
                    var result = slotContents(screen.getMenu().getSlot(index));
                    result.addProperty("title", screen.getTitle().getString());
                    result.addProperty("slot", index);
                    return ToolResult.ok(result);
                }
                return remaining-- == 0 ? ToolResult.error(mismatch) : null;
            }
        };
    }

    private String checkSlot(Arguments args, int index) {
        if (!(Platform.screen(client) instanceof AbstractContainerScreen<?> screen)) return "No container screen is open";
        String title = screen.getTitle().getString();
        if (args.has("title") && !args.text("title").equals(title)) {
            return "Expected container title " + args.text("title") + " but found " + title;
        }
        if (index >= screen.getMenu().slots.size() || !screen.getMenu().getSlot(index).isActive()) {
            return "No active container menu slot " + index;
        }
        var stack = screen.getMenu().getSlot(index).getItem();
        String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String label = stack.isEmpty() ? "" : stack.getHoverName().getString();
        if (args.has("item") && !args.text("item").equals(item)
            || args.has("name") && !args.text("name").equals(label)
            || args.has("count") && args.integer("count") != stack.getCount()) {
            return "Container menu slot " + index + " in " + title + " contains " + item + " (" + label + ") ×" + stack.getCount();
        }
        return null;
    }

    private static JsonObject slotContents(Slot slot) {
        var stack = slot.getItem();
        var details = new JsonObject();
        details.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        details.addProperty("label", stack.isEmpty() ? "" : stack.getHoverName().getString());
        details.addProperty("count", stack.getCount());
        var lore = new JsonArray();
        var loreComponent = stack.get(DataComponents.LORE);
        if (loreComponent != null) loreComponent.lines().forEach(line -> lore.add(line.getString()));
        details.add("lore", lore);
        details.addProperty("slot_signature", digest(details.toString()));
        details.addProperty("area", slot.container instanceof Inventory ? "player" : "container");
        return details;
    }

    private static void widgets(ContainerEventHandler parent, List<Control> controls, int width, int height) {
        for (var child : parent.children()) {
            if (child instanceof AbstractWidget widget && !(widget instanceof AbstractStringWidget) && widget.visible && widget.isActive()
                && widget.getWidth() > 0 && widget.getHeight() > 0 && widget.getX() >= 0 && widget.getY() >= 0
                && widget.getX() + widget.getWidth() <= width && widget.getY() + widget.getHeight() <= height) {
                controls.add(new Control(controls.size() + 1, "widget", widget.getMessage().getString(), -1,
                    widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(), null));
            } else if (child instanceof ContainerEventHandler nested) {
                widgets(nested, controls, width, height);
            }
        }
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    GameBridge.GameTask screenshot(String group) {
        var request = new Capture(group);
        capture = request;
        return request;
    }

    void frame() {
        Capture request = capture;
        if (request == null || request.started) return;
        request.started = true;
        try {
            Layout layout = request.group == null ? null : layout(request.group);
            int guiWidth = client.getWindow().getGuiScaledWidth();
            int guiHeight = client.getWindow().getGuiScaledHeight();
            Screenshot.takeScreenshot(Platform.renderTarget(client), nativeImage -> {
                try (nativeImage) {
                    if (request.image.isDone()) return;
                    int width = nativeImage.getWidth();
                    int height = nativeImage.getHeight();
                    int[] pixels = nativeImage.getPixels();
                    CompletableFuture.supplyAsync(() -> encode(pixels, width, height, guiWidth, guiHeight, layout))
                        .whenComplete((result, error) -> {
                            if (error == null) request.image.complete(result);
                            else request.image.completeExceptionally(error);
                        });
                } catch (RuntimeException failure) {
                    request.image.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            request.image.completeExceptionally(failure);
        }
    }

    private static ToolResult encode(int[] pixels, int width, int height, int guiWidth, int guiHeight, Layout layout) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        double scale = Math.min(1.0, 2048.0 / Math.max(width, height));
        int outputWidth = Math.max(1, (int) Math.round(width * scale));
        int outputHeight = Math.max(1, (int) Math.round(height * scale));
        var output = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawImage(image, 0, 0, outputWidth, outputHeight, null);
            if (layout != null) {
                double sx = (double) outputWidth / guiWidth;
                double sy = (double) outputHeight / guiHeight;
                graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, (int) Math.round(sy * 7))));
                for (Control control : layout.controls) {
                    int x = (int) Math.round(control.x * sx);
                    int y = (int) Math.round(control.y * sy);
                    var label = Integer.toString(control.id);
                    int badgeHeight = graphics.getFontMetrics().getHeight();
                    int badgeWidth = graphics.getFontMetrics().stringWidth(label) + 5;
                    graphics.setColor(new Color(0xFFDA66));
                    graphics.drawRect(x, y, (int) (control.width * sx) - 1, (int) (control.height * sy) - 1);
                    graphics.fillRect(x, y, badgeWidth, badgeHeight);
                    graphics.setColor(Color.BLACK);
                    graphics.drawString(label, x + 2, y + graphics.getFontMetrics().getAscent());
                }
            }
        } finally {
            graphics.dispose();
        }
        var metadata = layout == null ? new JsonObject() : layout.metadata;
        metadata.addProperty("width", outputWidth);
        metadata.addProperty("height", outputHeight);
        metadata.addProperty("gui_width", guiWidth);
        metadata.addProperty("gui_height", guiHeight);
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(output, "PNG", bytes);
            return new ToolResult(metadata, bytes.toByteArray(), false);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record Layout(Screen screen, List<Control> controls, JsonObject metadata) {}

    record Control(int id, String kind, String label, int slot, int x, int y, int width, int height, JsonObject contents) {
        JsonObject json() {
            var data = contents == null ? new JsonObject() : contents.deepCopy();
            data.addProperty("id", id);
            data.addProperty("type", kind);
            if (kind.equals("slot")) data.addProperty("slot", slot);
            else data.addProperty("label", label);
            data.addProperty("x", x);
            data.addProperty("y", y);
            data.addProperty("width", width);
            data.addProperty("height", height);
            return data;
        }
    }

    private final class Capture implements GameBridge.GameTask {
        final String group;
        final CompletableFuture<ToolResult> image = new CompletableFuture<>();
        boolean started;
        int remaining = 200;

        Capture(String group) { this.group = group; }

        @Override
        public ToolResult tick() {
            if (!image.isDone()) return --remaining == 0 ? ToolResult.error("Screenshot capture timed out") : null;
            try {
                return image.join();
            } catch (CompletionException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw failure;
            }
        }

        @Override
        public void close() {
            image.cancel(false);
            capture = null;
        }
    }
}
