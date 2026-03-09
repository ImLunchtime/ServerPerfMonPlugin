// Shared registry for game server runtime status
const registeredServers = new Map();
const pendingBinds = new Map(); // code -> { serverId, expiresAt }

module.exports = { registeredServers, pendingBinds };
