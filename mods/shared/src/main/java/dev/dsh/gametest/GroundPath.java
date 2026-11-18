package dev.dsh.gametest;

import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

final class GroundPath implements GameBridge.GameTask {
    private static final int SEARCH_LIMIT = 4096;
    private static final double FINAL_RADIUS = 0.27;
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

    private final Minecraft client;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final BlockPos goal;
    private final ArrayDeque<BlockPos> waypoints;
    private int remaining;

    GroundPath(Minecraft client, Arguments args) {
        this.client = client;
        level = client.level;
        player = client.player;
        if (level == null || player == null) throw new IllegalStateException("Join a world first");
        if (Platform.screen(client) != null) throw new IllegalStateException("Close the screen before moving");
        if (!player.onGround() && !player.getAbilities().flying) throw new IllegalStateException("Start movement on the ground");
        if (player.getAbilities().flying || player.isPassenger()) throw new IllegalStateException("Dismount or stop flying before moving");
        goal = args.block("position");
        WorldTools.requireLoaded(level, goal);
        if (player.position().distanceTo(Vec3.atBottomCenterOf(goal)) > 64) {
            throw new IllegalArgumentException("Target is more than 64 blocks away");
        }
        if (!standable(goal)) throw new IllegalArgumentException("Target has no safe standing space");
        waypoints = plan(player.blockPosition());
        remaining = args.integer("timeout_ticks", 400);
    }

    @Override
    public ToolResult tick() {
        if (level != client.level || player != client.player || Platform.screen(client) != null) {
            return ToolResult.error("Movement interrupted by a world or screen change");
        }
        if (--remaining < 0) return ToolResult.error("Movement timed out at " + WorldTools.position(player.position()));
        if (near(goal, FINAL_RADIUS) && player.onGround()) {
            var data = new JsonObject();
            data.add("position", WorldTools.position(player.position()));
            return ToolResult.ok(data);
        }
        while (waypoints.size() > 1 && near(waypoints.getFirst(), 0.34)) waypoints.removeFirst();
        BlockPos waypoint = waypoints.getFirst();
        if (!standable(waypoint)) return ToolResult.error("Movement path is now blocked at " + waypoint.toShortString());
        Vec3 dest = Vec3.atBottomCenterOf(waypoint).subtract(player.position());
        double yaw = Math.toDegrees(Math.atan2(-dest.x, dest.z));
        WorldTools.orient(player, yaw, player.getXRot());
        client.options.keyJump.setDown(dest.y > 0.5 && player.onGround());
        client.options.keyUp.setDown(true);
        return null;
    }

    private boolean near(BlockPos position, double radius) {
        Vec3 delta = Vec3.atBottomCenterOf(position).subtract(player.position());
        return Math.abs(delta.y) < 0.45 && delta.horizontalDistanceSqr() <= radius * radius;
    }

    @Override
    public void close() {
        client.options.keyUp.setDown(false);
        client.options.keyJump.setDown(false);
    }

    private record Node(BlockPos position, int cost, int priority) {}

    private ArrayDeque<BlockPos> plan(BlockPos start) {
        if (start.equals(goal)) return new ArrayDeque<>(List.of(goal));
        var queue = new PriorityQueue<>(Comparator.comparingInt(Node::priority).thenComparingInt(Node::cost));
        Map<BlockPos, Integer> costs = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        costs.put(start, 0);
        queue.add(new Node(start, 0, heuristic(start)));
        int visited = 0;
        while (!queue.isEmpty() && visited < SEARCH_LIMIT) {
            Node node = queue.remove();
            if (node.cost != costs.get(node.position)) continue;
            if (node.position.equals(goal)) {
                var path = new ArrayDeque<BlockPos>();
                for (BlockPos pos = goal; !pos.equals(start); pos = previous.get(pos)) path.addFirst(pos);
                return path;
            }
            visited++;
            for (Direction direction : HORIZONTAL) {
                BlockPos candidate = next(node.position, direction);
                if (candidate == null || candidate.distManhattan(start) > 110) continue;
                int cost = node.cost + 10 + 4 * Math.abs(candidate.getY() - node.position.getY());
                if (cost >= costs.getOrDefault(candidate, Integer.MAX_VALUE)) continue;
                costs.put(candidate, cost);
                previous.put(candidate, node.position);
                queue.add(new Node(candidate, cost, cost + heuristic(candidate)));
            }
        }
        throw new IllegalStateException("No ground path found within " + SEARCH_LIMIT + " positions");
    }

    private BlockPos next(BlockPos current, Direction direction) {
        BlockPos adjacent = current.relative(direction);
        if (standable(adjacent)) return adjacent;
        if (clear(current.above(2)) && standable(adjacent.above())) return adjacent.above();
        if (clear(adjacent) && clear(adjacent.above()) && standable(adjacent.below())) return adjacent.below();
        return null;
    }

    private int heuristic(BlockPos pos) {
        return 10 * (Math.abs(pos.getX() - goal.getX()) + Math.abs(pos.getZ() - goal.getZ()));
    }

    private boolean standable(BlockPos feet) {
        BlockPos floor = feet.below();
        return level.getWorldBorder().isWithinBounds(feet) && clear(feet) && clear(feet.above())
            && !level.isOutsideBuildHeight(floor)
            && level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)
            && level.getFluidState(floor).isEmpty();
    }

    private boolean clear(BlockPos position) {
        return !level.isOutsideBuildHeight(position) && level.hasChunkAt(position)
            && level.getBlockState(position).getCollisionShape(level, position).isEmpty()
            && level.getFluidState(position).isEmpty();
    }
}
