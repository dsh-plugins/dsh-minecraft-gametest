package dev.dsh.gametest;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;

final class Excavation {
    static final class Tunnel implements GameBridge.GameTask {
        private final BlockWork world;
        private final Direction direction;
        private final BlockPos start;
        private final int length;
        private int sections;
        private GroundPath path;
        private BlockWork.Breaker breaker;

        Tunnel(Minecraft client, Arguments args) {
            world = new BlockWork(client);
            direction = args.has("direction") ? Direction.valueOf(args.text("direction").toUpperCase(Locale.ROOT)) : world.player.getDirection();
            start = GroundPath.feet(world.player);
            if (!GroundPath.standable(world.level, start)) throw new IllegalStateException("Start a tunnel in a two-block-high standing space");
            length = args.integer("length", 8);
        }

        @Override
        public ToolResult tick() {
            if (sections >= length) return ToolResult.ok(progress());
            if (path != null) {
                ToolResult result = path.tick();
                if (result == null) return null;
                path.close();
                path = null;
                if (result.failed()) return result;
                sections++;
                return null;
            }
            if (breaker != null) {
                if (breaker.tick()) {
                    breaker.close();
                    breaker = null;
                }
                return null;
            }
            if (!world.player.onGround()) return null;
            BlockPos feet = start.relative(direction, sections + 1);
            WorldTools.requireLoaded(world.level, feet.below());
            var floor = world.level.getBlockState(feet.below());
            if (!floor.isFaceSturdy(world.level, feet.below(), Direction.UP) || !floor.getFluidState().isEmpty()) {
                throw new IllegalStateException("Tunnel requires solid floor at " + feet.below().toShortString());
            }
            // Open the upper block first so the lower block cannot drop sand or gravel into the passage.
            for (BlockPos pos : new BlockPos[]{feet.above(), feet}) {
                WorldTools.requireLoaded(world.level, pos);
                if (!world.level.getFluidState(pos).isEmpty()) throw new IllegalStateException("Fluid blocks the tunnel at " + pos.toShortString());
                if (!world.level.getBlockState(pos).isAir()) {
                    breaker = world.new Breaker(pos);
                    return null;
                }
            }
            path = new GroundPath(world.client, feet, 160);
            return null;
        }

        @Override
        public JsonObject progress() {
            var data = new JsonObject();
            data.addProperty("sections", sections);
            data.addProperty("length", length);
            data.addProperty("direction", direction.getName());
            return data;
        }

        @Override
        public void pause() {
            if (path != null) path.pause();
            if (breaker != null) breaker.close();
        }

        @Override
        public void close() {
            pause();
            world.restoreSlot();
        }
    }

    static final class Mine implements GameBridge.GameTask {
        private final BlockWork world;
        private final WorkArea area;
        private final Block block;
        private final Identifier id;
        private final int count;
        private final Map<Item, Integer> inventoryStart;
        private final Set<BlockPos> skipped = new HashSet<>();
        private int mined;
        private BlockPos target;
        private GroundPath route;
        private BlockWork.Breaker breaker;
        private BlockWork.Pickup pickup;

        Mine(Minecraft client, Arguments args) {
            world = new BlockWork(client);
            area = new WorkArea(world.player.blockPosition(), args);
            id = Identifier.parse(args.text("block"));
            block = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(() -> new IllegalArgumentException("Unknown block " + id));
            if (block.defaultBlockState().isAir() || block.defaultBlockState().liquid()) {
                throw new IllegalArgumentException("Choose a solid or mineable block");
            }
            count = args.integer("count", 16);
            inventoryStart = world.inventoryCounts();
        }

        @Override
        public ToolResult tick() {
            if (pickup != null) {
                if (pickup.tick()) {
                    pickup.close();
                    pickup = null;
                    target = null;
                }
                return null;
            }
            if (mined >= count) return ToolResult.ok(progress());
            if (breaker != null) {
                if (breaker.tick()) {
                    breaker.close();
                    breaker = null;
                    mined++;
                    pickup = world.new Pickup(target);
                }
                return null;
            }
            if (route != null) {
                ToolResult result = route.tick();
                if (result == null) return null;
                route.close();
                route = null;
                if (result.failed()) {
                    skipped.add(target);
                    target = null;
                }
                return null;
            }
            if (!world.player.onGround()) return null;
            if (target == null || !world.level.getBlockState(target).is(block)) {
                target = null;
                var candidates = area.candidates(world.level, world.player.position(), pos ->
                    !skipped.contains(pos) && world.level.getBlockState(pos).is(block));
                for (BlockPos candidate : candidates.stream().limit(4).toList()) {
                    target = candidate;
                    if (world.hit(target) != null) break;
                    try {
                        route = world.approach(target);
                        return null;
                    } catch (IllegalArgumentException | IllegalStateException blocked) {
                        skipped.add(target);
                        target = null;
                    }
                }
                if (target == null) {
                    if (candidates.size() > 4) return null;
                    if (mined == 0) return ToolResult.error("No reachable " + id + " found in the loaded search area");
                    return ToolResult.ok(progress());
                }
            }
            if (world.hit(target) == null) {
                skipped.add(target);
                target = null;
                return null;
            }
            breaker = world.new Breaker(target);
            return null;
        }

        @Override
        public JsonObject progress() {
            var data = new JsonObject();
            data.addProperty("block", id.toString());
            data.addProperty("mined", mined);
            data.addProperty("count", count);
            int collected = world.inventoryCounts().entrySet().stream()
                .mapToInt(entry -> Math.max(0, entry.getValue() - inventoryStart.getOrDefault(entry.getKey(), 0))).sum();
            data.addProperty("items_collected", collected);
            data.addProperty("unreachable", skipped.size());
            return data;
        }

        @Override
        public void pause() {
            if (route != null) route.pause();
            if (breaker != null) breaker.close();
            if (pickup != null) pickup.close();
        }

        @Override
        public void close() {
            pause();
            world.restoreSlot();
        }
    }
}
