// GL (General Ledger) UI E2E against a live site via Playwright. Walks the Accounting screens,
// posts a BALANCED manual journal through the post-journal editor, and confirms the trial balance
// reflects it (Balanced). Logs issues + screenshots, continues on failure.
// Env: WEB_BASE (default http://16.170.11.41), ROOT_USER, ROOT_PASS (REQUIRED), SHOTS_DIR, POST_DATE.
//   Run:  NODE_PATH=%TEMP%/erp-qa-e2e/node_modules ROOT_PASS=... node e2e/gl-ui-drive.js
const { chromium } = require('playwright-core');
const fs = require('fs'); const path = require('path');
const PWROOT = process.env.PLAYWRIGHT_BROWSERS_PATH || (process.env.LOCALAPPDATA + '/ms-playwright');
const EXE = fs.readdirSync(PWROOT).filter(d=>/^chromium-\d/.test(d)).sort().pop();
const BIN = ['chrome-win/chrome.exe','chrome-win64/chrome.exe'].map(p=>`${PWROOT}/${EXE}/${p}`).find(fs.existsSync);
const BASE = process.env.WEB_BASE || 'http://16.170.11.41';
const RUSER = process.env.ROOT_USER || 'rootadmin';
const RPASS = process.env.ROOT_PASS || '';
const SHOTS = process.env.SHOTS_DIR || (require('node:os').tmpdir() + '/erp-gl-shots');
const POST_DATE = process.env.POST_DATE || '2026-06-08'; // must fall in an OPEN fiscal period
fs.mkdirSync(SHOTS, { recursive: true });
if (!RPASS) { console.error('ROOT_PASS env required'); process.exit(2); }

const ISSUES = []; let N = 0;
const issue = (sev, area, msg, d) => { ISSUES.push({ sev, area, msg, d }); console.log(`  [${sev}] ${area}: ${msg}${d?' — '+d:''}`); };

