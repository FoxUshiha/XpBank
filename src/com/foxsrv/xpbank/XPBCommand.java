package com.foxsrv.xpbank;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class XPBCommand implements CommandExecutor, TabCompleter {

    private final XpBank plugin;
    private final PlayerData playerData;

    public XPBCommand(XpBank plugin) {
        this.plugin = plugin;
        this.playerData = new PlayerData(plugin);
    }

    // ---------------- Conversão de XP ----------------
    private int levelsToPoints(int levels) {
        int points = 0;
        for (int i = 0; i < levels; i++) {
            points += getExpToLevel(i);
        }
        return points;
    }

    private int getExpToLevel(int level) {
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    private int pointsToLevels(int points) {
        int level = 0;
        int remaining = points;
        while (true) {
            int expToNext = getExpToLevel(level);
            if (remaining < expToNext) break;
            remaining -= expToNext;
            level++;
        }
        return level;
    }

    private int getTotalExperience(Player p) {
        int exp = Math.round(getExpToLevel(p.getLevel()) * p.getExp());
        for (int i = 0; i < p.getLevel(); i++) {
            exp += getExpToLevel(i);
        }
        return exp;
    }

    private void setTotalExperience(Player p, int amount) {
        p.setTotalExperience(0);
        p.setLevel(0);
        p.setExp(0);
        p.giveExp(amount);
    }

    // ---------------- Executor ----------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String lbl, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can do that.");
            return true;
        }
        Player player = (Player) sender;

        // /xpb
        if (args.length == 0) {
            int xp = playerData.getXp(player);
            int levels = pointsToLevels(xp);
            player.sendMessage(ChatColor.GREEN + "You have " + xp + " xp points (" + levels + " lvl).");
            return true;
        }

        String sub = args[0].toLowerCase();

        // ---------------- RELOAD ----------------
        if (sub.equals("reload")) {
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }
            plugin.reloadConfig();
            plugin.reloadPlayers();
            player.sendMessage(ChatColor.GREEN + "XPBank reloaded!");
            return true;

        // ---------------- DEPOSIT ----------------
        } else if (sub.equals("deposit")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Uso: /xpb deposit <quantia[l]>");
                return true;
            }
            String input = args[1];
            boolean isLevel = input.endsWith("l");
            int amount = Integer.parseInt(input.replace("l", ""));
            int points = isLevel ? levelsToPoints(amount) : amount;

            int totalExp = getTotalExperience(player);
            if (totalExp < points) {
                player.sendMessage(ChatColor.RED + "You do not have enough of XP.");
                return true;
            }

            setTotalExperience(player, totalExp - points);
            playerData.addXp(player, points);
            player.sendMessage(ChatColor.GREEN + "Deposited " + points + " XP points in the bank.");
            return true;

        // ---------------- WITHDRAW / SAQUE ----------------
        } else if (sub.equals("withdraw") || sub.equals("saque")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /xpb withdraw <amount[l]>");
                return true;
            }
            String input = args[1];
            boolean isLevel = input.endsWith("l");
            int amount = Integer.parseInt(input.replace("l", ""));
            int points = isLevel ? levelsToPoints(amount) : amount;

            int bankXp = playerData.getXp(player);
            if (bankXp < points) {
                player.sendMessage(ChatColor.RED + "You do not have enough of XP points in the bank.");
                return true;
            }

            playerData.setXp(player, bankXp - points);
            setTotalExperience(player, getTotalExperience(player) + points);
            player.sendMessage(ChatColor.GREEN + "Withdrawl " + points + " XP points.");
            return true;

        // ---------------- BUY ----------------
        } else if (sub.equals("buy")) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /xpb buy <amount[l]>");
                return true;
            }
            String input = args[1];
            boolean isLevel = input.endsWith("l");
            int amount = Integer.parseInt(input.replace("l", ""));
            int points = isLevel ? levelsToPoints(amount) : amount;

            double price = plugin.getConfig().getDouble("Price", 1.0) * points;
            if (!plugin.getVaultHook().withdraw(player, price)) {
                player.sendMessage(ChatColor.RED + "You do not have enough of money.");
                return true;
            }

            String serverUUID = plugin.getConfig().getString("Server");
            plugin.getVaultHook().depositServer(serverUUID, price);

            playerData.addXp(player, points);
            player.sendMessage(ChatColor.GREEN + "You bought " + points + " XP points for $" + price);
            return true;

        // ---------------- PAY ----------------
        } else if (sub.equals("pay")) {
            if (args.length < 3) {
                player.sendMessage(ChatColor.RED + "Usage: /xpb pay <nick> <amount[l]>");
                return true;
            }
            String targetName = args[1];
            String input = args[2];
            boolean isLevel = input.endsWith("l");
            int amount = Integer.parseInt(input.replace("l", ""));
            int points = isLevel ? levelsToPoints(amount) : amount;

            if (points <= 0) {
                player.sendMessage(ChatColor.RED + "Invalid value.");
                return true;
            }

            int senderBalance = playerData.getXp(player);
            if (senderBalance < points) {
                player.sendMessage(ChatColor.RED + "You do not have enough of XP in the bank.");
                return true;
            }

            playerData.setXp(player, senderBalance - points);

            int targetBalance = plugin.getPlayersConfig().getInt(targetName + ".xp", 0);
            plugin.getPlayersConfig().set(targetName + ".xp", targetBalance + points);
            plugin.savePlayers();

            player.sendMessage(ChatColor.GREEN + "You sent " + points + " xp to " + targetName + ".");
            Player target = Bukkit.getPlayerExact(targetName);
            if (target != null && target.isOnline()) {
                target.sendMessage(ChatColor.GREEN + "You received " + points + " xp from " + player.getName() + ".");
            }

            return true;
        }

        return false;
    }

    // ---------------- TAB COMPLETER ----------------
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("deposit", "withdraw", "saque", "buy", "pay", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("pay")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return names;
        }
        return Collections.emptyList();
    }
}
