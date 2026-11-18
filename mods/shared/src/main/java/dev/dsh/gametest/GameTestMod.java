package dev.dsh.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

@Mod(value = "dsh_gametest", dist = Dist.CLIENT)
public final class GameTestMod {
    static final Logger LOGGER = LoggerFactory.getLogger("DSH Minecraft GameTest");
    private static final int DEFAULT_PORT = 4723;
    private final Path config = FMLPaths.CONFIGDIR.get().resolve("dsh-minecraft-gametest");
    private GameBridge bridge;
    private boolean initialized;

    public GameTestMod() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::frame);
        NeoForge.EVENT_BUS.addListener(this::systemChat);
        NeoForge.EVENT_BUS.addListener(this::commands);
    }

    private void tick(ClientTickEvent.Post event) {
        if (!initialized) {
            initialized = true;
            bridge = new GameBridge(Minecraft.getInstance());
            try {
                int port = System.getProperty("dsh.gametest.port") == null ? DEFAULT_PORT : Integer.getInteger("dsh.gametest.port", -1);
                if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid dsh.gametest.port");
                Files.createDirectories(config.resolve("replays"));
                var server = new McpServer(port, readToken(), bridge);
                Runtime.getRuntime().addShutdownHook(new Thread(server::close, "DSH Minecraft MCP shutdown"));
                if (port == 0) Files.writeString(config.resolve("mcp-port"), Integer.toString(server.port()));
                LOGGER.info("Minecraft MCP is listening at http://127.0.0.1:{}/mcp", server.port());
            } catch (IOException | IllegalArgumentException failure) {
                LOGGER.error("Could not start Minecraft MCP", failure);
            }
        }
        bridge.tick();
    }

    private void systemChat(ClientChatReceivedEvent.System event) {
        if (bridge != null) bridge.systemChat(event.getMessage(), event.isOverlay());
    }

    private void frame(RenderFrameEvent.Post event) {
        if (bridge != null) bridge.frame();
    }

    private String readToken() throws IOException {
        Path path = config.resolve("mcp-token");
        if (Files.notExists(path)) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            try {
                if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else Files.createFile(path);
                Files.writeString(path, Base64.getUrlEncoder().withoutPadding().encodeToString(random) + "\n");
            } catch (FileAlreadyExistsException ignored) {
                // Another Minecraft instance created the token.
            }
        }
        String token = Files.readString(path).strip();
        if (!token.matches("[A-Za-z0-9_-]{32,128}")) throw new IOException("Invalid Minecraft MCP token file");
        return token;
    }

    private void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("mctest")
            .then(Commands.literal("status").executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(bridge.replayReport().toString()), false);
                return 1;
            }))
            .then(Commands.literal("replay").then(Commands.argument("name", StringArgumentType.word()).executes(context -> {
                String name = StringArgumentType.getString(context, "name");
                if (!name.matches("[A-Za-z0-9_-]{1,64}")) {
                    context.getSource().sendFailure(Component.literal("Invalid recording name"));
                    return 0;
                }
                Path file = config.resolve("replays").resolve(name + ".json");
                try {
                    if (Files.size(file) > 2 * 1024 * 1024) throw new IOException("Recording is too large");
                    var args = new JsonObject();
                    args.add("recording", JsonParser.parseString(Files.readString(file)));
                    bridge.submit("replay_start", args).thenAccept(result -> {
                        if (result.failed()) {
                            context.getSource().sendFailure(Component.literal(result.data().get("error").getAsString()));
                        } else {
                            context.getSource().sendSuccess(() -> Component.literal("Replay started: " + name + ". Use /mctest status for results."), false);
                        }
                    });
                    return 1;
                } catch (IOException | RuntimeException failure) {
                    context.getSource().sendFailure(Component.literal("Could not open recording " + name + ": " + failure.getMessage()));
                    return 0;
                }
            }))));
    }
}
