package com.taspia.taspiamines;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class TaspiaMinesExpansion extends PlaceholderExpansion {

    private Plugin plugin;

    public TaspiaMinesExpansion(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist(){
        return true;
    }

    @Override
    public boolean canRegister(){
        return true;
    }

    @Override
    public String getAuthor(){
        return plugin.getDescription().getAuthors().toString();
    }

    @Override
    public String getIdentifier(){
        return "taspiamines";
    }

    @Override
    public String getVersion(){
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier){
        if(identifier.startsWith("cooldown_")){
            String farmKey = identifier.substring("cooldown_".length());
            int cooldown = ((TaspiaMines) plugin).getFarmsConfig().getInt("farms." + farmKey + ".cooldown", 0);
            int currentCooldown = ((TaspiaMines) plugin).getFarmsConfig().getInt("farms." + farmKey + ".currentCooldown", -1);
            if(cooldown == currentCooldown) {
                // Return the message "Ready to harvest!" in gold
                return "§6Ready to harvest!";
            } else {
                int minutesLeft = (int) Math.ceil(currentCooldown / 60.0);

                if (minutesLeft == 1) {
                    return "§cRegens in " + minutesLeft + " minute";
                } else if (minutesLeft == 0) {
                    return "§aRegenerating...";
                } else {
                    return "§cRegens in " + minutesLeft + " minutes";
                }
            }
        }
        return null; // Placeholder is unknown by this expansion
    }
}

