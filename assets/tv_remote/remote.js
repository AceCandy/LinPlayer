// Based on synamedia-senza/remote (ISC), but transport is WebSocket + JSON.

const params = new URLSearchParams(location.search);
const token = params.get('token') || '';

const statusEl = document.getElementById('status');

function wsUrl() {
  const isHttps = location.protocol === 'https:';
  const scheme = isHttps ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}/ws?token=${encodeURIComponent(token)}`;
}

let socket = null;
let socketReady = false;

function connect() {
  if (!token) {
    statusEl.textContent = '缺少 token：请重新扫码打开。';
    return;
  }
  socket = new WebSocket(wsUrl());
  socketReady = false;

  socket.onopen = () => {
    socketReady = true;
    statusEl.textContent = '已连接：可遥控 TV';
  };

  socket.onclose = () => {
    socketReady = false;
    statusEl.textContent = '连接已断开，正在重连…';
    setTimeout(connect, 800);
  };

  socket.onerror = () => {
    socketReady = false;
    statusEl.textContent = '连接错误，正在重连…';
  };
}

function sendCommand(name, payload = {}) {
  if (!socket || !socketReady) return;
  const msg = { type: 'command', name, ...payload };
  socket.send(JSON.stringify(msg));
}

// D-pad style commands
function left() { sendCommand('nav.left'); }
function right() { sendCommand('nav.right'); }
function up() { sendCommand('nav.up'); }
function down() { sendCommand('nav.down'); }
function enter() { sendCommand('nav.select'); }
function back() { sendCommand('nav.back'); }
function home() { sendCommand('nav.home'); }

connect();

// Optional: try to send some keys as text input commands.
const textfield = document.getElementById('textfield');
if (textfield) {
  textfield.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') return;
    if (event.key.length === 1) {
      sendCommand('input.text', { text: event.key });
    }
  });
}
