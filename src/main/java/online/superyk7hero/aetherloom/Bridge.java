package online.superyk7hero.aetherloom;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Inert without a per-launch configuration supplied by Aetherloom. */
@Mod("aetherloom_bridge")
public class Bridge {
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private JsonObject config;
    private volatile boolean loaded = false;
    private volatile IntegratedServer pendingServer;
    private long nextPoll = 0;
    private volatile boolean stop = false;
    private ServerStatusPinger pinger;
    private ServerData targetServer;
    private long joinDeadline;
    private boolean connecting = false;
    private boolean joined = false;
    private volatile boolean finished = false;

    public Bridge() {
        String file = System.getenv("AETHERLOOM_BRIDGE_FILE");
        if (file == null || file.isBlank()) return;
        try {
            config = gson.fromJson(Files.readString(Path.of(file)), JsonObject.class);
            URI uri = URI.create(config.get("endpoint").getAsString());
            if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()))
                throw new IllegalArgumentException("Bridge requires loopback endpoint");
            MinecraftForge.EVENT_BUS.addListener(this::started);
            MinecraftForge.EVENT_BUS.addListener(this::stopped);
            MinecraftForge.EVENT_BUS.addListener(this::tick);
            MinecraftForge.EVENT_BUS.addListener(this::negotiate);
        } catch (Exception e) { throw new IllegalStateException("Invalid Aetherloom Bridge configuration", e); }
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(URI.create(config.get("endpoint").getAsString()))
            .header("Authorization", "Bearer " + config.get("token").getAsString())
            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(3));
    }

    private void negotiate(PlayerNegotiationEvent negotiation) {
        if (config.has("join") || config.get("inspect").getAsBoolean()) return;
        // Vanilla offline LAN derives UUID from the name. Apply the identity
        // attached to this authenticated software member's scoped relay stream
        // before login acceptance loads playerdata; never infer ownership by name.
        negotiation.enqueueWork(() -> {
            try {
                String name = negotiation.getProfile().getName();
                HttpRequest req = HttpRequest.newBuilder(URI.create(config.get("endpoint").getAsString()+"/identity/"+URLEncoder.encode(name,StandardCharsets.UTF_8)))
                    .header("Authorization","Bearer "+config.get("token").getAsString()).timeout(Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = http.send(req,HttpResponse.BodyHandlers.ofString());
                if (response.statusCode()!=200) throw new IllegalStateException("Identity lookup rejected");
                JsonObject identity=gson.fromJson(response.body(),JsonObject.class);
                if (!identity.has("uuid") || identity.get("uuid").isJsonNull()) return;
                UUID id=UUID.fromString(identity.get("uuid").getAsString());
                java.lang.reflect.Field field=com.mojang.authlib.GameProfile.class.getDeclaredField("id");
                field.setAccessible(true);field.set(negotiation.getProfile(),id);
                if (!id.equals(negotiation.getProfile().getId())) throw new IllegalStateException("UUID update failed");
            } catch(Exception e) {
                negotiation.getConnection().disconnect(net.minecraft.network.chat.Component.literal("Aetherloom: UUID identity negotiation failed"));
            }
        });
    }

    private void event(String type, int port, IntegratedServer server) throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("event", type);
        data.addProperty("port", port);
        data.addProperty("world", server == null ? config.get("world").getAsString() : server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        data.addProperty("uuid", type.equals("error") ? config.get("identity").getAsString() : server == null ? (Minecraft.getInstance().player == null ? config.get("identity").getAsString() : Minecraft.getInstance().player.getUUID().toString()) : server.getSingleplayerProfile().getId().toString());
        HttpResponse<String> response = http.send(request().POST(HttpRequest.BodyPublishers.ofString(gson.toJson(data))).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Bridge event rejected");
    }

    private boolean selected(IntegratedServer server) {
        try { return Files.isSameFile(server.getWorldPath(LevelResource.ROOT),
            Path.of(config.get("world").getAsString())); }
        catch (Exception e) { return false; }
    }

    private void started(ServerStartedEvent event) {
        if (!(event.getServer() instanceof IntegratedServer server) || !selected(server)) return;
        pendingServer = server;
    }

    private void open(IntegratedServer server) {
        try {
            int port = 0;
            if (!config.has("inspect") || !config.get("inspect").getAsBoolean()) {
                try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
                server.setUsesAuthentication(false);
                if (!server.publishServer(server.getDefaultGameType(), false, port))
                    throw new IllegalStateException("Unable to publish selected world");
            }
            event("ready", port, server);
            loaded = true;
        } catch (Exception e) {
            System.err.println("Aetherloom could not prepare LAN: " + e);
            try { event("error", 0, server); } catch (Exception ignored) {}
            leave();
        }
    }

    private void stopped(ServerStoppedEvent event) {
        pendingServer = null;
        if (!loaded || !(event.getServer() instanceof IntegratedServer server) || !selected(server)) return;
        loaded = false;
        try { event("saved", 0, server); }
        catch (Exception e) { System.err.println("Aetherloom: saved event was not acknowledged; retain local recovery data."); }
        finished = true;
    }

    private void leave() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.level.disconnect();
        if (mc.getSingleplayerServer() != null) mc.getSingleplayerServer().halt(false);
        mc.clearLevel(new TitleScreen());
    }

    private void join(Minecraft mc) {
        if (joined) {
            if (mc.player == null && mc.level == null) { loaded = false; finished = true; }
            return;
        }
        if (mc.player != null && mc.level != null && mc.getSingleplayerServer() == null) {
            try { event("joined",0,null); joined=true; loaded=true; }
            catch(Exception e) {
                System.err.println("Aetherloom: joined identity/UUID rejected");
                try { event("error",0,null); } catch(Exception ignored) {}
                leave();finished=true;
            }
            return;
        }
        if (connecting) return;
        if (pinger == null && mc.screen instanceof TitleScreen) {
            targetServer = new ServerData("Aetherloom",config.get("join").getAsString(),false);
            targetServer.ping = -1;
            pinger = new ServerStatusPinger();
            joinDeadline = System.currentTimeMillis()+30000;
            try { pinger.pingServer(targetServer,()->{}); }
            catch(Exception e) { System.err.println("Aetherloom: server discovery failed"); }
        }
        if (pinger != null) {
            pinger.tick();
            // Forge uses ping metadata to select its login handshake. Vanilla
            // Quick Play can connect without that metadata and then time out.
            if (targetServer.ping >= 0 && targetServer.forgeData != null) {
                pinger.removeAll();pinger=null;connecting=true;
                ConnectScreen.startConnecting(new TitleScreen(),mc,ServerAddress.parseString(targetServer.ip),targetServer,false);
            } else if (System.currentTimeMillis()>joinDeadline) {
                pinger.removeAll();pinger=null;connecting=true;
                System.err.println("Aetherloom: Forge server discovery timed out");
                try { event("error",0,null); } catch(Exception ignored) {}
            }
        }
    }

    private void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (config.has("join")) join(mc);
        if (finished && mc.level == null && mc.getSingleplayerServer() == null) {
            finished = false;
            mc.stop();
            return;
        }
        IntegratedServer pending = pendingServer;
        // publishServer reads Minecraft.player, which is still null during
        // ServerStartedEvent. Wait for the local client to finish joining.
        if (pending != null && mc.player != null && mc.level != null && mc.getSingleplayerServer() == pending) {
            pendingServer = null;
            open(pending);
        }
        if (stop) {
            stop = false;
            System.out.println("Aetherloom: stopping managed world");
            if (mc.level != null || mc.getSingleplayerServer() != null) leave();
            else mc.stop();
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextPoll) return;
        nextPoll = now + 1000;
        http.sendAsync(request().GET().build(), HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() == 200) {
                    stop = gson.fromJson(response.body(), JsonObject.class).get("stop").getAsBoolean();
                    if (stop) System.out.println("Aetherloom: received stop command");
                }
            }).exceptionally(e -> { System.err.println("Aetherloom: loopback poll failed: " + e.getClass().getSimpleName()); return null; });
    }
}
