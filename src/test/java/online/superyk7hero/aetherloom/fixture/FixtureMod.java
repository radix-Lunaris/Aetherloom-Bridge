package online.superyk7hero.aetherloom.fixture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;

/** Separate test-only jar. Never included in the client distribution. */
@Mod("aetherloom_fixture")
public class FixtureMod {
    private boolean started=false;
    private boolean seenWorld=false;
    public FixtureMod() {
        MinecraftForge.EVENT_BUS.addListener(this::tick);
        MinecraftForge.EVENT_BUS.addListener(this::joined);
    }
    private void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (!"1".equals(System.getenv("AETHERLOOM_IDENTITY_CHECK")) || !(event.getEntity() instanceof ServerPlayer player)) return;
        String name=player.getGameProfile().getName();
        boolean a=name.equals("AetherloomTest"), b=name.equals("AetherloomBuddy");
        if (!a && !b) return;
        int diamonds=player.getInventory().countItem(Items.DIAMOND),emeralds=player.getInventory().countItem(Items.EMERALD);
        boolean repeat=player.getPersistentData().getBoolean("AetherloomFixtureSeeded");
        if (repeat) {
            if (player.experienceLevel!=(a?17:23) || diamonds!=(a?3:0) || emeralds!=(b?2:0))
                throw new IllegalStateException("Player inventory/XP changed identity during Hop");
        } else {
            if (player.experienceLevel!=0 || diamonds!=0 || emeralds!=0)
                throw new IllegalStateException("New Host inherited another player's inventory/XP");
            player.getInventory().add(new ItemStack(a?Items.DIAMOND:Items.EMERALD,a?3:2));
            player.giveExperienceLevels(a?17:23);
            player.getPersistentData().putBoolean("AetherloomFixtureSeeded",true);
        }
        try {
            Path output=player.server.getWorldPath(LevelResource.ROOT).resolve(".aetherloom/identity-result.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output,"{\"name\":\""+name+"\",\"repeat\":"+repeat+",\"xp\":"+player.experienceLevel+",\"diamonds\":"+player.getInventory().countItem(Items.DIAMOND)+",\"emeralds\":"+player.getInventory().countItem(Items.EMERALD)+"}");
        } catch(Exception e) {throw new IllegalStateException(e);}
    }
    private void tick(TickEvent.ClientTickEvent event) {
        String name=System.getenv("AETHERLOOM_CREATE_FIXTURE");
        if (name==null || event.phase!=TickEvent.Phase.END) return;
        Minecraft mc=Minecraft.getInstance();
        if (mc.level!=null) seenWorld=true;
        if (seenWorld && mc.screen instanceof TitleScreen) { mc.stop();return; }
        if (started) return;
        if (!(mc.screen instanceof TitleScreen)) return;
        started=true;
        mc.createWorldOpenFlows().createFreshLevel(name,
            new LevelSettings("Aetherloom isolated test",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT),
            new WorldOptions(123456789L,false,false),
            registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions());
    }
}
