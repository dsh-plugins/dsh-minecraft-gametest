package dev.dsh.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

final class Platform {
    static Screen screen(Minecraft client) { return client.screen; }

    static RenderTarget renderTarget(Minecraft client) { return client.getMainRenderTarget(); }
}
