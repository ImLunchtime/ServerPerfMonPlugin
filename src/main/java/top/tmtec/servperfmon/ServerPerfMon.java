package top.tmtec.servperfmon;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import top.tmtec.servperfmon.monitor.PerformanceMonitor;
import top.tmtec.servperfmon.network.NetworkClient;

import java.util.Collections;
import java.util.List;

public final class ServerPerfMon extends JavaPlugin implements TabExecutor {

    private NetworkClient networkClient;
    private PerformanceMonitor performanceMonitor;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        
        String monitorUrl = getConfig().getString("monitor-server-url", "http://localhost:8080");
        if (monitorUrl.endsWith("/")) {
            monitorUrl = monitorUrl.substring(0, monitorUrl.length() - 1);
        }
        if (monitorUrl.endsWith("/report")) {
            monitorUrl = monitorUrl.substring(0, monitorUrl.length() - "/report".length());
        }

        getLogger().info(ChatColor.GREEN + "--- ServerPerfMon 配置 ---");
        getLogger().info("监控服务器 URL: " + monitorUrl);

        String serverId = getConfig().getString("server-id", "");
        
        // Initialize components
        this.networkClient = new NetworkClient(this, monitorUrl, serverId);
        this.performanceMonitor = new PerformanceMonitor(this, networkClient);

        // Start tasks
        this.networkClient.start();
        this.performanceMonitor.start(); // This starts the async TPS check

        // Register commands
        var command = getCommand("spm");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info("ServerPerfMon 已启用！");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (performanceMonitor != null) {
            performanceMonitor.stop();
        }
        getLogger().info("ServerPerfMon 已禁用！");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("spm")) return false;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "用法: /spm <reload|report|test> （重载配置|手动发送报告|测试功能）");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            // In a real scenario, we might need to restart the network client with new URL
            // For now, just reload config values that are pulled dynamically
            sender.sendMessage(ChatColor.GREEN + "配置重载需重启服务器生效 (大部分设置)。"); 
            // Or ideally, re-instantiate components, but that requires careful cleanup.
            // Given the scope, let's keep it simple.
            return true;
        } else if (args[0].equalsIgnoreCase("report")) {
            sender.sendMessage(ChatColor.YELLOW + "正在强制发送报告...");
            performanceMonitor.forceReport();
            return true;
        } else if (args[0].equalsIgnoreCase("test")) {
            if (networkClient.isConnected()) {
                sender.sendMessage(ChatColor.GREEN + "当前已连接到监控服务器。");
            } else {
                sender.sendMessage(ChatColor.RED + "当前未连接到监控服务器。正在尝试重连...");
            }
            return true;
        }

        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("spm")) {
            if (args.length == 1) {
                return List.of("reload", "report", "test");
            }
        }
        return Collections.emptyList();
    }
}
