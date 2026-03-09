const express = require('express');
const { v4: uuidv4 } = require('uuid');
const db = require('./db');
const { broadcastServerStatus } = require('./bot');
const { registeredServers, pendingBinds } = require('./registry');

const app = express();
const port = 8080;

// Parse JSON bodies (as sent by API clients)
app.use(express.json());

// Initialize registry from DB
const dbServers = db.getServers();
Object.values(dbServers).forEach(s => {
    registeredServers.set(s.id, {
        name: s.name,
        version: s.version,
        lastSeen: new Date(0), // Assume offline initially
        loggedOffline: true
    });
});
console.log(`[INIT] Loaded ${registeredServers.size} servers from database.`);

// Check for offline servers every 1 second
setInterval(() => {
    const now = Date.now();
    registeredServers.forEach((info, id) => {
        // Skip if never seen (or loaded from DB with initial time)
        if (info.lastSeen.getTime() === 0) return;

        if (now - info.lastSeen.getTime() > 10000) { // 10 seconds timeout
            if (!info.loggedOffline) {
                console.log(`[ALERT] Game Server [${info.name} (${id})] is OFFLINE! (No keep-alive/handshake for >10s)`);
                info.loggedOffline = true;
                registeredServers.set(id, info);
                
                // Broadcast Offline Status
                broadcastServerStatus(id, 'offline');
            }
        }
    });
}, 1000);

app.post('/bind-request', (req, res) => {
    const { code, serverId } = req.body;
    
    if (!code || !serverId) {
        return res.status(400).send('Missing code or serverId');
    }

    if (!registeredServers.has(serverId)) {
        return res.status(404).send('Server not registered');
    }
    
    // Store with 5 min expiry
    const expiry = Date.now() + 5 * 60 * 1000;
    pendingBinds.set(code, {
        serverId: serverId,
        expiresAt: expiry
    });
    
    // Cleanup old binds
    for (const [c, info] of pendingBinds.entries()) {
        if (Date.now() > info.expiresAt) {
            pendingBinds.delete(c);
        }
    }
    
    console.log(`[BIND] Received bind request: Code ${code} -> Server ${serverId}`);
    res.status(200).send('OK');
});

app.post('/register', (req, res) => {
    const { serverName, version, existingId } = req.body;
    let serverId = existingId || uuidv4();
    
    // Save to DB
    db.updateServer(serverId, {
        name: serverName,
        version: version,
        lastSeen: new Date().toISOString()
    });

    // Update In-Memory
    if (existingId && registeredServers.has(existingId)) {
        const info = registeredServers.get(existingId);
        
        // Check if coming back online
        if (info.loggedOffline) {
            broadcastServerStatus(serverId, 'online');
        }

        info.lastSeen = new Date();
        info.loggedOffline = false; // Reset offline flag
        info.name = serverName || info.name;
        info.version = version || info.version;
        registeredServers.set(serverId, info);
        console.log(`[REGISTER] Server re-connected: ${info.name} (${serverId})`);
    } else {
        registeredServers.set(serverId, {
            name: serverName || 'Unknown Server',
            version: version || 'unknown',
            lastSeen: new Date(),
            loggedOffline: false
        });
        console.log(`[REGISTER] New server registered: ${serverName || 'Unknown'} (${serverId})`);
        // Broadcast New Server Online (if treated as online immediately)
        broadcastServerStatus(serverId, 'online');
    }
    
    res.json({ id: serverId, status: 'registered' });
});

app.post('/keepalive', (req, res) => {
    const { serverId } = req.body;
    if (serverId && registeredServers.has(serverId)) {
        const info = registeredServers.get(serverId);
        
        // Check if coming back online from what we thought was offline
        if (info.loggedOffline) {
             broadcastServerStatus(serverId, 'online');
        }

        info.lastSeen = new Date();
        info.loggedOffline = false;
        registeredServers.set(serverId, info);
        res.status(200).send('OK');
    } else {
        res.status(404).send('Server not found. Please re-register.');
    }
});

app.post('/report', (req, res) => {
    const report = req.body;
    const serverId = report.serverId || 'unknown-id';
    
    // Update last seen
    if (registeredServers.has(serverId)) {
        const info = registeredServers.get(serverId);
        
        if (info.loggedOffline) {
             broadcastServerStatus(serverId, 'online');
        }

        info.lastSeen = new Date();
        info.loggedOffline = false;
        
        // Cache report data for status queries
        if (report.type === 'lag_report' || report.type === 'manual_report' || report.type === 'status_report' || report.type === 'recovery_report') {
             info.lastReport = {
                 tps: report.tps,
                 players: report.players || [],
                 timestamp: report.timestamp
             };
        }
        
        registeredServers.set(serverId, info);
    }
    
    console.log(`--- Received Report from [${serverId}] ---`);
    console.log(JSON.stringify(report, null, 2));

    if (report.type === 'lag_report' || report.type === 'manual_report' || report.type === 'status_report' || report.type === 'recovery_report') {
        const typeLabel = report.type === 'lag_report' ? 'LAG REPORT' : (report.type === 'manual_report' ? 'MANUAL REPORT' : (report.type === 'recovery_report' ? 'RECOVERY REPORT' : 'STATUS REPORT'));
        console.log(`[${typeLabel}] Server: ${report.server}`);
        console.log(`TPS: ${report.tps}`);
        console.log(`Timestamp: ${new Date(report.timestamp).toLocaleString()}`);
        
        // Broadcast Lag Warning (only for lag_report)
        if (report.type === 'lag_report') {
            broadcastServerStatus(serverId, 'lag', {
                startTime: new Date(report.timestamp).toLocaleString(),
                tps: report.tps,
                report: report // Pass full report for detailed info
            });
        } else if (report.type === 'recovery_report') {
             broadcastServerStatus(serverId, 'recovery', {
                 startTime: new Date(report.timestamp).toLocaleString(),
                 tps: report.tps
             });
        }
        
        if (report.players && Array.isArray(report.players)) {
            // For status report, maybe don't log full player list to console if it's frequent
            if (report.type !== 'status_report') {
                console.log(`Players Online: ${report.players.length}`);
                report.players.forEach(p => {
                    console.log(`  - ${p.name} at [${p.world || 'unknown'}: ${p.x.toFixed(1)}, ${p.y.toFixed(1)}, ${p.z.toFixed(1)}]`);
                });
            } else {
                 console.log(`Players Online: ${report.players.length} (list suppressed for status report)`);
            }
        }

        if (report.topChunks && Array.isArray(report.topChunks)) {
            console.log(`Top 5 Chunks by Entity Count:`);
            report.topChunks.forEach((c, index) => {
                console.log(`  ${index + 1}. [${c.world}: ${c.x}, ${c.z}] - ${c.count} entities`);
            });
        }

        if (report.topEntityTypes && Array.isArray(report.topEntityTypes)) {
            console.log(`Top 5 Entity Types:`);
            report.topEntityTypes.forEach((e, index) => {
                console.log(`  ${index + 1}. ${e.type}: ${e.count}`);
            });
        }
    } else if (report.type === 'test') {
        console.log(`[TEST] Message: ${report.message}`);
    } else {
        console.log('[INFO] Unknown report type received.');
    }
    console.log('-----------------------\n');
    
    res.status(200).send('Report received');
});

function startHttpServer() {
    app.listen(port, () => {
        console.log(`Monitor server listening at http://localhost:${port}`);
    });
}

module.exports = { startHttpServer };
