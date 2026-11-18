package dev.dsh.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Base64;

record ToolResult(JsonObject data, byte[] png, boolean failed) {
    static ToolResult ok(JsonObject data) {
        return new ToolResult(data, null, false);
    }

    static ToolResult error(String message) {
        var data = new JsonObject();
        data.addProperty("error", message);
        return new ToolResult(data, null, true);
    }

    JsonObject toMcp() {
        var text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", data.toString());
        var content = new JsonArray();
        content.add(text);
        if (png != null) {
            var image = new JsonObject();
            image.addProperty("type", "image");
            image.addProperty("mimeType", "image/png");
            image.addProperty("data", Base64.getEncoder().encodeToString(png));
            content.add(image);
        }
        var response = new JsonObject();
        response.add("content", content);
        response.add("structuredContent", data);
        response.addProperty("isError", failed);
        return response;
    }
}
