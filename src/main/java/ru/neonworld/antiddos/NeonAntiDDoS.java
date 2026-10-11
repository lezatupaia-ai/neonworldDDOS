package ru.neonworld.antiddos;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NeonAntiDDoS extends JavaPlugin implements Listener {

    private static final long JOIN_WINDOW_MS = 60_000L;

    private final Deque<Long> joinTimestamps = new ArrayDeque<>();
    private final Map<String, Long> kickedList = new ConcurrentHashMap<>();
    private final Set<String> onlineNames = ConcurrentHashMap.newKeySet();
    private final Set<String> whitelist = ConcurrentHashMap.newKeySet();

    private boolean protectionActive = false;
    private long lastBotSeen = 0;
    private File kickedFile;
    private int totalKicked = 0;
    private int totalBanned = 0;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        kickedFile = new File(getDataFolder(), "kicked.json");
        loadKicked();
        loadWhitelistFromConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        new BukkitRunnable() {
            @Override
            public void run() { checkProtectionExit(); }
        }.runTaskTimer(this, 100L, 100L);

        new BukkitRunnable() {
            @Override
            public void run() { cleanOldKicked(); }
        }.runTaskTimerAsynchronously(this, 6000L, 6000L);

        getLogger().info("NeonAntiDDoS включен. Защита готова.");
    }

    @Override
    public void onDisable() {
        saveKicked();
        getLogger().info("NeonAntiDDoS выключен.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String nick = player.getName();
        onlineNames.add(nick);

        long now = System.currentTimeMillis();
        synchronized (joinTimestamps) {
            joinTimestamps.addLast(now);
            cleanOldTimestamps(now);
        }

        if (protectionActive) {
            handlePossibleBot(player);
            return;
        }

        int recentJoins;
        synchronized (joinTimestamps) { recentJoins = joinTimestamps.size(); }

        int threshold = getConfig().getInt("join-threshold", 10);
        if (recentJoins >= threshold) {
            startProtection(recentJoins);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        onlineNames.remove(event.getPlayer().getName());
    }

    private void handlePossibleBot(Player player) {
        String nick = player.getName();
        if (isWhitelisted(nick)) return;

        int similarCount = 0;
        double threshold = getConfig().getDouble("similarity-threshold", 0.7);
        for (String online : onlineNames) {
            if (online.equals(nick)) continue;
            if (similarity(nick, online) >= threshold) similarCount++;
        }

        int minCluster = getConfig().getInt("min-cluster-size", 3);
        int required = Math.max(1, minCluster - 1);
        if (similarCount < required) return;

        lastBotSeen = System.currentTimeMillis();

        if (kickedList.containsKey(nick)) {
            banPlayer(player, nick);
        } else {
            kickPlayer(player, nick);
        }
    }

    private void kickPlayer(Player player, String nick) {
        String reason = color(getConfig().getString("kick-reason", "&cОбнаружен как бот"));
        Bukkit.getScheduler().runTask(this, () -> player.kickPlayer(reason));
        kickedList.put(nick, System.currentTimeMillis());
        totalKicked++;
        saveKicked();
        notifyAdmins("&e👢 Кикнут бот: &f" + nick);
        getLogger().info("Kicked bot: " + nick);
    }

    private void banPlayer(Player player, String nick) {
        String reason = color(getConfig().getString("ban-reason", "&cПовторный заход бота"));
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                BanList banList = Bukkit.getBanList(BanList.Type.NAME);
                banList.addBan(nick, reason, (Date) null, "NeonAntiDDoS");
                player.kickPlayer(reason);
            } catch (Exception e) {
                player.kickPlayer(reason);
            }
        });
        totalBanned++;
        kickedList.remove(nick);
        saveKicked();
        notifyAdmins("&c🔨 Забанен повторный бот: &f" + nick);
        getLogger().info("Banned bot: " + nick);
    }

    private void startProtection(int recentJoins) {
        if (protectionActive) return;
        protectionActive = true;
        lastBotSeen = System.currentTimeMillis();
        notifyAdmins("&c🚨 АНТИ-DDOS ЗАЩИТА ВКЛЮЧЕНА! Заходов за минуту: &f" + recentJoins);
        getLogger().warning("Anti-DDoS ACTIVATED. Joins in last minute: " + recentJoins);
    }

    private void checkProtectionExit() {
        if (!protectionActive) return;
        int exitSeconds = getConfig().getInt("protection-exit-seconds", 30);
        long elapsed = (System.currentTimeMillis() - lastBotSeen) / 1000;
        if (elapsed >= exitSeconds) {
            protectionActive = false;
            notifyAdmins("&a✅ Анти-DDoS защита отключена. Тишина " + elapsed + " сек.");
        }
    }

    private void cleanOldTimestamps(long now) {
        while (!joinTimestamps.isEmpty() && now - joinTimestamps.peekFirst() > JOIN_WINDOW_MS) {
            joinTimestamps.pollFirst();
        }
    }

    private void cleanOldKicked() {
        int hours = getConfig().getInt("kicked-memory-hours", 24);
        long limit = hours * 3600_000L;
        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, Long>> it = kickedList.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > limit) {
                it.remove();
                changed = true;
            }
        }
        if (changed) saveKicked();
    }

    private void loadWhitelistFromConfig() {
        whitelist.clear();
        for (String s : getConfig().getStringList("whitelist")) {
            whitelist.add(s.toLowerCase());
        }
    }

    private boolean isWhitelisted(String nick) {
        if (whitelist.contains(nick.toLowerCase())) return true;
        Player p = Bukkit.getPlayerExact(nick);
        return p != null && p.hasPermission("neonddos.bypass");
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private void notifyAdmins(String msg) {
        if (!getConfig().getBoolean("notify-admins", true)) return;
        String colored = color(msg);
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("neonddos.admin")) {
                    p.sendMessage(color("&8[&cAntiDDoS&8] ") + colored);
                }
            }
            Bukkit.getConsoleSender().sendMessage(color("&8[&cAntiDDoS&8] ") + colored);
        });
    }

    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        String x = a.toLowerCase();
        String y = b.toLowerCase();
        if (x.equals(y)) return 1.0;

        int pref = 0;
        int minLen = Math.min(x.length(), y.length());
        for (int i = 0; i < minLen; i++) {
            if (x.charAt(i) == y.charAt(i)) pref++;
            else break;
        }

        int suf = 0;
        for (int i = 1; i <= minLen; i++) {
            if (x.charAt(x.length() - i) == y.charAt(y.length() - i)) suf++;
            else break;
        }

        int maxLen = Math.max(x.length(), y.length());
        if (maxLen == 0) return 0.0;
        return (double) (pref + suf) / maxLen;
    }

    private void loadKicked() {
        if (!kickedFile.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(kickedFile), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String content = sb.toString().trim();
            if (content.isEmpty() || content.equals("{}")) return;
            content = content.replace("{", "").replace("}", "").replace("\"", "");
            for (String pair : content.split(",")) {
                String[] parts = pair.split(":");
                if (parts.length == 2) {
                    try {
                        kickedList.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            getLogger().warning("Не удалось загрузить kicked.json: " + e.getMessage());
        }
    }

    private void saveKicked() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(kickedFile), StandardCharsets.UTF_8))) {
                w.write("{");
                boolean first = true;
                for (Map.Entry<String, Long> e : kickedList.entrySet()) {
                    if (!first) w.write(",");
                    w.write("\"" + e.getKey() + "\":" + e.getValue());
                    first = false;
                }
                w.write("}");
            }
        } catch (Exception e) {
            getLogger().warning("Не удалось сохранить kicked.json: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("neonddos")) return false;

        if (!sender.hasPermission("neonddos.admin")) {
            sender.sendMessage(color("&cУ вас нет прав."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload":
                reloadConfig();
                loadWhitelistFromConfig();
                sender.sendMessage(color("&aКонфиг перезагружен."));
                break;

            case "status":
                sender.sendMessage(color("&8&m───── &cAnti-DDoS Status &8&m─────"));
                sender.sendMessage(color("&7Защита: " + (protectionActive ? "&cАКТИВНА" : "&aвыкл")));
                sender.sendMessage(color("&7Заходов за минуту: &f" + joinTimestamps.size()));
                sender.sendMessage(color("&7Кикнуто: &f" + totalKicked + " &7| Забанено: &f" + totalBanned));
                sender.sendMessage(color("&7Помню кикнутых: &f" + kickedList.size()));
                sender.sendMessage(color("&7Whitelist: &f" + whitelist.size()));
                break;

            case "clear":
                kickedList.clear();
                saveKicked();
                sender.sendMessage(color("&aСписок кикнутых очищен."));
                break;

            case "whitelist":
                if (args.length < 2) {
                    sender.sendMessage(color("&c/neonddos whitelist <add|remove|list> [ник]"));
                    return true;
                }
                String action = args[1].toLowerCase();
                if (action.equals("list")) {
                    sender.sendMessage(color("&7Whitelist: &f" + String.join(", ", whitelist)));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(color("&cУкажи ник."));
                    return true;
                }
                String nick = args[2];
                List<String> wl = new ArrayList<>(getConfig().getStringList("whitelist"));
                if (action.equals("add")) {
                    if (wl.stream().noneMatch(s -> s.equalsIgnoreCase(nick))) wl.add(nick);
                    getConfig().set("whitelist", wl);
                    saveConfig();
                    loadWhitelistFromConfig();
                    sender.sendMessage(color("&aДобавлен в whitelist: &f" + nick));
                } else if (action.equals("remove")) {
                    wl.removeIf(s -> s.equalsIgnoreCase(nick));
                    getConfig().set("whitelist", wl);
                    saveConfig();
                    loadWhitelistFromConfig();
                    sender.sendMessage(color("&aУдалён из whitelist: &f" + nick));
                } else {
                    sender.sendMessage(color("&cДействия: add | remove | list"));
                }
                break;

            default:
                sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender s) {
        s.sendMessage(color("&8&m───── &cNeonAntiDDoS &8&m─────"));
        s.sendMessage(color("&f/neonddos &7status &8— &7статус"));
        s.sendMessage(color("&f/neonddos &7reload &8— &7конфиг"));
        s.sendMessage(color("&f/neonddos &7clear &8— &7очистить список"));
        s.sendMessage(color("&f/neonddos &7whitelist &7add|remove|list [ник]"));
    }
}
