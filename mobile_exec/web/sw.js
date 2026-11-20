/*
 * Orbix App service worker.
 *
 * THIS IS A TEMPLATE. dist/build-pwa.ps1 stamps VERSION and PRECACHE after
 * `flutter build web`. Unstamped (a plain `flutter run -d chrome`), the worker
 * caches nothing and passes every request straight to the network.
 *
 * What it caches - the app's own static files ONLY:
 *   - PRECACHE: every file of this build except the per-browser engine
 *     variants, fetched at install into a cache named after the build;
 *   - the CanvasKit engine variant this browser uses, on first use.
 *
 * What it NEVER touches: anything under /api/ (sign-in, tokens, sales, stock,
 * till closes - all live and private), any non-GET request, and any other
 * origin. Those go to the network exactly as if there were no worker.
 *
 * Versioning: one cache per build ("orbix-app-<version>"). A new deploy is a
 * new sw.js, so the browser installs it beside the old one; it then WAITS.
 * The page shows "A new version is ready" and only the user's tap sends
 * SKIP_WAITING. On activation every older orbix-app-* cache is deleted.
 */
'use strict';

const VERSION = '__ORBIX_VERSION__';
const PRECACHE = /*__ORBIX_PRECACHE__*/ [];

const STAMPED = VERSION.indexOf('__') !== 0;
const PREFIX = 'orbix-app-';
const CACHE = PREFIX + VERSION;
const SHELL = './index.html';
const OFFLINE = './offline.html';

// Same-origin paths that are never cached, even on a GET.
function isUncacheable(url) {
  const p = url.pathname;
  return p.startsWith('/api/') || p === '/api' ||
    p.endsWith('/sw.js') || p.endsWith('/version.json');
}

self.addEventListener('install', (event) => {
  if (!STAMPED) {
    self.skipWaiting();
    return;
  }
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // cache:'reload' bypasses the HTTP cache so the build is internally
    // consistent. If ANY file fails, install fails and the old version stays.
    await cache.addAll(PRECACHE.map((u) => new Request(u, { cache: 'reload' })));
    // First install (no older version controlling a page) needs no consent.
    const existing = await self.registration.active;
    if (!existing) await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys
      .filter((k) => k.startsWith(PREFIX) && k !== CACHE)
      .map((k) => caches.delete(k)));
    await self.clients.claim();
  })());
});

self.addEventListener('message', (event) => {
  const data = event.data || {};
  if (data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  } else if (data.type === 'GET_VERSION' && event.ports && event.ports[0]) {
    event.ports[0].postMessage({ version: STAMPED ? VERSION : 'dev' });
  } else if (data.type === 'CACHE_URLS' && STAMPED && Array.isArray(data.urls)) {
    event.waitUntil((async () => {
      const cache = await caches.open(CACHE);
      for (const raw of data.urls.slice(0, 200)) {
        let url;
        try {
          url = new URL(raw, self.location.origin);
        } catch (e) {
          continue;
        }
        if (url.origin !== self.location.origin || isUncacheable(url)) continue;
        if (await cache.match(url.pathname)) continue;
        try {
          const res = await fetch(url.pathname);
          if (res.ok && res.type === 'basic') await cache.put(url.pathname, res);
        } catch (e) {
          /* offline right now - it will be cached on next use */
        }
      }
    })());
  }
});

self.addEventListener('fetch', (event) => {
  if (!STAMPED) return;
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== self.location.origin || isUncacheable(url)) return;
  // Range requests (media) would be answered wrongly from a full cached body.
  if (req.headers.has('range')) return;

  if (req.mode === 'navigate') {
    event.respondWith(navigate(req));
    return;
  }
  event.respondWith(cacheFirst(req));
});

// Every page of the app is the same single-page shell. Serving it from the
// build's cache keeps index.html and the scripts it loads on one version; a
// newer deploy arrives through the update prompt, never half-way.
async function navigate(req) {
  const cache = await caches.open(CACHE);
  const shell = await cache.match(SHELL);
  if (shell) return shell;
  try {
    return await fetch(req);
  } catch (e) {
    return (await cache.match(OFFLINE)) ||
      new Response('Orbix App is offline.', {
        status: 503,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      });
  }
}

async function cacheFirst(req) {
  const cache = await caches.open(CACHE);
  const url = new URL(req.url);
  // Ignore the query string: Flutter adds none to its own files, and a stray
  // ?v= must not miss the cache.
  const hit = await cache.match(url.pathname);
  if (hit) return hit;
  const res = await fetch(req);
  // Runtime-cache this build's static files (e.g. the CanvasKit variant).
  if (res.ok && res.type === 'basic') {
    cache.put(url.pathname, res.clone()).catch(() => {});
  }
  return res;
}
