package com.taspia.taspiamines;

import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Material;
import org.bukkit.World;
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
import java.util.List;
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
            List<String> blocks = farmsConfig.getStringList("farms." + farm + ".blocks");

            getLogger().info("Initializing farm: " + name + " with cooldown: " + cooldown);
            getLogger().info("Blocks for this farm: " + String.join(", ", blocks));
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

            World world = getServer().getWorld(farmsConfig.getString("farms." + farmKey + ".world"));
            if (world != null) {
                Clipboard clipboard = loadSchematic(farmsConfig.getString("farms." + farmKey + ".schematic"));
                if (clipboard != null) {
                    double totalHarvestedPercentage = 0;
                    List<String> blocks = farmsConfig.getStringList("farms." + farmKey + ".blocks");
                    for (String blockType : blocks) {
                        totalHarvestedPercentage += calculateHarvestedPercentage(world, clipboard, blockType.toLowerCase());
                    }
                    double averageHarvestedPercentage = totalHarvestedPercentage / blocks.size();

                    if (averageHarvestedPercentage >= regenPercentage) {
                        if (currentCooldown <= 0) {
                            if (!world.getPlayers().isEmpty()) {
                                for (String blockType : blocks) {
                                    replantBlockType(world, clipboard, blockType);
                                }
                                farmsConfig.set("farms." + farmKey + ".currentCooldown", cooldown); // Reset cooldown
                            }
                        } else {
                            currentCooldown -= 10; // Decrease cooldown
                            farmsConfig.set("farms." + farmKey + ".currentCooldown", currentCooldown);
                        }
                    }
                } else {
                    getLogger().warning("Clipboard is null for farm: " + farmKey);
                }
            } else {
                getLogger().warning("World is null for farm: " + farmKey);
            }
        }
    }


    private void replantBlockType(World world, Clipboard clipboard, String blockType) {
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();

        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    BlockVector3 pos = BlockVector3.at(x, y, z);
                    com.sk89q.worldedit.world.block.BlockState clipboardState = clipboard.getBlock(pos);

                    if (clipboardState.getBlockType().id().equals("minecraft:" + blockType.toLowerCase())) {
                        Block block = world.getBlockAt(x, y, z);
                        org.bukkit.block.data.BlockData bukkitData = BukkitAdapter.adapt(clipboardState);

                        block.setBlockData(bukkitData, false);
                    }
                }
            }
        }
    }

    private double calculateHarvestedPercentage(World world, Clipboard clipboard, String blockType) {
        int totalBlocks = 0;
        int harvestedBlocks = 0;
        BlockVector3 min = clipboard.getRegion().getMinimumPoint();
        BlockVector3 max = clipboard.getRegion().getMaximumPoint();

        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    BlockVector3 pos = BlockVector3.at(x, y, z);
                    BlockType type = clipboard.getBlock(pos).getBlockType();

                    if (type.id().equals("minecraft:" + blockType.toLowerCase())) {
                        totalBlocks++;
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() == Material.AIR) {
                            harvestedBlocks++;
                        }
                    }
                }
            }
        }
        return totalBlocks == 0 ? 0 : (double) harvestedBlocks / totalBlocks * 100;
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