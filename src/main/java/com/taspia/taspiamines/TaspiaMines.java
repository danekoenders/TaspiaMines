package com.taspia.taspiamines;

import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.configuration.file.FileConfiguration;
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
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
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

    private void initFarms() {
        Set<String> farms = farmsConfig.getConfigurationSection("farms").getKeys(false);
        for (String farm : farms) {
            String name = farmsConfig.getString("farms." + farm + ".name");
            int cooldown = farmsConfig.getInt("farms." + farm + ".cooldown");
            String cropType = farmsConfig.getString("farms." + farm + ".cropType");
            String blockType = farmsConfig.getString("farms." + farm + ".blockType");
            getLogger().info("Initializing farm: " + name + " with cooldown: " + cooldown + ", cropType: " + cropType + ", blockType: " + blockType);
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
        }.runTaskTimer(this, 20L, 20L); // Run every second (20 ticks)
        getLogger().info("Replanting task started.");
    }

    private void replantFarms() {
        getLogger().info("Starting to replant farms.");
        for (String farmKey : farmsConfig.getConfigurationSection("farms").getKeys(false)) {
            int cooldown = farmsConfig.getInt("farms." + farmKey + ".cooldown");
            getLogger().info("Processing farm: " + farmKey + " with cooldown: " + cooldown);
            if (cooldown > 0) {
                cooldown--;
                farmsConfig.set("farms." + farmKey + ".cooldown", cooldown);
            } else {
                World world = getServer().getWorld(farmsConfig.getString("farms." + farmKey + ".world"));
                if (world != null) {
                    getLogger().info("World found for farm: " + farmKey);
                    if (isPlayerInWorld(world)) {
                        ProtectedRegion region = getRegion(world, farmKey);
                        if (region != null) {
                            String schematicName = farmsConfig.getString("farms." + farmKey + ".schematic");
                            String cropType = farmsConfig.getString("farms." + farmKey + ".cropType");
                            Clipboard clipboard = loadSchematic(schematicName);

                            if (clipboard != null) {
                                BlockVector3 min = clipboard.getRegion().getMinimumPoint();
                                BlockVector3 max = clipboard.getRegion().getMaximumPoint();

                                for (int x = min.getX(); x <= max.getX(); x++) {
                                    for (int y = min.getY(); y <= max.getY(); y++) {
                                        for (int z = min.getZ(); z <= max.getZ(); z++) {
                                            BlockVector3 pos = BlockVector3.at(x, y, z);
                                            BlockType blockType = clipboard.getBlock(pos).getBlockType();

                                            if (blockType.getId().equals(cropType)) {
                                                Block block = world.getBlockAt(x, y, z);
                                                if (block.getType() == Material.FARMLAND && block.getRelative(BlockFace.UP).getType() == Material.AIR) {
                                                    block.getRelative(BlockFace.UP).setType(Material.valueOf(cropType.toUpperCase())); // Replace with the actual crop type
                                                    getLogger().info("Replanted crop at " + block.getLocation().toString());
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        getLogger().info("No players in world for farm: " + farmKey);
                    }
                } else {
                    getLogger().warning("World not found for farm: " + farmKey);
                }
                farmsConfig.set("farms." + farmKey + ".cooldown", 20); // Reset cooldown to 20 seconds, adjust as needed
            }
        }
        getLogger().info("Finished replanting farms.");
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

    private ProtectedRegion getRegion(World world, String regionName) {
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

    private List<Block> getBlocksInRegion(World world, ProtectedRegion region) {
        List<Block> blocks = new ArrayList<>();
        try {
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();

            for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
                for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                    for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        blocks.add(block);
                    }
                }
            }
            getLogger().info("Collected blocks in region: " + region.getId());
        } catch (Exception e) {
            getLogger().severe("Error while getting blocks in region: " + e.getMessage());
            e.printStackTrace();
        }
        return blocks;
    }
}
