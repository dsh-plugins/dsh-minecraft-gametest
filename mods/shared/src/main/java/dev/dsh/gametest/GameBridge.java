package dev.dsh.gametest;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

final class GameBridge {
    private final Minecraft client;
    private final GameUi ui;
    private final Controls controls;
    private final WorldTools world;
    private final Automation automation;
    private final CommandFeedback commandFeedback;
    private Pending pending;
    private Replay replay;
    private long tick;

    GameBridge(Minecraft client) {
        this.client = client;
        ui = new GameUi(client);
        controls = new Controls(client, ui);
        world = new WorldTools(client);
        automation = new Automation(client, world);
        commandFeedback = new CommandFeedback(client);
    }

    CompletableFuture<ToolResult> submit(String name, JsonObject args) {
        var result = new CompletableFuture<ToolResult>();
        startOnClient(() -> {
            if (result.isCancelled()) return;
            try {
                ToolCatalog.validate(name, args);
                var arguments = new Arguments(args);
                if (name.equals("replay_status")) result.complete(ToolResult.ok(replayReport()));
                else if (name.equals("status")) result.complete(ToolResult.ok(world.status()));
                else if (name.equals("task") && Automation.control(Automation.action(arguments))) {
                    result.complete(ToolResult.ok(automation.command(Automation.action(arguments))));
                } else if (pending != null || (replay != null && !replay.done())) {
                    result.complete(ToolResult.error("Another game action or replay is running"));
                } else if (automation.busy() && !observable(name)) {
                    result.complete(ToolResult.error("A task is running or paused; wait for it or cancel it first"));
                } else if (name.equals("task")) {
                    automation.start(arguments);
                    result.complete(ToolResult.ok(automation.report()));
                } else if (name.equals("replay_start")) {
                    replay = parseReplay(args.getAsJsonObject("recording"));
                    result.complete(ToolResult.ok(replayReport()));
                } else begin(name, arguments, result);
            } catch (RuntimeException failure) {
                result.complete(error(failure));
            }
        }, result);
        return result;
    }

    private static boolean observable(String name) {
        return name.startsWith("get_") || name.equals("screenshot") || name.equals("screenshot_ui");
    }

    private void startOnClient(Runnable action, CompletableFuture<ToolResult> future) {
        try {
            client.execute(action);
        } catch (RuntimeException failure) {
            future.complete(error(failure));
        }
    }

    void tick() {
        tick++;
        automation.tick();
        if (pending != null) {
            if (pending.result.isCancelled()) {
                cancelPending();
                return;
            }
            try {
                ToolResult result = pending.task.tick();
                if (result != null) finish(result);
            } catch (RuntimeException failure) {
                finish(error(failure));
            }
            return;
        }
        if (replay == null || replay.done() || replay.nextTick > tick) return;
        if (client.level != replay.level || client.player != replay.player) {
            replay.failure = "World or local player changed during replay";
            return;
        }
        Step step = replay.steps.get(replay.results.size());
        var result = new CompletableFuture<ToolResult>();
        result.thenAccept(outcome -> {
            JsonObject row = new JsonObject();
            row.addProperty("index", replay.results.size() + 1);
            row.addProperty("tool", step.name);
            row.add("result", outcome.data());
            row.addProperty("passed", !outcome.failed());
            replay.results.add(row);
            if (outcome.failed()) replay.failure = outcome.data().get("error").getAsString();
            replay.nextTick = tick + 1;
        });
        try {
            begin(step.name, new Arguments(step.arguments), result);
        } catch (RuntimeException failure) {
            result.complete(error(failure));
        }
    }

    void systemChat(Component message, boolean overlay) {
        commandFeedback.record(message, overlay);
    }

    void frame() {
        ui.frame();
    }

