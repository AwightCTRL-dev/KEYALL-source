package com.example.keyall;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeyAll: every player has their own playtime counter. The counter only ticks
 * while the player is online (and, optionally, not idle). When it reaches the
 * configured interval, the plugin runs a console command that gives them a key.
 */
public final class KeyAllPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, Long> seconds = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();

    private File dataFile;
    private long intervalSeconds;
    private String crateId;
    private String giveCommand;
    private boolean countAfk;
    private long afkMillis;
    private String rewardMessage;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadSettings();
        loadData();

        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            lastActive.put(p.getUniqueId(), now);
        }

        getServer().getPluginManager().registerEvents(this, this);
        // Count one second at a time on the main thread.
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // Autosave every 5 minutes so a crash only loses a little progress.
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::saveData, 6000L, 6000L);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();
        intervalSeconds = Math.max(1L, c.getLong("interval-minutes", 240L)) * 60L;
        crateId = c.getString("crate-id", "keyall");
        giveCommand = c.getString("command", "crate giveKey %crate% %player% 1");
        countAfk = c.getBoolean("count-afk-time", false);
        afkMillis = Math.max(1L, c.getLong("afk-after-seconds", 300L)) * 1000L;
        rewardMessage = c.getString("reward-message", "");
    }

    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = y.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                seconds.put(UUID.fromString(key), section.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // skip invalid entries
            }
        }
    }

    private synchronized void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : seconds.entrySet()) {
            y.set("players." + e.getKey(), e.getValue());
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!countAfk) {
                long last = lastActive.getOrDefault(id, now);
                if (now - last > afkMillis) {
                    continue; // idle, do not count this second
                }
            }
            long total = seconds.merge(id, 1L, Long::sum);
            if (total >= intervalSeconds) {
                seconds.put(id, total - intervalSeconds);
                reward(p);
            }
        }
    }

    private void reward(Player p) {
        String cmd = giveCommand
                .replace("%player%", p.getName())
                .replace("%crate%", crateId);
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        if (!rewardMessage.isEmpty()) {
            p.sendMessage(color(rewardMessage));
        }
    }

    private static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private static String format(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return m + "m " + s + "s";
    }

    private void touch(UUID id) {
        lastActive.put(id, System.currentTimeMillis());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        touch(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastActive.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.hasChangedPosition() || e.hasChangedOrientation()) {
            touch(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        touch(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        touch(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        touch(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("keyall.admin")) {
                sender.sendMessage(color("&cYou do not have permission."));
                return true;
            }
            loadSettings();
            sender.sendMessage(color("&aKeyAll config reloaded."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can check their timer.");
            return true;
        }
        long left = Math.max(0L, intervalSeconds - seconds.getOrDefault(p.getUniqueId(), 0L));
        p.sendMessage(color("&7Next &bKeyAll &7key in &e" + format(left) + "&7 of playtime."));
        return true;
    }
}
