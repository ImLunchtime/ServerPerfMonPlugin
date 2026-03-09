const WebSocket = require('ws');
const fs = require('fs');
const path = require('path');
const http = require('http');
const db = require('./db');
const { registeredServers, pendingBinds } = require('./registry');

function loadConfig() {
    try {
        const configPath = path.join(__dirname, 'config.json');
        if (fs.existsSync(configPath)) {
            const configData = fs.readFileSync(configPath, 'utf8');
            return JSON.parse(configData);
        }
    } catch (error) {
        console.error('[BOT] Failed to load config.json:', error);
    }
    return {};
}

let globalSendGroupMsg = null;
let globalSendPrivateMsg = null;

function broadcastServerStatus(serverId, status, extraInfo = {}) {
    if (!globalSendGroupMsg && !globalSendPrivateMsg) return;

    const server = db.getServer(serverId);
    if (!server) return;

    let message = '';
    if (status === 'online') {
        message = `【服务器上线】\n服务器已连接`;
    } else if (status === 'offline') {
        message = `【服务器离线】\n服务器已断开连接 (连接超时，服务器可能出错了或者已经崩溃)`;
    } else if (status === 'lag') {
        const r = extraInfo.report;
        const alertType = r.alertType;
        
        if (alertType === 'mspt_high') {
             message = `⚠️ 【瞬时卡顿警告】\n检测到服务器出现瞬时卡顿。\n过去20Tick最长耗时: ${r.maxTickTime}ms\n当前TPS: ${r.tps}`;
        } else if (alertType === 'mspt_critical') {
             message = `🔴 【严重卡顿警告】\n检测到服务器出现严重线程阻塞！\n过去20Tick最长耗时: ${r.maxTickTime}ms\n当前TPS: ${r.tps}`;
        } else if (alertType === 'tps_low') {
             message = `📉 【低TPS警告】\n服务器负载持续过高 (TPS < 10)。\n当前TPS: ${r.tps}\n持续时间: >30秒`;
        } else {
             // Fallback
             message = `【性能警告】\n${r.alertTitle || '未知性能问题'}\n当前TPS: ${r.tps}`;
        }
        
        message += `\n事件时间: ${extraInfo.startTime}`;
    } else if (status === 'recovery') {
        message = `【TPS恢复正常】\n服务器TPS已回升至正常水平。\n当前TPS: ${extraInfo.tps}`;
    }

    if (!message) return;

    // Detailed message for admins
    let detailedMessage = message;
    if (status === 'lag' && extraInfo.report) {
         const r = extraInfo.report;
         detailedMessage += `\n\n【详细信息】`;
         // Add chunks
         if (r.topChunks && r.topChunks.length > 0) {
             detailedMessage += `\n[高负载区块]\n`;
             r.topChunks.forEach((c, i) => {
                 detailedMessage += `${i+1}. ${c.world} (${c.x}, ${c.z}) - ${c.count}实体\n`;
             });
         }
         // Add players
         if (r.players && r.players.length > 0) {
             detailedMessage += `\n[在线玩家 (${r.players.length})]\n`;
             const playerNames = r.players.map(p => p.name);
             const displayNames = playerNames.slice(0, 10);
             detailedMessage += displayNames.join(', ');
             if (playerNames.length > 10) {
                 detailedMessage += ` 等... (共${playerNames.length}人)`;
             }
         }
    }

    // 1. Broadcast to Groups
    console.log('[BOT] Broadcasting Message:\n' + message);
    if (server.groups && globalSendGroupMsg) {
        server.groups.forEach(groupId => {
            globalSendGroupMsg(groupId, message);
        });
    }

    // 2. Broadcast to Private Subscribers
    if (server.subscribers && globalSendPrivateMsg) {
        server.subscribers.forEach(userId => {
            // Check if admin (loose comparison for ID types)
            if (server.admins && server.admins.some(adminId => adminId == userId)) {
                 globalSendPrivateMsg(userId, detailedMessage);
            } else {
                 globalSendPrivateMsg(userId, message);
            }
        });
    }
}

