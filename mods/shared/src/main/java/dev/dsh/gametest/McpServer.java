package dev.dsh.gametest;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class McpServer implements AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final Set<String> PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");
    private final HttpServer server;
    private final byte[] authorization;
    private final GameBridge bridge;
    private final Semaphore calls = new Semaphore(8);
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    McpServer(int port, String token, GameBridge bridge) throws IOException {
        this.bridge = bridge;
        this.authorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        server.createContext("/mcp", this::handle);
        server.setExecutor(workers);
        server.start();
    }

    int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/mcp")) {
                send(exchange, 404, null);
                return;
            }
            if (exchange.getRequestHeaders().containsKey("Origin")) {
                send(exchange, 403, null);
                return;
            }
            String supplied = exchange.getRequestHeaders().getFirst("Authorization");
            if (supplied == null || !MessageDigest.isEqual(authorization, supplied.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                send(exchange, 401, null);
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                exchange.getResponseHeaders().set("Allow", "POST");
                send(exchange, 405, null);
                return;
            }
            String protocol = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (protocol != null && !PROTOCOLS.contains(protocol)) {
                send(exchange, 400, error(JsonNull.INSTANCE, -32600, "Unsupported MCP protocol version"));
                return;
            }
            if (!calls.tryAcquire()) {
                send(exchange, 429, null);
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (body.length > MAX_REQUEST_BYTES) {
                    send(exchange, 413, null);
                    return;
                }
                JsonObject request;
                try {
                    JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
                    if (!parsed.isJsonObject()) {
                        send(exchange, 400, error(JsonNull.INSTANCE, -32600, "Expected a JSON-RPC request object"));
                        return;
                    }
                    request = parsed.getAsJsonObject();
                } catch (JsonParseException failure) {
                    send(exchange, 400, error(JsonNull.INSTANCE, -32700, "Invalid JSON"));
                    return;
                }
                handleRpc(exchange, request);
            } finally {
                calls.release();
            }
        }
    }

    private void handleRpc(HttpExchange exchange, JsonObject request) throws IOException {
        JsonElement id = request.get("id");
        if (id != null && !(id.isJsonPrimitive() && (id.getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber()))) {
            send(exchange, 400, error(JsonNull.INSTANCE, -32600, "Invalid request ID"));
            return;
        }
        if (!new JsonPrimitive("2.0").equals(request.get("jsonrpc"))
            || !(request.get("method") instanceof JsonPrimitive method) || !method.isString()) {
            send(exchange, 400, error(id, -32600, "Invalid JSON-RPC request"));
            return;
        }
        if (id == null) {
            send(exchange, 202, null);
            return;
        }
        try {
            if (request.has("params") && !request.get("params").isJsonObject()) {
                throw new IllegalArgumentException("params must be an object");
            }
            JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
            JsonObject result = switch (method.getAsString()) {
                case "initialize" -> initialize(params);
                case "ping" -> new JsonObject();
                case "tools/list" -> {
                    var list = new JsonObject();
                    list.add("tools", ToolCatalog.list());
                    yield list;
                }
                case "tools/call" -> call(params);
                default -> null;
            };
            if (result == null) send(exchange, 200, error(id, -32601, "Unknown method"));
            else {
                JsonObject response = envelope(id);
                response.add("result", result);
                send(exchange, 200, response);
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            send(exchange, 200, error(id, -32602, failure.getMessage()));
        }
    }

    private static JsonObject initialize(JsonObject params) {
        String requested = params.get("protocolVersion") instanceof JsonPrimitive version ? version.getAsString() : "";
        var result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOLS.contains(requested) ? requested : "2025-11-25");
        var capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);
        var serverInfo = new JsonObject();
        serverInfo.addProperty("name", "dsh-minecraft-gametest");
        serverInfo.addProperty("version", "0.1.0");
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject call(JsonObject params) {
        if (!(params.get("name") instanceof JsonPrimitive name) || !name.isString()) {
            throw new IllegalArgumentException("name must be a string");
        }
        if (params.has("arguments") && !params.get("arguments").isJsonObject()) {
            throw new IllegalArgumentException("arguments must be an object");
        }
        JsonObject arguments = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();
        CompletableFuture<ToolResult> pending = bridge.submit(name.getAsString(), arguments);
        try {
            return pending.get(90, TimeUnit.SECONDS).toMcp();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            pending.cancel(false);
            return ToolResult.error("MCP server closed").toMcp();
        } catch (java.util.concurrent.TimeoutException failure) {
            pending.cancel(false);
            return ToolResult.error("Minecraft did not finish the action within 90 seconds").toMcp();
        } catch (java.util.concurrent.ExecutionException failure) {
            return ToolResult.error("Minecraft tool failed").toMcp();
        }
    }

    private static JsonObject envelope(JsonElement id) {
        var response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        return response;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = envelope(id);
        var data = new JsonObject();
        data.addProperty("code", code);
        data.addProperty("message", message);
        response.add("error", data);
        return response;
    }

    private static void send(HttpExchange exchange, int status, JsonObject response) throws IOException {
        if (response == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = new GsonBuilder().serializeNulls().create().toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
