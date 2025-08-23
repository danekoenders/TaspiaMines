package com.taspia.taspiamines;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.bukkit.Bukkit.getLogger;

public class FarmBlockBreakListener implements Listener {

    private final TaspiaMines plugin;
    private final boolean taspiaDropsAvailable;

    public FarmBlockBreakListener(TaspiaMines plugin) {
        this.plugin = plugin;
        this.taspiaDropsAvailable = Bukkit.getPluginManager().getPlugin("TaspiaDrops") != null;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();

        for (String farmKey : plugin.getFarmsConfig().getConfigurationSection("farms").getKeys(false)) {
            ProtectedRegion region = plugin.getRegion(world, farmKey);
            if (region != null && region.contains(block.getX(), block.getY(), block.getZ())) {
                List<String> blocks = plugin.getFarmsConfig().getStringList("farms." + farmKey + ".blocks");

                if (!blocks.contains(block.getType().toString())) {
                    // If the block broken is not in the blocks list, cancel the event
                    event.setCancelled(true);
                    return;
                }

                // If the block is in the blocks list, handle the farm block break
                handleFarmBlockBreak(event, farmKey);
                
                // Invalidate harvest percentage cache for this farm to ensure accurate readings
                plugin.invalidateHarvestCache(farmKey);
                return;
            }
        }
    }

    private void handleFarmBlockBreak(BlockBreakEvent event, String farmKey) {
        List<String> dropsConfig = plugin.getFarmsConfig().getStringList("farms." + farmKey + ".drops");
        List<String> taspiaDropsConfig = plugin.getFarmsConfig().getStringList("farms." + farmKey + ".taspiadrops");
        int xp = plugin.getFarmsConfig().getInt("farms." + farmKey + ".xp");
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        int fortuneLevel = tool.getEnchantmentLevel(Enchantment.FORTUNE);
        Block block = event.getBlock();

        event.setDropItems(false);
        event.setExpToDrop(xp);

        // Handle regular drops
        if (!dropsConfig.isEmpty()) {
            for (String dropEntry : dropsConfig) {
                String[] parts = dropEntry.split(" ");
                if (parts.length < 3) continue;

                Material material = Material.getMaterial(parts[0]);
                int amount = Integer.parseInt(parts[1]);
                double chance = Double.parseDouble(parts[2]);
                boolean isFortuneApplicable = Arrays.asList(parts).contains("fortune");
                boolean isSpecialDrop = Arrays.asList(parts).contains("special");

                if (material != null && Math.random() < chance) {
                    if (isFortuneApplicable && fortuneLevel > 0) {
                        amount = calculateFortuneDropAmount(amount, fortuneLevel);
                    }
                    ItemStack drop = new ItemStack(material, amount);
                    block.getWorld().dropItemNaturally(block.getLocation(), drop);

                    if (isSpecialDrop) {
                        sendSpecialDropActionBar(player);
                    }
                }
            }
        }

        // Handle TaspiaDrops items
        if (taspiaDropsAvailable && !taspiaDropsConfig.isEmpty()) {
            handleTaspiaDrops(taspiaDropsConfig, block, player, fortuneLevel);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                block.setType(Material.AIR);
            }
        }.runTaskLater(plugin, 1L); // Delay of 1 tick for proper drop handling
    }

    private void handleTaspiaDrops(List<String> taspiaDropsConfig, Block block, Player player, int fortuneLevel) {
        try {
            // Dynamically load the TaspiaDrops API class to avoid hard dependency
            Class<?> apiClass = Class.forName("com.taspia.taspiadrops.api.ItemDropAPI");
            
            for (String dropEntry : taspiaDropsConfig) {
                String[] parts = dropEntry.split(" ");
                if (parts.length < 3) continue;

                String itemId = parts[0];
                int amount = Integer.parseInt(parts[1]);
                double chance = Double.parseDouble(parts[2]);
                boolean isFortuneApplicable = Arrays.asList(parts).contains("fortune");
                boolean isSpecialDrop = Arrays.asList(parts).contains("special");

                if (Math.random() < chance) {
                    if (isFortuneApplicable && fortuneLevel > 0) {
                        amount = calculateFortuneDropAmount(amount, fortuneLevel);
                    }

                    // Drop the item multiple times if amount > 1, since TaspiaDrops doesn't have amount parameter
                    try {
                        // Get the basic dropItem method with Location and String parameters
                        java.lang.reflect.Method dropItemMethod = apiClass.getMethod("dropItem", 
                            org.bukkit.Location.class, String.class);
                        
                        // Drop the item 'amount' times
                        for (int i = 0; i < amount; i++) {
                            dropItemMethod.invoke(null, block.getLocation(), itemId);
                        }
                        
                        if (isSpecialDrop) {
                            sendSpecialDropActionBar(player);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Failed to drop TaspiaDrops item '" + itemId + "': " + e.getMessage());
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("TaspiaDrops API not found despite plugin being available!");
        }
    }

    private int calculateFortuneDropAmount(int baseAmount, int fortuneLevel) {
        Random random = new Random();
        int bonus = random.nextInt(fortuneLevel + 2) - 1;
        if (bonus < 0) bonus = 0;
        return baseAmount * (bonus + 1);
    }

    private void sendSpecialDropActionBar(Player player) {
        player.sendActionBar(net.kyori.adventure.text.Component.text("Special drop has dropped!")
                .color(net.kyori.adventure.text.format.NamedTextColor.GOLD)
                .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD));
    }
}

