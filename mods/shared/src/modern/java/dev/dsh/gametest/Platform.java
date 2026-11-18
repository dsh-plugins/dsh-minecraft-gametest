package dev.dsh.gametest;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;

final class Platform {
    static Screen screen(Minecraft client) { return client.gui.screen(); }

    static void returnToTitle(Minecraft client) {
        if (client.level != null || client.getConnection() != null) client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        client.gui.setScreen(new TitleScreen());
    }

    static RenderTarget renderTarget(Minecraft client) { return client.gameRenderer.mainRenderTarget(); }
}
