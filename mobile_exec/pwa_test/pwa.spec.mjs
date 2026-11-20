// What the Orbix App PWA promises, checked against the real release build
// (mobile_exec/build/web) behind serve.mjs, which applies web.config's headers,
// MIME types, /api/ proxy and SPA fallback like IIS does.
import { test, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

// ------------------------------------------------------------------ helpers

/** Loads the app and waits until Flutter has drawn and the worker is active. */
async function boot(page, url = '/') {
  await page.goto(url);
  await page.waitForFunction(() => !document.getElementById('orbix-splash'), null, {
    timeout: 90_000,
  });
  await page.waitForFunction(() => window.orbixPwa && JSON.parse(window.orbixPwa.stateJson()).swActive);
}

const pwaState = (page) => page.evaluate(() => JSON.parse(window.orbixPwa.stateJson()));

/** Turns on Flutter's accessibility tree so text and buttons are in the DOM. */
async function semantics(page) {
  const placeholder = page.locator('flt-semantics-placeholder');
  if (await placeholder.count()) await placeholder.evaluate((e) => e.click());
  await page.waitForSelector('flt-semantics');
}

/**
 * Text Flutter has drawn. Its accessibility tree carries most strings as
 * aria-labels rather than DOM text, so look in both.
 */
function shown(page, text) {
  const want = text instanceof RegExp ? text : new RegExp(text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  return expect.poll(() => page.evaluate((src) => {
    const re = new RegExp(src.source, src.flags);
    return [...document.querySelectorAll('flt-semantics, flt-semantics *')]
      .some((e) => re.test(e.getAttribute('aria-label') || '') || re.test(e.textContent || ''));
  }, { source: want.source, flags: want.flags }), { message: `"${text}" on screen` });
}

const cacheKeys = (page) => page.evaluate(async () => {
  const out = {};
  for (const name of await caches.keys()) {
    out[name] = (await (await caches.open(name)).keys()).map((r) => new URL(r.url).pathname);
  }
  return out;
});

/** A throwaway copy of the site on its own port, which a test can kill. */
function startServer(port) {
  const child = spawn(process.execPath, [path.join(here, 'serve.mjs')], {
    env: { ...process.env, PORT: String(port) },
    stdio: 'ignore',
  });
  const exited = new Promise((r) => child.once('exit', r));
  return {
    origin: `http://localhost:${port}`,
    stop: () => { if (child.exitCode === null && !child.killed) child.kill(); return exited; },
    ready: async () => {
      for (let i = 0; i < 100; i++) {
        try {
          if ((await fetch(`http://localhost:${port}/manifest.webmanifest`)).ok) return;
        } catch { /* not up yet */ }
        await new Promise((r) => setTimeout(r, 100));
      }
      throw new Error('test server did not start');
    },
  };
}

function pngSize(buf) {
  expect(buf.subarray(1, 4).toString('ascii')).toBe('PNG');
  return [buf.readUInt32BE(16), buf.readUInt32BE(20)];
}

// ------------------------------------------------------------------ manifest

test('manifest is valid: name, start URL, standalone, real icons', async ({ request }) => {
  const res = await request.get('/manifest.webmanifest');
  expect(res.ok()).toBeTruthy();
  expect(res.headers()['content-type']).toContain('application/manifest+json');
  const m = await res.json();
  expect(m.name).toBe('Orbix App');
  expect(m.short_name).toBe('Orbix');
  expect(m.start_url).toBe('/');
  expect(m.display).toBe('standalone');
  expect(m.theme_color).toMatch(/^#[0-9A-Fa-f]{6}$/);

  const want = { any: ['192x192', '512x512'], maskable: ['192x192', '512x512'] };
  for (const [purpose, sizes] of Object.entries(want)) {
    for (const size of sizes) {
      const icon = m.icons.find((i) => i.purpose === purpose && i.sizes === size);
      expect(icon, `${purpose} ${size}`).toBeTruthy();
      const img = await request.get('/' + icon.src);
      expect(img.headers()['content-type']).toBe('image/png');
      expect(pngSize(await img.body()).join('x')).toBe(size);
    }
  }
  const apple = await request.get('/icons/apple-touch-icon.png');
  expect(pngSize(await apple.body())).toEqual([180, 180]);
});

test('index.html: manifest, iOS meta, theme colour, title', async ({ request }) => {
  const html = await (await request.get('/')).text();
  expect(html).toContain('<link rel="manifest" href="manifest.webmanifest">');
  expect(html).toContain('<link rel="apple-touch-icon" href="icons/apple-touch-icon.png">');
  expect(html).toContain('name="apple-mobile-web-app-title" content="Orbix"');
  expect(html).toContain('name="theme-color"');
  expect(html).toContain('<title>Orbix App</title>');
  expect(html).not.toMatch(/<script>(?!\s*<\/script>)/); // no inline script (CSP)
});

// ------------------------------------------------------------------ boot

test('boots under the production CSP with no third-party requests', async ({ page }) => {
  const errors = [];
  const external = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push(String(e)));
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (u.origin !== 'http://localhost:4310' && u.protocol.startsWith('http')) external.push(r.url());
  });
  await boot(page);
  await expect(page).toHaveTitle('Orbix App');
  expect(errors, errors.join('\n')).toEqual([]);
  expect(external, external.join('\n')).toEqual([]);
});