function startBot() {
    const config = loadConfig();
    const port = config.botServerPort || 8081;
    const botPath = config.botServerPath || '/onebot';
    const accessToken = config.botAccessToken || '';

    // Create a separate HTTP server for the WebSocket to attach to
    const server = http.createServer();
    const wss = new WebSocket.Server({ noServer: true });

    server.on('upgrade', (request, socket, head) => {
        const url = new URL(request.url, `http://${request.headers.host}`);
        
        // 1. Check Path
        if (url.pathname !== botPath) {
            socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
            socket.destroy();
            return;
        }

        // 2. Check Token (Authorization Header or Query Param)
        // Header format: "Authorization: Bearer <token>"
        // Query param: "?access_token=<token>"
        let token = '';
        const authHeader = request.headers['authorization'];
        if (authHeader && authHeader.startsWith('Bearer ')) {
            token = authHeader.substring(7);
        } else if (url.searchParams.has('access_token')) {
            token = url.searchParams.get('access_token');
        }

        if (accessToken && token !== accessToken) {
            console.log(`[BOT] Connection rejected: Invalid token (provided: ${token ? '***' : 'none'})`);
            socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
            socket.destroy();
            return;
        }

        wss.handleUpgrade(request, socket, head, (ws) => {
            wss.emit('connection', ws, request);
        });
    });

    wss.on('connection', (ws, req) => {
        const remoteAddress = req.socket.remoteAddress;
        console.log(`[BOT] New OneBot client connected from ${remoteAddress}`);

        const sendGroupMsg = (groupId, msg) => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({
                    action: 'send_group_msg',
                    params: {
                        group_id: groupId,
                        message: msg
                    }
                }));
            }
        };

        const sendPrivateMsg = (userId, msg) => {
            if (ws.readyState === WebSocket.OPEN) {
                ws.send(JSON.stringify({
                    action: 'send_private_msg',
                    params: {
                        user_id: userId,
                        message: msg
                    }
                }));
            }
        };

        // Update global senders (Simple Last-Write-Wins for single bot instance)
        globalSendGroupMsg = sendGroupMsg;
        globalSendPrivateMsg = sendPrivateMsg;

        ws.on('message', (data) => {
            try {
                const messageStr = data.toString();
                const message = JSON.parse(messageStr);

                // Heartbeat messages are very frequent, skip logging them to keep console clean
                if (message.meta_event_type === 'heartbeat') return;

                // We only care about message events
                if (message.post_type !== 'message') return;

                const rawMessage = message.raw_message || '';
                const messageType = message.message_type; // 'private' or 'group'
                const sender = message.sender || {};
                const userId = sender.user_id || message.user_id;
                const groupId = message.group_id;
                const role = sender.role; // 'owner', 'admin', 'member' (for group messages)

                // Normalize message
                const trimmedMsg = rawMessage.trim();
                
                // --- Help Command ---
                // Trigger: "help" in private, or "@Bot help" in group
                let isHelp = false;
                if (messageType === 'private' && trimmedMsg.toLowerCase() === 'help') {
                    isHelp = true;
                } else if (messageType === 'group') {
                    const cleanMsg = rawMessage.replace(/\[CQ:at,qq=\d+\]/g, '').trim();
                    if (cleanMsg.toLowerCase() === 'help') {
                        isHelp = true;
                    }
                }

                if (isHelp) {
                    const helpMsg = 
`【Minecraft 监控机器人指令列表】
1. 绑定服务器
   - 群聊: @机器人 bind <配对码>
   - 私聊: bind <配对码>
   (请在服务器输入 /spm bind 获取配对码)
2. 解除绑定
   - 群聊: @机器人 unbind
   - 私聊: unbind
3. 查询状态
   - 发送: "s", "ss", "status", "状态", "@机器人"
   - 返回: 服务器在线状态、TPS、在线玩家列表
4. 帮助
   - 群聊: @机器人 help
   - 私聊: help`;
                    if (messageType === 'private') sendPrivateMsg(userId, helpMsg);
                    else sendGroupMsg(groupId, helpMsg);
                    return;
                }

                // --- Status Query Command ---
                const statusKeywords = ['s', 'ss', 'status', '状态'];
                
                // Check if message is exactly one of the keywords OR just @Bot (empty)
                let isStatusQuery = statusKeywords.includes(trimmedMsg.toLowerCase());
                
                if (!isStatusQuery && messageType === 'group') {
                    // Check for exact @Bot mention with no other text
                    if (rawMessage.includes('[CQ:at,qq=') && rawMessage.replace(/\[CQ:at,qq=\d+\]/g, '').trim() === '') {
                        isStatusQuery = true;
                    }
                }

                if (isStatusQuery) {
                    // Find bound server(s)
                    let targetServerIds = [];
                    
                    if (messageType === 'group') {
                        // Find server bound to this group
                        const allServers = db.getServers();
                        for (const sId in allServers) {
                            if (allServers[sId].groups && allServers[sId].groups.includes(groupId)) {
                                targetServerIds.push(sId);
                                break; // One server per group
                            }
                        }
                    } else if (messageType === 'private') {
                        // Find servers subscribed by user
                        const allServers = db.getServers();
                        for (const sId in allServers) {
                            if (allServers[sId].subscribers && allServers[sId].subscribers.includes(userId)) {
                                targetServerIds.push(sId);
                            }
                        }
                    }

                    if (targetServerIds.length === 0) {
                        // Do not reply if not bound, to avoid spamming "s" in normal chat if it's a common word (though 's' is rare as a standalone sentence)
                        // But requirement says "In bound chat...". If not bound, maybe silent?
                        // "In chat that bound a server..." -> So if not bound, we probably shouldn't respond or say "not bound".
                        // Let's reply to help user know why it failed if they expected it to work.
                        // const msg = `【查询失败】当前聊天未绑定任何服务器。`;
                        // if (messageType === 'private') sendPrivateMsg(userId, msg);
                        // else sendGroupMsg(groupId, msg);
                        return;
                    }

                    // Generate Status Report
                    targetServerIds.forEach(sId => {
                        // Re-fetch from registry to ensure we have the Map object
                        const serverInfo = registeredServers.get(sId);
                        const dbInfo = db.getServer(sId);
                        const name = dbInfo ? dbInfo.name : (serverInfo ? serverInfo.name : 'Unknown');
                        
                        let statusMsg = '';
                        if (!serverInfo || serverInfo.loggedOffline) {
                             const lastSeenText = serverInfo && serverInfo.lastSeen ? serverInfo.lastSeen.toLocaleString() : '从未';
                             statusMsg = `【服务器状态查询】\n服务器: ${name}\n状态: 🔴 离线\n(最后在线: ${lastSeenText})`;
                        } else {
                            const lastReport = serverInfo.lastReport || {};
                            const tps = lastReport.tps !== undefined ? Number(lastReport.tps).toFixed(1) : '未知';
                            const players = lastReport.players || [];
                            const playerCount = players.length;
                            const playerNames = players.map(p => p.name).join(', ') || '无';

                            statusMsg = `【服务器状态查询】\n服务器: ${name}\n状态: 🟢 在线\nTPS: ${tps}\n在线玩家: ${playerCount}人\n玩家列表: ${playerNames}`;
                        }

                        if (messageType === 'private') sendPrivateMsg(userId, statusMsg);
                        else sendGroupMsg(groupId, statusMsg);
                    });
                    return;
                }

                // --- 1. Private Message (Admin Commands) ---
                if (messageType === 'private') {
                    const operators = db.getOperators();
                    
                    // Permission Check: Must be in trusted list
                    if (!operators.includes(userId)) {
                         // Optional: log or ignore
                         return;
                    }

                    // Command: bind <Code>
                    if (trimmedMsg.startsWith('bind ')) {
                        const code = trimmedMsg.split(' ')[1];
                        if (!code) {
                            sendPrivateMsg(userId, '用法：bind <配对码>');
                            return;
                        }
                        
                        // Check code
                        const bindInfo = pendingBinds.get(code);
                        if (!bindInfo) {
                            sendPrivateMsg(userId, '绑定失败：配对码无效或已过期！');
                            return;
                        }
                        
                        if (Date.now() > bindInfo.expiresAt) {
                            pendingBinds.delete(code);
                            sendPrivateMsg(userId, '绑定失败：配对码已过期！');
                            return;
                        }
                        
                        const serverId = bindInfo.serverId;
                        const result = db.bindUser(serverId, userId);
                        if (result.success) {
                            pendingBinds.delete(code); // Consume code
                            const server = db.getServer(serverId);
                            sendPrivateMsg(userId, `[成功] 已成功绑定服务器 [${server.name}]！`);
                        } else {
                            sendPrivateMsg(userId, `[错误] 绑定失败：${result.reason}`);
                        }
                    } 
                    // Command: unbind
                    else if (trimmedMsg === 'unbind') {
                        const count = db.unbindUser(userId);
                        if (count > 0) {
                            sendPrivateMsg(userId, `[成功] 已成功取消绑定 ${count} 个服务器！`);
                        } else {
                            sendPrivateMsg(userId, `[信息] 您当前没有任何活跃的绑定服务器！`);
                        }
                    }
                }

                // --- 2. Group Message ---
                if (messageType === 'group') {
                    const hasAt = rawMessage.includes('[CQ:at,qq=');
                    if (!hasAt) return; 

                    const cleanMsg = rawMessage.replace(/\[CQ:at,qq=\d+\]/g, '').trim();
                    const operators = db.getOperators();
                    
                    // Permission Check: Admin, Owner, or Trusted Operator
                    const isGroupAdmin = role === 'admin' || role === 'owner';
                    const isTrusted = operators.includes(userId);
                    
                    if (!isGroupAdmin && !isTrusted) {
                        return; // Ignore
                    }

                    // Command: bind <Code>
                    if (cleanMsg.startsWith('bind ')) {
                        const code = cleanMsg.split(' ')[1];
                        if (!code) {
                            sendGroupMsg(groupId, '用法: @机器人 bind <配对码>');
                            return;
                        }

                        // Check code
                        const bindInfo = pendingBinds.get(code);
                        if (!bindInfo) {
                            sendGroupMsg(groupId, '绑定失败：配对码无效或已过期！');
                            return;
                        }
                        
                        if (Date.now() > bindInfo.expiresAt) {
                            pendingBinds.delete(code);
                            sendGroupMsg(groupId, '绑定失败：配对码已过期！');
                            return;
                        }

                        const serverId = bindInfo.serverId;
                        const result = db.bindGroup(serverId, groupId);
                        if (result.success) {
                            pendingBinds.delete(code);
                            const server = db.getServer(serverId);
                            sendGroupMsg(groupId, `[成功] 已成功绑定服务器 [${server.name}]！`);
                        } else {
                            sendGroupMsg(groupId, `[错误] 绑定失败：${result.reason}`);
                        }
                    }
                    // Command: unbind
                    else if (cleanMsg === 'unbind') {
                        const success = db.unbindGroup(groupId);
                        if (success) {
                            sendGroupMsg(groupId, '[成功] 已成功取消绑定服务器！');
                        } else {
                            sendGroupMsg(groupId, '[信息] 本群还没有绑定服务器！');
                        }
                    }
                }

            } catch (e) {
                console.error('[BOT] Error processing message:', e);
            }
        });

        ws.on('close', () => {
            console.log(`[BOT] Client disconnected (${remoteAddress})`);
        });

        ws.on('error', (err) => {
            console.error('[BOT] WebSocket error:', err.message);
        });
    });

    server.listen(port, '0.0.0.0', () => {
        console.log(`[BOT] Reverse WebSocket Server listening on 0.0.0.0:${port}, path: ${botPath}`);
        console.log(`[BOT] Expected Token: ${accessToken}`);
        
        // Print local IPs to help with Docker configuration
        const { networkInterfaces } = require('os');
        const nets = networkInterfaces();
        const results = Object.create(null);
        for (const name of Object.keys(nets)) {
            for (const net of nets[name]) {
                // Skip over non-IPv4 and internal (i.e. 127.0.0.1) addresses
                if (net.family === 'IPv4' && !net.internal) {
                    if (!results[name]) {
                        results[name] = [];
                    }
                    results[name].push(net.address);
                }
            }
        }
        // not needed anymore
        //console.log('[BOT] Available Host IPs (for Docker connection):', JSON.stringify(results, null, 2));
    });
}

module.exports = { startBot, broadcastServerStatus };
