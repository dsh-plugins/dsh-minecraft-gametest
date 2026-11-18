package dev.dsh.gametest;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

record Arguments(JsonObject values) {
    boolean has(String key) { return values.has(key); }

    String text(String key) { return values.get(key).getAsString(); }

    int integer(String key) { return values.get(key).getAsInt(); }

    int integer(String key, int fallback) { return has(key) ? integer(key) : fallback; }

    double number(String key) { return values.get(key).getAsDouble(); }

    double number(String key, double fallback) { return has(key) ? number(key) : fallback; }

    JsonObject object(String key) { return values.getAsJsonObject(key); }

    Vec3 point(String key) {
        var coords = new Arguments(object(key));
        return new Vec3(coords.number("x"), coords.number("y"), coords.number("z"));
    }

    BlockPos block(String key) {
        var coords = new Arguments(object(key));
        if (Math.abs((long) coords.integer("x")) > 30_000_000 || Math.abs((long) coords.integer("z")) > 30_000_000) {
            throw new IllegalArgumentException("Block position is outside the world");
        }
        return new BlockPos(coords.integer("x"), coords.integer("y"), coords.integer("z"));
    }
}
