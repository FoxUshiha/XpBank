package com.foxsrv.xpbank;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public class PlayerData {

    private final XpBank plugin;

    public PlayerData(XpBank plugin) {
        this.plugin = plugin;
    }

    public int getXp(Player player) {
        FileConfiguration cfg = plugin.getPlayersConfig();
        return cfg.getInt(player.getName() + ".xp", 0);
    }

    public void setXp(Player player, int amount) {
        FileConfiguration cfg = plugin.getPlayersConfig();
        cfg.set(player.getName() + ".xp", Math.max(0, amount));
        plugin.savePlayers();
    }

    public void addXp(Player player, int amount) {
        setXp(player, getXp(player) + amount);
    }
}
