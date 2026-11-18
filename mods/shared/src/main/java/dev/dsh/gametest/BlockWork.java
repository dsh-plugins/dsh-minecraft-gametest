package dev.dsh.gametest;

import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class BlockWork {
    final Minecraft client;
    final ClientLevel level;
    final LocalPlayer player;
    final MultiPlayerGameMode mode;
    private final int originalSlot;

    BlockWork(Minecraft client) {
        this.client = client;
        level = client.level;
        player = client.player;
        mode = client.gameMode;
        if (!player.onGround()) throw new IllegalStateException("Start block work on the ground");
        originalSlot = player.getInventory().getSelectedSlot();
    }

    void restoreSlot() {
        if (client.player == player) player.getInventory().setSelectedSlot(originalSlot);
    }

    BlockHitResult hit(BlockPos pos) {
        return hit(pos, player.getEyePosition());
    }

    private BlockHitResult hit(BlockPos pos, Vec3 eye) {
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) return null;
        var shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) return null;
        Vec3 center = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        if (eye.distanceTo(center) >= player.blockInteractionRange() - 0.2) return null;
        BlockHitResult found = level.clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return found.getType() == HitResult.Type.BLOCK && found.getBlockPos().equals(pos) ? found : null;
    }

    BlockHitResult topHit(BlockPos pos) { return topHit(pos, player.getEyePosition()); }

    private BlockHitResult topHit(BlockPos pos, Vec3 eye) {
        if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) return null;
        var shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) return null;
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + shape.bounds().maxY - 0.001, pos.getZ() + 0.5);
        if (eye.distanceTo(top) >= player.blockInteractionRange() - 0.2) return null;
        BlockHitResult found = level.clip(new ClipContext(eye, top, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return found.getType() == HitResult.Type.BLOCK && found.getBlockPos().equals(pos) && found.getDirection() == Direction.UP ? found : null;
    }

    GroundPath approach(BlockPos pos) {
        return GroundPath.find(client, feet -> hit(pos, Vec3.atBottomCenterOf(feet).add(0, player.getEyeHeight(), 0)) != null, 240);
    }

    GroundPath approachTop(BlockPos pos) {
        return GroundPath.find(client, feet -> topHit(pos, Vec3.atBottomCenterOf(feet).add(0, player.getEyeHeight(), 0)) != null, 240);
    }

    boolean select(Predicate<ItemStack> filter) {
        for (int slot = 0; slot < 9; slot++) {
            if (filter.test(player.getInventory().getItem(slot))) {
                player.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        return false;
    }

    void face(Vec3 destination) { face(player, destination); }

    static void face(LocalPlayer player, Vec3 destination) {
        Vec3 delta = destination.subtract(player.getEyePosition());
        WorldTools.orient(player, Math.toDegrees(Math.atan2(-delta.x, delta.z)),
            Math.toDegrees(Math.atan2(-delta.y, delta.horizontalDistance())));
    }

    private void pickTool(BlockState state) {
        if (mode.getPlayerMode().isCreative()) return;
        int slot = -1;
        float best = 0;
        for (int index = 0; index < 9; index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state)) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > best || (speed == best && index == player.getInventory().getSelectedSlot())) {
                slot = index;
                best = speed;
            }
        }
        if (slot == -1) throw new IllegalStateException("No hotbar tool can harvest " + state.getBlock().getName().getString());
        player.getInventory().setSelectedSlot(slot);
    }

    Map<Item, Integer> inventoryCounts() {
        var counts = new HashMap<Item, Integer>();
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    final class Breaker implements AutoCloseable {
        private final BlockPos pos;
        private final BlockState initial;
        private boolean mining;

        Breaker(BlockPos pos) {
            this.pos = pos;
            initial = level.getBlockState(pos);
            if (initial.getDestroySpeed(level, pos) < 0) throw new IllegalStateException("Cannot break block at " + pos.toShortString());
        }

        boolean tick() {
            if (level.getBlockState(pos).getBlock() != initial.getBlock()) return true;
            BlockHitResult hit = hit(pos);
            if (hit == null) throw new IllegalStateException("Block is out of reach or obstructed at " + pos.toShortString());
            face(hit.getLocation());
            if (!mining) {
                pickTool(initial);
                if (!mode.startDestroyBlock(pos, hit.getDirection())) {
                    throw new IllegalStateException("Minecraft refused to mine at " + pos.toShortString());
                }
                mining = true;
            } else mode.continueDestroyBlock(pos, hit.getDirection());
            return level.getBlockState(pos).getBlock() != initial.getBlock();
        }

        @Override
        public void close() {
            if (mining && client.gameMode == mode && client.level == level) mode.stopDestroyBlock();
            mining = false;
        }
    }

    final class Pickup implements AutoCloseable {
        private final BlockPos pos;
        private GroundPath route;
        private int elapsed;
        private boolean sawDrops;

        Pickup(BlockPos pos) { this.pos = pos; }

        boolean tick() {
            if (mode.getPlayerMode().isCreative()) return true;
            elapsed++;
            List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0));
            var closest = items.stream().min(Comparator.comparingDouble(item -> item.distanceToSqr(player))).orElse(null);
            if (closest == null) return sawDrops || elapsed >= 14;
            sawDrops = true;
            if (elapsed >= 95) throw new IllegalStateException("Could not pick up drops at " + pos.toShortString());
            if (closest.distanceToSqr(player) <= 0.7) {
                close();
                return false;
            }
            if (route == null && player.onGround() && elapsed % 6 == 1) {
                try {
                    Vec3 target = closest.position();
                    route = GroundPath.find(client, feet -> {
                        Vec3 delta = target.subtract(Vec3.atBottomCenterOf(feet));
                        return delta.horizontalDistanceSqr() <= 0.8 && delta.y >= -0.22 && delta.y <= 2;
                    }, 60);
                } catch (IllegalArgumentException | IllegalStateException unreachable) {
                    // Drops can still be airborne or moving. Try again within the pickup tick budget.
                    return false;
                }
            }
            if (route != null && route.tick() != null) close();
            return false;
        }

        @Override
        public void close() {
            if (route != null) {
                route.close();
                route = null;
            }
        }
    }
}
