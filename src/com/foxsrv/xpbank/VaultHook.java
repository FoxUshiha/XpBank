package com.foxsrv.xpbank;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

public class VaultHook {

    private final XpBank plugin;
    private Economy econ;

    public VaultHook(XpBank plugin) {
        this.plugin = plugin;
    }

    public boolean setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    public boolean withdraw(Player player, double amount) {
        if (econ == null) return false;
        if (econ.getBalance(player) < amount) return false;
        econ.withdrawPlayer(player, amount);
        return true;
    }

    public void depositServer(String uuid, double amount) {
        if (econ == null) return;
        econ.depositPlayer(Bukkit.getOfflinePlayer(UUID.fromString(uuid)), amount);
    }
}
