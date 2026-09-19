'use strict';

// Boots the bundled Mineradio backend inside the app (nodejs-mobile runtime).

const path = require('path');
const fs = require('fs');

// Android often resolves hosts over IPv6 first, which NetEase drops with ECONNRESET.
try {
  require('dns').setDefaultResultOrder('ipv4first');
} catch (e) {
  // Older Node.js runtimes ignore this.
}

process.env.PORT = process.env.PORT || '3000';
process.env.HOST = process.env.HOST || '127.0.0.1';
process.env.COOKIE_FILE = process.env.COOKIE_FILE || path.join(__dirname, '.cookie');
process.env.QQ_COOKIE_FILE = process.env.QQ_COOKIE_FILE || path.join(__dirname, '.qq-cookie');
process.env.MINERADIO_BEAT_CACHE_DIR = process.env.MINERADIO_BEAT_CACHE_DIR || path.join(__dirname, 'cache', 'beatmaps');
process.env.MINERADIO_UPDATE_DIR = process.env.MINERADIO_UPDATE_DIR || path.join(__dirname, 'updates');

[process.env.MINERADIO_BEAT_CACHE_DIR, process.env.MINERADIO_UPDATE_DIR].forEach(function (dir) {
  try {
    fs.mkdirSync(dir, { recursive: true });
  } catch (e) {
    // Directory already exists or is not writable - the server reports this itself.
  }
});

require(path.join(__dirname, 'server.js'));
