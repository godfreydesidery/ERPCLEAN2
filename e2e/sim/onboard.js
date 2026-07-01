// ONBOARDING DRIVER — the "IT setup" wave, run as rootadmin through the REAL web UI.
// Creates (idempotently): the 9 branches, the 12 roles (with scoped permissions), and the 16 persona
// user accounts, each granted its role and assigned its home branch as default.
// Structured as CREATE passes then CONFIGURE passes so a detail-page visit never leaks into the next
// create (the bug that broke the first run). Functional problems are captured as candidate UPRs.
//
//   NODE_PATH=d:/My_Works/ERP/ERPCLEAN2/web/node_modules node e2e/sim/onboard.js
const L = require('./sim-lib');
const D = require('./sim-data');

const ROOT_USER = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS || 'RootPass12345';

async function tableText(page) {
  const t = page.locator('table').first();
  if (!await t.count()) return '';
  return (await t.innerText().catch(() => '')) || '';
}
// Submit a specific form by its aria-label (the user-detail page has several "Assign" buttons).
async function submitForm(page, ariaLabel) {
  const form = page.locator(`form[aria-label="${ariaLabel}"]`).first();
  if (!await form.count()) return false;
  const btn = form.locator('button[type="submit"]').first();
  if (await btn.count() && await btn.isEnabled().catch(() => false)) { await btn.click(); return true; }
  return false;
}
// Find the row containing rowText and click the link whose text matches linkText (e.g. "Edit"/"Assignments").
async function openDetail(page, rowText, linkText) {
  const row = page.locator('tr', { hasText: rowText }).first();
  if (!await row.count()) return false;
  const link = row.locator('a', { hasText: linkText }).first();
  if (await link.count()) { await link.click(); await page.waitForTimeout(900); return true; }
  const anyLink = row.locator('a').first();
  if (await anyLink.count()) { await anyLink.click(); await page.waitForTimeout(900); return true; }
  return false;
}

