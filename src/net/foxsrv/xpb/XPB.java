package net.foxsrv.xpb;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class XPB extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private final Map<UUID, Long> balanceCache = new ConcurrentHashMap<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private File dataFile;
    private final Object saveLock = new Object(); // Para evitar concorrência na escrita do arquivo

    // Color constants
    private static final ChatColor PRIMARY = ChatColor.GOLD;
    private static final ChatColor SECONDARY = ChatColor.YELLOW;
    private static final ChatColor SUCCESS = ChatColor.GREEN;
    private static final ChatColor ERROR = ChatColor.RED;
    private static final ChatColor INFO = ChatColor.WHITE;
    private static final ChatColor VALUE = ChatColor.WHITE;

    @Override
    public void onEnable() {
        // Garantir que a pasta do plugin exista
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "users.dat");

        // Carregar todos os dados do arquivo YAML
        loadAllData();

        // Registrar eventos e comandos
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("xpb")).setExecutor(this);
        Objects.requireNonNull(getCommand("xpb")).setTabCompleter(this);

        getLogger().info("XPBank enabled successfully!");
    }

    @Override
    public void onDisable() {
        // Salvar todos os dados antes de desligar (síncrono para garantir)
        saveAllDataSync();
        fileExecutor.shutdown();
        try {
            if (!fileExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                fileExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            fileExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        getLogger().info("XPBank disabled.");
    }

    // ======================== DATA LOAD/SAVE (YAML) ========================
    private void loadAllData() {
        if (!dataFile.exists()) {
            getLogger().info("No data file found. Starting fresh.");
            return;
        }
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
            for (String uuidStr : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    long balance = yaml.getLong(uuidStr);
                    if (balance > 0) {
                        balanceCache.put(uuid, balance);
                    }
                } catch (IllegalArgumentException e) {
                    getLogger().log(Level.WARNING, "Invalid UUID in data file: " + uuidStr, e);
                }
            }
            getLogger().info("Loaded " + balanceCache.size() + " player balances.");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to load data file!", e);
        }
    }

    private void saveAllDataSync() {
        synchronized (saveLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, Long> entry : balanceCache.entrySet()) {
                if (entry.getValue() > 0) {
                    yaml.set(entry.getKey().toString(), entry.getValue());
                } else {
                    yaml.set(entry.getKey().toString(), null); // remove entries with zero balance
                }
            }
            try {
                yaml.save(dataFile);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Failed to save data file!", e);
            }
        }
    }

    private void saveAllDataAsync() {
        fileExecutor.submit(this::saveAllDataSync);
    }

    // ======================== EVENTS ========================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // Garantir que o jogador tenha uma entrada no cache (se não existir, coloca 0)
        balanceCache.putIfAbsent(uuid, 0L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Não precisa remover do cache, mantemos para persistência
        // O cache continuará com o saldo, mas o arquivo já estará atualizado
        // (cada operação já salva o arquivo, então não há perda)
    }

    // ======================== HELPER METHODS ========================
    private long getBalance(UUID uuid) {
        return balanceCache.getOrDefault(uuid, 0L);
    }

    private boolean hasEnoughBalance(UUID uuid, long amount) {
        return getBalance(uuid) >= amount;
    }

    private boolean addBalance(UUID uuid, long amount, boolean asyncSave) {
        if (amount < 0) return false;
        long newBalance = getBalance(uuid) + amount;
        balanceCache.put(uuid, newBalance);
        if (asyncSave) {
            saveAllDataAsync();
        } else {
            saveAllDataSync();
        }
        return true;
    }

    private boolean removeBalance(UUID uuid, long amount, boolean asyncSave) {
        if (amount < 0) return false;
        long current = getBalance(uuid);
        if (current < amount) return false;
        long newBalance = current - amount;
        if (newBalance == 0) {
            balanceCache.remove(uuid);
        } else {
            balanceCache.put(uuid, newBalance);
        }
        if (asyncSave) {
            saveAllDataAsync();
        } else {
            saveAllDataSync();
        }
        return true;
    }

    private void depositXP(Player player, int levels) {
        int currentLevel = player.getLevel();
        if (currentLevel < levels) {
            player.sendMessage(ERROR + "You don't have enough XP levels in your bar! (You have " + currentLevel + " levels)");
            return;
        }
        float currentExp = player.getExp();
        player.setLevel(currentLevel - levels);
        player.setExp(currentExp);
        addBalance(player.getUniqueId(), levels, true);
        player.sendMessage(SUCCESS + "Deposited " + SECONDARY + levels + SUCCESS + " levels to your account. Balance: " + VALUE + getBalance(player.getUniqueId()));
    }

    private void withdrawXP(Player player, int levels) {
        UUID uuid = player.getUniqueId();
        if (!hasEnoughBalance(uuid, levels)) {
            player.sendMessage(ERROR + "Insufficient balance! Your balance: " + VALUE + getBalance(uuid));
            return;
        }
        float currentExp = player.getExp();
        player.setLevel(player.getLevel() + levels);
        player.setExp(currentExp);
        removeBalance(uuid, levels, true);
        player.sendMessage(SUCCESS + "Withdrew " + SECONDARY + levels + SUCCESS + " levels from your account. Remaining balance: " + VALUE + getBalance(uuid));
    }

    private void payXP(CommandSender sender, String targetName, int amount) {
        if (amount <= 0) {
            sender.sendMessage(ERROR + "Amount must be positive.");
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(ERROR + "Player not found or offline.");
            return;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ERROR + "Console cannot send XP to players. Use /xpb admin give.");
            return;
        }
        UUID sourceUUID = ((Player) sender).getUniqueId();
        if (sourceUUID.equals(target.getUniqueId())) {
            sender.sendMessage(ERROR + "You cannot pay yourself.");
            return;
        }
        if (!hasEnoughBalance(sourceUUID, amount)) {
            sender.sendMessage(ERROR + "Insufficient balance! Your balance: " + VALUE + getBalance(sourceUUID));
            return;
        }
        if (removeBalance(sourceUUID, amount, true) && addBalance(target.getUniqueId(), amount, true)) {
            sender.sendMessage(SUCCESS + "You sent " + SECONDARY + amount + SUCCESS + " levels to " + target.getName() + ". Current balance: " + VALUE + getBalance(sourceUUID));
            target.sendMessage(SUCCESS + "You received " + SECONDARY + amount + SUCCESS + " levels from " + ((Player) sender).getName() + ". Current balance: " + VALUE + getBalance(target.getUniqueId()));
        } else {
            sender.sendMessage(ERROR + "Failed to transfer XP. Please try again.");
        }
    }

    private void adminGive(CommandSender sender, String targetName, int amount) {
        if (amount <= 0) {
            sender.sendMessage(ERROR + "Amount must be positive.");
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target != null && target.isOnline()) {
            addBalance(target.getUniqueId(), amount, true);
            sender.sendMessage(SUCCESS + "Added " + SECONDARY + amount + SUCCESS + " levels to " + target.getName() + ". New balance: " + VALUE + getBalance(target.getUniqueId()));
            target.sendMessage(SUCCESS + "An administrator added " + SECONDARY + amount + SUCCESS + " levels to your account. Balance: " + VALUE + getBalance(target.getUniqueId()));
        } else {
            // Offline player: obter UUID e modificar cache diretamente
            UUID offlineUUID = getOfflineUUID(targetName);
            if (offlineUUID == null) {
                sender.sendMessage(ERROR + "Player has never played on this server.");
                return;
            }
            long currentBalance = getBalance(offlineUUID);
            long newBalance = currentBalance + amount;
            balanceCache.put(offlineUUID, newBalance);
            saveAllDataAsync();
            sender.sendMessage(SUCCESS + "Added " + SECONDARY + amount + SUCCESS + " levels to offline player " + targetName + ". New balance: " + VALUE + newBalance);
        }
    }

    private void adminRemove(CommandSender sender, String targetName, int amount) {
        if (amount <= 0) {
            sender.sendMessage(ERROR + "Amount must be positive.");
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target != null && target.isOnline()) {
            if (removeBalance(target.getUniqueId(), amount, true)) {
                sender.sendMessage(SUCCESS + "Removed " + SECONDARY + amount + SUCCESS + " levels from " + target.getName() + ". New balance: " + VALUE + getBalance(target.getUniqueId()));
                target.sendMessage(ERROR + "An administrator removed " + SECONDARY + amount + ERROR + " levels from your account. Balance: " + VALUE + getBalance(target.getUniqueId()));
            } else {
                sender.sendMessage(ERROR + "Insufficient balance! " + target.getName() + "'s balance: " + VALUE + getBalance(target.getUniqueId()));
            }
        } else {
            UUID offlineUUID = getOfflineUUID(targetName);
            if (offlineUUID == null) {
                sender.sendMessage(ERROR + "Player has never played on this server.");
                return;
            }
            long currentBalance = getBalance(offlineUUID);
            if (currentBalance < amount) {
                sender.sendMessage(ERROR + "Insufficient balance! Current balance: " + VALUE + currentBalance);
                return;
            }
            long newBalance = currentBalance - amount;
            if (newBalance == 0) {
                balanceCache.remove(offlineUUID);
            } else {
                balanceCache.put(offlineUUID, newBalance);
            }
            saveAllDataAsync();
            sender.sendMessage(SUCCESS + "Removed " + SECONDARY + amount + SUCCESS + " levels from offline player " + targetName + ". New balance: " + VALUE + newBalance);
        }
    }

    private UUID getOfflineUUID(String playerName) {
        @SuppressWarnings("deprecation")
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(playerName);
        if (off.hasPlayedBefore() || off.isOnline()) {
            return off.getUniqueId();
        }
        return null;
    }

    // ======================== COMMANDS ========================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("bal") || sub.equals("balance")) {
            if (!(sender instanceof Player) && args.length < 2) {
                sender.sendMessage(ERROR + "Console: use /xpb balance <player>");
                return true;
            }
            if (args.length >= 2) {
                String targetName = args[1];
                Player targetPlayer = Bukkit.getPlayer(targetName);
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    sender.sendMessage(PRIMARY + targetPlayer.getName() + "'s balance: " + VALUE + getBalance(targetPlayer.getUniqueId()));
                } else {
                    UUID offlineUUID = getOfflineUUID(targetName);
                    if (offlineUUID == null) {
                        sender.sendMessage(ERROR + "Player has never played on this server.");
                    } else {
                        long balance = getBalance(offlineUUID);
                        sender.sendMessage(PRIMARY + "Offline balance of " + targetName + ": " + VALUE + balance);
                    }
                }
                return true;
            } else {
                Player p = (Player) sender;
                long bal = getBalance(p.getUniqueId());
                p.sendMessage(PRIMARY + "Your balance: " + VALUE + bal);
                return true;
            }
        }

        if (sub.equals("deposit")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ERROR + "Only players can deposit XP.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ERROR + "Usage: /xpb deposit <amount>");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ERROR + "Invalid amount. Use a positive integer.");
                return true;
            }
            depositXP((Player) sender, amount);
            return true;
        }

        if (sub.equals("withdraw")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ERROR + "Only players can withdraw XP.");
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ERROR + "Usage: /xpb withdraw <amount>");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ERROR + "Invalid amount. Use a positive integer.");
                return true;
            }
            withdrawXP((Player) sender, amount);
            return true;
        }

        if (sub.equals("pay")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ERROR + "Only players can send XP.");
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(ERROR + "Usage: /xpb pay <player> <amount>");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ERROR + "Invalid amount. Use a positive integer.");
                return true;
            }
            payXP(sender, args[1], amount);
            return true;
        }

        if (sub.equals("admin")) {
            if (!sender.hasPermission("xpb.admin") && !sender.isOp()) {
                sender.sendMessage(ERROR + "You don't have permission to use this command.");
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(ERROR + "Usage: /xpb admin give|remove <player> <amount>");
                return true;
            }
            String action = args[1].toLowerCase();
            String targetName = args[2];
            if (args.length != 4) {
                sender.sendMessage(ERROR + "Specify amount: /xpb admin " + action + " " + targetName + " <amount>");
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[3]);
                if (amount <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                sender.sendMessage(ERROR + "Invalid amount. Use a positive integer.");
                return true;
            }
            if (action.equals("give")) {
                adminGive(sender, targetName, amount);
            } else if (action.equals("remove")) {
                adminRemove(sender, targetName, amount);
            } else {
                sender.sendMessage(ERROR + "Invalid action. Use 'give' or 'remove'.");
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(PRIMARY + "=== XPBank Help ===");
        sender.sendMessage(SECONDARY + "/xpb balance [player] " + INFO + "- Check balance");
        sender.sendMessage(SECONDARY + "/xpb deposit <amount> " + INFO + "- Deposit XP levels");
        sender.sendMessage(SECONDARY + "/xpb withdraw <amount> " + INFO + "- Withdraw XP levels");
        sender.sendMessage(SECONDARY + "/xpb pay <player> <amount> " + INFO + "- Send XP to another player");
        if (sender.hasPermission("xpb.admin") || sender.isOp()) {
            sender.sendMessage(ERROR + "/xpb admin give <player> <amount> " + INFO + "- Give XP to a player");
            sender.sendMessage(ERROR + "/xpb admin remove <player> <amount> " + INFO + "- Remove XP from a player");
        }
    }

    // ======================== TAB COMPLETER ========================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("balance", "bal", "deposit", "withdraw", "pay"));
            if (sender.hasPermission("xpb.admin") || sender.isOp()) {
                subs.add("admin");
            }
            String partial = args[0].toLowerCase();
            return subs.stream().filter(s -> s.startsWith(partial)).collect(Collectors.toList());
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("balance") || args[0].equalsIgnoreCase("bal")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("pay")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> !n.equals(sender.getName())).collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("admin") && (sender.hasPermission("xpb.admin") || sender.isOp())) {
                return Arrays.asList("give", "remove");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("remove"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("admin") && (args[1].equalsIgnoreCase("give") || args[1].equalsIgnoreCase("remove"))) {
            return Collections.singletonList("<amount>");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("deposit") || args[0].equalsIgnoreCase("withdraw"))) {
            return Collections.singletonList("<amount>");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("pay")) {
            return Collections.singletonList("<amount>");
        }
        return Collections.emptyList();
    }
}
