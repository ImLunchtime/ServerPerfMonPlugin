package top.tmtec.servperfmon.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import top.tmtec.servperfmon.ServerPerfMon;
import top.tmtec.servperfmon.network.NetworkClient;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class PerformanceMonitor {
    private final ServerPerfMon plugin;
    private final NetworkClient networkClient;
    
    private BukkitTask syncMonitorTask;
    private BukkitTask asyncCheckTask;
    
    // Data Collection
    private final ConcurrentLinkedQueue<Long> tickDurations = new ConcurrentLinkedQueue<>();
    private final LinkedList<Double> tpsHistory = new LinkedList<>();
    private long lastTickTimeNano = 0;
    
    // State
    // Stores the type of alert sent and whether it is waiting for recovery.
    // If a type is in this set, we cannot send it again until recovery.
    private final Set<String> activeAlerts = new HashSet<>();
    private int recoveryCounter = 0; // Counts seconds with TPS > 15

    public PerformanceMonitor(ServerPerfMon plugin, NetworkClient networkClient) {
        this.plugin = plugin;
        this.networkClient = networkClient;
    }

    public void start() {
        // Sync Task: Measure tick duration
        lastTickTimeNano = System.nanoTime();
        syncMonitorTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            long durationNano = now - lastTickTimeNano;
            lastTickTimeNano = now;
            
            // Convert to ms
            long durationMs = durationNano / 1_000_000;
            
            tickDurations.add(durationMs);
            // Keep only last 20 ticks (approx 1 second history)
            while (tickDurations.size() > 20) {
                tickDurations.poll();
            }
        }, 1L, 1L); // Run every tick

        // Async Task: Check conditions every 1 second (20 ticks)
        asyncCheckTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::checkPerformance, 20L, 20L);
    }

    public void stop() {
        if (syncMonitorTask != null) syncMonitorTask.cancel();
        if (asyncCheckTask != null) asyncCheckTask.cancel();
    }

    public void forceReport() {
        double currentTps = getTPS();
        Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject report = initReport(currentTps, "manual_report", "手动报告", 0);
            networkClient.sendReport(report).thenAccept(success -> {
                 String status = success ? ChatColor.GREEN + "成功" : ChatColor.RED + "失败";
                 plugin.getLogger().info("手动报告发送结果: " + status);
            });
        });
    }

    public double getTPS() {
        try {
            double[] tps = Bukkit.getTPS();
            return tps[0];
        } catch (NoSuchMethodError e) {
            if (tickDurations.isEmpty()) return 20.0;
            long sum = 0;
            int count = 0;
            for (Long duration : tickDurations) {
                sum += duration;
                count++;
            }
            if (count == 0) return 20.0;
            double avgTickMs = (double) sum / count;
            if (avgTickMs <= 50.0) return 20.0;
            return Math.min(20.0, 1000.0 / avgTickMs);
        }
    }

    private void checkPerformance() {
        // Get snapshot of data
        List<Long> recentTicks = new ArrayList<>(tickDurations);
        if (recentTicks.size() < 5) return; // Need at least 5 ticks

        // Find max tick time in past 20 ticks (for reporting)
        double maxTickTime20 = recentTicks.stream().mapToLong(v -> v).max().orElse(0);

        // Get TPS (1m average)
        double currentTps = getTPS();
        
        // Track TPS history (1 second interval)
        tpsHistory.add(currentTps);
        while (tpsHistory.size() > 30) {
            tpsHistory.poll();
        }

        // --- Recovery Logic ---
        if (currentTps > 15.0) {
            recoveryCounter++;
        } else {
            recoveryCounter = 0;
        }

        if (recoveryCounter >= 30) {
            // Server has recovered for 30 seconds
            if (!activeAlerts.isEmpty()) {
                plugin.getLogger().info(ChatColor.GREEN + "TPS 恢复正常，清除警报冷却。");
                activeAlerts.clear();
            }
            recoveryCounter = 30; // Cap
        }

        // --- Alert Logic ---

        // 1. Instant MSPT High: 2 out of last 5 ticks > 200ms
        int countAbove200 = 0;
        int checkCount = Math.min(recentTicks.size(), 5);
        for (int i = 0; i < checkCount; i++) {
            // recentTicks is a queue converted to list, order is oldest -> newest
            // We want last 5, so iterate from end
            if (recentTicks.get(recentTicks.size() - 1 - i) > 200) {
                countAbove200++;
            }
        }
        
        if (countAbove200 >= 2) {
             if (!activeAlerts.contains("mspt_high")) {
                 sendAlert("mspt_high", "瞬时 MSPT 高", currentTps, maxTickTime20);
             }
        }

        // 2. Instant MSPT Critical: Any 1 tick > 1000ms (in recent buffer)
        boolean hasCriticalTick = recentTicks.stream().anyMatch(t -> t > 1000);
        if (hasCriticalTick) {
            if (!activeAlerts.contains("mspt_critical")) {
                sendAlert("mspt_critical", "瞬时 MSPT 过高", currentTps, maxTickTime20);
            }
        }

        // 3. TPS Low: Last 5 seconds TPS < 15
        // tpsHistory stores 1 sample per second (approx)
        if (tpsHistory.size() >= 5) {
            boolean tpsLowRecently = true;
            // Check last 5 entries
            for (int i = 0; i < 5; i++) {
                if (tpsHistory.get(tpsHistory.size() - 1 - i) >= 15.0) {
                    tpsLowRecently = false;
                    break;
                }
            }
            
            if (tpsLowRecently) {
                if (!activeAlerts.contains("tps_low")) {
                    sendAlert("tps_low", "TPS 持续过低", currentTps, maxTickTime20);
                }
            }
        }
    }

    private void sendAlert(String type, String title, double currentTps, double maxTickTime) {
        activeAlerts.add(type);
        recoveryCounter = 0; // Reset recovery counter on new alert
        
        plugin.getLogger().warning(ChatColor.RED + "Performance Warning: " + title);
        
        // Collect data on main thread (required for Bukkit API)
        Bukkit.getScheduler().runTask(plugin, () -> {
            JsonObject report = initReport(currentTps, type, title, maxTickTime);
            networkClient.sendReport(report);
        });
    }

    private JsonObject initReport(double tps, String type, String title, double maxTickTime) {
        JsonObject report = new JsonObject();
        report.addProperty("server", Bukkit.getServer().getName());
        report.addProperty("tps", tps);
        report.addProperty("timestamp", System.currentTimeMillis());
        report.addProperty("type", "lag_report"); // Generic type for bot to handle
        report.addProperty("alertType", type);    // Specific alert type
        report.addProperty("alertTitle", title);
        report.addProperty("maxTickTime", maxTickTime);
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
