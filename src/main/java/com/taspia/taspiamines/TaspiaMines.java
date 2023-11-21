package com.taspia.taspiamines;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.managers.RegionManager;

import java.io.File;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TaspiaMines extends JavaPlugin {

    private WorldGuardPlugin worldGuard;

    @Override
    public void onEnable() {
        // Plugin startup logic
        getLogger().info("TaspiaMines is being enabled!");
        loadFarmsConfig();
        worldGuard = getWorldGuard();
        startReplantingTask();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        getLogger().info("TaspiaMines is being disabled!");
        savePluginState();
    }

    private void loadFarmsConfig() {
        farmsConfigFile = new File(getDataFolder(), "farms.yml");
        if (!farmsConfigFile.exists()) {
            farmsConfigFile.getParentFile().mkdirs();
            saveResource("farms.yml", false);
        }
        farmsConfig = new YamlConfiguration();
        try {
            farmsConfig.load(farmsConfigFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void initFarms() {
        Set<String> farms = farmsConfig.getConfigurationSection("farms").getKeys(false);
        for (String farm : farms) {
            String name = farmsConfig.getString("farms." + farm + ".name");
            int cooldown = farmsConfig.getInt("farms." + farm + ".cooldown");
            int currentCooldown = farmsConfig.getInt("farms." + farm + ".currentCooldown");
            String cropType = farmsConfig.getString("farms." + farm + ".cropType");
            String blockType = farmsConfig.getString("farms." + farm + ".blockType");

            // Here, initialize each farm with its configuration
            // For example, create a new Farm object and set its properties
            // You might also need to handle the actual creation of farm in the Minecraft world
        }
    }

    private void startReplantingTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                replantFarms();
            }
        }.runTaskTimer(this, 20L, 20L); // Run every second (20 ticks)
    }

    private void replantFarms() {
        for (String farmKey : farmsConfig.getConfigurationSection("farms").getKeys(false)) {
            int cooldown = farmsConfig.getInt("farms." + farmKey + ".cooldown");
            if (cooldown <= 0) {
                World world = getServer().getWorld(farmsConfig.getString("farms." + farmKey + ".world"));
                if (world != null && isPlayerInWorld(world)) {
                    ProtectedRegion region = getRegion(world, farmKey);
                    if (region != null) {
                        for (Block block : getBlocksInRegion(world, region)) {
                            if (block.getType() == Material.FARMLAND && block.getRelative(BlockFace.UP).getType() == Material.AIR) {
                                block.getRelative(BlockFace.UP).setType(Material.WHEAT);
                            }
                        }
                    }
                }
            }
        }
    }

    private WorldGuardPlugin getWorldGuard() {
        // Implement logic to get the WorldGuard plugin instance
        return (WorldGuardPlugin) getServer().getPluginManager().getPlugin("WorldGuard");
    }

    private boolean isPlayerInWorld(World world) {
        for (Player player : world.getPlayers()) {
            if (player != null && player.isOnline()) {
                return true;
            }
        }
        return false;
    }

    private ProtectedRegion getRegion(World world, String regionName) {
        RegionManager regionManager = worldGuard.getRegionContainer().get(world);
        if (regionManager != null) {
            return regionManager.getRegion(regionName);
        }
        return null;
    }

    private Iterable<Block> getBlocksInRegion(World world, ProtectedRegion region) {
        // Implement logic to iterate over all blocks in the region
        // You need to convert region's corners to actual block coordinates and iterate through them
    }
}

