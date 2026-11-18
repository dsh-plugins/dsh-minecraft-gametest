package dev.dsh.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

record WorkArea(BlockPos from, BlockPos to) {
    WorkArea(BlockPos origin, Arguments args) {
        this(origin, args, args.integer("radius", 12));
    }

    private WorkArea(BlockPos origin, Arguments args, int radius) {
        this(args.has("from") ? args.block("from") : origin.offset(-radius, -3, -radius),
            args.has("to") ? args.block("to") : origin.offset(radius, 3, radius));
    }

    WorkArea {
        long x = Math.abs((long) from.getX() - to.getX()) + 1;
        long y = Math.abs((long) from.getY() - to.getY()) + 1;
        long z = Math.abs((long) from.getZ() - to.getZ()) + 1;
        if (x > 64 || y > 64 || z > 64 || x * y * z > 32768) {
            throw new IllegalArgumentException("Task area may cover at most 32768 blocks and span at most 64 blocks on any axis");
        }
    }

    List<BlockPos> candidates(ClientLevel level, Vec3 origin, Predicate<BlockPos> filter) {
        var found = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (!level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && filter.test(pos)) found.add(pos.immutable());
        }
        found.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(origin)));
        return found;
    }
}
