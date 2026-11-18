package dev.dsh.gametest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class WorldTools {
    private final Minecraft client;

    WorldTools(Minecraft client) { this.client = client; }

    JsonObject execute(String name, Arguments args) {
        return switch (name) {
            case "look" -> look(args);
            case "get_entities" -> entities(args);
            case "get_block" -> readBlock(args.block("position"), true);
            case "get_blocks" -> blocks(args);
            case "get_player" -> playerData();
            case "assert_block" -> assertBlock(args);
            case "assert_player_position" -> assertPosition(args);
            case "assert_screen" -> assertScreen(args);
            default -> throw new IllegalArgumentException("Unknown tool " + name);
        };
    }

    JsonObject status() {
        var result = new JsonObject();
        result.addProperty("minecraft", SharedConstants.getCurrentVersion().name());
        result.addProperty("in_world", client.level != null && client.player != null);
        result.addProperty("screen", GameUi.screenId(Platform.screen(client)));
        if (client.level != null) result.addProperty("dimension", dimension());
        return result;
    }

    String dimension() { return level().dimension().identifier().toString(); }

    ClientLevel level() {
        if (client.level == null) throw new IllegalStateException("Join a world first");
        return client.level;
    }

    LocalPlayer player() {
        if (client.player == null) throw new IllegalStateException("Join a world first");
        return client.player;
    }

    private JsonObject look(Arguments args) {
        LocalPlayer player = player();
        int forms = (args.has("target") ? 1 : 0) + (args.has("entity_uuid") ? 1 : 0) + (args.has("yaw") || args.has("pitch") ? 1 : 0);
        if (forms != 1 || args.has("yaw") != args.has("pitch")) {
            throw new IllegalArgumentException("Supply exactly one of target, entity_uuid, or both yaw and pitch");
        }
        if (args.has("yaw")) orient(player, args.number("yaw"), args.number("pitch"));
        else {
            Vec3 target = args.has("target") ? args.point("target") : entity(UUID.fromString(args.text("entity_uuid"))).getEyePosition();
            Vec3 direction = target.subtract(player.getEyePosition());
            if (direction.lengthSqr() < 1e-12) throw new IllegalArgumentException("Target coincides with the player's eyes");
            double yaw = Math.toDegrees(Math.atan2(-direction.x, direction.z));
            double pitch = Math.toDegrees(Math.atan2(-direction.y, Math.hypot(direction.x, direction.z)));
            orient(player, yaw, pitch);
        }
        var result = new JsonObject();
        result.addProperty("yaw", player.getYRot());
        result.addProperty("pitch", player.getXRot());
        return result;
    }

    static void orient(LocalPlayer player, double yaw, double pitch) {
        float yRot = (float) Mth.wrapDegrees(yaw);
        player.setYRot(yRot);
        player.setYHeadRot(yRot);
        player.setXRot((float) pitch);
    }

    private Entity entity(UUID id) {
        if (player().getUUID().equals(id)) return player();
        for (Entity entity : level().entitiesForRendering()) {
            if (entity.getUUID().equals(id)) return entity;
        }
        throw new IllegalArgumentException("Entity UUID is not loaded");
    }

    private JsonObject entities(Arguments args) {
        Vec3 from = args.point("from"), to = args.point("to");
        if (!legalPoint(from) || !legalPoint(to)) throw new IllegalArgumentException("Entity search corners are outside the world");
        var bounds = new AABB(from, to).inflate(0.000001);
        var result = new JsonObject();
        var rows = new JsonArray();
        boolean truncated = false;
        for (Entity entity : level().entitiesForRendering()) {
            if (!bounds.intersects(entity.getBoundingBox())) continue;
            if (rows.size() == 500) {
                truncated = true;
                break;
            }
            rows.add(entityData(entity));
        }
        result.add("entities", rows);
        result.addProperty("truncated", truncated);
        return result;
    }

    private static boolean legalPoint(Vec3 point) {
        return Math.abs(point.x) <= 30_000_000 && Math.abs(point.z) <= 30_000_000 && Math.abs(point.y) <= 20_000_000;
    }

    private JsonObject readBlock(BlockPos pos, boolean data) {
        ClientLevel level = level();
        requireLoaded(level, pos);
        BlockState state = level.getBlockState(pos);
        var result = new JsonObject();
        result.add("position", position(pos.getX(), pos.getY(), pos.getZ()));
        result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var properties = new JsonObject();
        for (Property<?> property : state.getProperties()) properties.addProperty(property.getName(), stateValue(state, property));
        result.add("properties", properties);
        if (data) {
            var blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) result.addProperty("block_entity_snbt", blockEntity.saveWithFullMetadata(level.registryAccess()).toString());
        }
        return result;
    }

    private static <T extends Comparable<T>> String stateValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private JsonObject blocks(Arguments args) {
        BlockPos a = args.block("from"), b = args.block("to");
        requireLoaded(level(), a);
        requireLoaded(level(), b);
        long volume = (Math.abs((long) a.getX() - b.getX()) + 1)
            * (Math.abs((long) a.getY() - b.getY()) + 1)
            * (Math.abs((long) a.getZ() - b.getZ()) + 1);
        if (volume > 4096) throw new IllegalArgumentException("Block query exceeds 4096 blocks");
        var rows = new JsonArray();
        for (BlockPos pos : BlockPos.betweenClosed(a, b)) rows.add(readBlock(pos, false));
        var result = new JsonObject();
        result.add("blocks", rows);
        return result;
    }

    static void requireLoaded(ClientLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) {
            throw new IllegalArgumentException("Block is outside build height or its chunk is not loaded: " + pos.toShortString());
        }
    }

    private JsonObject playerData() {
        LocalPlayer player = player();
        var result = entityData(player);
        result.addProperty("dimension", dimension());
        result.addProperty("health", player.getHealth());
        result.addProperty("max_health", player.getMaxHealth());
        result.addProperty("food", player.getFoodData().getFoodLevel());
        result.addProperty("saturation", player.getFoodData().getSaturationLevel());
        result.addProperty("experience_level", player.experienceLevel);
        result.addProperty("experience_progress", player.experienceProgress);
        result.addProperty("on_ground", player.onGround());
        result.addProperty("selected_slot", player.getInventory().getSelectedSlot());
        result.addProperty("game_mode", client.gameMode.getPlayerMode().getName());
        var inventory = new JsonArray();
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            var stack = player.getInventory().getItem(index);
            if (stack.isEmpty()) continue;
            var row = new JsonObject();
            row.addProperty("slot", index);
            row.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            row.addProperty("count", stack.getCount());
            row.addProperty("name", stack.getHoverName().getString());
            inventory.add(row);
        }
        result.add("inventory", inventory);
        return result;
    }

    private JsonObject assertBlock(Arguments args) {
        var actual = readBlock(args.block("position"), false);
        if (!args.text("block").equals(actual.get("block").getAsString())) {
            throw new IllegalStateException("Expected " + args.text("block") + " but found " + actual.get("block").getAsString());
        }
        if (args.has("properties")) {
            for (var property : args.object("properties").entrySet()) {
                var found = actual.getAsJsonObject("properties").get(property.getKey());
                if (!property.getValue().equals(found)) {
                    throw new IllegalStateException("Expected " + property.getKey() + "=" + property.getValue() + " but found " + found);
                }
            }
        }
        return actual;
    }

    private JsonObject assertPosition(Arguments args) {
        var player = player();
        double tolerance = args.number("tolerance", 0.5);
        if (player.position().distanceTo(args.point("position")) > tolerance) {
            throw new IllegalStateException("Player position " + position(player.position()) + " is outside tolerance " + tolerance);
        }
        return position(player.position());
    }

    private JsonObject assertScreen(Arguments args) {
        var result = status();
        String current = result.get("screen").getAsString();
        if (!current.equals(args.text("screen"))) throw new IllegalStateException("Expected screen " + args.text("screen") + " but found " + current);
        return result;
    }

    private static JsonObject entityData(Entity entity) {
        var result = new JsonObject();
        result.addProperty("uuid", entity.getStringUUID());
        result.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        result.addProperty("name", entity.getName().getString());
        result.add("position", position(entity.position()));
        result.addProperty("yaw", entity.getYRot());
        result.addProperty("pitch", entity.getXRot());
        return result;
    }

    static JsonObject position(Vec3 vec) { return position(vec.x, vec.y, vec.z); }

    private static JsonObject position(double x, double y, double z) {
        var result = new JsonObject();
        result.addProperty("x", x);
        result.addProperty("y", y);
        result.addProperty("z", z);
        return result;
    }
}
