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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TaspiaMines extends JavaPlugin {

    // Constants
    private static final long REPLANT_TASK_INTERVAL = 200L; // 10 seconds in ticks
    private static final int COOLDOWN_DECREMENT = 10; // seconds
    private static final long TASK_DELAY = 1L; // 1 tick delay for block breaking
    
    // Core components
    private WorldGuardPlugin worldGuard;
    private File farmsConfigFile;
    private FileConfiguration farmsConfig;
    
    // Schematic caching system
    private final Map<String, Clipboard> schematicCache = new ConcurrentHashMap<>();
    private final Set<String> failedSchematics = ConcurrentHashMap.newKeySet();
    
    // Performance optimization: cache last harvest percentages
    private final Map<String, Double> lastHarvestPercentage = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPercentageCheck = new ConcurrentHashMap<>();
    private static final long PERCENTAGE_CACHE_TIME = 30000; // 30 seconds cache
    
    // Smart cache invalidation: only invalidate once per farm per time window
    private final Map<String, Long> lastCacheInvalidation = new ConcurrentHashMap<>();
    private static final long CACHE_INVALIDATION_COOLDOWN = 5000; // 5 seconds between invalidations

    @Override
    public void onEnable() {
        getLogger().info("TaspiaMines is being enabled!");

        getServer().getPluginManager().registerEvents(new FarmBlockBreakListener(this), this);
        if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null){
            new TaspiaMinesExpansion(this).register();
        }

        // Check for TaspiaDrops API availability
        if(Bukkit.getPluginManager().getPlugin("TaspiaDrops") != null){
            getLogger().info("TaspiaDrops found! Special item drops are enabled.");
        } else {
            getLogger().info("TaspiaDrops not found. Only regular drops will be available.");
        }

        loadFarmsConfig();
        worldGuard = getWorldGuard();
        initFarms();
        preloadSchematics();
        startReplantingTask();
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
    
    /**
     * Preloads all schematics into cache during startup for better performance
     */
    private void preloadSchematics() {
        if (farmsConfig.getConfigurationSection("farms") == null) {
            getLogger().warning("No farms section found in configuration!");
            return;
        }
        
        Set<String> farms = farmsConfig.getConfigurationSection("farms").getKeys(false);
        getLogger().info("Preloading schematics for " + farms.size() + " farms...");
        
        int successCount = 0;
        for (String farm : farms) {
            String schematicName = farmsConfig.getString("farms." + farm + ".schematic");
            if (schematicName != null && !schematicName.trim().isEmpty()) {
                Clipboard clipboard = loadSchematic(schematicName);
                if (clipboard != null) {
                    successCount++;
                }
            }
        }
        
        getLogger().info("Successfully preloaded " + successCount + "/" + farms.size() + " schematics");
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
        clearSchematicCache();
        loadFarmsConfig();
        initFarms();
        preloadSchematics();
        getLogger().info("Configuration reloaded.");
    }
    
    /**
     * Clears the schematic cache and failed schematics set
     */
    private void clearSchematicCache() {
        int cachedCount = schematicCache.size();
        schematicCache.clear();
        failedSchematics.clear();
        loggedRegionWarnings.clear(); // Clear warning cache on reload
        lastHarvestPercentage.clear(); // Clear percentage cache on reload
        lastPercentageCheck.clear();
        lastCacheInvalidation.clear(); // Clear invalidation tracking on reload
        getLogger().info("Cleared " + cachedCount + " cached schematics, percentage cache, and warning cache");
    }

    /**
     * Loads a schematic from cache or disk. Uses caching for performance.
     * @param schematicName The name of the schematic file (without extension)
     * @return The loaded clipboard or null if failed
     */
    private Clipboard loadSchematic(String schematicName) {
        if (schematicName == null || schematicName.trim().isEmpty()) {
            return null;
        }
        
        // Check if we already failed to load this schematic
        if (failedSchematics.contains(schematicName)) {
            return null;
        }
        
        // Check cache first
        Clipboard cached = schematicCache.get(schematicName);
        if (cached != null) {
            return cached;
        }
        
        // Load from disk
        Clipboard clipboard = loadSchematicFromDisk(schematicName);
        if (clipboard != null) {
            schematicCache.put(schematicName, clipboard);
            getLogger().info("Cached schematic: " + schematicName);
        } else {
            failedSchematics.add(schematicName);
        }
        
        return clipboard;
    }
    
    /**
     * Loads a schematic directly from disk without caching
     * @param schematicName The name of the schematic file
     * @return The loaded clipboard or null if failed
     */
    private Clipboard loadSchematicFromDisk(String schematicName) {
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
            getLogger().severe("Error loading schematic '" + schematicName + "': " + e.getMessage());
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
        }.runTaskTimer(this, REPLANT_TASK_INTERVAL, REPLANT_TASK_INTERVAL);
    }

    private void replantFarms() {
        if (farmsConfig.getConfigurationSection("farms") == null) {
            return;
        }
        
        for (String farmKey : farmsConfig.getConfigurationSection("farms").getKeys(false)) {
            try {
                processFarm(farmKey);
            } catch (Exception e) {
                getLogger().severe("Error processing farm '" + farmKey + "': " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
    
    /**
     * Processes a single farm for regeneration
     * @param farmKey The farm configuration key
     */
    private void processFarm(String farmKey) {
        int cooldown = farmsConfig.getInt("farms." + farmKey + ".cooldown");
        int currentCooldown = farmsConfig.getInt("farms." + farmKey + ".currentCooldown");
        double regenPercentage = farmsConfig.getDouble("farms." + farmKey + ".regenPercentage");
        String worldName = farmsConfig.getString("farms." + farmKey + ".world");
        String schematicName = farmsConfig.getString("farms." + farmKey + ".schematic");

        // Validate configuration
        if (worldName == null || schematicName == null) {
            return;
        }

        World world = getServer().getWorld(worldName);
        if (world == null) {
            return;
        }

        // Get or calculate current harvest percentage efficiently
        double averageHarvestedPercentage = getCachedHarvestPercentage(farmKey, world, schematicName, regenPercentage);
        
        // Only process cooldown and regeneration if harvest threshold is met
        if (averageHarvestedPercentage >= regenPercentage) {
            if (currentCooldown <= 0) {
                // Only regenerate if there are players online
                if (!getServer().getOnlinePlayers().isEmpty()) {
                    // Load schematic for regeneration
                    Clipboard clipboard = loadSchematic(schematicName);
                    if (clipboard != null) {
                        List<String> blocks = farmsConfig.getStringList("farms." + farmKey + ".blocks");
                        // Regenerate the farm
                        for (String blockType : blocks) {
                            replantBlockType(world, clipboard, blockType);
                        }
                        farmsConfig.set("farms." + farmKey + ".currentCooldown", cooldown); // Reset cooldown
                        
                        // Clear cache since farm is now regenerated
                        lastHarvestPercentage.remove(farmKey);
                        lastPercentageCheck.remove(farmKey);
                        
                        getLogger().info("Regenerated farm: " + farmKey + " (" + String.format("%.1f", averageHarvestedPercentage) + "% harvested)");
                    }
                }
            } else {
                // Decrease cooldown only when threshold is met
                currentCooldown -= COOLDOWN_DECREMENT;
                farmsConfig.set("farms." + farmKey + ".currentCooldown", currentCooldown);
            }
        }
        // If threshold not met, cooldown stays the same (doesn't decrease)
    }
    
    /**
     * Gets harvest percentage from cache or calculates it if cache is expired
     * @param farmKey Farm identifier
     * @param world The world
     * @param schematicName Schematic name
     * @param regenPercentage The regeneration percentage threshold
     * @return The harvest percentage
     */
    private double getCachedHarvestPercentage(String farmKey, World world, String schematicName, double regenPercentage) {
        long now = System.currentTimeMillis();
        Long lastCheck = lastPercentageCheck.get(farmKey);
        Double cached = lastHarvestPercentage.get(farmKey);
        
        // Smart cache strategy based on how close we are to threshold
        if (lastCheck != null && cached != null && (now - lastCheck) < PERCENTAGE_CACHE_TIME) {
            double thresholdBuffer = 10.0; // 10% buffer zone
            
            if (cached < (regenPercentage - thresholdBuffer)) {
                // Well below threshold, safe to use cache for longer
                return cached;
            } else if (cached < regenPercentage) {
                // Close to threshold but not reached, use shorter cache time
                long shortCacheTime = PERCENTAGE_CACHE_TIME / 3; // 10 seconds instead of 30
                if ((now - lastCheck) < shortCacheTime) {
                    return cached;
                }
            }
            // Above threshold or close to it - always calculate fresh for accuracy
        }
        
        // Calculate fresh percentage
        Clipboard clipboard = loadSchematic(schematicName);
        if (clipboard == null) {
            return 0;
        }
        
        List<String> blocks = farmsConfig.getStringList("farms." + farmKey + ".blocks");
        if (blocks.isEmpty()) {
            return 0;
        }
        
        double totalHarvestedPercentage = 0;
        for (String blockType : blocks) {
            totalHarvestedPercentage += calculateHarvestedPercentage(world, clipboard, blockType.toLowerCase());
        }
        double averageHarvestedPercentage = totalHarvestedPercentage / blocks.size();
        
        // Cache the result
        lastHarvestPercentage.put(farmKey, averageHarvestedPercentage);
        lastPercentageCheck.put(farmKey, now);
        
        return averageHarvestedPercentage;
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

    // Cache for region lookup warnings to prevent spam
    private final Set<String> loggedRegionWarnings = ConcurrentHashMap.newKeySet();
    
    /**
     * Intelligently invalidates the harvest percentage cache for a specific farm
     * Uses rate limiting to prevent excessive invalidations during rapid block breaking
     * @param farmKey The farm identifier
     */
    public void invalidateHarvestCache(String farmKey) {
        long now = System.currentTimeMillis();
        Long lastInvalidation = lastCacheInvalidation.get(farmKey);
        
        // Only invalidate if enough time has passed since last invalidation
        if (lastInvalidation == null || (now - lastInvalidation) >= CACHE_INVALIDATION_COOLDOWN) {
            lastHarvestPercentage.remove(farmKey);
            lastPercentageCheck.remove(farmKey);
            lastCacheInvalidation.put(farmKey, now);
        }
        // If recently invalidated, skip - cache is already fresh enough
    }
    
    public ProtectedRegion getRegion(World world, String regionName) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regionManager = container.get(BukkitAdapter.adapt(world));
        if (regionManager != null) {
            ProtectedRegion region = regionManager.getRegion(regionName);
            if (region == null) {
                // Only log this warning once per region to prevent spam
                String warningKey = world.getName() + ":" + regionName;
                if (!loggedRegionWarnings.contains(warningKey)) {
                    getLogger().warning("Region not found: " + regionName + " in world: " + world.getName());
                    loggedRegionWarnings.add(warningKey);
                }
            }
            return region;
        } else {
            // Only log this warning once per world
            String warningKey = "manager:" + world.getName();
            if (!loggedRegionWarnings.contains(warningKey)) {
                getLogger().warning("Region manager not found for world: " + world.getName());
                loggedRegionWarnings.add(warningKey);
            }
            return null;
        }
    }
}