(async () => {
  const b = await chromium.launch({ executablePath: BIN, headless: true });
  const pg = await (await b.newContext({ viewport:{width:1400,height:1000} })).newPage();
  const cerr = []; const api5xx = [];
  pg.on('console', m => { if (m.type()==='error') cerr.push(m.text()); });
  pg.on('response', r => { if (r.url().includes('/api/') && r.status()>=500) api5xx.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE,'')}`); });
  const shot = async n => { try { await pg.screenshot({ path: path.join(SHOTS, String(++N).padStart(2,'0')+'-'+n+'.png'), fullPage:true }); } catch {} };
  const goto = async p => { await pg.goto(BASE+p,{waitUntil:'networkidle'}).catch(()=>{}); await pg.waitForTimeout(600); };
  const visText = async t => pg.locator(`text=${t}`).first().isVisible().catch(()=>false);

  try {
    console.log(`=== LOGIN ${RUSER} @ ${BASE} ===`);
    await goto('/login');
    await pg.fill('input[type="text"]', RUSER); await pg.fill('input[type="password"]', RPASS);
    await pg.click('button[type="submit"]'); await pg.waitForTimeout(2000);
    if (pg.url().includes('/login')) { issue('BLOCKER','login','could not log in'); await shot('FAIL-login'); throw new Error('login'); }
    console.log('  ok');

    // 1. Chart of Accounts — seeded TZ CoA should render
    console.log('=== CHART OF ACCOUNTS ===');
    await goto('/admin/gl/accounts'); await shot('01-chart-of-accounts');
    const acctRows = await pg.locator('table tbody tr').count().catch(()=>0);
    console.log(`  account rows: ${acctRows}`);
    if (acctRows < 10) issue('HIGH','coa',`expected ~13 seeded accounts, saw ${acctRows}`);

    // 2. Trial balance BEFORE — fresh, should be balanced (0/0 or empty)
    console.log('=== TRIAL BALANCE (before) ===');
    await goto('/admin/gl/trial-balance'); await shot('02-trial-balance-before');
    const balancedBefore = await visText('Balanced');
    console.log(`  balanced indicator before: ${balancedBefore}`);

    // 3. POST a balanced manual journal (the centerpiece)
    console.log('=== POST MANUAL JOURNAL ===');
    await goto('/admin/gl/journals/post');
    await shot('03-post-journal-empty');
    // company auto-selects (single company). Fill header.
    await pg.fill('#postingDate', POST_DATE).catch(()=>{});
    await pg.fill('#description', 'E2E manual journal').catch(()=>{});
    // 2 line rows exist by default. Pick two DISTINCT accounts; DR line0, CR line1, equal amounts.
    const acctSelects = pg.locator('select[id^="lineAccount_"]');
    const debitInputs = pg.locator('input[id^="lineDebit_"]');
    const creditInputs = pg.locator('input[id^="lineCreditAmt_"]');
    const lineCount = await acctSelects.count();
    console.log(`  default line rows: ${lineCount}`);
    if (lineCount >= 2) {
      // line 0: debit
      await acctSelects.nth(0).selectOption({ index: 1 }).catch(()=>{});
      await debitInputs.nth(0).fill('50000');
      // line 1: credit, different account
      await acctSelects.nth(1).selectOption({ index: 2 }).catch(()=>{});
      await creditInputs.nth(1).fill('50000');
      await pg.waitForTimeout(400);
      await shot('04-post-journal-filled');
      const balancedNow = await visText('Balanced');
      console.log(`  balance indicator shows Balanced: ${balancedNow}`);
      if (!balancedNow) issue('MEDIUM','post-journal','balanced indicator not shown for 50000=50000');
      // Post
      const postBtn = pg.locator('button[type="submit"]', { hasText: /Post Journal/i }).first();
      const disabled = await postBtn.isDisabled().catch(()=>true);
      console.log(`  Post button disabled (should be false when balanced): ${disabled}`);
      if (disabled) { issue('HIGH','post-journal','Post disabled while balanced 2-line entry'); await shot('FAIL-post-disabled'); }
      else {
        await postBtn.click(); await pg.waitForTimeout(2500);
        await shot('05-after-post');
        const onDetailOrList = /gl\/journals/.test(pg.url());
        const batchShown = await pg.locator('text=/JB-|JG-|E2E manual journal/i').first().isVisible().catch(()=>false);
        console.log(`  post navigated to ${pg.url().replace(BASE,'')}, entry visible=${batchShown}`);
        if (!batchShown && !onDetailOrList) issue('HIGH','post-journal','no entry visible after Post');
      }
    } else {
      issue('HIGH','post-journal',`expected >=2 default line rows, saw ${lineCount}`); await shot('FAIL-no-lines');
    }

    // 4. Trial balance AFTER — should now show the 50000 and Balanced
    console.log('=== TRIAL BALANCE (after) ===');
    await goto('/admin/gl/trial-balance'); await shot('06-trial-balance-after');
    const balancedAfter = await visText('Balanced');
    const has50k = await pg.locator('text=/50,?000/').first().isVisible().catch(()=>false);
    console.log(`  balanced after post: ${balancedAfter}; 50,000 visible: ${has50k}`);
    if (!balancedAfter) issue('HIGH','trial-balance','not Balanced after a balanced post');
    if (!has50k) issue('MEDIUM','trial-balance','posted 50,000 not reflected in TB');

    // 5. Journal Entries list + Fiscal Periods + Posting Accounts render
    console.log('=== JOURNALS / PERIODS / CONFIG screens render ===');
    await goto('/admin/gl/journals'); await shot('07-journal-list');
    await goto('/admin/gl/periods'); await shot('08-fiscal-periods');
    const periodRows = await pg.locator('table tbody tr').count().catch(()=>0);
    if (periodRows < 12) issue('LOW','periods',`expected 12 periods, saw ${periodRows}`);
    await goto('/admin/gl/config'); await shot('09-posting-accounts');

  } catch(e) { issue('HIGH','FATAL', String(e&&e.message||e).slice(0,120)); await shot('FATAL'); }
  finally {
    console.log('\n=== CONSOLE ERRORS ==='); console.log(cerr.length?[...new Set(cerr)].slice(0,8).join('\n'):'(none)');
    console.log('=== API 5xx ==='); console.log(api5xx.length?[...new Set(api5xx)].join('\n'):'(none)');
    const sev={BLOCKER:0,HIGH:0,MEDIUM:0,LOW:0}; for(const i of ISSUES) sev[i.sev]=(sev[i.sev]||0)+1;
    console.log('=== ISSUES ==='); console.log(JSON.stringify(sev));
    fs.writeFileSync(SHOTS+'/gl-issues.json', JSON.stringify({ issues:ISSUES, consoleErrors:[...new Set(cerr)], api5xx:[...new Set(api5xx)] }, null, 2));
    console.log('shots+json → '+SHOTS);
    await b.close(); process.exit(0);
  }
})();
