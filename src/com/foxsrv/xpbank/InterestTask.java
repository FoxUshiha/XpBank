package com.foxsrv.xpbank;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;

public class InterestTask implements Runnable {

    private final XpBank plugin;
    private final PlayerData playerData;

    public InterestTask(XpBank plugin) {
        this.plugin = plugin;
        this.playerData = new PlayerData(plugin);
    }

    @Override
    public void run() {
        FileConfiguration cfg = plugin.getPlayersConfig();
        double interest = plugin.getConfig().getDouble("Interest", 0.0);

        if (interest <= 0) return;

        for (String name : cfg.getKeys(false)) {
            int xp = cfg.getInt(name + ".xp", 0);
            int bonus = (int) Math.floor(xp * interest);
            if (bonus > 0) {
                cfg.set(name + ".xp", xp + bonus);
                Player p = Bukkit.getPlayerExact(name);
                if (p != null && p.isOnline()) {
                    p.sendMessage("You received " + bonus + " xp as bank interests.");
                }
            }
        }
        plugin.savePlayers();
    }
}
