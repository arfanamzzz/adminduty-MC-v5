package com.arfan.adminduty;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class AdminDuty extends JavaPlugin implements Listener {

    // Disimpan di memori saja: restart server / logout = semua otomatis OFF DUTY.
    private final Set<UUID> onDuty = new HashSet<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Set<String> adminNames = new HashSet<>();
    private final Set<String> adminPlugins = new HashSet<>();
    private final Set<String> alwaysAllowed = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLists();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            refreshAccess(p);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            removeAttachment(p);
        }
    }

    private void loadLists() {
        adminNames.clear();
        adminPlugins.clear();
        alwaysAllowed.clear();
        for (String s : getConfig().getStringList("admins")) {
            adminNames.add(s.trim().toLowerCase(Locale.ROOT));
        }
        for (String s : getConfig().getStringList("admin-plugins")) {
            adminPlugins.add(s.toLowerCase(Locale.ROOT));
        }
        for (String s : getConfig().getStringList("always-allowed")) {
            alwaysAllowed.add(s.toLowerCase(Locale.ROOT));
        }
    }

    private boolean isAdmin(Player p) {
        return adminNames.contains(p.getName().toLowerCase(Locale.ROOT));
    }

    private String msg(String key) {
        String prefix = getConfig().getString("messages.prefix", "&6[AdminDuty] ");
        String text = getConfig().getString("messages." + key, "");
        return ChatColor.translateAlternateColorCodes('&', prefix + text);
    }

    // Beri / cabut akses /adminduty sesuai daftar nama di config.
    private void removeAttachment(Player p) {
        PermissionAttachment old = attachments.remove(p.getUniqueId());
        if (old != null) {
            try {
                p.removeAttachment(old);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void refreshAccess(Player p) {
        removeAttachment(p);
        if (isAdmin(p)) {
            PermissionAttachment a = p.addAttachment(this);
            a.setPermission("adminduty.use", true);
            attachments.put(p.getUniqueId(), a);
        } else {
            onDuty.remove(p.getUniqueId());
        }
        p.updateCommands();
    }

    // ---------------- /adminduty ----------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /adminduty reload  (admin atau console)
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            boolean allowed = sender instanceof ConsoleCommandSender
                    || (sender instanceof Player pl && isAdmin(pl));
            if (!allowed) return true;
            reloadConfig();
            loadLists();
            for (Player p : Bukkit.getOnlinePlayers()) {
                refreshAccess(p);
            }
            sender.sendMessage(msg("reloaded"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("console"));
            return true;
        }
        if (!isAdmin(player)) {
            return true; // pengaman ekstra, player biasa tidak dapat apa-apa
        }
        UUID id = player.getUniqueId();
        if (onDuty.remove(id)) {
            player.sendMessage(msg("off-duty"));
        } else {
            onDuty.add(id);
            player.sendMessage(msg("on-duty"));
        }
        return true;
    }

    // ---------------- Blokir command admin kalau belum on duty ----------------
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();

        // Bukan admin di config: plugin ini tidak ikut campur sama sekali, tidak ada notif.
        if (!isAdmin(p)) {
            return;
        }
        if (onDuty.contains(p.getUniqueId())) {
            return;
        }

        String raw = e.getMessage();
        if (raw.length() < 2) return;
        String label = raw.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (label.isEmpty()) return;

        if (isAdminCommand(label)) {
            e.setCancelled(true);
            p.sendMessage(msg("not-on-duty"));
        }
    }

    private boolean isAdminCommand(String label) {
        // "essentials:heal" -> "heal"
        String shortLabel = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
        if (alwaysAllowed.contains(label) || alwaysAllowed.contains(shortLabel)) {
            return false;
        }

        Command cmd = Bukkit.getCommandMap().getCommand(label);
        if (cmd == null) {
            return false; // bukan command yang dikenal, biarkan server yang menangani
        }

        // 1) Command yang punya permission default OP (termasuk command vanilla) = admin.
        String perm = cmd.getPermission();
        if (perm != null && !perm.isBlank()) {
            for (String part : perm.split(";")) {
                Permission pr = Bukkit.getPluginManager().getPermission(part.trim());
                PermissionDefault def = (pr == null) ? PermissionDefault.OP : pr.getDefault();
                if (def == PermissionDefault.TRUE || def == PermissionDefault.NOT_OP) {
                    return false;
                }
            }
            return true;
        }

        // 2) Tidak ada permission di command-nya (contoh EssentialsX): lihat plugin pemiliknya.
        if (cmd instanceof PluginIdentifiableCommand pic) {
            String owner = pic.getPlugin().getName().toLowerCase(Locale.ROOT);
            return adminPlugins.contains(owner);
        }
        return false;
    }

    // ---------------- Join / Logout ----------------
    // Join: tidak ada pesan apa pun (otomatis OFF DUTY secara diam-diam).
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        onDuty.remove(e.getPlayer().getUniqueId());
        refreshAccess(e.getPlayer());
    }

    // Logout: otomatis OFF DUTY.
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        onDuty.remove(e.getPlayer().getUniqueId());
        removeAttachment(e.getPlayer());
    }
}
