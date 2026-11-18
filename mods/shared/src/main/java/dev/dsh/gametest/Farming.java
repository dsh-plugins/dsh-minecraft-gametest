package dev.dsh.gametest;

import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;

final class Farming implements GameBridge.GameTask {
    private static final Crop[] CROPS = {
        new Crop("wheat", Blocks.WHEAT, Items.WHEAT_SEEDS, Blocks.FARMLAND),
        new Crop("carrots", Blocks.CARROTS, Items.CARROT, Blocks.FARMLAND),
        new Crop("potatoes", Blocks.POTATOES, Items.POTATO, Blocks.FARMLAND),
        new Crop("beetroots", Blocks.BEETROOTS, Items.BEETROOT_SEEDS, Blocks.FARMLAND),
        new Crop("nether_wart", Blocks.NETHER_WART, Items.NETHER_WART, Blocks.SOUL_SAND)
    };

    private final BlockWork world;
    private final WorkArea area;
    private final Crop selected;
    private final boolean replant;
    private final boolean till;
    private final int count;
    private final Set<BlockPos> done = new HashSet<>();
    private final Set<BlockPos> skipped = new HashSet<>();
    private BlockPos target;
    private GroundPath route;
    private BlockWork.Breaker breaker;
    private BlockWork.Pickup pickup;
    private Work waitingFor;
    private int settle;
    private int harvested;
    private int planted;
    private int tilled;

    Farming(Minecraft client, Arguments args) {
        world = new BlockWork(client);
        area = new WorkArea(world.player.blockPosition(), args);
        selected = args.has("crop") ? crop(args.text("crop")) : null;
        String mode = args.has("farm_mode") ? args.text("farm_mode") : "harvest";
        replant = !mode.equals("harvest");
        till = mode.equals("till");
        count = args.integer("count", 16);
    }

    private static Crop crop(String name) {
        for (Crop crop : CROPS) if (crop.name.equals(name)) return crop;
        throw new IllegalArgumentException("Unknown crop " + name);
    }

    @Override
    public ToolResult tick() {
        if (waitingFor != null) {
            if (waitingFor == Work.PLANT && world.level.getBlockState(target).is(selected.block)) {
                planted++;
                finishPlot();
            } else if (waitingFor == Work.TILL && world.level.getBlockState(target.below()).is(Blocks.FARMLAND)) {
                tilled++;
                waitingFor = null;
            } else if (--settle == 0) {
                throw new IllegalStateException("Minecraft did not finish " + waitingFor + " at " + target.toShortString());
            }
            return null;
        }
        if (pickup != null) {
            if (pickup.tick()) {
                pickup.close();
                pickup = null;
                if (!replant) finishPlot();
            }
            return null;
        }
        if (done.size() >= count) return ToolResult.ok(progress());
        if (breaker != null) {
            if (breaker.tick()) {
                breaker.close();
                breaker = null;
                harvested++;
                pickup = world.new Pickup(target);
            }
            return null;
        }
        if (route != null) {
            ToolResult result = route.tick();
            if (result == null) return null;
            route.close();
            route = null;
            if (result.failed()) skipPlot();
            return null;
        }
        if (!world.player.onGround()) return null;
        if (target != null) {
            Work work = workAt(target);
            if (work == null) finishPlot();
            else if (!act(work)) skipPlot();
            return null;
        }
        var candidates = area.candidates(world.level, world.player.position(), pos ->
            !done.contains(pos) && !skipped.contains(pos) && workAt(pos) != null);
        for (BlockPos candidate : candidates.stream().limit(4).toList()) {
            target = candidate;
            if (act(workAt(target))) return null;
            skipPlot();
        }
        if (candidates.size() > 4) return null;
        if (done.isEmpty() && harvested == 0 && tilled == 0) {
            return ToolResult.error("No reachable crop or eligible plot found in the loaded search area");
        }
        return ToolResult.ok(progress());
    }

    private Work workAt(BlockPos pos) {
        BlockState state = world.level.getBlockState(pos);
        for (Crop crop : CROPS) {
            if ((selected == null || selected == crop) && state.is(crop.block) && crop.mature(state)) return Work.HARVEST;
        }
        if (!replant || !state.isAir()) return null;
        BlockState soil = world.level.getBlockState(pos.below());
        if (soil.is(selected.soil)) return Work.PLANT;
        if (till && selected.soil == Blocks.FARMLAND && (soil.is(Blocks.DIRT) || soil.is(Blocks.GRASS_BLOCK))) {
            return Work.TILL;
        }
        return null;
    }

    private boolean act(Work work) {
        BlockPos click = work == Work.HARVEST ? target : target.below();
        var hit = work == Work.HARVEST ? world.hit(click) : world.topHit(click);
        if (hit == null) {
            try {
                route = work == Work.HARVEST ? world.approach(click) : world.approachTop(click);
                return true;
            } catch (IllegalArgumentException | IllegalStateException unreachable) {
                return false;
            }
        }
        if (work == Work.HARVEST) {
            breaker = world.new Breaker(target);
            return true;
        }
        if (work == Work.TILL && !world.select(stack -> stack.is(ItemTags.HOES))) {
            throw new IllegalStateException("Put a hoe in the hotbar to till soil");
        }
        if (work == Work.PLANT && !world.select(stack -> stack.is(selected.seed))) {
            if (harvested == 0 && planted == 0) throw new IllegalStateException("Put " + selected.name + " planting items in the hotbar");
            return false;
        }
        world.face(hit.getLocation());
        if (!world.mode.useItemOn(world.player, InteractionHand.MAIN_HAND, hit).consumesAction()) return false;
        waitingFor = work;
        settle = 20;
        return true;
    }

    private void finishPlot() {
        done.add(target);
        target = null;
        waitingFor = null;
    }

    private void skipPlot() {
        skipped.add(target);
        target = null;
    }

    @Override
    public JsonObject progress() {
        var data = new JsonObject();
        data.addProperty("harvested", harvested);
        data.addProperty("planted", planted);
        data.addProperty("tilled", tilled);
        data.addProperty("plots_completed", done.size());
        data.addProperty("skipped", skipped.size());
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

    private enum Work { HARVEST, PLANT, TILL }

    private record Crop(String name, Block block, Item seed, Block soil) {
        boolean mature(BlockState state) {
            return block instanceof CropBlock crop ? crop.isMaxAge(state) : state.getValue(NetherWartBlock.AGE) == NetherWartBlock.MAX_AGE;
        }
    }
}
