package top.tmtec.servperfmon.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import top.tmtec.servperfmon.ServerPerfMon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class NetworkClient {
    private final ServerPerfMon plugin;
    private final String monitorUrl;
    private String serverId;
    private final HttpClient httpClient;
    private final Gson gson;
    
    private boolean isConnected = false;
    private long lastActionTime = 0; // Timestamp of last successful action or attempt start

    public NetworkClient(ServerPerfMon plugin, String monitorUrl, String serverId) {
        this.plugin = plugin;
        this.monitorUrl = monitorUrl;
        this.serverId = serverId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)) // Short timeout for faster failure detection
                .build();
        this.gson = new Gson();
    }

    public void start() {
        // Run check loop every 1 second (20 ticks) asynchronously
        // Bukkit Scheduler is tied to game ticks. If server lags, this lags.
        // We should use a Java ScheduledExecutorService for network operations to be independent of TPS.
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                connectionLoop();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in network loop", e);
            }
        }, 0, 1, java.util.concurrent.TimeUnit.SECONDS);
    }
    
    public boolean isConnected() {
        return isConnected;
    }
    
    public void stop() {
        // No explicit stop needed for async task as plugin disable handles it, but good practice
    }

    private void connectionLoop() {
        if (!plugin.isEnabled()) return;
        
        long now = System.currentTimeMillis();

        if (!isConnected) {
            // Handshake Phase: Retry every 10 seconds
            if (now - lastActionTime >= 10000) {
                attemptHandshake();
            }
        } else {
            // Keep-alive Phase: Send every 5 seconds
            if (now - lastActionTime >= 5000) {
                sendKeepAlive();
            }
        }
    }

    private void attemptHandshake() {
        lastActionTime = System.currentTimeMillis();
        plugin.getLogger().info("正在尝试连接监控服务器...");

        JsonObject json = new JsonObject();
        // Use config name or fallback to server implementation name
        String configName = plugin.getConfig().getString("server-name", "Minecraft Server");
        json.addProperty("serverName", configName);
        json.addProperty("version", Bukkit.getVersion());
        if (serverId != null && !serverId.isEmpty()) {
            json.addProperty("existingId", serverId);
        }

        sendRequest("/register", json).thenAccept(response -> {
            if (response != null && response.statusCode() == 200) {
                try {
                    String body = response.body();
                    JsonObject resp = gson.fromJson(body, JsonObject.class);
                    String newId = resp.get("id").getAsString();
                    
                    // Update ID if changed
                    if (serverId == null || !serverId.equals(newId)) {
                        this.serverId = newId;
                        // Save config on main thread
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            plugin.getConfig().set("server-id", newId);
                            plugin.saveConfig();
                        });
                    }

                    isConnected = true;
                    // lastActionTime is set at start of method
                    plugin.getLogger().info(ChatColor.GREEN + "成功连接到监控服务器！ID: " + newId);
                } catch (Exception e) {
                    plugin.getLogger().warning("解析握手响应失败: " + e.getMessage());
                    isConnected = false;
                }
            } else {
                plugin.getLogger().warning("握手失败。将在 10 秒后重试。");
                isConnected = false;
            }
        });
    }

    private void sendKeepAlive() {
        lastActionTime = System.currentTimeMillis();
        
        JsonObject json = new JsonObject();
        json.addProperty("serverId", serverId);

        // Include current status in keep-alive for real-time updates
        // This makes "status" command work even without a lag report
        JsonObject statusReport = new JsonObject();
        statusReport.addProperty("tps", Bukkit.getTPS()[0]);
        statusReport.addProperty("timestamp", System.currentTimeMillis());
        
        // Players (Simplified for keep-alive to save bandwidth, full list in report)
        // Actually, for the "status" command to work, we need the player list.
        // Let's send a "status_update" type report periodically instead of just keepalive?
        // Or piggyback on keepalive.
        // The server expects /report for data. Let's send a report every keep-alive interval instead?
        // Or just add data to keepalive and handle it on server.
        // BUT server code for /keepalive only updates timestamp.
        
        // BETTER APPROACH: Send a "status_report" to /report endpoint periodically (e.g. every 5s) instead of /keepalive
        // If we send /report, it also updates lastSeen, so it acts as keepalive.
        
        // Let's change strategy:
        // If connected, send a "status_report" every 5 seconds to /report.
        // This replaces the empty /keepalive.
        
        sendReport(initStatusReport()).thenAccept(success -> {
             if (!success) {
                 plugin.getLogger().warning(ChatColor.RED + "与监控服务器失去连接！将在 10 秒后尝试重新握手。");
                 isConnected = false;
             }
        });
    }
    
    private JsonObject initStatusReport() {
        JsonObject report = new JsonObject();
        report.addProperty("type", "status_report"); // New type
        report.addProperty("tps", Bukkit.getTPS()[0]);
        report.addProperty("timestamp", System.currentTimeMillis());
        report.addProperty("server", plugin.getConfig().getString("server-name", "Minecraft Server"));
        
        // Players
        com.google.gson.JsonArray players = new com.google.gson.JsonArray();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            JsonObject playerObj = new JsonObject();
            playerObj.addProperty("name", p.getName());
            players.add(playerObj);
        }
        report.add("players", players);
        
        return report;
    }

    public CompletableFuture<Boolean> sendReport(JsonObject json) {
        if (!isConnected) {
            return CompletableFuture.completedFuture(false);
        }

        if (serverId != null && !serverId.isEmpty()) {
            json.addProperty("serverId", serverId);
        }

        return sendRequest("/report", json).thenApply(response -> {
            if (response != null && response.statusCode() == 200) {
                return true;
            } else {
                // If report fails, we might be disconnected, but let keep-alive handle the state change usually.
                // However, a 404 would mean we need to re-register.
                if (response != null && response.statusCode() == 404) {
                    isConnected = false;
                }
                return false;
            }
        });
    }

    private CompletableFuture<HttpResponse<String>> sendRequest(String endpoint, JsonObject payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(monitorUrl + endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .exceptionally(e -> {
                        // plugin.getLogger().log(Level.WARNING, "网络请求失败: " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "创建请求失败", e);
            return CompletableFuture.completedFuture(null);
        }
    }
}