test('Chrome reports the app as installable', async ({ page }) => {
  await boot(page);
  await page.reload();
  await boot(page);
  const cdp = await page.context().newCDPSession(page);
  const { installabilityErrors } = await cdp.send('Page.getInstallabilityErrors');
  expect(installabilityErrors).toEqual([]);
  const manifest = await cdp.send('Page.getAppManifest');
  expect(manifest.errors).toEqual([]);
});

// ------------------------------------------------------------------ worker + cache

test('service worker controls the page; shell precached; /api never cached', async ({ page }) => {
  await boot(page);
  await page.reload();
  await boot(page);
  expect(await page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);

  const { version } = await pwaState(page);
  expect(version).toMatch(/^\d+\.\d+\.\d+\+\d+-[0-9a-f]{10}$/);

  // Hit the API (GET and POST) - neither may land in any cache.
  await page.evaluate(async () => {
    await fetch('/api/v1/auth/me').catch(() => {});
    await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'nobody-pwa-test', password: 'wrong-password-123' }),
    }).catch(() => {});
  });

  const keys = await cacheKeys(page);
  expect(Object.keys(keys)).toEqual([`orbix-app-${version}`]);
  const paths = keys[`orbix-app-${version}`];
  for (const p of ['/index.html', '/main.dart.js', '/flutter_bootstrap.js', '/offline.html', '/manifest.webmanifest']) {
    expect(paths).toContain(p);
  }
  expect(paths.filter((p) => p.startsWith('/api'))).toEqual([]);
  expect(paths).not.toContain('/sw.js');
});

test('starts with the server DOWN after one visit (offline)', async ({ browser }) => {
  const srv = startServer(4320);
  await srv.ready();
  const context = await browser.newContext({ serviceWorkers: 'allow' });
  const page = await context.newPage();
  try {
    await boot(page, srv.origin + '/');
    // The CanvasKit engine is warmed into the cache after the first visit.
    await expect.poll(async () => Object.values(await cacheKeys(page)).flat()
      .some((p) => p.endsWith('canvaskit.wasm'))).toBe(true);

    await srv.stop();
    await context.setOffline(true);
    await boot(page, srv.origin + '/');
    expect((await pwaState(page)).online).toBe(false);
    await semantics(page);
    await shown(page, 'You are offline').toBe(true);
  } finally {
    await context.close();
    await srv.stop().catch(() => {});
  }
});

