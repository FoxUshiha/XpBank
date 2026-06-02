package net.foxsrv.xpb;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
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
    private File playerDataFolder;

    // Color constants
    private static final ChatColor PRIMARY = ChatColor.GOLD;
    private static final ChatColor SECONDARY = ChatColor.YELLOW;
    private static final ChatColor SUCCESS = ChatColor.GREEN;
    private static final ChatColor ERROR = ChatColor.RED;
    private static final ChatColor INFO = ChatColor.WHITE;
    private static final ChatColor VALUE = ChatColor.WHITE;

    @Override
    public void onEnable() {
        playerDataFolder = new File(getDataFolder(), "playerdata");
        if (!playerDataFolder.exists()) {
            playerDataFolder.mkdirs();
        }

        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("xpb")).setExecutor(this);
        Objects.requireNonNull(getCommand("xpb")).setTabCompleter(this);

        getLogger().info("XPBank enabled successfully!");
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            saveBalanceAsync(player.getUniqueId(), balanceCache.get(player.getUniqueId()));
        }
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

    // ======================== EVENTS ========================
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        long balance = loadBalanceFromFile(uuid);
        balanceCache.put(uuid, balance);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Long balance = balanceCache.remove(uuid);
        if (balance != null) {
            saveBalanceAsync(uuid, balance);
        }
    }

    // ======================== FILE OPERATIONS (ASYNC) ========================
    private long loadBalanceFromFile(UUID uuid) {
        File file = new File(playerDataFolder, uuid.toString() + ".dat");
        if (!file.exists()) return 0L;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            return dis.readLong();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to load balance for " + uuid, e);
            return 0L;
        }
    }

    private void saveBalanceToFile(UUID uuid, long balance) {
        File file = new File(playerDataFolder, uuid.toString() + ".dat");
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            dos.writeLong(balance);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to save balance for " + uuid, e);
        }
    }

    private void saveBalanceAsync(UUID uuid, long balance) {
        fileExecutor.submit(() -> saveBalanceToFile(uuid, balance));
    }

    private void updateBalance(UUID uuid, long newBalance, boolean asyncSave) {
        balanceCache.put(uuid, newBalance);
        if (asyncSave) {
            saveBalanceAsync(uuid, newBalance);
        } else {
            saveBalanceToFile(uuid, newBalance);
        }
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
        updateBalance(uuid, newBalance, asyncSave);
        return true;
    }

    private boolean removeBalance(UUID uuid, long amount, boolean asyncSave) {
        if (amount < 0) return false;
        long current = getBalance(uuid);
        if (current < amount) return false;
        long newBalance = current - amount;
        updateBalance(uuid, newBalance, asyncSave);
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
            UUID offlineUUID = getOfflineUUID(targetName);
            if (offlineUUID == null) {
                sender.sendMessage(ERROR + "Player has never played on this server.");
                return;
            }
            long currentBalance = loadBalanceFromFile(offlineUUID);
            long newBalance = currentBalance + amount;
            saveBalanceToFile(offlineUUID, newBalance);
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
            long currentBalance = loadBalanceFromFile(offlineUUID);
            if (currentBalance < amount) {
                sender.sendMessage(ERROR + "Insufficient balance! Current balance: " + VALUE + currentBalance);
                return;
            }
            long newBalance = currentBalance - amount;
            saveBalanceToFile(offlineUUID, newBalance);
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
                        long balance = loadBalanceFromFile(offlineUUID);
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
