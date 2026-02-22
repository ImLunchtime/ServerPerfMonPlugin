const { startHttpServer } = require('./http');
const { startBot } = require('./bot');

console.log('Starting Minecraft Monitor Server...');

// Start HTTP Server
try {
    startHttpServer();
} catch (error) {
    console.error('Failed to start HTTP server:', error);
}

// Start Bot Client
try {
    startBot();
} catch (error) {
    console.error('Failed to start Bot client:', error);
}
