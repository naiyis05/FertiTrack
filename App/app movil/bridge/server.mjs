/**
 * FertiTrack Node Bridge Server (bridge/server.mjs)
 * - Conecta por TCP puro al Maestro ESP32 (puerto 8888) igual que WiFiService.cs
 * - Hace proxy HTTP al Esclavo ESP32 (puerto 80 -> /leer, /fert_on, /fert_off) igual que EsclavoService.cs
 * - Expone WebSocket en ws://localhost:8889/ws para la App Web/Móvil Responsive
 */
import http from 'http';
import net from 'net';
import { WebSocketServer } from 'ws';
import { URL } from 'url';

const PORT = 8889;
let tcpClient = null;
let maestroConectado = false;
let ipMaestroActual = '192.168.1.50';

const server = http.createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const parsedUrl = new URL(req.url, `http://localhost:${PORT}`);

  // Proxy HTTP hacia el Esclavo ESP32 (/api/esclavo/leer?ip=192.168.1.7)
  if (parsedUrl.pathname === '/api/esclavo/leer') {
    const ip = parsedUrl.searchParams.get('ip') || '192.168.4.2';
    const targetUrl = ip.startsWith('http') ? `${ip}/leer` : `http://${ip}/leer`;
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2800);
      const r = await fetch(targetUrl, { signal: controller.signal });
      clearTimeout(timeout);
      const text = await r.text();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, raw: text.trim() }));
    } catch (err) {
      res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          ok: false,
          error: 'No se pudo alcanzar el host. Verifica que el ESP32 esté en la misma red.',
          detail: err.message,
        })
      );
    }
    return;
  }

  // Proxy HTTP hacia el Esclavo para fertilización (/api/esclavo/fertilizar?ip=...&on=true)
  if (parsedUrl.pathname === '/api/esclavo/fertilizar') {
    const ip = parsedUrl.searchParams.get('ip') || '192.168.4.2';
    const encender = parsedUrl.searchParams.get('on') !== 'false';
    const ruta = encender ? '/fert_on' : '/fert_off';
    const targetUrl = ip.startsWith('http') ? `${ip}${ruta}` : `http://${ip}${ruta}`;
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 2800);
      const r = await fetch(targetUrl, { signal: controller.signal });
      clearTimeout(timeout);
      const text = await r.text();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, raw: text.trim() }));
    } catch (err) {
      res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: err.message }));
    }
    return;
  }

  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(
    JSON.stringify({
      service: 'FertiTrack Bridge',
      maestroConectado,
      ipMaestroActual,
    })
  );
});

const wss = new WebSocketServer({ server, path: '/ws' });

function broadcast(payload) {
  const str = JSON.stringify(payload);
  for (const client of wss.clients) {
    if (client.readyState === 1) {
      client.send(str);
    }
  }
}

function conectarMaestroTCP(ip = '192.168.4.1', puerto = 8888) {
  desconectarMaestroTCP();
  ipMaestroActual = ip;

  const socket = new net.Socket();
  tcpClient = socket;

  let buffer = '';

  socket.setTimeout(5000);

  socket.connect(puerto, ip, () => {
    maestroConectado = true;
    socket.setTimeout(0);
    broadcast({ type: 'MAESTRO_STATUS', conectado: true, ip, puerto });
    broadcast({ type: 'MAESTRO_LINE', linea: 'SISTEMA:CONECTADO' });
  });

  socket.on('data', (chunk) => {
    buffer += chunk.toString('utf8');
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const line of lines) {
      if (line.trim()) {
        broadcast({ type: 'MAESTRO_LINE', linea: line.trim() });
      }
    }
  });

  socket.on('timeout', () => {
    socket.destroy(new Error('Timeout al conectar con el Maestro TCP'));
  });

  socket.on('error', (err) => {
    maestroConectado = false;
    broadcast({
      type: 'MAESTRO_ERROR',
      error: `No se pudo conectar al Maestro ${ip}:${puerto} (${err.message})`,
    });
  });

  socket.on('close', () => {
    maestroConectado = false;
    broadcast({ type: 'MAESTRO_STATUS', conectado: false, ip, puerto });
  });
}

function desconectarMaestroTCP() {
  if (tcpClient) {
    try {
      tcpClient.destroy();
    } catch {}
    tcpClient = null;
  }
  maestroConectado = false;
}

wss.on('connection', (ws) => {
  ws.send(
    JSON.stringify({
      type: 'MAESTRO_STATUS',
      conectado: maestroConectado,
      ip: ipMaestroActual,
      puerto: 8888,
    })
  );

  ws.on('message', (msg) => {
    try {
      const data = JSON.parse(msg.toString());
      if (data.action === 'CONECTAR_MAESTRO') {
        conectarMaestroTCP(data.ip || '192.168.4.1', data.puerto || 8888);
      } else if (data.action === 'DESCONECTAR_MAESTRO') {
        desconectarMaestroTCP();
        broadcast({ type: 'MAESTRO_STATUS', conectado: false, ip: ipMaestroActual, puerto: 8888 });
      } else if (data.action === 'ENVIAR_COMANDO_MAESTRO' && tcpClient && maestroConectado) {
        tcpClient.write(`${data.comando}\n`);
      }
    } catch {}
  });
});

server.listen(PORT, () => {
  console.log(`🌱 FertiTrack Bridge escuchando en http://localhost:${PORT} (WS: /ws)`);
});
