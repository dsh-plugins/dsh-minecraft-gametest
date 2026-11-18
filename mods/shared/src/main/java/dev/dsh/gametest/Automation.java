package dev.dsh.gametest;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class Automation {
    private final Minecraft client;
    private final WorldTools world;
    private Run current;
    private int nextId;

    Automation(Minecraft client, WorldTools world) {
        this.client = client;
        this.world = world;
    }

    static String action(Arguments args) { return args.text("action").replaceFirst("^#", ""); }

    static boolean control(String action) {
        return switch (action) {
            case "status", "pause", "paused", "resume", "cancel" -> true;
            default -> false;
        };
    }

    static void validate(Arguments args) {
        String action = action(args);
        Set<String> allowed = switch (action) {
            case "move_to", "elytra" -> Set.of("position", "timeout_ticks");
            case "tunnel" -> Set.of("direction", "length", "timeout_ticks");
            case "mine" -> Set.of("from", "to", "radius", "block", "count", "timeout_ticks");
            case "farm" -> Set.of("from", "to", "radius", "crop", "count", "farm_mode", "timeout_ticks");
            case "follow" -> Set.of("entity_uuid", "distance", "duration_ticks", "timeout_ticks");
            case "surface" -> Set.of("timeout_ticks");
            default -> Set.of();
        };
        for (String key : args.values().keySet()) {
            if (!key.equals("action") && !allowed.contains(key)) throw new IllegalArgumentException(key + " cannot be used with " + action);
        }
        String required = switch (action) {
            case "move_to", "elytra" -> "position";
            case "mine" -> "block";
            case "follow" -> "entity_uuid";
            default -> null;
        };
        if (required != null && !args.has(required)) throw new IllegalArgumentException(required + " is required for " + action);
        if (args.has("from") != args.has("to") || args.has("from") && args.has("radius")) {
            throw new IllegalArgumentException("Supply either radius or both area corners");
        }
        if (action.equals("farm") && args.has("farm_mode") && !args.text("farm_mode").equals("harvest") && !args.has("crop")) {
            throw new IllegalArgumentException("Choose a crop before replanting or tilling");
        }
    }

    boolean busy() { return current != null && current.result == null; }

    JsonObject command(String action) {
        switch (action) {
            case "pause" -> {
                if (!busy()) throw new IllegalStateException("No active task");
                if (!current.paused) {
                    current.task.pause();
                    current.paused = true;
                }
            }
            case "resume" -> {
                if (!busy() || !current.paused) throw new IllegalStateException("No paused task");
                current.paused = false;
            }
            case "cancel" -> {
                if (!busy()) throw new IllegalStateException("No active task");
                finish(ToolResult.error("Task cancelled"), "cancelled");
            }
            case "status", "paused" -> {}
            default -> throw new IllegalArgumentException("Unknown task control " + action);
        }
        return report();
    }

    Run start(Arguments args) {
        if (busy()) throw new IllegalStateException("A task is already running or paused");
        var player = world.player();
        var level = world.level();
        if (Platform.screen(client) != null) throw new IllegalStateException("Close the screen before starting a task");
        if (player.isPassenger() || player.getAbilities().flying || !player.isAlive()) {
            throw new IllegalStateException("Start the task as a living player without a mount or creative flight");
        }
        String action = action(args);
        int timeout = args.integer("timeout_ticks", switch (action) {
            case "move_to", "surface" -> 800;
            case "follow" -> args.integer("duration_ticks", 600);
            case "elytra" -> 1200;
            default -> 2400;
        });
        GameBridge.GameTask task = switch (action) {
            case "move_to" -> new GroundPath(client, args.block("position"), timeout);
            case "surface" -> surface(timeout);
            case "follow" -> new Follow(UUID.fromString(args.text("entity_uuid")), args.number("distance", 3), args.integer("duration_ticks", 600));
            case "tunnel" -> new Excavation.Tunnel(client, args);
            case "mine" -> new Excavation.Mine(client, args);
            case "farm" -> new Farming(client, args);
            case "elytra" -> new ElytraPath(client, args.block("position"));
            default -> throw new IllegalArgumentException("Unknown task action " + action);
        };
        current = new Run(++nextId, action, level, player, task, timeout);
        return current;
    }

    void tick() {
        if (!busy()) return;
        if (client.level != current.level || client.player != current.player || !current.player.isAlive()) {
            finish(ToolResult.error("World or local player changed during the task"), "failed");
            return;
        }
        if (current.paused) return;
        if (current.elapsed >= current.timeout) {
            finish(ToolResult.error("Task reached its tick limit"), "failed");
            return;
        }
        try {
            if (Platform.screen(client) != null) {
                current.task.pause();
                current.paused = true;
                return;
            }
            current.elapsed++;
            ToolResult result = current.task.tick();
            if (result != null) finish(result, result.failed() ? "failed" : "succeeded");
        } catch (RuntimeException failure) {
            finish(GameBridge.error(failure), "failed");
        }
    }

    JsonObject report() {
        var data = new JsonObject();
        data.addProperty("paused", busy() && current.paused);
        if (current == null) {
            data.addProperty("state", "idle");
            return data;
        }
        data.addProperty("task_id", current.id);
        data.addProperty("action", current.action);
        data.addProperty("state", current.result == null ? current.paused ? "paused" : "running" : current.state);
        data.addProperty("ticks", current.elapsed);
        data.add("progress", current.result == null ? current.task.progress() : current.finalProgress);
        if (current.result != null) {
            data.add("result", current.result.data());
            if (current.result.failed()) data.add("error", current.result.data().get("error"));
        }
        return data;
    }

    private void finish(ToolResult result, String state) {
        try {
            current.finalProgress = current.task.progress();
        } catch (RuntimeException failure) {
            current.finalProgress = new JsonObject();
        }
        try {
            current.task.close();
        } catch (RuntimeException failure) {
            result = GameBridge.error(failure);
            state = "failed";
        }
        current.result = result;
        current.state = state;
        current.paused = false;
    }

    private GroundPath surface(int timeout) {
        var level = world.level();
        if (!level.dimensionType().hasSkyLight()) throw new IllegalStateException("The current dimension has no open sky");
        return GroundPath.find(client, pos -> level.canSeeSky(pos.above()), timeout);
    }

    static final class Run {
        final int id;
        final String action;
        final ClientLevel level;
        final LocalPlayer player;
        final GameBridge.GameTask task;
        final int timeout;
        int elapsed;
        boolean paused;
        String state;
        ToolResult result;
        JsonObject finalProgress;

        Run(int id, String action, ClientLevel level, LocalPlayer player, GameBridge.GameTask task, int timeout) {
            this.id = id;
            this.action = action;
            this.level = level;
            this.player = player;
            this.task = task;
            this.timeout = timeout;
        }
    }

    private final class Follow implements GameBridge.GameTask {
        private final UUID id;
        private final double distance;
        private int left;
        private GroundPath route;
        private BlockPos lastTarget;
        private int retry;
        private int missing;

        Follow(UUID id, double distance, int duration) {
            this.id = id;
            this.distance = distance;
            left = duration;
            if (world.entity(id) == world.player()) throw new IllegalArgumentException("Choose another entity to follow");
        }

        @Override
        public ToolResult tick() {
            if (--left <= 0) return ToolResult.ok(progress());
            var player = world.player();
            var target = world.findEntity(id);
            if (target == null || !target.isAlive()) {
                close();
                return ++missing >= 40 ? ToolResult.error("Follow target is no longer loaded or alive") : null;
            }
            missing = 0;
            if (player.position().distanceTo(target.position()) <= distance) {
                close();
                return null;
            }
            if (player.onGround() && (lastTarget == null || lastTarget.distManhattan(target.blockPosition()) >= 2)) close();
            if (route == null && player.onGround() && --retry <= 0) {
                lastTarget = target.blockPosition();
                Vec3 destination = target.position();
                try {
                    route = GroundPath.find(client, pos -> Vec3.atBottomCenterOf(pos).distanceTo(destination) <= distance, 200);
                } catch (IllegalArgumentException | IllegalStateException unreachable) {
                    retry = 20;
                    return null;
                }
            }
            if (route != null && route.tick() != null) close();
            return null;
        }

        @Override
        public JsonObject progress() {
            var data = new JsonObject();
            data.addProperty("entity_uuid", id.toString());
            data.addProperty("remaining_ticks", Math.max(0, left));
            return data;
        }

        @Override
        public void pause() { close(); }

        @Override
        public void close() {
            if (route != null) {
                route.close();
                route = null;
            }
        }
    }
}
