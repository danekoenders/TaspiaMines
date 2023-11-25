package com.taspia.taspiamines;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
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

    public FarmBlockBreakListener(TaspiaMines plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        World world = block.getWorld();

        for (String farmKey : plugin.getFarmsConfig().getConfigurationSection("farms").getKeys(false)) {
            ProtectedRegion region = plugin.getRegion(world, farmKey);
            if (region != null && region.contains(block.getX(), block.getY(), block.getZ())) {
                String cropType = plugin.getFarmsConfig().getString("farms." + farmKey + ".cropType");
                if (cropType != null && !block.getType().toString().equalsIgnoreCase(cropType)) {
                    // If the block broken is not the crop type, cancel the event
                    event.setCancelled(true);
                    return;
                }

                // If the block is the crop type, handle the farm block break (existing logic)
                handleFarmBlockBreak(event, farmKey);
                return;
            }
        }
    }


    private void handleFarmBlockBreak(BlockBreakEvent event, String farmKey) {
        List<String> dropsConfig = plugin.getFarmsConfig().getStringList("farms." + farmKey + ".drops");
        int xp = plugin.getFarmsConfig().getInt("farms." + farmKey + ".xp");
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        int fortuneLevel = tool.getEnchantmentLevel(Enchantment.LOOT_BONUS_BLOCKS);

        event.setDropItems(false);
        event.setExpToDrop(xp);

        for (String dropEntry : dropsConfig) {
            String[] parts = dropEntry.split(" ");
            if (parts.length < 3) continue;

            Material material = Material.getMaterial(parts[0]);
            int amount = Integer.parseInt(parts[1]);
            double chance = Double.parseDouble(parts[2]);
            boolean isFortuneApplicable = Arrays.asList(parts).contains("fortune");
            boolean isSpecialDrop = Arrays.asList(parts).contains("special");
            Block block = event.getBlock();

            if (material != null && Math.random() < chance) {
                if (isFortuneApplicable && fortuneLevel > 0) {
                    amount = calculateFortuneDropAmount(amount, fortuneLevel);
                }
                ItemStack drop = new ItemStack(material, amount);
                event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), drop);

                if (isSpecialDrop) {
                    sendSpecialDropActionBar(player);
                }

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        block.setType(Material.AIR);
                    }
                }.runTaskLater(plugin, 1L); // Delay of 1 tick
            }
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