test('offline fallback page when the app is not cached', async ({ browser }) => {
  const srv = startServer(4321);
  await srv.ready();
  const context = await browser.newContext({ serviceWorkers: 'allow' });
  const page = await context.newPage();
  try {
    await boot(page, srv.origin + '/');
    // Simulate a shell that is not cached (e.g. evicted): only offline.html left.
    await page.evaluate(async () => {
      for (const name of await caches.keys()) await (await caches.open(name)).delete('./index.html');
    });
    await srv.stop();
    await context.setOffline(true);
    await page.goto(srv.origin + '/reports');
    await expect(page.getByRole('heading', { name: 'You are offline' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Try again' })).toBeVisible();
  } finally {
    await context.close();
    await srv.stop().catch(() => {});
  }
});

// ------------------------------------------------------------------ updates

test('a new deploy is announced, applied only on request, old cache removed', async ({ page, request }) => {
  await request.post('/__test/reset');
  try {
    await boot(page);
    const before = (await pwaState(page)).version;
    await page.evaluate(() => { window.__sameDocument = true; });

    await request.post('/__test/deploy-next');
    await page.evaluate(() => navigator.serviceWorker.getRegistration().then((r) => r.update()));
    await page.waitForFunction(() => JSON.parse(window.orbixPwa.stateJson()).updateReady);

    // Nothing reloads by itself - unsaved work is safe.
    await page.waitForTimeout(2000);
    expect(await page.evaluate(() => window.__sameDocument)).toBe(true);
    expect((await pwaState(page)).version).toBe(before);

    await semantics(page);
    await shown(page, 'A new version of Orbix App is ready').toBe(true);
    const reloaded = page.waitForEvent('load');
    await page.getByRole('button', { name: 'Update now' }).click();
    await reloaded;

    await boot(page);
    expect(await page.evaluate(() => window.__sameDocument)).toBeUndefined();
    const after = (await pwaState(page)).version;
    expect(after).toBe(`${before}-next`);
    expect(Object.keys(await cacheKeys(page))).toEqual([`orbix-app-${after}`]);
    expect((await pwaState(page)).updateReady).toBe(false);
  } finally {
    await request.post('/__test/reset');
  }
});

// ------------------------------------------------------------------ routing + API

test('deep links get the app; missing files and web.config are 404', async ({ request }) => {
  const deep = await request.get('/reports/sales/today');
  expect(deep.status()).toBe(200);
  expect(await deep.text()).toContain('<title>Orbix App</title>');
  expect((await request.get('/nope.js')).status()).toBe(404);
  expect((await request.get('/web.config')).status()).toBe(404);
});

test('sign-in goes through the /api proxy; a wrong password gets a clear message', async ({ page }) => {
  await boot(page);
  await semantics(page);
  // Type like a person: focus each field, then key in. (fill() can set the DOM
  // value without Flutter's text field seeing it.)
  const boxes = page.getByRole('textbox');
  await expect(boxes).toHaveCount(2);
  for (const [i, value] of [[0, 'nobody-pwa-test'], [1, 'wrong-password-123']]) {
    await boxes.nth(i).click();
    await page.keyboard.type(value, { delay: 15 });
  }
  await expect(boxes.nth(1)).toHaveValue('wrong-password-123');

  const login = page.waitForResponse((r) => r.url().endsWith('/api/v1/auth/login'));
  await page.getByRole('button', { name: 'Sign in' }).click();
  const res = await login;
  expect(res.status()).toBe(401);
  expect(res.url()).toBe('http://localhost:4310/api/v1/auth/login'); // same origin
  await shown(page, /username or password/i).toBe(true);
});

// ------------------------------------------------------------------ install UX

test('Android/desktop Chrome: Install uses the browser prompt', async ({ page }) => {
  await boot(page);
  await semantics(page);
  // Headless Chrome never fires beforeinstallprompt; stand in for it.
  await page.evaluate(() => {
    const e = new Event('beforeinstallprompt', { cancelable: true });
    e.prompt = () => { window.__prompted = true; return Promise.resolve(); };
    e.userChoice = Promise.resolve({ outcome: 'accepted', platform: 'web' });
    window.dispatchEvent(e);
  });
  await shown(page, 'Install Orbix App').toBe(true);
  await page.getByRole('button', { name: 'Install', exact: true }).click();
  await shown(page, 'Install Orbix App').toBe(false);
  expect(await page.evaluate(() => window.__prompted)).toBe(true);
  expect((await pwaState(page)).installed).toBe(true);
});

test('Not now: the hint stays away for 30 days', async ({ page }) => {
  const fire = () => page.evaluate(() => {
    const e = new Event('beforeinstallprompt', { cancelable: true });
    e.prompt = () => Promise.resolve();
    e.userChoice = Promise.resolve({ outcome: 'dismissed', platform: 'web' });
    window.dispatchEvent(e);
  });
  await boot(page);
  await semantics(page);
  await fire();
  await page.getByRole('button', { name: 'Not now' }).click();
  await shown(page, 'Install Orbix App').toBe(false);
  const until = await page.evaluate(() => Number(localStorage.getItem('orbix.pwa.installHintSnoozedUntil')));
  expect(until).toBeGreaterThan(Date.now() + 29 * 864e5);

  await page.reload();
  await boot(page);
  await semantics(page);
  await fire();
  await page.waitForTimeout(1000);
  await shown(page, 'Install Orbix App').toBe(false);
});

test('iPhone Safari: the hint explains Share > Add to Home Screen', async ({ browser }) => {
  const context = await browser.newContext({
    serviceWorkers: 'allow',
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 3,
    isMobile: true,
    hasTouch: true,
    userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 '
      + '(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1',
  });
  const page = await context.newPage();
  try {
    await boot(page, 'http://localhost:4310/');
    expect((await pwaState(page)).ios).toBe(true);
    await semantics(page);
    await shown(page, 'Install Orbix App').toBe(true);
    await page.getByRole('button', { name: 'Show me how' }).click();
    await shown(page, /Add to Home Screen/).toBe(true);
    await page.screenshot({ path: 'test-results/iphone-install-hint.png' });
  } finally {
    await context.close();
  }
});

// ------------------------------------------------------------------ responsive

for (const [name, size, mobile] of [
  ['iphone', { width: 390, height: 844 }, true],
  ['android', { width: 412, height: 915 }, true],
  ['ipad', { width: 820, height: 1180 }, true],
  ['desktop', { width: 1440, height: 900 }, false],
]) {
  test(`layout fits ${name} ${size.width}x${size.height} with no sideways scroll`, async ({ browser }) => {
    const context = await browser.newContext({ viewport: size, isMobile: mobile, hasTouch: mobile });
    const page = await context.newPage();
    try {
      await boot(page, 'http://localhost:4310/');
      await page.waitForTimeout(500);
      const m = await page.evaluate(() => {
        const v = document.querySelector('flutter-view, flt-glass-pane');
        const r = v.getBoundingClientRect();
        return { w: r.width, h: r.height, sw: document.documentElement.scrollWidth, iw: innerWidth };
      });
      expect(m.w).toBe(size.width);
      expect(m.h).toBe(size.height);
      expect(m.sw).toBeLessThanOrEqual(m.iw);
      await page.screenshot({ path: `test-results/layout-${name}.png` });
    } finally {
      await context.close();
    }
  });
}