(async () => {
  const persona = { fullName: 'IT Admin (rootadmin)', designation: 'System Administrator', username: ROOT_USER };
  const browser = await L.launch();
  const { page, buf } = await L.newSession(browser);
  const rec = L.makeRecorder(persona);
  const sum = { branches: { created: 0, existing: 0 }, roles: { created: 0, existing: 0, perms: {} }, users: { created: 0, existing: 0, granted: 0, branchAssigned: 0 } };

  try {
    console.log(`=== ONBOARD: login ${ROOT_USER} ===`);
    const li = await L.login(page, ROOT_USER, ROOT_PASS);
    if (!li.ok) { rec.problem('BLOCKED', 'login', '/login', 'rootadmin could not sign in', buf.snapshot()); throw new Error('login failed'); }
    console.log('  ok ->', li.landedUrl);

    // ---------- PASS A: BRANCHES (select a company first, then the create form renders) ----------
    console.log('=== BRANCHES ===');
    await L.goto(page, '/admin/branches');
    await L.pickFirstReal(page, '#branchCompany');   // branch-admin gates the form behind a company pick
    await page.waitForTimeout(800);
    if (!await page.locator('#branchCode').first().isVisible().catch(() => false)) {
      rec.problem('BLOCKED', 'open branches', '/admin/branches', 'branch create form never appeared after selecting a company', buf.snapshot());
    } else {
      let existing = await tableText(page);
      for (const b of D.BRANCHES) {
        if (existing.includes(b.name)) { sum.branches.existing++; continue; }
        try {
          buf.clear();
          await L.setField(page, '#branchCode', b.code);
          await L.setField(page, '#branchName', b.name);
          if (b.makeDefault) await page.locator('#makeDefault').check().catch(() => {});
          if (!await L.clickButton(page, /add branch/i)) await L.submitByEnter(page, '#branchName');
          await page.waitForTimeout(900);
          existing = await tableText(page);
          if (existing.includes(b.name)) { sum.branches.created++; rec.ok('branch'); console.log(`  + ${b.name}`); }
          else { const s = buf.snapshot(); rec.problem('BLOCKED', 'create branch', '/admin/branches', `"${b.name}" did not appear after submit`, s); }
        } catch (e) { rec.problem('BLOCKED', 'create branch', '/admin/branches', `${b.name}: ${String(e.message || e).slice(0, 80)}`, buf.snapshot()); }
      }
    }

    // ---------- PASS B: ROLES (create only) ----------
    console.log('=== ROLES (create) ===');
    await L.goto(page, '/admin/roles');
    let rolesText = await tableText(page);
    for (const r of D.ROLES) {
      if (rolesText.includes(r.name)) { sum.roles.existing++; continue; }
      try {
        buf.clear();
        await L.setField(page, '#roleCode', r.key);
        await L.setField(page, '#roleName', r.name);
        await L.setField(page, '#roleDesc', r.desc).catch(() => {});
        if (!await L.clickButton(page, /add role/i)) await L.submitByEnter(page, '#roleName');
        await page.waitForTimeout(900);
        rolesText = await tableText(page);
        if (rolesText.includes(r.name)) { sum.roles.created++; console.log(`  + role ${r.name}`); }
        else rec.problem('BLOCKED', 'create role', '/admin/roles', `"${r.name}" did not appear`, buf.snapshot());
      } catch (e) { rec.problem('BLOCKED', 'create role', '/admin/roles', `${r.name}: ${String(e.message || e).slice(0, 80)}`, buf.snapshot()); }
    }

    // ---------- PASS C: ROLE PERMISSIONS (one detail visit per role, fresh navigation) ----------
    console.log('=== ROLES (permissions) ===');
    for (const r of D.ROLES) {
      try {
        await L.goto(page, '/admin/roles');
        if (!await openDetail(page, r.name, 'Edit')) { rec.problem('SLOW', 'open role', '/admin/roles', `could not open Edit for ${r.name}`, buf.snapshot()); continue; }
        const boxes = page.locator('input[type="checkbox"][id^="perm-"]');
        const n = await boxes.count();
        let checked = 0;
        for (let i = 0; i < n; i++) {
          const cb = boxes.nth(i);
          const id = (await cb.getAttribute('id').catch(() => '')) || '';
          const code = id.replace(/^perm-/, '').toLowerCase();
          const extra = (r.extraPerms || []).map(c => c.toLowerCase());
          if (r.keywords.some(k => code.includes(k.toLowerCase())) || extra.includes(code)) {
            if (!await cb.isChecked().catch(() => true)) await cb.check().catch(() => {});
            checked++;
          }
        }
        buf.clear();
        await L.clickButton(page, /save permissions/i);
        await page.waitForTimeout(700);
        sum.roles.perms[r.key] = `${checked}/${n}`;
        console.log(`  ${r.key}: ${checked}/${n}`);
        if (n === 0) rec.problem('SLOW', 'assign permissions', '/admin/roles/uid', `no permission checkboxes for ${r.name}`, buf.snapshot());
      } catch (e) { rec.problem('SLOW', 'assign permissions', '/admin/roles/uid', `${r.name}: ${String(e.message || e).slice(0, 80)}`, buf.snapshot()); }
    }

    // ---------- PASS D: USERS (create only) ----------
    console.log('=== USERS (create) ===');
    await L.goto(page, '/admin/users');
    let usersText = await tableText(page);
    for (const u of D.STAFF) {
      if (usersText.includes(u.username)) { sum.users.existing++; continue; }
      try {
        buf.clear();
        await L.setField(page, '#newUsername', u.username);
        await L.setField(page, '#newDisplayName', u.fullName);
        await L.setField(page, '#newPassword', L.SIM_PASSWORD);
        if (!await L.clickButton(page, /add user/i)) await L.submitByEnter(page, '#newPassword');
        await page.waitForTimeout(1000);
        usersText = await tableText(page);
        if (usersText.includes(u.username)) { sum.users.created++; rec.ok('user'); console.log(`  + ${u.username}`); }
        else rec.problem('BLOCKED', 'create user', '/admin/users', `"${u.username}" did not appear`, buf.snapshot());
      } catch (e) { rec.problem('BLOCKED', 'create user', '/admin/users', `${u.username}: ${String(e.message || e).slice(0, 80)}`, buf.snapshot()); }
    }

    // ---------- PASS E: USER CONFIG (branch default + role grant; fresh nav per user) ----------
    console.log('=== USERS (assign branch + grant role) ===');
    for (const u of D.STAFF) {
      try {
        await L.goto(page, '/admin/users');
        if (!await openDetail(page, u.username, 'Assignments')) { rec.problem('SLOW', 'open user', '/admin/users', `could not open assignments for ${u.username}`, buf.snapshot()); continue; }
        let detail = (await page.locator('body').innerText().catch(() => '')) || '';

        // STEP 1: company membership (REQUIRED before branch/role — ADR-0046, else 409)
        const needsCompany = detail.includes('No company memberships') || detail.includes('Assign this user to a company first');
        if (needsCompany) {
          buf.clear();
          await L.pickFirstReal(page, '#assignCompanySelect');
          await page.waitForTimeout(300);
          if (await submitForm(page, 'Assign a company to this user')) { await page.waitForTimeout(900); }
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 400 && a.status !== 409)) rec.problem('SLOW', 'assign company', '/admin/users/uid', `${u.username} company membership (${s.api.find(a => a.status >= 400).status})`, s);
          detail = (await page.locator('body').innerText().catch(() => '')) || '';
        }

        // STEP 2: home branch as default. Always ATTEMPT (dup 409 tolerated) — the old
        // `detail.includes(homeBranch)` guard false-positive-skipped because the branch dropdown lists
        // every branch name in the page text, so a new persona (e.g. a fresh hire) never got a branch.
        {
          buf.clear();
          await L.pickFirstReal(page, '#assignCompany');
          await page.waitForTimeout(500);
          if (!await L.pickByLabel(page, '#assignBranch', u.homeBranch)) await L.pickFirstReal(page, '#assignBranch');
          await page.locator('#assignMakeDefault').check().catch(() => {});
          if (await submitForm(page, 'Assign a branch to this user')) { await page.waitForTimeout(900); sum.users.branchAssigned++; }
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 400 && a.status !== 409)) rec.problem('SLOW', 'assign branch', '/admin/users/uid', `${u.username} -> ${u.homeBranch} (${s.api.find(a => a.status >= 400).status})`, s);
        }

        // STEP 3: grant role (company-wide). Always attempt — the grant dropdown lists every role
        // name in the page text so a body-text idempotency check is useless; the backend 409s on a
        // duplicate grant, which we tolerate. Check the granted-roles table for the role code instead.
        const role = D.ROLES.find(x => x.key === u.role);
        const grantsTable = (await page.locator('section:has-text("Role Assignments") table').first().innerText().catch(() => '')) || '';
        const alreadyGranted = grantsTable.includes(role.key) || grantsTable.includes(role.name);
        if (!alreadyGranted) {
          buf.clear();
          let rm = await L.pickByLabel(page, '#grantRole', role.name);
          if (!rm) rm = await L.pickByLabel(page, '#grantRole', role.key);
          if (!rm) await L.pickFirstReal(page, '#grantRole');
          await L.pickFirstReal(page, '#grantCompany').catch(() => {});
          await page.waitForTimeout(500);
          if (await submitForm(page, 'Grant a role to this user')) { await page.waitForTimeout(900); sum.users.granted++; console.log(`  granted ${role.key} -> ${u.username}`); }
          else rec.problem('SLOW', 'grant role', '/admin/users/uid', `${u.username}: grant button stayed disabled (role/company not selected?)`, buf.snapshot());
          const s = buf.snapshot();
          if (s.api.some(a => a.status >= 400 && a.status !== 409)) rec.problem('SLOW', 'grant role', '/admin/users/uid', `${u.username} -> ${u.role} (${s.api.find(a => a.status >= 400).status})`, s);
        } else { sum.users.granted++; }
      } catch (e) { rec.problem('SLOW', 'configure user', '/admin/users/uid', `${u.username}: ${String(e.message || e).slice(0, 80)}`, buf.snapshot()); }
    }

  } catch (e) {
    rec.problem('BLOCKED', 'onboard', 'fatal', String(e && e.message || e).slice(0, 140), buf.snapshot());
    await L.shot(page, 'onboard-FATAL');
  } finally {
    console.log('\n=== ONBOARD SUMMARY ===');
    console.log(JSON.stringify(sum, null, 2));
    console.log('problems:', rec.problems.length);
    L.saveResults('onboard-summary.json', { summary: sum, created: rec.created });
    L.saveResults('onboard-problems.json', { persona: persona.fullName, problems: rec.problems });
    await browser.close();
    process.exit(0);
  }
})();
