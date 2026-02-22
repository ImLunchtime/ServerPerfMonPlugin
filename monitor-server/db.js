const fs = require('fs');
const path = require('path');

const DB_FILE = path.join(__dirname, 'data', 'db.json');
const DATA_DIR = path.join(__dirname, 'data');

// Ensure data directory exists
if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR);
}

// In-memory cache
let db = {
    servers: {}, // Map serverId -> { name, version, groups: [], admins: [], owner: null }
    operators: [] // List of trusted QQ numbers
};

// Load DB
function loadDb() {
    try {
        if (fs.existsSync(DB_FILE)) {
            const data = fs.readFileSync(DB_FILE, 'utf8');
            db = JSON.parse(data);
            
            // Ensure structure
            if (!db.servers) db.servers = {};
            if (!db.operators) db.operators = [];
            
            console.log(`[DB] Loaded ${Object.keys(db.servers).length} servers and ${db.operators.length} operators.`);
        } else {
            saveDb(); // Create empty file
        }
    } catch (err) {
        console.error('[DB] Failed to load database:', err);
    }
}

// Save DB
function saveDb() {
    try {
        fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
    } catch (err) {
        console.error('[DB] Failed to save database:', err);
    }
}

// Get all servers
function getServers() {
    return db.servers;
}

// Get specific server
function getServer(id) {
    return db.servers[id];
}

// Update or Add server
function updateServer(id, data) {
    if (!db.servers[id]) {
        db.servers[id] = {
            id: id,
            name: data.name || 'Unknown',
            version: data.version || 'unknown',
            groups: [],
            admins: [],
            owner: null,
            firstSeen: new Date().toISOString()
        };
    }
    
    // Update fields if provided
    if (data.name) db.servers[id].name = data.name;
    if (data.version) db.servers[id].version = data.version;
    if (data.lastSeen) db.servers[id].lastSeen = data.lastSeen; // Optional: persist last seen if needed, but usually runtime

    saveDb();
    return db.servers[id];
}

// Bind Group to Server (1-to-1)
function bindGroup(serverId, groupId) {
    const server = db.servers[serverId];
    if (!server) return { success: false, reason: 'Server not found' };

    // 1. Check if this group is already bound to ANY server
    for (const sId in db.servers) {
        const s = db.servers[sId];
        if (s.groups && s.groups.includes(groupId)) {
            // If it's this server, we are good (idempotent)
            if (sId === serverId) return { success: true, reason: 'Already bound' };
            // If another server, fail
            return { success: false, reason: `This group is already bound to server: ${s.name} (${sId})` };
        }
    }

    // 2. Check if this server is already bound to ANY group
    if (server.groups && server.groups.length > 0) {
        return { success: false, reason: `This server is already bound to group: ${server.groups[0]}` };
    }
    
    // Bind
    if (!server.groups) server.groups = [];
    server.groups.push(groupId);
    saveDb();
    return { success: true };
}

// Unbind Group from ANY Server
function unbindGroup(groupId) {
    let found = false;
    for (const sId in db.servers) {
        const s = db.servers[sId];
        if (s.groups && s.groups.includes(groupId)) {
            s.groups = s.groups.filter(g => g !== groupId);
            found = true;
        }
    }
    if (found) saveDb();
    return found;
}

// Bind User (Private) to Server (Many-to-Many)
function bindUser(serverId, userId) {
    const server = db.servers[serverId];
    if (!server) return { success: false, reason: 'Server not found' };

    if (!server.subscribers) server.subscribers = [];
    if (!server.subscribers.includes(userId)) {
        server.subscribers.push(userId);
        saveDb();
    }
    return { success: true };
}

// Unbind User from ALL Servers (or specific?)
// Requirement: "unbind" to unbind. Implies context or all. Let's support unbind all for simplicity or unbind specific.
// The user command is just "unbind" in private chat.
// "bind <UUID>" -> binds to specific.
// "unbind" -> Let's assume unbind ALL subscriptions for this user, as no UUID is provided in the example "unbind来解绑".
function unbindUser(userId) {
    let count = 0;
    for (const sId in db.servers) {
        const s = db.servers[sId];
        if (s.subscribers && s.subscribers.includes(userId)) {
            s.subscribers = s.subscribers.filter(u => u !== userId);
            count++;
        }
    }
    if (count > 0) saveDb();
    return count;
}

// Add Admin
function addAdmin(serverId, qq) {
    const server = db.servers[serverId];
    if (!server) return false;
    
    if (!server.admins) server.admins = [];
    if (!server.admins.includes(qq)) {
        server.admins.push(qq);
        saveDb();
    }
    return true;
}

// Set Owner
function setOwner(serverId, qq) {
    const server = db.servers[serverId];
    if (!server) return false;
    
    server.owner = qq;
    saveDb();
    return true;
}

// Get Operators
function getOperators() {
    return db.operators;
}

// Add Operator
function addOperator(qq) {
    if (!db.operators.includes(qq)) {
        db.operators.push(qq);
        saveDb();
        return true;
    }
    return false;
}

// Initialize
loadDb();

module.exports = {
    getServers,
    getServer,
    updateServer,
    bindGroup,
    unbindGroup,
    bindUser,
    unbindUser,
    addAdmin,
    setOwner,
    getOperators,
    addOperator
};
