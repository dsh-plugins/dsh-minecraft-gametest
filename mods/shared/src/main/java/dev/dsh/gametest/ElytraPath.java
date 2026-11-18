package dev.dsh.gametest;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class ElytraPath implements GameBridge.GameTask {
    private static final double ARRIVAL = 3.0;
    private final Minecraft client;
    private final ClientLevel level;
    private final LocalPlayer player;
    private final Vec3 goal;
    private Vec3 waypoint;
    private int takeoff;
    private int rocketCooldown;
    private int rockets;
    private int replan;
    private int grounded;

    ElytraPath(Minecraft client, BlockPos target) {
        this.client = client;
        level = client.level;
        player = client.player;
        var elytra = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!elytra.is(Items.ELYTRA) || elytra.getDamageValue() >= elytra.getMaxDamage() - 1) {
            throw new IllegalStateException("Equip a usable elytra");
        }
        WorldTools.requireLoaded(level, target);
        goal = Vec3.atCenterOf(target);
        if (player.position().distanceTo(goal) > 160) throw new IllegalArgumentException("Elytra target is more than 160 blocks away");
        if (!level.getBlockState(target).getCollisionShape(level, target).isEmpty()) {
            throw new IllegalArgumentException("Elytra target must be in open space");
        }
    }

    @Override
    public ToolResult tick() {
        Vec3 position = player.position();
        if (position.distanceTo(goal) <= ARRIVAL) return ToolResult.ok(progress());
        if (!player.isFallFlying()) return launch();
        grounded = 0;
        client.options.keyJump.setDown(false);
        if (player.horizontalCollision || player.isInWater()) {
            return ToolResult.error("Elytra flight hit terrain or entered water");
        }
        if (rocketCooldown > 0) rocketCooldown--;
        if (waypoint == null || position.distanceTo(waypoint) <= ARRIVAL || --replan <= 0) {
            waypoint = nextWaypoint(position);
            replan = 10;
        }
        Vec3 delta = waypoint.subtract(position);
        double yaw = Math.toDegrees(Math.atan2(-delta.x, delta.z));
        double pitch = Math.toDegrees(Math.atan2(-delta.y, delta.horizontalDistance()));
        WorldTools.orient(player, yaw, Mth.clamp(pitch, -42, 52));
        if (rocketCooldown == 0 && (delta.y > 3 || player.getDeltaMovement().horizontalDistance() < 0.55)
            && position.distanceTo(goal) > 7 && boost()) {
            rockets++;
            rocketCooldown = 28;
        }
        return null;
    }

    private ToolResult launch() {
        if (++takeoff > 50 || ++grounded > 24 && (player.onGround() || player.isInWater())) {
            return ToolResult.error("Elytra could not take off or landed before reaching the target; start from open height or use fireworks");
        }
        Vec3 delta = goal.subtract(player.position());
        WorldTools.orient(player, Math.toDegrees(Math.atan2(-delta.x, delta.z)), 0);
        if (player.onGround()) client.options.keyJump.setDown(true);
        else {
            client.options.keyJump.setDown(false);
            if (player.tryToStartFallFlying()) {
                player.connection.send(new ServerboundPlayerCommandPacket(player, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        }
        return null;
    }

    private Vec3 nextWaypoint(Vec3 origin) {
        if (clear(origin, goal)) return goal;
        Vec3 toward = goal.subtract(origin);
        Vec3 heading = new Vec3(toward.x, 0, toward.z).normalize();
        Vec3 side = new Vec3(-heading.z, 0, heading.x);
        Vec3 best = null;
        double cost = Double.POSITIVE_INFINITY;
        for (int forward : new int[]{4, 10, 18}) {
            for (int offset : new int[]{0, -8, 8, -16, 16}) {
                for (int rise : new int[]{0, 6, 12, -6}) {
                    Vec3 candidate = origin.add(heading.scale(forward)).add(side.scale(offset)).add(0, rise, 0);
                    if (!clear(origin, candidate) || !clear(candidate, goal)) continue;
                    double weight = origin.distanceTo(candidate) + candidate.distanceTo(goal) + Math.abs(rise) * 0.5;
                    if (weight >= cost) continue;
                    best = candidate;
                    cost = weight;
                }
            }
        }
        if (best == null) throw new IllegalStateException("No clear elytra corridor through loaded terrain");
        return best;
    }

    private boolean clear(Vec3 from, Vec3 to) {
        int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / 6));
        for (int i = 0; i <= steps; i++) {
            BlockPos sample = BlockPos.containing(from.lerp(to, (double) i / steps));
            if (level.isOutsideBuildHeight(sample) || !level.hasChunkAt(sample) || !level.getWorldBorder().isWithinBounds(sample)) return false;
        }
        return line(from, to) && line(from.add(0, 0.7, 0), to.add(0, 0.7, 0))
            && line(from.add(0.35, 0.25, 0.35), to.add(0.35, 0.25, 0.35))
            && line(from.add(-0.35, 0.25, -0.35), to.add(-0.35, 0.25, -0.35));
    }

    private boolean line(Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player)).getType() == HitResult.Type.MISS;
    }

    private boolean boost() {
        if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            return client.gameMode.useItem(player, InteractionHand.OFF_HAND).consumesAction();
        }
        var inventory = player.getInventory();
        int previous = inventory.getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) {
            if (!inventory.getItem(slot).is(Items.FIREWORK_ROCKET)) continue;
            inventory.setSelectedSlot(slot);
            try {
                return client.gameMode.useItem(player, InteractionHand.MAIN_HAND).consumesAction();
            } finally {
                inventory.setSelectedSlot(previous);
            }
        }
        return false;
    }

    @Override
    public JsonObject progress() {
        var data = new JsonObject();
        data.add("position", WorldTools.position(player.position()));
        data.add("destination", WorldTools.position(goal));
        data.addProperty("gliding", player.isFallFlying());
        data.addProperty("rockets_used", rockets);
        return data;
    }

    @Override
    public void pause() {
        client.options.keyJump.setDown(false);
        waypoint = null;
    }

    @Override
    public void close() { pause(); }
}
