// Orchestrator: run operate.js for every STAFF persona (limited concurrency), then aggregate every
// persona's captured problems into all-problems.json — the raw material for the User Problem Reports.
//
//   NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules node e2e/sim/run-personas.js
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const D = require('./sim-data');

const OUT = process.env.SIM_OUT || (require('node:os').tmpdir() + '/tembo-sim');
const CONC = +(process.env.SIM_CONCURRENCY || 3);
const ONLY = process.env.ONLY ? process.env.ONLY.split(',') : null;
const slugs = (ONLY || D.STAFF.map(p => p.slug));

function runOne(slug) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [path.join(__dirname, 'operate.js'), slug], {
      env: { ...process.env }, stdio: ['ignore', 'pipe', 'pipe'],
    });
    let tail = '';
    const cap = (d) => { tail = (tail + d.toString()).slice(-400); };
    child.stdout.on('data', cap); child.stderr.on('data', cap);
    child.on('close', (code) => { console.log(`  [done ${slug}] exit=${code} ${tail.split('\n').filter(Boolean).pop() || ''}`); resolve(slug); });
  });
}

async function pool(items, n, fn) {
  const q = [...items]; const running = [];
  const results = [];
  while (q.length || running.length) {
    while (running.length < n && q.length) {
      const it = q.shift();
      const p = fn(it).then(r => { running.splice(running.indexOf(p), 1); results.push(r); });
      running.push(p);
    }
    await Promise.race(running);
  }
  return results;
}

(async () => {
  console.log(`=== running ${slugs.length} personas (concurrency ${CONC}) ===`);
  await pool(slugs, CONC, runOne);

  // aggregate
  const all = [];
  const perPersona = [];
  for (const p of D.STAFF) {
    const f = path.join(OUT, `operate-${p.slug}.json`);
    if (!fs.existsSync(f)) { perPersona.push({ persona: p.fullName, slug: p.slug, ran: false }); continue; }
    const j = JSON.parse(fs.readFileSync(f, 'utf8'));
    perPersona.push({ persona: p.fullName, slug: p.slug, ran: true, loggedIn: j.loggedIn, role: j.role, access: j.access, created: j.created, problemCount: (j.problems || []).length });
    for (const pr of (j.problems || [])) all.push({ ...pr, slug: p.slug });
  }
  const bySeverity = all.reduce((m, p) => { m[p.severity] = (m[p.severity] || 0) + 1; return m; }, {});
  fs.writeFileSync(path.join(OUT, 'all-problems.json'), JSON.stringify({ totals: { problems: all.length, bySeverity }, perPersona, problems: all }, null, 2));
  console.log('\n=== AGGREGATE ===');
  console.log('personas ran:', perPersona.filter(p => p.ran).length, '/', D.STAFF.length);
  console.log('logged in:', perPersona.filter(p => p.loggedIn).length);
  console.log('total problems:', all.length, JSON.stringify(bySeverity));
  console.log('-> all-problems.json');
  process.exit(0);
})();
