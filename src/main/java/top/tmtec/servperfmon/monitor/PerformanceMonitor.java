package top.tmtec.servperfmon.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import top.tmtec.servperfmon.ServerPerfMon;
import top.tmtec.servperfmon.network.NetworkClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PerformanceMonitor {
    private final ServerPerfMon plugin;
    private final NetworkClient networkClient;
    
    private BukkitTask monitorTask;
    private final AtomicBoolean isVerifying = new AtomicBoolean(false);
    private final AtomicBoolean isLowTpsState = new AtomicBoolean(false);
    private JsonObject cachedReport;

    public PerformanceMonitor(ServerPerfMon plugin, NetworkClient networkClient) {
        this.plugin = plugin;
        this.networkClient = networkClient;
    }

    public void start() {
        long intervalSeconds = plugin.getConfig().getLong("check-interval", 5);
        // Reduce TPS check interval to 1 second (20 ticks) for faster reaction
        long intervalTicks = 20L;
        
        monitorTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkTps, intervalTicks, intervalTicks);
    }

    public void stop() {
        if (monitorTask != null && !monitorTask.isCancelled()) {
            monitorTask.cancel();
        }
    }

    public void forceReport() {
        double currentTps = Bukkit.getTPS()[0];
        Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject report = initReport(currentTps, "manual_report");
            networkClient.sendReport(report).thenAccept(success -> {
                broadcastReportResult(success, currentTps, false);
            });
        });
    }

    private void checkTps() {
        if (isVerifying.get()) return;

        double[] tps = Bukkit.getTPS();
        double currentTps = tps[0];
        double threshold = plugin.getConfig().getDouble("tps-threshold", 15.0);

        if (currentTps >= threshold) {
            if (isLowTpsState.compareAndSet(true, false)) {
                plugin.getLogger().info(ChatColor.GREEN + "服务器 TPS 已完全恢复正常。");
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    JsonObject report = initReport(currentTps, "recovery_report");
                    networkClient.sendReport(report).thenAccept(success -> {
                        if (success) {
                             plugin.getLogger().info(ChatColor.GREEN + "已发送 TPS 恢复报告。");
                        }
                    });
                });
            }
            return;
        }

        if (isLowTpsState.get()) return;

        if (currentTps < threshold) {
            // Directly report low TPS without waiting for verification
            if (isLowTpsState.compareAndSet(false, true)) {
                plugin.getLogger().warning(ChatColor.RED + "检测到低 TPS (" + String.format("%.2f", currentTps) + ")。正在发送报告...");
                
                Bukkit.getScheduler().runTask(plugin, () -> {
                    JsonObject report = initReport(currentTps, "lag_report");
                    networkClient.sendReport(report).thenAccept(success -> {
                        broadcastReportResult(success, currentTps, true);
                    });
                });
            }
        }
    }

    private void broadcastReportResult(boolean success, double currentTps, boolean isLagReport) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String prefix = ChatColor.GOLD + "[ServerPerfMon] ";
            String status = success ? ChatColor.GREEN + "成功" : ChatColor.RED + "失败";
            
            if (isLagReport) {
                String msg = prefix + ChatColor.RED + "检测到低 TPS！" + 
                           ChatColor.YELLOW + String.format("%.2f", currentTps) + 
                           ChatColor.GRAY + " | 报告: " + status;
                Bukkit.broadcast(msg, "serverperfmon.admin"); // Assuming permission or OP check
                Bukkit.getConsoleSender().sendMessage(msg);
            } else {
                String msg = prefix + ChatColor.YELLOW + "手动报告已发送。TPS: " + 
                           String.format("%.2f", currentTps) + 
                           ChatColor.GRAY + " | 结果: " + status;
                Bukkit.broadcast(msg, "serverperfmon.admin");
                Bukkit.getConsoleSender().sendMessage(msg);
            }
        });
    }

    private JsonObject initReport(double tps, String type) {
        JsonObject report = new JsonObject();
        report.addProperty("server", Bukkit.getServer().getName());
        report.addProperty("tps", tps);
        report.addProperty("timestamp", System.currentTimeMillis());
        report.addProperty("type", type);
        collectReportData(report);
        return report;
    }

    private record ChunkEntityCount(String worldName, int x, int z, int count) {}

    private void collectReportData(JsonObject report) {
        // Players
        JsonArray players = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject playerObj = new JsonObject();
            playerObj.addProperty("name", p.getName());
            if (p.getLocation().getWorld() != null) {
                playerObj.addProperty("world", p.getLocation().getWorld().getName());
            }
            playerObj.addProperty("x", p.getLocation().getX());
            playerObj.addProperty("y", p.getLocation().getY());
            playerObj.addProperty("z", p.getLocation().getZ());
            players.add(playerObj);
        }
        report.add("players", players);

        // Chunks
        JsonArray topChunks = new JsonArray();
        List<ChunkEntityCount> chunkCounts = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                chunkCounts.add(new ChunkEntityCount(world.getName(), chunk.getX(), chunk.getZ(), chunk.getEntities().length));
            }
        }
        chunkCounts.sort(Comparator.comparingInt(ChunkEntityCount::count).reversed());
        
        for (int i = 0; i < Math.min(5, chunkCounts.size()); i++) {
            ChunkEntityCount c = chunkCounts.get(i);
            JsonObject chunkObj = new JsonObject();
            chunkObj.addProperty("world", c.worldName());
            chunkObj.addProperty("x", c.x());
            chunkObj.addProperty("z", c.z());
            chunkObj.addProperty("count", c.count());
            topChunks.add(chunkObj);
        }
        report.add("topChunks", topChunks);

        // Entities
        JsonArray topEntities = new JsonArray();
        Map<String, Integer> typeCounts = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                String type = entity.getType().name();
                typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
            }
        }
        
        typeCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(5)
            .forEach(entry -> {
                JsonObject typeObj = new JsonObject();
                typeObj.addProperty("type", entry.getKey());
                typeObj.addProperty("count", entry.getValue());
                topEntities.add(typeObj);
            });
        report.add("topEntityTypes", topEntities);
    }
}
