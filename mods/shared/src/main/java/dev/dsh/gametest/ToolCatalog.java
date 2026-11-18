package dev.dsh.gametest;

import com.google.gson.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ToolCatalog {
    private static final Map<String, JsonObject> TOOLS = new LinkedHashMap<>();

    static {
        var stream = Objects.requireNonNull(ToolCatalog.class.getResourceAsStream("/tools.json"));
        var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
        for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
            JsonObject tool = element.getAsJsonObject();
            TOOLS.put(tool.get("name").getAsString(), tool);
        }
    }

    static JsonArray list() {
        var result = new JsonArray();
        for (JsonObject tool : TOOLS.values()) {
            JsonObject copy = tool.deepCopy();
            copy.remove("record");
            copy.remove("recordActions");
            result.add(copy);
        }
        return result;
    }

    static boolean replayable(String tool, JsonObject args) {
        JsonObject info = TOOLS.get(tool);
        if (info == null) return false;
        if (info.get("record").getAsBoolean()) return true;
        if (!(args.get("action") instanceof JsonPrimitive action) || !action.isString() || !info.has("recordActions")) return false;
        return info.getAsJsonArray("recordActions").contains(new JsonPrimitive(Automation.action(new Arguments(args))));
    }

    static void validate(String tool, JsonObject args) {
        JsonObject definition = TOOLS.get(tool);
        if (definition == null) throw new IllegalArgumentException("Unknown tool " + tool);
        validate(definition.getAsJsonObject("inputSchema"), args, "arguments");
        if (tool.equals("task")) Automation.validate(new Arguments(args));
        if (tool.equals("run_command")) CommandFeedback.command(new Arguments(args));
    }

    private static void validate(JsonObject schema, JsonElement value, String path) {
        String type = schema.get("type").getAsString();
        boolean valid = switch (type) {
            case "object" -> value.isJsonObject();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "number" -> numeric(value) && Double.isFinite(value.getAsDouble());
            case "integer" -> numeric(value) && value.getAsDouble() == value.getAsInt();
            default -> throw new IllegalStateException("Unknown schema type " + type);
        };
        if (!valid) throw new IllegalArgumentException(path + " must be " + type);
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) {
            throw new IllegalArgumentException(path + " must be one of " + schema.get("enum"));
        }
        if ((schema.has("minimum") && value.getAsDouble() < schema.get("minimum").getAsDouble())
            || (schema.has("maximum") && value.getAsDouble() > schema.get("maximum").getAsDouble())) {
            throw new IllegalArgumentException(path + " is out of range");
        }
        if (!value.isJsonObject()) return;
        JsonObject object = value.getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");
        if (schema.has("required")) {
            for (JsonElement key : schema.getAsJsonArray("required")) {
                if (!object.has(key.getAsString())) throw new IllegalArgumentException(path + "." + key.getAsString() + " is required");
            }
        }
        JsonElement additional = schema.get("additionalProperties");
        for (var entry : object.entrySet()) {
            JsonObject child = properties == null ? null : properties.getAsJsonObject(entry.getKey());
            String childPath = path + "." + entry.getKey();
            if (child == null && additional != null) {
                if (additional.isJsonObject()) child = additional.getAsJsonObject();
                else if (!additional.getAsBoolean()) throw new IllegalArgumentException("Unexpected " + childPath);
            }
            if (child != null) validate(child, entry.getValue(), childPath);
        }
    }

    private static boolean numeric(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }
}
