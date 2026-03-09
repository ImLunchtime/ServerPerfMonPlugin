package top.tmtec.servperfmon;

import org.bukkit.Bukkit;
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
import java.util.Random;

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
        this.performanceMonitor.start();

        // Register commands
        var command = getCommand("spm");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info("ServerPerfMon 已启用！");
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (performanceMonitor != null) {
            performanceMonitor.stop();
        }
        if (networkClient != null) {
            networkClient.stop();
        }
        getLogger().info("ServerPerfMon 已禁用！");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("spm")) return false;

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "用法: /spm <reload|report|test|bind> （重载配置|手动发送报告|测试功能|绑定服务器）");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(ChatColor.GREEN + "配置重载需重启服务器生效 (大部分设置)。"); 
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
        } else if (args[0].equalsIgnoreCase("bind")) {
            // Permission check: OP or spm.bind
            if (!sender.isOp() && !sender.hasPermission("spm.bind")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令！");
                return true;
            }

            if (!networkClient.isConnected()) {
                sender.sendMessage(ChatColor.RED + "未连接到监控服务器，无法获取绑定码！请先检查连接状态 (/spm test)。");
                return true;
            }

            sender.sendMessage(ChatColor.YELLOW + "正在获取绑定码...");
            
            // Generate 6-digit code
            int code = 100000 + new Random().nextInt(900000);
            String codeStr = String.valueOf(code);

            networkClient.sendBindRequest(codeStr).thenAccept(success -> {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (success) {
                        sender.sendMessage(ChatColor.GREEN + "============== 绑定代码 ==============");
                        sender.sendMessage(ChatColor.WHITE + "代码: " + ChatColor.AQUA + codeStr);
                        sender.sendMessage(ChatColor.GRAY + "请在 QQ 群或私聊中发送: " + ChatColor.YELLOW + "@机器人 bind " + codeStr);
                        sender.sendMessage(ChatColor.GRAY + "注意: 此代码仅在短时间内有效。");
                        sender.sendMessage(ChatColor.GREEN + "======================================");
                    } else {
                        sender.sendMessage(ChatColor.RED + "获取绑定码失败：监控服务器拒绝请求。");
                    }
                });
            });
            return true;
        }

        return false;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("spm")) {
            if (args.length == 1) {
                return List.of("reload", "report", "test", "bind");
            }
        }
        return Collections.emptyList();
    }
}