    private void begin(String name, Arguments args, CompletableFuture<ToolResult> result) {
        GameTask task = switch (name) {
            case "screenshot" -> ui.screenshot(null);
            case "screenshot_ui" -> ui.screenshot(args.text("control_type"));
            case "click_ui" -> controls.clickControl(args);
            case "key_input" -> controls.key(args);
            case "mouse_input" -> controls.mouse(args);
            case "run_command" -> commandFeedback.run(args);
            case "task" -> {
                var running = automation.start(args);
                yield () -> running.result;
            }
            case "look" -> after(1, world.execute(name, args));
            case "wait_ticks" -> after(args.integer("ticks"), new JsonObject());
            default -> {
                result.complete(ToolResult.ok(world.execute(name, args)));
                yield null;
            }
        };
        if (task != null) pending = new Pending(result, task);
    }

    private void finish(ToolResult outcome) {
        Pending completed = pending;
        pending = null;
        try {
            completed.task.close();
        } catch (RuntimeException failure) {
            completed.result.complete(error(failure));
            return;
        }
        completed.result.complete(outcome);
    }

    private void cancelPending() {
        Pending cancelled = pending;
        pending = null;
        cancelled.task.close();
    }

    private Replay parseReplay(JsonObject recording) {
        if (!new JsonPrimitive(1).equals(recording.get("version")) || !(recording.get("steps") instanceof JsonArray steps)
            || steps.isEmpty() || steps.size() > 500) {
            throw new IllegalArgumentException("Expected a version 1 recording with 1 to 500 steps");
        }
        world.player();
        if (recording.has("dimension") && !new JsonPrimitive(world.dimension()).equals(recording.get("dimension"))) {
            throw new IllegalStateException("Replay requires dimension " + recording.get("dimension") + "; current dimension is " + world.dimension());
        }
        var parsed = new ArrayList<Step>();
        for (JsonElement item : steps) {
            if (!(item instanceof JsonObject step) || !(step.get("tool") instanceof JsonPrimitive name) || !name.isString()
                || !(step.get("arguments") instanceof JsonObject rawArgs)) {
                throw new IllegalArgumentException("Invalid tool at step " + (parsed.size() + 1));
            }
            String tool = name.getAsString();
            try {
                ToolCatalog.validate(tool, rawArgs);
                if (!ToolCatalog.replayable(tool, rawArgs)) throw new IllegalArgumentException("Tool or action is not replayable");
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Step " + (parsed.size() + 1) + ": " + failure.getMessage());
            }
            parsed.add(new Step(tool, rawArgs.deepCopy()));
        }
        return new Replay(recording.has("name") ? recording.get("name").getAsString() : "recording", parsed, client.level, client.player, tick + 1);
    }

    JsonObject replayReport() {
        var report = new JsonObject();
        if (replay == null) {
            report.addProperty("state", "idle");
            return report;
        }
        report.addProperty("name", replay.name);
        report.addProperty("state", replay.failure != null ? "failed" : replay.done() ? "passed" : "running");
        report.addProperty("total", replay.steps.size());
        report.addProperty("completed", replay.results.size());
        report.add("steps", replay.results.deepCopy());
        if (replay.failure != null) report.addProperty("error", replay.failure);
        return report;
    }

    static ToolResult error(RuntimeException failure) {
        if (!(failure instanceof IllegalArgumentException || failure instanceof IllegalStateException)) {
            GameTestMod.LOGGER.error("Unhandled Minecraft MCP tool error", failure);
        }
        return ToolResult.error(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
    }

    static GameTask after(int ticks, JsonObject response) {
        return new GameTask() {
            int remaining = ticks;

            @Override
            public ToolResult tick() {
                return --remaining <= 0 ? ToolResult.ok(response) : null;
            }
        };
    }

    interface GameTask {
        ToolResult tick();
        default JsonObject progress() { return new JsonObject(); }
        default void pause() {}
        default void close() {}
    }

    private record Pending(CompletableFuture<ToolResult> result, GameTask task) {}
    private record Step(String name, JsonObject arguments) {}

    private static final class Replay {
        final String name;
        final List<Step> steps;
        final Object level;
        final Object player;
        final JsonArray results = new JsonArray();
        long nextTick;
        String failure;

        Replay(String name, List<Step> steps, Object level, Object player, long nextTick) {
            this.name = name;
            this.steps = steps;
            this.level = level;
            this.player = player;
            this.nextTick = nextTick;
        }

        boolean done() { return failure != null || results.size() == steps.size(); }
    }
}
