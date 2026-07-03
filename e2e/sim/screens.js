// EVERY-SCREEN coverage pass. Logs in as the org administrator (full access, so no screen is unreachable
// by permission) and visits EVERY navigable screen in the system (e2e/sim/all-screens.json, generated from
// admin.routes.ts). For each screen it records whether it loaded, catches a server/JS error, and scans the
// rendered page for a visible id/uid leak. This guarantees every screen in the system is exercised.
// Run:  NODE_PATH=web/node_modules node e2e/sim/screens.js
const L = require('./sim-lib');
const ALL = require('./all-screens.json');

(async () => {
  const browser = await L.launch();
  const { page, buf } = await L.newSession(browser);
  const admin = { fullName: 'System Administrator (coverage)', designation: 'Administrator',
    username: 'rootadmin', homeBranch: 'Dar es Salaam HQ', role: 'ROOT', slug: 'coverage' };
  const rec = L.makeRecorder(admin);
  const result = { role: 'ROOT', access: [], loggedIn: false };
  try {
    console.log(`=== EVERY-SCREEN COVERAGE: visiting all ${ALL.length} screens as ${admin.username} ===`);
    const li = await L.login(page, 'rootadmin', 'RootPass12345');
    if (!li.ok) { console.log('coverage login FAILED'); process.exit(1); }
    result.loggedIn = true;

    for (const [path, label] of ALL) {
      buf.clear();
      await L.goto(page, path);
      const url = page.url().replace(L.BASE, '');
      let outcome = 'OK';
      if (url.includes('/login')) { outcome = 'KICKED_TO_LOGIN'; rec.problem('BLOCKED', 'open screen', path, `${label} threw me back to login`, buf.snapshot()); }
      else if (/\/admin\/home$|\/admin$/.test(url) && !/\/home$|^\/admin$/.test(path)) { outcome = 'REDIRECTED_HOME'; rec.problem('SLOW', 'open screen', path, `${label} bounced to home (even as admin — route/guard issue)`, buf.snapshot()); }
      else {
        const snap = buf.snapshot();
        const realCon = snap.console.filter(e => !/favicon|ResizeObserver|net::ERR_/.test(e));
        if (snap.api.some(a => a.status >= 500)) { outcome = 'SERVER_ERROR'; rec.problem('BLOCKED', 'open screen', path, `${label} failed to load (server error)`, snap); }
        else if (snap.page.length || realCon.length) { outcome = 'JS_ERROR'; rec.problem('SLOW', 'open screen', path, `${label} loaded but the screen reported an error`, { ...snap, console: realCon }); }
      }
      rec.visited(label, outcome === 'OK');
      if (outcome === 'OK') {
        const ids = await L.scanVisibleIds(page);
        if (ids.hits && ids.hits.length) {
          rec.problem('HYGIENE', 'id/uid visible in the UI', path,
            `raw id/uid visible on ${label} — a uid belongs only in the URL. e.g. "${ids.snippet}"`,
            { hits: ids.hits.slice(0, 6), snippet: ids.snippet });
        }
      }
      result.access.push({ path, label, outcome });
      console.log(`  ${outcome.padEnd(16)} ${path}`);
    }
  } catch (e) {
    rec.problem('BLOCKED', 'coverage', 'fatal', String(e && e.message || e).slice(0, 140), buf.snapshot());
  } finally {
    const used = result.access.filter(a => a.outcome === 'OK').length;
    result.usage = rec.usage;
    result.problems = rec.problems;
    result.summary = { total: ALL.length, used, notUsed: ALL.length - used };
    L.saveResults('coverage-all-screens.json', result);
    console.log(`\n=== COVERAGE: ${used}/${ALL.length} screens loaded OK; ${rec.problems.length} problems (server/JS errors + id-leaks) ===`);
    await browser.close();
    process.exit(0);
  }
})();
