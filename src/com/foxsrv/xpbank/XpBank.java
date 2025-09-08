package com.foxsrv.xpbank;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;

public class XpBank extends JavaPlugin {

    private File playersFile;
    private FileConfiguration playersConfig;
    private VaultHook vaultHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        createPlayersFile();

        vaultHook = new VaultHook(this);
        vaultHook.setupEconomy();

        XPBCommand command = new XPBCommand(this);
        getCommand("xpb").setExecutor(command);
        getCommand("xpb").setTabCompleter(command);

        // ---------------- Agendar InterestTask ----------------
        int intervalSeconds = getConfig().getInt("Interval", 60);
        long intervalTicks = intervalSeconds * 20L; // 1 segundo = 20 ticks
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, new InterestTask(this), intervalTicks, intervalTicks);

        getLogger().info("XPBank habilitado!");
    }

    @Override
    public void onDisable() {
        savePlayers();
        getLogger().info("XPBank desabilitado!");
    }

    // ---------------- Players.yml ----------------
    private void createPlayersFile() {
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            playersFile.getParentFile().mkdirs();
            saveResource("players.yml", false);
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);
    }

    public FileConfiguration getPlayersConfig() {
        return playersConfig;
    }

    public void savePlayers() {
        try {
            playersConfig.save(playersFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void reloadPlayers() {
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);
    }

    // ---------------- Vault ----------------
    public VaultHook getVaultHook() {
        return vaultHook;
    }
}
