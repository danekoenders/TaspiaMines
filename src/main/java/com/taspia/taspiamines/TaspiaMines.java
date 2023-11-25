package com.taspia.taspiamines;

import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Set;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TaspiaMines extends JavaPlugin {

    private WorldGuardPlugin worldGuard;
    private File farmsConfigFile;
    private FileConfiguration farmsConfig;

    @Override
    public void onEnable() {
        getLogger().info("TaspiaMines is being enabled!");

        getServer().getPluginManager().registerEvents(new FarmBlockBreakListener(this), this);
        if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null){
            new TaspiaMinesExpansion(this).register();
        }

        loadFarmsConfig();
        worldGuard = getWorldGuard();
        startReplantingTask();
        initFarms();
    }

    @Override
    public void onDisable() {
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
            getLogger().info("Farms configuration loaded successfully.");
        } catch (Exception e) {
            getLogger().severe("Error loading farms configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public FileConfiguration getFarmsConfig() {
        return farmsConfig;
    }

    private void initFarms() {
        Set<String> farms = farmsConfig.getConfigurationSection("farms").getKeys(false);
        for (String farm : farms) {
            String name = farmsConfig.getString("farms." + farm + ".name");
            int cooldown = farmsConfig.getInt("farms." + farm + ".cooldown");

            String cropType = farmsConfig.getString("farms." + farm + ".cropType");
            String blockType = farmsConfig.getString("farms." + farm + ".blockType");
            getLogger().info("Initializing farm: " + name + " with cooldown: " + cooldown + ", cropType: " + cropType);
            // Initialize each farm with its configuration
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("taspiamines")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                reloadPluginConfig();
                sender.sendMessage("TaspiaMines configuration reloaded.");
                return true;
            }
        }
        return false;
    }

    private void reloadPluginConfig() {
        loadFarmsConfig();
        initFarms();
        getLogger().info("Configuration reloaded.");
    }

    private Clipboard loadSchematic(String schematicName) {
        // Create or get the schematics directory
        File schematicsDir = new File(getDataFolder(), "schematics");
        if (!schematicsDir.exists()) {
            if (!schematicsDir.mkdirs()) {
                getLogger().severe("Failed to create schematics directory!");
                return null;
            }
        }

        // Look for the schematic file in the schematics directory
        File schematicFile = new File(schematicsDir, schematicName + ".schem");

        if (!schematicFile.exists()) {
            getLogger().warning("Schematic file not found: " + schematicName);
            return null;
        }

        Clipboard clipboard;
        try (FileInputStream fis = new FileInputStream(schematicFile)) {
            ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
            if (format == null) {
                getLogger().warning("Unknown schematic format: " + schematicName);
                return null;
            }

            try (ClipboardReader reader = format.getReader(fis)) {
                clipboard = reader.read();
            }
        } catch (Exception e) {
            getLogger().severe("Error loading schematic: " + e.getMessage());
            e.printStackTrace();
            return null;
        }

        return clipboard;
    }

    private void startReplantingTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                replantFarms();
            }
        }.runTaskTimer(this, 200L, 200L); // Run every second (20 ticks)
    }

    private void replantFarms() {
        for (String farmKey : farmsConfig.getConfigurationSection("farms").getKeys(false)) {
            int cooldown = farmsConfig.getInt("farms." + farmKey + ".cooldown");
            int currentCooldown = farmsConfig.getInt("farms." + farmKey + ".currentCooldown");
            double regenPercentage = farmsConfig.getDouble("farms." + farmKey + ".regenPercentage");
            String cropType = farmsConfig.getString("farms." + farmKey + ".cropType");

            World world = getServer().getWorld(farmsConfig.getString("farms." + farmKey + ".world"));
            if (world != null) {
                Clipboard clipboard = loadSchematic(farmsConfig.getString("farms." + farmKey + ".schematic"));
                if (clipboard != null) {
                    double harvestedPercentage = calculateHarvestedPercentage(world, clipboard, farmsConfig.getString("farms." + farmKey + ".cropType").toLowerCase());

                    if (harvestedPercentage >= regenPercentage) {
                        if (currentCooldown > 0) {
                            currentCooldown -= 10; // Decrease cooldown
                            farmsConfig.set("farms." + farmKey + ".currentCooldown", currentCooldown);
                        } else if (!world.getPlayers().isEmpty()){
                            BlockVector3 min = clipboard.getRegion().getMinimumPoint();
                            BlockVector3 max = clipboard.getRegion().getMaximumPoint();

                            for (int x = min.getX(); x <= max.getX(); x++) {
                                for (int y = min.getY(); y <= max.getY(); y++) {
                                    for (int z = min.getZ(); z <= max.getZ(); z++) {
                                        BlockVector3 pos = BlockVector3.at(x, y, z);
                                        BlockType blockType = clipboard.getBlock(pos).getBlockType();

                                        if (blockType.getId().equals("minecraft:" + cropType.toLowerCase())) {
                                            Block block = world.getBlockAt(x, y, z);
                                            block.setType(Material.getMaterial(cropType.toUpperCase()));
                                            if (block.getBlockData() instanceof Ageable) {
                                                Ageable age = (Ageable) block.getBlockData();
                                                age.setAge(age.getMaximumAge());
                                                block.setBlockData(age);
                                            }
                                        }
                                    }
                                }
                            }
                            farmsConfig.set("farms." + farmKey + ".currentCooldown", cooldown); // Reset cooldown
                        }
                    }
                } else {
                    getLogger().warning("Clipboard is null for farm: " + farmKey);
                }
            }
        }
    }

    private double calculateHarvestedPercentage(World world, Clipboard clipboard, String cropType) {
        int totalCrops = 0;
        int harvestedCrops = 0;
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockVector3 pos = BlockVector3.at(x, y, z);
                    BlockType blockType = clipboard.getBlock(pos).getBlockType();

                    if (blockType.getId().equals("minecraft:" + cropType.toLowerCase())) {
                        totalCrops++;
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.AIR) {
                            harvestedCrops++;
                        }
                    }
                }
            }
        }

        return totalCrops == 0 ? 0 : (double) harvestedCrops / totalCrops * 100;
    }


    private void savePluginState() {
        try {
            farmsConfig.save(farmsConfigFile);
            getLogger().info("Farms configuration saved successfully.");
        } catch (IOException e) {
            getLogger().severe("Could not save farms config to file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private WorldGuardPlugin getWorldGuard() {
        WorldGuardPlugin wgPlugin = (WorldGuardPlugin) getServer().getPluginManager().getPlugin("WorldGuard");
        if (wgPlugin == null) {
            getLogger().warning("WorldGuard plugin not found!");
        }
        return wgPlugin;
    }

    private boolean isPlayerInWorld(World world) {
        for (Player player : world.getPlayers()) {
            if (player != null && player.isOnline()) {
                return true;
            }
        }
        return false;
    }

    public ProtectedRegion getRegion(World world, String regionName) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regionManager = container.get(BukkitAdapter.adapt(world));
        if (regionManager != null) {
            ProtectedRegion region = regionManager.getRegion(regionName);
            if (region == null) {
                getLogger().warning("Region not found: " + regionName);
            }
            return region;
        } else {
            getLogger().warning("Region manager not found for world: " + world.getName());
            return null;
        }
    }
}