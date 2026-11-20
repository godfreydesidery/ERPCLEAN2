// Local stand-in for the IIS site, for trying and testing the PWA build.
//
//   node serve.mjs                 -> http://localhost:4310  (serves ../build/web)
//   ORBIX_API=http://127.0.0.1:8081 node serve.mjs
//
// It behaves like the production web.config so that what passes here is what
// IIS will do: the response headers (CSP included), MIME types, the /api/
// reverse proxy, the SPA fallback and "web.config is never served" are all
// READ FROM ../web/web.config rather than repeated here.
//
// localhost counts as a secure context, so the service worker, install
// criteria and offline mode all work without a certificate.
//
// ORBIX_TEST_HOOKS=1 adds POST /__test/deploy-next and /__test/reset, which
// make sw.js look like a new deploy (used by pwa.spec.mjs to test updates).

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(process.env.ORBIX_WEB_ROOT || path.join(here, '..', 'build', 'web'));
const PORT = Number(process.env.PORT || 4310);
const API = new URL(process.env.ORBIX_API || 'http://127.0.0.1:8081');
const HOOKS = process.env.ORBIX_TEST_HOOKS === '1';

// ------------------------------------------------------------ web.config
const configText = fs.readFileSync(path.join(here, '..', 'web', 'web.config'), 'utf8')
  .replace(/<!--[\s\S]*?-->/g, '');
const decode = (s) => s.replace(/&quot;/g, '"').replace(/&apos;/g, "'")
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');

const headersBlock = (configText.match(/<customHeaders>([\s\S]*?)<\/customHeaders>/) || [])[1] || '';
const HEADERS = {};
for (const m of headersBlock.matchAll(/<add\s+name="([^"]+)"\s+value="([^"]*)"\s*\/>/g)) {
  HEADERS[m[1]] = decode(m[2]);
}
const MIME = {};
for (const m of configText.matchAll(/<mimeMap\s+fileExtension="([^"]+)"\s+mimeType="([^"]+)"\s*\/>/g)) {
  MIME[m[1].toLowerCase()] = m[2];
}
// IIS's own defaults for the types web.config does not override.
Object.assign(MIME, {
  '.html': 'text/html', '.css': 'text/css', '.png': 'image/png', '.ico': 'image/x-icon',
  '.svg': 'image/svg+xml',
}, MIME);
const apiRule = configText.match(/<action\s+type="Rewrite"\s+url="(http:\/\/[^"]+)\/api\/\{R:1\}"/);
if (!apiRule) throw new Error('web.config has no /api/ proxy rule');

// ------------------------------------------------------------ state for hooks
let deployNext = false;

function send(res, status, body, headers = {}) {
  res.writeHead(status, { ...HEADERS, ...headers });
  res.end(body);
}

function proxyApi(req, res) {
  const target = new URL(req.url, API);
  const headers = { ...req.headers, host: target.host, 'x-forwarded-for': req.socket.remoteAddress };
  const upstream = http.request(target, { method: req.method, headers }, (up) => {
    res.writeHead(up.statusCode || 502, { ...up.headers, ...HEADERS });
    up.pipe(res);
  });
  upstream.on('error', () => {
    // What IIS/ARR answers when the ERP is down: a 502, no detail.
    if (!res.headersSent) send(res, 502, 'Bad Gateway', { 'Content-Type': 'text/plain' });
  });
  req.pipe(upstream);
}

function serveFile(res, file, rel) {
  let body = fs.readFileSync(file);
  if (rel === '/sw.js' && deployNext) {
    body = Buffer.from(body.toString('utf8').replace(/const VERSION = '([^']+)';/, "const VERSION = '$1-next';"));
  }
  const ext = path.extname(file).toLowerCase() || '.';
  const type = MIME[ext];
  if (!type) return send(res, 404, 'Not found (no MIME type - IIS 404.3)', { 'Content-Type': 'text/plain' });
  send(res, 200, body, { 'Content-Type': type, 'Cache-Control': 'no-cache' });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const p = decodeURIComponent(url.pathname);

  if (HOOKS && req.method === 'POST' && p === '/__test/deploy-next') {
    deployNext = true;
    return send(res, 204, '');
  }
  if (HOOKS && req.method === 'POST' && p === '/__test/reset') {
    deployNext = false;
    return send(res, 204, '');
  }

  if (p === '/api' || p.startsWith('/api/')) return proxyApi(req, res);

  const file = path.join(ROOT, p);
  if (!file.startsWith(ROOT)) return send(res, 400, 'Bad request');
  if (path.basename(file).toLowerCase() === 'web.config') return send(res, 404, 'Not found');

  if (fs.existsSync(file) && fs.statSync(file).isFile()) return serveFile(res, file, p);
  if (p === '/' || !/\.[A-Za-z0-9]{1,12}$/.test(p)) {
    return serveFile(res, path.join(ROOT, 'index.html'), '/index.html');
  }
  return send(res, 404, 'Not found', { 'Content-Type': 'text/plain' });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Orbix App PWA on http://localhost:${PORT}  (root ${ROOT}, api ${API.origin})`);
});
