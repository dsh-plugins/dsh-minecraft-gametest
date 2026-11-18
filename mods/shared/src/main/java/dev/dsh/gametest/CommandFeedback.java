package dev.dsh.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.StringUtil;

final class CommandFeedback {
    private static final int MAX_MESSAGES = 64;
    private static final int MAX_MESSAGE_LENGTH = 4096;
    private final Minecraft client;
    private Capture active;

    CommandFeedback(Minecraft client) {
        this.client = client;
    }

    static String command(Arguments args) {
        String raw = args.text("command");
        if (!raw.codePoints().allMatch(StringUtil::isAllowedChatCharacter)) {
            throw new IllegalArgumentException("Command must be a single line of Minecraft chat text");
        }
        String command = raw.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank() || command.startsWith("/") || Character.isWhitespace(command.codePointAt(0))
            || command.length() > 256) {
            throw new IllegalArgumentException("Enter one Minecraft command, up to 256 characters, with an optional leading slash");
        }
        if (args.has("expect_feedback") && args.text("expect_feedback").isBlank()) {
            throw new IllegalArgumentException("expect_feedback must contain text");
        }
        return command;
    }

    GameBridge.GameTask run(Arguments args) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null || client.player == null || client.level == null) {
            throw new IllegalStateException("Join a world before running a command");
        }
        String expected = args.has("expect_feedback") ? args.text("expect_feedback") : null;
        active = new Capture(connection, command(args), expected, args.integer("wait_ticks", 30));
        return active;
    }

    void record(Component message, boolean overlay) {
        Capture capture = active;
        if (capture == null || !capture.sent || client.getConnection() != capture.connection) return;
        if (capture.feedback.size() >= MAX_MESSAGES) {
            capture.truncated = true;
            return;
        }
        String text = message.getString(MAX_MESSAGE_LENGTH + 1);
        if (text.length() > MAX_MESSAGE_LENGTH) {
            int boundary = MAX_MESSAGE_LENGTH;
            if (Character.isHighSurrogate(text.charAt(boundary - 1))) boundary--;
            text = text.substring(0, boundary);
            capture.truncated = true;
        }
        var row = new JsonObject();
        row.addProperty("text", text);
        row.addProperty("overlay", overlay);
        if (message.getContents() instanceof TranslatableContents translation) {
            row.addProperty("translation_key", translation.getKey());
        }
        capture.feedback.add(row);
        if (capture.expected != null && text.contains(capture.expected)) capture.matched = true;
    }

    private final class Capture implements GameBridge.GameTask {
        private final ClientPacketListener connection;
        private final String command;
        private final String expected;
        private final JsonArray feedback = new JsonArray();
        private int remaining;
        private boolean sent;
        private boolean matched;
        private boolean truncated;

        Capture(ClientPacketListener connection, String command, String expected, int waitTicks) {
            this.connection = connection;
            this.command = command;
            this.expected = expected;
            remaining = waitTicks;
        }

        @Override
        public ToolResult tick() {
            if (client.getConnection() != connection) {
                return result("disconnected", "Lost the Minecraft connection while waiting for command feedback");
            }
            if (!sent) {
                sent = true;
                connection.sendCommand(command);
                return null;
            }
            if (--remaining > 0) return null;
            String error = expected != null && !matched ? "Command feedback did not contain the expected text" : null;
            return result(feedback.isEmpty() ? "no_feedback" : "feedback_received", error);
        }

        private ToolResult result(String state, String error) {
            var data = new JsonObject();
            data.addProperty("state", state);
            data.add("feedback", feedback);
            data.addProperty("truncated", truncated);
            if (expected != null) data.addProperty("matched", matched);
            if (error != null) data.addProperty("error", error);
            return new ToolResult(data, null, error != null);
        }

        @Override
        public void close() {
            if (active == this) active = null;
        }
    }
}
