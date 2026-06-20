// Phase-B live-data seeder for QA. Logs in as rootadmin, discovers the Tier-1 data
// (products/customers/suppliers seeded by seed-and-flow.js), then runs one seed function
// per Phase-B module to populate its screens with realistic, lifecycle-varied LIVE data.
// Never aborts on a module failure — logs the issue and continues.
//   Run:  API_BASE=http://16.170.11.41/api/v1 ROOT_PASS=... node e2e/phaseb-seed.js
const http = require('http');
const B = process.env.API_BASE || 'http://16.170.11.41/api/v1';
const ROOT_USER = process.env.ROOT_USER || 'rootadmin';
const ROOT_PASS = process.env.ROOT_PASS || '';
if (!ROOT_PASS) { console.error('ROOT_PASS env required'); process.exit(2); }

function req(method, path, token, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null;
    const u = new URL(B + path);
    const opt = { method, hostname: u.hostname, port: u.port || 80, path: u.pathname + u.search,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) } };
    if (data) opt.headers['Content-Length'] = Buffer.byteLength(data);
    const r = http.request(opt, (res) => { let b = ''; res.on('data', c => b += c); res.on('end', () => {
      let j = null; try { j = b ? JSON.parse(b) : null; } catch {}
      resolve({ status: res.statusCode, body: j, raw: b });
    }); });
    r.on('error', e => resolve({ status: 0, body: null, raw: String(e) }));
    if (data) r.write(data); r.end();
  });
}
const log = (m) => console.log('  ' + m);
const ok = (r) => r.status >= 200 && r.status < 300;
const dataOf = (r) => (r && r.body && r.body.data) ? r.body.data : null;

// --- the authored per-module seed functions are concatenated below by the build step ---
// ===== cost-centre -> seedCostCentre =====
async function seedCostCentre(req, ctx, log) {
  const created = {
    dimensionValuesCC: 0,
    dimensionValuesDept: 0,
    deactivated: 0,
    reactivated: 0,
    mandatorySet: 0,
    reportProbed: 0,
  };
  const issues = [];

  function pushIssue(label, r) {
    const snippet = (r && r.raw) ? String(r.raw).slice(0, 80) : "(no body)";
    issues.push(`${label}: HTTP ${r && r.status} â€” ${snippet}`);
  }

  // â”€â”€ 1. Fetch seeded dimension types for this company â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log("cost-centre: listing dimension types...");
  const dimsR = await req(
    "GET",
    `/dimensions?companyId=${ctx.companyId}`,
    ctx.token,
    null
  );
  if (!dimsR || dimsR.status < 200 || dimsR.status >= 300) {
    pushIssue("list-dimensions", dimsR);
    return { created, issues };
  }

  const dims = (dimsR.body && dimsR.body.data) ? dimsR.body.data : [];
  if (!Array.isArray(dims) || dims.length === 0) {
    issues.push("list-dimensions: no dimensions found for company â€” company bootstrap may be incomplete");
    return { created, issues };
  }

  let ccDim = null;
  let deptDim = null;
  for (const d of dims) {
    if (d.slot === "COST_CENTRE")  ccDim   = d;
    if (d.slot === "DEPARTMENT")   deptDim = d;
  }

  // â”€â”€ 2. Create Cost Centre hierarchy â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Structure:
  //   HO (root)
  //     â”œâ”€â”€ Operations
  //     â”‚     â”œâ”€â”€ Warehouse
  //     â”‚     â””â”€â”€ Logistics
  //     â”œâ”€â”€ Finance
  //     â”‚     â”œâ”€â”€ Accounts Payable
  //     â”‚     â””â”€â”€ Accounts Receivable
  //     â””â”€â”€ Sales & Marketing (root-level child)
  //         â””â”€â”€ Retail Sales (leaf, will be deactivated then reactivated)
  //         â””â”€â”€ Corporate Sales (leaf)

  const ccValues = []; // { uid, code, name, parentUid }

  if (ccDim) {
    log("cost-centre: creating Cost Centre dimension values...");

    async function createCC(code, name, parentUid) {
      const body = {
        dimensionUid: ccDim.uid,
        code,
        name,
        parentUid: parentUid || null,
      };
      const r = await req("POST", "/dimension-values", ctx.token, body);
      if (r && r.status >= 200 && r.status < 300) {
        const v = r.body && r.body.data;
        created.dimensionValuesCC++;
        log(`cost-centre: created CC value [${code}] uid=${v && v.uid}`);
        return v;
      } else {
        pushIssue(`create-cc-value-${code}`, r);
        return null;
      }
    }

    const hoNode       = await createCC("CC-HO",   "Head Office",         null);
    const opsNode      = await createCC("CC-OPS",  "Operations",          hoNode   ? hoNode.uid   : null);
    const wh           = await createCC("CC-WH",   "Warehouse",           opsNode  ? opsNode.uid  : null);
    const logi         = await createCC("CC-LOG",  "Logistics",           opsNode  ? opsNode.uid  : null);
    const finNode      = await createCC("CC-FIN",  "Finance",             hoNode   ? hoNode.uid   : null);
    const ap           = await createCC("CC-AP",   "Accounts Payable",    finNode  ? finNode.uid  : null);
    const ar           = await createCC("CC-AR",   "Accounts Receivable", finNode  ? finNode.uid  : null);
    const salesNode    = await createCC("CC-SM",   "Sales & Marketing",   hoNode   ? hoNode.uid   : null);
    const retailSales  = await createCC("CC-RTLS", "Retail Sales",        salesNode ? salesNode.uid : null);
    const corpSales    = await createCC("CC-CORP", "Corporate Sales",     salesNode ? salesNode.uid : null);

    // collect non-null uids for lifecycle exercises
    for (const v of [hoNode, opsNode, wh, logi, finNode, ap, ar, salesNode, retailSales, corpSales]) {
      if (v && v.uid) ccValues.push(v);
    }

    // Rename one node to show update works
    if (finNode && finNode.uid) {
      log("cost-centre: renaming Finance node to 'Finance & Treasury'...");
      const updR = await req(
        "PUT",
        `/dimension-values/uid/${finNode.uid}`,
        ctx.token,
        { name: "Finance & Treasury", parentUid: null, clearParent: false }
      );
      if (!updR || updR.status < 200 || updR.status >= 300) {
        pushIssue("update-cc-finance-name", updR);
      } else {
        log("cost-centre: Finance renamed OK");
      }
    }

    // Deactivate Retail Sales â€” shows INACTIVE tagging gate on the list screen
    if (retailSales && retailSales.uid) {
      log("cost-centre: deactivating Retail Sales (CC-RTLS)...");
      const deactR = await req(
        "PATCH",
        `/dimension-values/uid/${retailSales.uid}/deactivate`,
        ctx.token,
        null
      );
      if (deactR && deactR.status >= 200 && deactR.status < 300) {
        created.deactivated++;
        log("cost-centre: CC-RTLS deactivated");
      } else {
        pushIssue("deactivate-cc-rtls", deactR);
      }

      // Immediately re-activate to show the activate path also works
      log("cost-centre: re-activating Retail Sales (CC-RTLS)...");
      const reactR = await req(
        "PATCH",
        `/dimension-values/uid/${retailSales.uid}/activate`,
        ctx.token,
        null
      );
      if (reactR && reactR.status >= 200 && reactR.status < 300) {
        created.reactivated++;
        log("cost-centre: CC-RTLS re-activated");
      } else {
        pushIssue("reactivate-cc-rtls", reactR);
      }
    }

    // Leave Logistics permanently deactivated so the list shows a real mix
    if (logi && logi.uid) {
      log("cost-centre: permanently deactivating Logistics (CC-LOG)...");
      const permDeactR = await req(
        "PATCH",
        `/dimension-values/uid/${logi.uid}/deactivate`,
        ctx.token,
        null
      );
      if (permDeactR && permDeactR.status >= 200 && permDeactR.status < 300) {
        created.deactivated++;
        log("cost-centre: CC-LOG deactivated (permanently for demo)");
      } else {
        pushIssue("deactivate-cc-log", permDeactR);
      }
    }

    // Set COST_CENTRE dimension mandatory (FR-CC-13) â€” every posting must carry a CC tag
    log("cost-centre: setting COST_CENTRE dimension mandatory=true...");
    const mandR = await req(
      "PATCH",
      `/dimensions/uid/${ccDim.uid}/mandatory`,
      ctx.token,
      { mandatory: true }
    );
    if (mandR && mandR.status >= 200 && mandR.status < 300) {
      created.mandatorySet++;
      log("cost-centre: COST_CENTRE dimension is now mandatory");
    } else {
      pushIssue("set-cc-mandatory", mandR);
    }
  } else {
    issues.push("skip-cc-values: no COST_CENTRE dimension found for company");
  }

  // â”€â”€ 3. Create Department hierarchy â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Structure:
  //   MGMT (root)
  //   FNDEPT (root)
  //     â”œâ”€â”€ FN-CTRL (Controller)
  //     â””â”€â”€ FN-TAX  (Tax)
  //   HRDEPT (root)
  //     â”œâ”€â”€ HR-REC  (Recruitment)
  //     â””â”€â”€ HR-PAY  (Payroll) â€” will be deactivated to show state
  //   ITDEPT (root)
  //     â””â”€â”€ IT-INF  (Infrastructure)
  //   SALESDEPT (root)
  //     â”œâ”€â”€ SL-INT  (Internal Sales)
  //     â””â”€â”€ SL-EXT  (External Sales)

  if (deptDim) {
    log("cost-centre: creating Department dimension values...");

    async function createDept(code, name, parentUid) {
      const body = {
        dimensionUid: deptDim.uid,
        code,
        name,
        parentUid: parentUid || null,
      };
      const r = await req("POST", "/dimension-values", ctx.token, body);
      if (r && r.status >= 200 && r.status < 300) {
        const v = r.body && r.body.data;
        created.dimensionValuesDept++;
        log(`cost-centre: created Dept value [${code}] uid=${v && v.uid}`);
        return v;
      } else {
        pushIssue(`create-dept-value-${code}`, r);
        return null;
      }
    }

    const mgmt    = await createDept("DEPT-MGMT", "Management",     null);
    const fnDept  = await createDept("DEPT-FIN",  "Finance Dept",   null);
    const fnCtrl  = await createDept("FN-CTRL",   "Controller",     fnDept ? fnDept.uid : null);
    const fnTax   = await createDept("FN-TAX",    "Tax",            fnDept ? fnDept.uid : null);
    const hrDept  = await createDept("DEPT-HR",   "Human Resources",null);
    const hrRec   = await createDept("HR-REC",    "Recruitment",    hrDept ? hrDept.uid : null);
    const hrPay   = await createDept("HR-PAY",    "Payroll Dept",   hrDept ? hrDept.uid : null);
    const itDept  = await createDept("DEPT-IT",   "Information Technology", null);
    const itInfra = await createDept("IT-INF",    "Infrastructure", itDept ? itDept.uid : null);
    const slsDept = await createDept("DEPT-SLS",  "Sales",          null);
    const slsInt  = await createDept("SL-INT",    "Internal Sales", slsDept ? slsDept.uid : null);
    const slsExt  = await createDept("SL-EXT",    "External Sales", slsDept ? slsDept.uid : null);

    // Deactivate HR-PAY to show inactive state in the Dept list
    if (hrPay && hrPay.uid) {
      log("cost-centre: deactivating Payroll Dept (HR-PAY)...");
      const deactR = await req(
        "PATCH",
        `/dimension-values/uid/${hrPay.uid}/deactivate`,
        ctx.token,
        null
      );
      if (deactR && deactR.status >= 200 && deactR.status < 300) {
        created.deactivated++;
        log("cost-centre: HR-PAY deactivated");
      } else {
        pushIssue("deactivate-dept-hrpay", deactR);
      }
    }

    // Leave DEPARTMENT optional (default) â€” contrast with CC which is mandatory
    log("cost-centre: leaving DEPARTMENT dimension optional (mandatory=false â€” contrast with CC)");

  } else {
    issues.push("skip-dept-values: no DEPARTMENT dimension found for company");
  }

  // â”€â”€ 4. Probe the sliced-trial-balance report (may return empty rows on a fresh DB) â”€
  log("cost-centre: probing sliced-trial-balance report for COST_CENTRE slot...");
  const reportR = await req(
    "GET",
    `/costing/reports/sliced-trial-balance?companyId=${ctx.companyId}&slot=COST_CENTRE&rollUp=false`,
    ctx.token,
    null
  );
  if (reportR && reportR.status >= 200 && reportR.status < 300) {
    const rowCount = (reportR.body && reportR.body.data && Array.isArray(reportR.body.data.rows))
      ? reportR.body.data.rows.length
      : 0;
    created.reportProbed++;
    log(`cost-centre: sliced-trial-balance returned ${rowCount} rows (0 expected on fresh DB â€” normal)`);
  } else {
    pushIssue("probe-report-cc", reportR);
  }

  // Also probe the DEPARTMENT slot
  log("cost-centre: probing sliced-trial-balance report for DEPARTMENT slot...");
  const reportDeptR = await req(
    "GET",
    `/costing/reports/sliced-trial-balance?companyId=${ctx.companyId}&slot=DEPARTMENT&rollUp=true`,
    ctx.token,
    null
  );
  if (reportDeptR && reportDeptR.status >= 200 && reportDeptR.status < 300) {
    const rowCount = (reportDeptR.body && reportDeptR.body.data && Array.isArray(reportDeptR.body.data.rows))
      ? reportDeptR.body.data.rows.length
      : 0;
    created.reportProbed++;
    log(`cost-centre: sliced-trial-balance DEPT rollUp=true returned ${rowCount} rows`);
  } else {
    pushIssue("probe-report-dept", reportDeptR);
  }

  log(
    `cost-centre: done. CC values=${created.dimensionValuesCC}, ` +
    `Dept values=${created.dimensionValuesDept}, ` +
    `deactivated=${created.deactivated}, reactivated=${created.reactivated}, ` +
    `mandatory-set=${created.mandatorySet}, reports-probed=${created.reportProbed}, ` +
    `issues=${issues.length}`
  );
  return { created, issues };
}

// ===== fixed-assets -> seedFixedAssets =====
async function seedFixedAssets(req, ctx, log) {
  const created = {
    categories: 0,
    assets_draft: 0,
    assets_in_service: 0,
    assets_transferred: 0,
    assets_revalued: 0,
    assets_disposed: 0,
    assets_written_off: 0,
    depreciation_runs: 0,
    depreciation_previews: 0,
  };
  const issues = [];

  function fail(label, r) {
    issues.push(`${label}: status=${r.status} ${(r.raw || '').slice(0, 80)}`);
  }

  // â”€â”€ 1. Resolve companyId (numeric) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const companyId = ctx.companyId ? Number(ctx.companyId) : null;
  if (!companyId) {
    issues.push('ctx.companyId missing â€” cannot seed fixed-assets');
    return { created, issues };
  }
  const branchId = ctx.branchId ? Number(ctx.branchId) : null;
  if (!branchId) {
    issues.push('ctx.branchId missing â€” cannot seed fixed-assets');
    return { created, issues };
  }

  // â”€â”€ 2. Resolve GL account IDs from gl_configs seeded by FixedAssetGlSeeder â”€
  //    Keys needed: FIXED_ASSETS (1600), ACCUMULATED_DEPRECIATION (1700),
  //                 DEPRECIATION_EXPENSE (5500)
  log('fixed-assets: fetching GL configs...');
  const glCfgR = await req('GET', `/gl/configs?companyId=${companyId}`, ctx.token, null);
  if (glCfgR.status >= 300) {
    fail('gl-configs fetch', glCfgR);
    issues.push('Cannot resolve GL account IDs â€” aborting fixed-assets seed');
    return { created, issues };
  }
  const glCfgs = Array.isArray(glCfgR.body && glCfgR.body.data)
    ? glCfgR.body.data
    : [];
  function glAccId(key) {
    const c = glCfgs.find(x => x.configKey === key);
    return c ? c.accountId : null;
  }
  const acctFixedAssets    = glAccId('FIXED_ASSETS');
  const acctAccumDep       = glAccId('ACCUMULATED_DEPRECIATION');
  const acctDepExpense     = glAccId('DEPRECIATION_EXPENSE');

  if (!acctFixedAssets || !acctAccumDep || !acctDepExpense) {
    issues.push(
      `GL accounts not seeded yet: FIXED_ASSETS=${acctFixedAssets}, ` +
      `ACCUMULATED_DEPRECIATION=${acctAccumDep}, DEPRECIATION_EXPENSE=${acctDepExpense}. ` +
      'FixedAssetGlSeeder should run on company bootstrap. Continuing with partial data.'
    );
  }

  // If any of the three required accounts is missing, we cannot create categories.
  // Log but still try (the server will reject â€” we capture that in issues).

  // â”€â”€ 3. Create asset categories â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('fixed-assets: creating asset categories...');

  const categoryDefs = [
    {
      code: 'BLDG',
      name: 'Buildings & Civil Works',
      defaultMethod: 'STRAIGHT_LINE',
      defaultLifePeriods: 240, // 20 years Ã— 12 months
      defaultReducingRate: null,
    },
    {
      code: 'VEHC',
      name: 'Motor Vehicles',
      defaultMethod: 'REDUCING_BALANCE',
      defaultLifePeriods: 60,  // 5 years
      defaultReducingRate: 25.0000,
    },
    {
      code: 'EQUIP',
      name: 'Office Equipment & Furniture',
      defaultMethod: 'STRAIGHT_LINE',
      defaultLifePeriods: 60,
      defaultReducingRate: null,
    },
    {
      code: 'COMP',
      name: 'Computers & IT Hardware',
      defaultMethod: 'STRAIGHT_LINE',
      defaultLifePeriods: 36,
      defaultReducingRate: null,
    },
    {
      code: 'PLANT',
      name: 'Plant & Machinery',
      defaultMethod: 'REDUCING_BALANCE',
      defaultLifePeriods: 120,
      defaultReducingRate: 12.5000,
    },
  ];

  const categoryUids = {}; // code -> uid
  const categoryIds  = {}; // code -> id (numeric string from response)

  for (const def of categoryDefs) {
    if (!acctFixedAssets || !acctAccumDep || !acctDepExpense) {
      issues.push(`Skipping category ${def.code} â€” GL account IDs unavailable`);
      continue;
    }
    const body = {
      companyId,
      code: def.code,
      name: def.name,
      defaultMethod: def.defaultMethod,
      defaultLifePeriods: def.defaultLifePeriods,
      assetAccountId: acctFixedAssets,
      accumDepAccountId: acctAccumDep,
      depExpenseAccountId: acctDepExpense,
    };
    if (def.defaultReducingRate !== null) {
      body.defaultReducingRate = def.defaultReducingRate;
    }
    const r = await req('POST', '/fixed-assets/categories', ctx.token, body);
    if (r.status >= 300) {
      fail(`category ${def.code} create`, r);
    } else {
      const d = r.body && r.body.data;
      if (d) {
        categoryUids[def.code] = d.uid;
        categoryIds[def.code]  = d.id;   // serialised as string by Jackson
        created.categories++;
        log(`fixed-assets: category ${def.code} created uid=${d.uid}`);
      }
    }
  }

  if (created.categories === 0) {
    issues.push('No categories created â€” remaining asset seed will be skipped');
    return { created, issues };
  }

  // Pick the first available category per method for assets below
  const slCatCode = ['BLDG','EQUIP','COMP'].find(c => categoryIds[c]) || Object.keys(categoryIds)[0];
  const rbCatCode = ['VEHC','PLANT'].find(c => categoryIds[c])         || slCatCode;

  function catId(code) {
    const v = categoryIds[code];
    return v ? Number(v) : null;
  }

  // â”€â”€ 4. Resolve an OPEN fiscal period â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('fixed-assets: fetching fiscal periods...');
  const fpR = await req('GET', `/gl/periods?companyId=${companyId}`, ctx.token, null);
  let openPeriod = null;
  if (fpR.status < 300 && Array.isArray(fpR.body && fpR.body.data)) {
    // Prefer a 2026 period that is OPEN
    openPeriod = fpR.body.data.find(p => p.status === 'OPEN' && String(p.startDate).startsWith('2026'));
    if (!openPeriod) {
      openPeriod = fpR.body.data.find(p => p.status === 'OPEN');
    }
  }
  if (!openPeriod) {
    fail('fiscal-periods list', fpR);
    issues.push('No OPEN fiscal period found â€” place-in-service and depreciation runs will be skipped');
  } else {
    log(`fixed-assets: using open period uid=${openPeriod.uid} (${openPeriod.startDate} â†’ ${openPeriod.endDate})`);
  }

  // Derive a posting date inside the open period (use startDate as safe default)
  const postingDate = openPeriod ? openPeriod.startDate : '2026-01-01';
  // Acquisition / depreciation start dates â€” one month before period start for realism
  function monthsBack(dateStr, n) {
    if (!dateStr) return '2025-12-01';
    const d = new Date(dateStr + 'T00:00:00Z');
    d.setUTCMonth(d.getUTCMonth() - n);
    return d.toISOString().slice(0, 10);
  }
  const acqDate  = monthsBack(postingDate, 1);
  const depStart = postingDate; // start depreciating from the service period

  // â”€â”€ 5. Register assets (DRAFT) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('fixed-assets: registering assets (DRAFT)...');

  // We will create 7 assets with varied categories / methods / costs.
  // Some will go all the way to IN_SERVICE; one stays DRAFT; one gets disposed; one written off.
  const assetDefs = [
    // [catCode, name, cost, salvage, method, life, tag, location, note]
    ['BLDG',  'Head Office Building',           85000000, 5000000,  'STRAIGHT_LINE',    240, 'BLD-001', 'Dar es Salaam HQ',     'place+depreciate+revalue'],
    ['VEHC',  'Toyota Land Cruiser GX',          42000000, 3000000,  'REDUCING_BALANCE',  60, 'VEH-001', 'Fleet â€“ Dar branch',   'place+depreciate'],
    ['VEHC',  'Isuzu NMR Pickup',                28500000, 2000000,  'REDUCING_BALANCE',  60, 'VEH-002', 'Fleet â€“ Mwanza',       'place+dispose'],
    ['EQUIP', 'Executive Boardroom Furniture',    4800000,  200000,  'STRAIGHT_LINE',     60, 'EQP-001', 'HQ 3rd Floor',         'place+transfer'],
    ['COMP',  'HP ProBook Laptop Batch (Ã—10)',    9500000,  500000,  'STRAIGHT_LINE',     36, 'ICT-001', 'IT Department',        'place+depreciate'],
    ['PLANT', 'Diesel Generator 100kVA',         18000000, 1000000,  'REDUCING_BALANCE', 120, 'PLT-001', 'Server Room',          'place+write-off'],
    ['EQUIP', 'Air Conditioner Split Units (Ã—5)', 3200000,  100000,  'STRAIGHT_LINE',     60, 'EQP-002', 'Main Office',          'stays-draft'],
  ];

  const registeredAssets = []; // { uid, note, catCode, method }

  for (let i = 0; i < assetDefs.length; i++) {
    const [catCode, name, cost, salvage, method, life, tag, location, note] = assetDefs[i];
    const cid = catId(catCode) || catId(slCatCode);
    if (!cid) {
      issues.push(`No category id for ${catCode} â€” skipping asset ${name}`);
      continue;
    }
    const body = {
      companyId,
      branchId,
      categoryId: cid,
      name,
      acquisitionCost: cost,
      salvageValue: salvage,
      depreciationMethod: method,
      lifePeriods: life,
      acquisitionDate: acqDate,
      depreciationStartDate: depStart,
      location,
      assetTag: tag,
    };
    if (method === 'REDUCING_BALANCE') {
      // find rate from categoryDefs
      const catDef = categoryDefs.find(d => d.code === catCode);
      if (catDef && catDef.defaultReducingRate) body.reducingRate = catDef.defaultReducingRate;
    }
    const r = await req('POST', '/fixed-assets', ctx.token, body);
    if (r.status >= 300) {
      fail(`register asset ${name}`, r);
    } else {
      const d = r.body && r.body.data;
      if (d) {
        registeredAssets.push({ uid: d.uid, id: d.id, name, note, catCode, method });
        created.assets_draft++;
        log(`fixed-assets: registered DRAFT uid=${d.uid} "${name}"`);
      }
    }
  }

  if (registeredAssets.length === 0) {
    issues.push('No assets registered â€” skipping lifecycle steps');
    return { created, issues };
  }

  // â”€â”€ 6. Update one DRAFT asset (non-financial fields) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const draftTarget = registeredAssets.find(a => a.note === 'stays-draft');
  if (draftTarget) {
    const r = await req(
      'PUT', `/fixed-assets/uid/${draftTarget.uid}`, ctx.token,
      { name: draftTarget.name + ' [QA VERIFIED]', location: 'Main Office â€“ Updated', assetTag: 'EQP-002-REV' }
    );
    if (r.status >= 300) fail(`update DRAFT asset ${draftTarget.uid}`, r);
    else log(`fixed-assets: updated DRAFT asset ${draftTarget.uid}`);
  }

  // â”€â”€ 7. Place assets in service (DRAFT â†’ IN_SERVICE) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  if (!openPeriod) {
    issues.push('Skipping place-in-service â€” no open period');
  } else {
    log('fixed-assets: placing assets in service...');
    for (const asset of registeredAssets) {
      if (asset.note === 'stays-draft') continue;
      const r = await req(
        'POST', `/fixed-assets/uid/${asset.uid}/place-in-service`, ctx.token,
        { postingDate }
      );
      if (r.status >= 300) {
        fail(`place-in-service ${asset.uid} "${asset.name}"`, r);
      } else {
        asset.inService = true;
        created.assets_in_service++;
        log(`fixed-assets: placed in service uid=${asset.uid}`);
      }
    }
  }

  // â”€â”€ 8. Transfer one IN_SERVICE asset â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const transferTarget = registeredAssets.find(a => a.note === 'place+transfer' && a.inService);
  if (transferTarget) {
    const r = await req(
      'POST', `/fixed-assets/uid/${transferTarget.uid}/transfer`, ctx.token,
      { location: 'HQ 4th Floor â€“ Relocated', branchId }
    );
    if (r.status >= 300) fail(`transfer ${transferTarget.uid}`, r);
    else {
      created.assets_transferred++;
      log(`fixed-assets: transferred uid=${transferTarget.uid}`);
    }
  }

  // â”€â”€ 9. Revalue one IN_SERVICE asset (UP) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const revalTarget = registeredAssets.find(a => a.note === 'place+depreciate+revalue' && a.inService);
  if (revalTarget) {
    const r = await req(
      'POST', `/fixed-assets/uid/${revalTarget.uid}/revalue`, ctx.token,
      {
        direction: 'UP',
        deltaAmount: 5000000,
        revaluationDate: postingDate,
        reason: 'Market valuation uplift â€” independent surveyor report Q1-2026',
      }
    );
    if (r.status >= 300) fail(`revalue UP ${revalTarget.uid}`, r);
    else {
      created.assets_revalued++;
      log(`fixed-assets: revalued UP uid=${revalTarget.uid}`);
    }
  }

  // â”€â”€ 10. Run depreciation preview (read-only) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  if (openPeriod) {
    log('fixed-assets: running depreciation preview...');
    const prevR = await req(
      'POST',
      `/fixed-assets/depreciation-runs/preview?companyId=${companyId}&fiscalPeriodUid=${openPeriod.uid}`,
      ctx.token,
      null
    );
    if (prevR.status >= 300) {
      fail('depreciation preview', prevR);
    } else {
      created.depreciation_previews++;
      const pd = prevR.body && prevR.body.data;
      log(
        `fixed-assets: preview done â€” assetCount=${pd ? pd.assetCount : '?'} ` +
        `totalCharge=${pd ? pd.totalChargeAmount : '?'}`
      );
    }
  }

  // â”€â”€ 11. Post depreciation run â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  if (openPeriod) {
    log('fixed-assets: posting depreciation run...');
    const runR = await req('POST', '/fixed-assets/depreciation-runs', ctx.token, {
      companyId,
      fiscalPeriodUid: openPeriod.uid,
      postingDate,
    });
    if (runR.status >= 300) {
      fail('depreciation run post', runR);
    } else {
      const rd = runR.body && runR.body.data;
      created.depreciation_runs++;
      log(
        `fixed-assets: depreciation run posted uid=${rd ? rd.uid : '?'} ` +
        `runNumber=${rd ? rd.runNumber : '?'} assetCount=${rd ? rd.assetCount : '?'}`
      );
    }
  }

  // â”€â”€ 12. Dispose one asset (SALE) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const disposeTarget = registeredAssets.find(a => a.note === 'place+dispose' && a.inService);
  if (disposeTarget) {
    log(`fixed-assets: disposing asset (SALE) uid=${disposeTarget.uid}...`);
    const dispDate = monthsBack(postingDate, 0); // same as posting date
    const r = await req(
      'POST', `/fixed-assets/uid/${disposeTarget.uid}/dispose`, ctx.token,
      {
        disposalDate: dispDate,
        proceedsAmount: 22000000,
        reason: 'Vehicle sold at auction â€” Fleet Rationalisation 2026',
      }
    );
    if (r.status >= 300) fail(`dispose ${disposeTarget.uid}`, r);
    else {
      disposeTarget.disposed = true;
      created.assets_disposed++;
      const dd = r.body && r.body.data;
      log(
        `fixed-assets: disposed uid=${disposeTarget.uid} ` +
        `gainLoss=${dd ? dd.gainLossAmount : '?'}`
      );
    }
  }

  // â”€â”€ 13. Write off one asset â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const writeOffTarget = registeredAssets.find(a => a.note === 'place+write-off' && a.inService);
  if (writeOffTarget) {
    log(`fixed-assets: writing off asset uid=${writeOffTarget.uid}...`);
    const r = await req(
      'POST', `/fixed-assets/uid/${writeOffTarget.uid}/write-off`, ctx.token,
      {
        disposalDate: postingDate,
        reason: 'Generator beyond economic repair â€” insurance claim written off',
      }
    );
    if (r.status >= 300) fail(`write-off ${writeOffTarget.uid}`, r);
    else {
      created.assets_written_off++;
      log(`fixed-assets: written off uid=${writeOffTarget.uid}`);
    }
  }

  // â”€â”€ 14. Fetch depreciation schedule for one IN_SERVICE asset â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const scheduleTarget = registeredAssets.find(a => a.inService && !a.disposed);
  if (scheduleTarget) {
    const sr = await req(
      'GET', `/fixed-assets/uid/${scheduleTarget.uid}/schedule`, ctx.token, null
    );
    if (sr.status >= 300) {
      fail(`schedule fetch ${scheduleTarget.uid}`, sr);
    } else {
      const lines = sr.body && sr.body.data;
      log(
        `fixed-assets: schedule for uid=${scheduleTarget.uid} ` +
        `has ${Array.isArray(lines) ? lines.length : '?'} lines`
      );
    }
  }

  // â”€â”€ 15. Fetch revaluations for the revalued asset â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  if (revalTarget && revalTarget.inService) {
    const rvR = await req(
      'GET', `/fixed-assets/uid/${revalTarget.uid}/revaluations`, ctx.token, null
    );
    if (rvR.status >= 300) {
      fail(`revaluations fetch ${revalTarget.uid}`, rvR);
    } else {
      const rvList = rvR.body && rvR.body.data;
      log(
        `fixed-assets: ${Array.isArray(rvList) ? rvList.length : '?'} ` +
        `revaluation(s) on uid=${revalTarget.uid}`
      );
    }
  }

  // â”€â”€ 16. Reconciliation report â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('fixed-assets: fetching FA-to-GL reconciliation report...');
  const reconR = await req(
    'GET', `/fixed-assets/reconciliation?companyId=${companyId}`, ctx.token, null
  );
  if (reconR.status >= 300) {
    fail('reconciliation report', reconR);
  } else {
    const recon = reconR.body && reconR.body.data;
    log(
      `fixed-assets: reconciliation â€” costTies=${recon ? recon.costTies : '?'} ` +
      `accumDepTies=${recon ? recon.accumDepTies : '?'}`
    );
  }

  // â”€â”€ 17. List assets by status for UI coverage â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  for (const status of ['DRAFT', 'IN_SERVICE', 'DISPOSED', 'WRITTEN_OFF']) {
    const lr = await req(
      'GET',
      `/fixed-assets?companyId=${companyId}&status=${status}&page=0&size=20`,
      ctx.token,
      null
    );
    if (lr.status >= 300) {
      fail(`list assets status=${status}`, lr);
    } else {
      const meta = lr.body && lr.body.meta;
      log(
        `fixed-assets: list status=${status} â†’ ` +
        `totalElements=${meta ? meta.totalElements : '?'}`
      );
    }
  }

  // â”€â”€ 18. List depreciation runs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const runListR = await req(
    'GET',
    `/fixed-assets/depreciation-runs?companyId=${companyId}&page=0&size=10`,
    ctx.token,
    null
  );
  if (runListR.status >= 300) {
    fail('list depreciation runs', runListR);
  } else {
    const meta = runListR.body && runListR.body.meta;
    log(`fixed-assets: depreciation runs list â†’ totalElements=${meta ? meta.totalElements : '?'}`);
  }

  log(`fixed-assets: seed complete â€” ${JSON.stringify(created)}`);
  return { created, issues };
}


// ===== hr-payroll -> seedHrPayroll =====
async function seedHrPayroll(req, ctx, log) {
  const created = {
    departments: 0,
    employees: 0,
    contracts: 0,
    payComponents: 0,
    glAccounts: 0,
    cashBankAccounts: 0,
    payrollRuns: 0,
    leaveRequests: 0,
    loans: 0,
  };
  const issues = [];

  function push(msg, r) {
    issues.push(msg + ' | status=' + r.status + ' | ' + String(r.raw).slice(0, 80));
  }

  const companyId = Number(ctx.companyId);
  const companyUid = ctx.companyUid;
  const branchId = ctx.branchId ? Number(ctx.branchId) : null;
  const branchUid = ctx.branchUid || null;

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 1: GL accounts â€” find or create accounts needed by
  //         pay-components (EXPENSE 5700) and employee loans (ASSET 1450)
  //         plus a fresh ASSET account (1100) for the cash/bank link.
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 1: resolving GL accounts');

  let salaryExpenseGlId = null;   // 5700 EXPENSE â€” for pay components
  let loanReceivableGlId = null;  // 1450 ASSET   â€” for employee loans
  let cashGlUid = null;           // 1100 ASSET   â€” for cash/bank account link

  // List existing GL accounts for this company (paginated, page 0, size 200)
  const glListR = await req('GET', '/gl/accounts?companyId=' + companyId + '&size=200&page=0', ctx.token, null);
  if (glListR.status >= 200 && glListR.status < 300) {
    const accounts = (glListR.body && glListR.body.data) ? glListR.body.data : [];
    for (const acct of accounts) {
      if (acct.accountCode === '5700') salaryExpenseGlId = acct.id;
      if (acct.accountCode === '1450') loanReceivableGlId = acct.id;
      if (acct.accountCode === '1100') cashGlUid = acct.uid;
    }
  } else {
    push('[hr-payroll] GL account list failed', glListR);
  }

  // Create 5700 if missing
  if (!salaryExpenseGlId) {
    const r = await req('POST', '/gl/accounts', ctx.token, {
      companyUid: companyUid,
      accountCode: '5700',
      name: 'Salaries & Wages Expense',
      accountType: 'EXPENSE',
    });
    if (r.status >= 200 && r.status < 300) {
      const data = r.body.data || r.body;
      salaryExpenseGlId = data.id;
      created.glAccounts++;
      log('[hr-payroll] Created GL 5700 id=' + salaryExpenseGlId);
    } else {
      push('[hr-payroll] GL 5700 create failed', r);
    }
  }

  // Create 1450 if missing
  if (!loanReceivableGlId) {
    const r = await req('POST', '/gl/accounts', ctx.token, {
      companyUid: companyUid,
      accountCode: '1450',
      name: 'Employee Loans Receivable',
      accountType: 'ASSET',
    });
    if (r.status >= 200 && r.status < 300) {
      const data = r.body.data || r.body;
      loanReceivableGlId = data.id;
      created.glAccounts++;
      log('[hr-payroll] Created GL 1450 id=' + loanReceivableGlId);
    } else {
      push('[hr-payroll] GL 1450 create failed', r);
    }
  }

  // Create 1100 (bank asset) if missing â€” needed for cash/bank account link
  if (!cashGlUid) {
    const r = await req('POST', '/gl/accounts', ctx.token, {
      companyUid: companyUid,
      accountCode: '1100',
      name: 'Bank Account â€” Main Operating',
      accountType: 'ASSET',
    });
    if (r.status >= 200 && r.status < 300) {
      const data = r.body.data || r.body;
      cashGlUid = data.uid;
      created.glAccounts++;
      log('[hr-payroll] Created GL 1100 uid=' + cashGlUid);
    } else {
      push('[hr-payroll] GL 1100 create failed', r);
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 2: Cash/bank account â€” needed for payroll disburse
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 2: cash/bank account');
  let cashBankAccountUid = null;

  if (cashGlUid) {
    // Check if one already exists for this company
    const cbListR = await req('GET', '/cash/accounts?companyId=' + companyId, ctx.token, null);
    if (cbListR.status >= 200 && cbListR.status < 300) {
      const cbAccounts = Array.isArray(cbListR.body) ? cbListR.body
        : (cbListR.body && cbListR.body.data ? cbListR.body.data : []);
      if (cbAccounts.length > 0) {
        cashBankAccountUid = cbAccounts[0].uid;
        log('[hr-payroll] Reusing existing cash/bank account uid=' + cashBankAccountUid);
      }
    }
    if (!cashBankAccountUid) {
      const r = await req('POST', '/cash/accounts', ctx.token, {
        companyUid: companyUid,
        branchUid: branchUid,
        name: 'CRDB Business Current Account',
        accountType: 'BANK',
        bankName: 'CRDB Bank',
        bankAccountNo: '0150123456789',
        bankBranch: 'Dar es Salaam Main',
        glAccountUid: cashGlUid,
        setAsDefault: true,
      });
      if (r.status >= 200 && r.status < 300) {
        const data = r.body.data || r.body;
        cashBankAccountUid = data.uid;
        created.cashBankAccounts++;
        log('[hr-payroll] Created cash/bank account uid=' + cashBankAccountUid);
      } else {
        push('[hr-payroll] Cash/bank account create failed', r);
      }
    }
  } else {
    issues.push('[hr-payroll] Skipped cash/bank account â€” no GL 1100 uid available');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 3: Pay components
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 3: pay components');

  const payComponentDefs = [
    { code: 'HOUSE_ALLOW', name: 'Housing Allowance',  kind: 'EARNING',    basis: 'FIXED',           taxable: true,  pensionable: false },
    { code: 'TRANSPORT',   name: 'Transport Allowance', kind: 'EARNING',    basis: 'FIXED',           taxable: false, pensionable: false },
    { code: 'SAC_DEDUCT',  name: 'SACCOS Deduction',    kind: 'DEDUCTION',  basis: 'FIXED',           taxable: false, pensionable: false },
  ];

  const payComponentIds = {};  // code -> id (Long)

  if (salaryExpenseGlId) {
    // Check existing to avoid duplicate code conflict
    const pcListR = await req('GET', '/hr/pay-components?companyId=' + companyId, ctx.token, null);
    const existingPcCodes = new Set();
    if (pcListR.status >= 200 && pcListR.status < 300) {
      const pcs = (pcListR.body && pcListR.body.data) ? pcListR.body.data : [];
      for (const pc of pcs) {
        existingPcCodes.add(pc.code);
        payComponentIds[pc.code] = pc.id;
      }
    }

    for (const def of payComponentDefs) {
      if (existingPcCodes.has(def.code)) {
        log('[hr-payroll] Pay component ' + def.code + ' already exists, skipping');
        continue;
      }
      const r = await req('POST', '/hr/pay-components', ctx.token, {
        code: def.code,
        name: def.name,
        kind: def.kind,
        basis: def.basis,
        glAccountId: salaryExpenseGlId,
        taxable: def.taxable,
        pensionable: def.pensionable,
      });
      if (r.status >= 200 && r.status < 300) {
        const data = r.body.data || r.body;
        payComponentIds[def.code] = data.id;
        created.payComponents++;
        log('[hr-payroll] Created pay component ' + def.code + ' id=' + data.id);
      } else {
        push('[hr-payroll] Pay component ' + def.code + ' create failed', r);
      }
    }
  } else {
    issues.push('[hr-payroll] Skipped pay components â€” no GL 5700 id available');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 4: Departments
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 4: departments');

  const deptDefs = [
    { code: 'FINANCE', name: 'Finance & Accounts' },
    { code: 'OPERATIONS', name: 'Operations' },
  ];
  const deptIds = {};  // code -> numeric id

  const deptListR = await req('GET', '/hr/departments?companyId=' + companyId, ctx.token, null);
  if (deptListR.status >= 200 && deptListR.status < 300) {
    const depts = (deptListR.body && deptListR.body.data) ? deptListR.body.data : [];
    for (const d of depts) {
      deptIds[d.code] = d.id;
    }
  }

  for (const def of deptDefs) {
    if (deptIds[def.code]) {
      log('[hr-payroll] Department ' + def.code + ' already exists id=' + deptIds[def.code]);
      continue;
    }
    const r = await req('POST', '/hr/departments', ctx.token, {
      code: def.code,
      name: def.name,
    });
    if (r.status >= 200 && r.status < 300) {
      const data = r.body.data || r.body;
      deptIds[def.code] = data.id;
      created.departments++;
      log('[hr-payroll] Created department ' + def.code + ' id=' + data.id);
    } else {
      push('[hr-payroll] Department ' + def.code + ' create failed', r);
    }
  }

  const finDeptId  = deptIds['FINANCE']    || null;
  const opsDeptId  = deptIds['OPERATIONS'] || null;

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 5: Employees (4 employees)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 5: employees');

  const employeeDefs = [
    {
      firstName: 'Amina',   lastName: 'Mwangi',
      nationalId: 'T1234567A', tin: '100-234-567', nssfNumber: 'NSSF-001-2345',
      heslbNumber: null,
      dateOfBirth: '1988-04-15', gender: 'FEMALE',
      hireDate: '2020-03-01',
      departmentId: finDeptId, jobTitle: 'Finance Manager',
      salary: '1800000.0000', contractType: 'PERMANENT',
      payeResident: true, nssfMember: true, heslbBorrower: false, wcfCovered: true, sdlCounted: true,
    },
    {
      firstName: 'John',    lastName: 'Kiprotich',
      nationalId: 'T2345678B', tin: '100-345-678', nssfNumber: 'NSSF-001-3456',
      heslbNumber: 'HESLB-00456',
      dateOfBirth: '1991-08-22', gender: 'MALE',
      hireDate: '2021-06-15',
      departmentId: finDeptId, jobTitle: 'Senior Accountant',
      salary: '1200000.0000', contractType: 'PERMANENT',
      payeResident: true, nssfMember: true, heslbBorrower: true, wcfCovered: true, sdlCounted: true,
    },
    {
      firstName: 'Grace',   lastName: 'Odhiambo',
      nationalId: 'T3456789C', tin: '100-456-789', nssfNumber: 'NSSF-001-4567',
      heslbNumber: null,
      dateOfBirth: '1993-11-05', gender: 'FEMALE',
      hireDate: '2022-01-10',
      departmentId: opsDeptId, jobTitle: 'Operations Coordinator',
      salary: '900000.0000', contractType: 'FIXED_TERM',
      payeResident: true, nssfMember: true, heslbBorrower: false, wcfCovered: true, sdlCounted: true,
    },
    {
      firstName: 'David',   lastName: 'Baraka',
      nationalId: 'T4567890D', tin: '100-567-890', nssfNumber: 'NSSF-001-5678',
      heslbNumber: 'HESLB-00789',
      dateOfBirth: '1995-03-18', gender: 'MALE',
      hireDate: '2023-09-01',
      departmentId: opsDeptId, jobTitle: 'Logistics Officer',
      salary: '700000.0000', contractType: 'PROBATION',
      payeResident: true, nssfMember: true, heslbBorrower: true, wcfCovered: false, sdlCounted: true,
    },
  ];

  const employees = [];  // { uid, id, firstName, lastName, salary, contractType, ... }

  for (const def of employeeDefs) {
    const empBody = {
      firstName:    def.firstName,
      lastName:     def.lastName,
      nationalId:   def.nationalId,
      tin:          def.tin,
      nssfNumber:   def.nssfNumber,
      heslbNumber:  def.heslbNumber,
      dateOfBirth:  def.dateOfBirth,
      gender:       def.gender,
      hireDate:     def.hireDate,
      jobTitle:     def.jobTitle,
    };
    if (def.departmentId) empBody.departmentId = def.departmentId;
    if (branchId) empBody.branchId = branchId;

    const r = await req('POST', '/hr/employees', ctx.token, empBody);
    if (r.status >= 200 && r.status < 300) {
      const data = r.body.data || r.body;
      employees.push({
        uid: data.uid,
        id: data.id,
        firstName: def.firstName,
        lastName: def.lastName,
        salary: def.salary,
        contractType: def.contractType,
        payeResident: def.payeResident,
        nssfMember: def.nssfMember,
        heslbBorrower: def.heslbBorrower,
        wcfCovered: def.wcfCovered,
        sdlCounted: def.sdlCounted,
      });
      created.employees++;
      log('[hr-payroll] Created employee ' + def.firstName + ' ' + def.lastName + ' uid=' + data.uid);
    } else {
      push('[hr-payroll] Employee ' + def.firstName + ' ' + def.lastName + ' create failed', r);
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 6: Employment contracts (one active contract per employee)
  //         The payroll calculate step selects employees that have
  //         an ACTIVE contract â€” this is mandatory for a non-empty run.
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 6: employment contracts');

  for (const emp of employees) {
    const r = await req('POST', '/hr/contracts/employee/' + emp.uid, ctx.token, {
      contractType:    emp.contractType,
      baseSalaryAmount: emp.salary,
      startDate:       '2024-01-01',
      payeResident:    emp.payeResident,
      nssfMember:      emp.nssfMember,
      heslbBorrower:   emp.heslbBorrower,
      wcfCovered:      emp.wcfCovered,
      sdlCounted:      emp.sdlCounted,
    });
    if (r.status >= 200 && r.status < 300) {
      created.contracts++;
      const data = r.body.data || r.body;
      log('[hr-payroll] Created contract for ' + emp.firstName + ' uid=' + data.uid);
    } else {
      push('[hr-payroll] Contract for ' + emp.firstName + ' failed', r);
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 7: Leave type ID probe
  //         V61 migration seeds leave_types per company via BigSerial.
  //         No list API endpoint exists. Probe candidate IDs starting
  //         at (companyId-1)*6+1. Submit a test request and check for
  //         success (201) vs FK violation (500).
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 7: probing leave type IDs');

  let annualLeaveTypeId = null;
  let sickLeaveTypeId = null;
  let maternityLeaveTypeId = null;

  if (employees.length > 0) {
    // Try to find leave type IDs. V61 inserts 6 rows per company:
    // order: ANNUAL(0), SICK(1), MATERNITY(2), PATERNITY(3), COMPASSION(4), UNPAID(5)
    // The actual numeric IDs depend on how many companies/migrations ran before.
    // Strategy: probe a range of IDs for "ANNUAL" by submitting a 1-day leave
    // request with a test employee and a range of candidate IDs.
    const probeEmp = employees[0];
    // Candidate starting IDs: try (companyId-1)*6+1, then fallback to 1, then 7, 13, 19
    const baseGuess = Math.max(1, (companyId - 1) * 6 + 1);
    const candidates = [baseGuess, 1, 7, 13, 19, 25];
    const seen = new Set();

    for (const candidateId of candidates) {
      if (seen.has(candidateId)) continue;
      seen.add(candidateId);
      if (annualLeaveTypeId) break;

      const probeR = await req('POST', '/hr/leave-requests/employee/' + probeEmp.uid, ctx.token, {
        leaveTypeId: candidateId,
        fromDate:    '2026-07-14',
        toDate:      '2026-07-14',
        days:        '1',
        reason:      'Probe â€” annual leave type detection',
      });
      if (probeR.status >= 200 && probeR.status < 300) {
        // Success â€” this is a valid leave type ID (ANNUAL is always first seeded)
        annualLeaveTypeId   = candidateId;
        sickLeaveTypeId     = candidateId + 1;
        maternityLeaveTypeId = candidateId + 2;
        created.leaveRequests++;
        const ld = probeR.body.data || probeR.body;
        log('[hr-payroll] Probe succeeded: annualLeaveTypeId=' + annualLeaveTypeId + ' leaveRequestUid=' + ld.uid);
        break;
      }
      // 500 or 404 means FK failed â€” try next candidate
    }

    if (!annualLeaveTypeId) {
      issues.push('[hr-payroll] Could not resolve leave type IDs â€” all candidate probes failed; leave requests skipped');
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 8: Leave requests (varied states)
  //         Already have 1 PENDING from the probe above.
  //         Create more: APPROVED annual for emp[1], REJECTED sick for emp[2],
  //         PENDING maternity for emp[2] (leave screens show 4 states).
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 8: leave requests');

  const leaveScenarios = [];

  if (annualLeaveTypeId && employees.length >= 2) {
    // Annual leave for emp[1] â€” will APPROVE
    leaveScenarios.push({
      empUid: employees[1].uid,
      body: {
        leaveTypeId: annualLeaveTypeId,
        fromDate:    '2026-08-04',
        toDate:      '2026-08-08',
        days:        '5',
        reason:      'Annual family vacation',
      },
      decision: 'APPROVED',
      note: 'Approved by HR Manager',
    });
  }

  if (sickLeaveTypeId && employees.length >= 3) {
    // Sick leave for emp[2] â€” will REJECT (dates conflict with operations peak)
    leaveScenarios.push({
      empUid: employees[2].uid,
      body: {
        leaveTypeId: sickLeaveTypeId,
        fromDate:    '2026-06-23',
        toDate:      '2026-06-25',
        days:        '3',
        reason:      'Flu and fever',
      },
      decision: 'REJECTED',
      note: 'Peak season â€” medical certificate required first',
    });
  }

  if (maternityLeaveTypeId && employees.length >= 3) {
    // Maternity leave for emp[2] â€” leave PENDING
    leaveScenarios.push({
      empUid: employees[2].uid,
      body: {
        leaveTypeId: maternityLeaveTypeId,
        fromDate:    '2026-09-01',
        toDate:      '2026-11-28',
        days:        '84',
        reason:      'Maternity leave as per statutory entitlement',
      },
      decision: null,  // stays PENDING
    });
  }

  const leaveUids = [];
  for (const scenario of leaveScenarios) {
    const r = await req('POST', '/hr/leave-requests/employee/' + scenario.empUid, ctx.token, scenario.body);
    if (r.status >= 200 && r.status < 300) {
      const data = r.body.data || r.body;
      leaveUids.push(data.uid);
      created.leaveRequests++;
      log('[hr-payroll] Created leave request uid=' + data.uid + ' status=' + data.status);

      if (scenario.decision) {
        const decR = await req('POST', '/hr/leave-requests/uid/' + data.uid + '/decide', ctx.token, {
          decision: scenario.decision,
          decisionNote: scenario.note || null,
        });
        if (decR.status >= 200 && decR.status < 300) {
          log('[hr-payroll] Decided leave ' + data.uid + ' -> ' + scenario.decision);
        } else {
          push('[hr-payroll] Leave decide failed uid=' + data.uid, decR);
        }
      }
    } else {
      push('[hr-payroll] Leave request create failed for emp ' + scenario.empUid, r);
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 9: Employee loans
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 9: employee loans');

  if (loanReceivableGlId && employees.length >= 2) {
    // Loan 1: emp[1] â€” salary advance, will approve
    const loan1Body = {
      employeeId:        employees[1].id,
      principalAmount:   '2400000.0000',
      installmentAmount: '200000.0000',
      glAccountId:       loanReceivableGlId,
      startDate:         '2026-06-01',
      currency:          'TZS',
    };
    const loan1R = await req('POST', '/hr/loans/employee/' + employees[1].uid, ctx.token, loan1Body);
    if (loan1R.status >= 200 && loan1R.status < 300) {
      created.loans++;
      const loan1Data = loan1R.body.data || loan1R.body;
      log('[hr-payroll] Created loan for ' + employees[1].firstName + ' uid=' + loan1Data.uid);
      // Approve the loan
      const approveR = await req('POST', '/hr/loans/uid/' + loan1Data.uid + '/approve', ctx.token, null);
      if (approveR.status >= 200 && approveR.status < 300) {
        log('[hr-payroll] Approved loan uid=' + loan1Data.uid + ' status=ACTIVE');
      } else {
        push('[hr-payroll] Loan approve failed uid=' + loan1Data.uid, approveR);
      }
    } else {
      push('[hr-payroll] Loan create for emp[1] failed', loan1R);
    }

    if (employees.length >= 4) {
      // Loan 2: emp[3] â€” equipment purchase, leave pending (not approved)
      const loan2Body = {
        employeeId:        employees[3].id,
        principalAmount:   '1500000.0000',
        installmentAmount: '150000.0000',
        glAccountId:       loanReceivableGlId,
        startDate:         '2026-06-15',
        currency:          'TZS',
      };
      const loan2R = await req('POST', '/hr/loans/employee/' + employees[3].uid, ctx.token, loan2Body);
      if (loan2R.status >= 200 && loan2R.status < 300) {
        created.loans++;
        const loan2Data = loan2R.body.data || loan2R.body;
        log('[hr-payroll] Created loan (pending approval) for ' + employees[3].firstName + ' uid=' + loan2Data.uid);
      } else {
        push('[hr-payroll] Loan create for emp[3] failed', loan2R);
      }
    }
  } else {
    issues.push('[hr-payroll] Skipped loans â€” no GL 1450 id or insufficient employees');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 10: Payroll run #1 â€” April 2026 â€” full lifecycle
  //          DRAFT -> CALCULATED -> APPROVED -> POSTED -> PAID
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 10: payroll run April 2026 (full lifecycle)');

  let run1Uid = null;

  const run1Body = {
    periodMonth: 4,
    periodYear:  2026,
    payDate:     '2026-04-30',
  };
  if (branchId) run1Body.branchId = branchId;

  const run1R = await req('POST', '/hr/payroll-runs', ctx.token, run1Body);
  if (run1R.status >= 200 && run1R.status < 300) {
    const data = run1R.body.data || run1R.body;
    run1Uid = data.uid;
    created.payrollRuns++;
    log('[hr-payroll] Created payroll run April 2026 uid=' + run1Uid + ' runNumber=' + data.runNumber);
  } else if (run1R.status === 409) {
    // Already exists â€” try to find it via list
    log('[hr-payroll] Payroll run Apr-2026 already exists (409), fetching from list');
    const listR = await req('GET', '/hr/payroll-runs?companyId=' + companyId + '&size=50&page=0', ctx.token, null);
    if (listR.status >= 200 && listR.status < 300) {
      const runs = (listR.body && listR.body.data) ? listR.body.data : [];
      for (const run of runs) {
        if (String(run.periodYear) === '2026' && String(run.periodMonth) === '4') {
          run1Uid = run.uid;
          log('[hr-payroll] Found existing Apr-2026 run uid=' + run1Uid + ' status=' + run.status);
          break;
        }
      }
    }
  } else {
    push('[hr-payroll] Payroll run Apr-2026 create failed', run1R);
  }

  if (run1Uid) {
    // Calculate
    const calcR = await req('POST', '/hr/payroll-runs/uid/' + run1Uid + '/calculate', ctx.token, null);
    if (calcR.status >= 200 && calcR.status < 300) {
      const data = calcR.body.data || calcR.body;
      log('[hr-payroll] Calculated run ' + run1Uid + ' status=' + data.status
        + ' gross=' + data.grossTotal + ' net=' + data.netTotal);
    } else {
      push('[hr-payroll] Payroll run calculate failed uid=' + run1Uid, calcR);
    }

    // Check for FLAGGED lines before approve
    const linesR = await req('GET', '/hr/payroll-runs/uid/' + run1Uid + '/lines', ctx.token, null);
    let hasFlagged = false;
    if (linesR.status >= 200 && linesR.status < 300) {
      const lines = Array.isArray(linesR.body) ? linesR.body
        : (linesR.body && linesR.body.data ? linesR.body.data : []);
      const flagged = lines.filter(function(l) { return l.status === 'FLAGGED'; });
      hasFlagged = flagged.length > 0;
      log('[hr-payroll] Run lines: total=' + lines.length + ' flagged=' + flagged.length);
      if (hasFlagged) {
        issues.push('[hr-payroll] ' + flagged.length + ' FLAGGED lines in run ' + run1Uid
          + ' â€” approve will be skipped; check negative net employees');
      }
    } else {
      push('[hr-payroll] Run lines fetch failed uid=' + run1Uid, linesR);
    }

    if (!hasFlagged) {
      // Approve
      const approveR = await req('POST', '/hr/payroll-runs/uid/' + run1Uid + '/approve', ctx.token, null);
      if (approveR.status >= 200 && approveR.status < 300) {
        const data = approveR.body.data || approveR.body;
        log('[hr-payroll] Approved run ' + run1Uid + ' status=' + data.status);
      } else {
        push('[hr-payroll] Payroll run approve failed uid=' + run1Uid, approveR);
        // If approve failed, skip post/disburse
        run1Uid = null;
      }

      if (run1Uid) {
        // Post
        const postR = await req('POST', '/hr/payroll-runs/uid/' + run1Uid + '/post', ctx.token, null);
        if (postR.status >= 200 && postR.status < 300) {
          const data = postR.body.data || postR.body;
          log('[hr-payroll] Posted run ' + run1Uid + ' status=' + data.status + ' glEntryUid=' + data.glEntryUid);
        } else {
          push('[hr-payroll] Payroll run post failed uid=' + run1Uid, postR);
          run1Uid = null;
        }
      }

      if (run1Uid && cashBankAccountUid) {
        // Brief pause â€” PAYROLL.FINALISED outbox event is async and the GL handler
        // must complete before disburse checks netTotal > 0 from the GL perspective.
        // The service itself reads netTotal from the PayrollRun entity (already set by post),
        // so no mandatory wait â€” but emit a note in case the outbox async GL write races.
        const disburseR = await req('POST', '/hr/payroll-runs/uid/' + run1Uid + '/disburse', ctx.token, {
          cashBankAccountUid: cashBankAccountUid,
          txnDate: '2026-04-30',
        });
        if (disburseR.status >= 200 && disburseR.status < 300) {
          const data = disburseR.body.data || disburseR.body;
          log('[hr-payroll] Disbursed run ' + run1Uid + ' status=' + data.status + ' netTotal=' + data.netTotal);
        } else {
          push('[hr-payroll] Payroll run disburse failed uid=' + run1Uid, disburseR);
        }
      } else if (run1Uid && !cashBankAccountUid) {
        issues.push('[hr-payroll] Skipped disburse for run ' + run1Uid + ' â€” no cashBankAccountUid available');
      }
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 11: Payroll run #2 â€” May 2026 â€” DRAFT only (shows pending state)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Step 11: payroll run May 2026 (DRAFT)');

  const run2Body = {
    periodMonth: 5,
    periodYear:  2026,
    payDate:     '2026-05-31',
  };
  if (branchId) run2Body.branchId = branchId;

  const run2R = await req('POST', '/hr/payroll-runs', ctx.token, run2Body);
  if (run2R.status >= 200 && run2R.status < 300) {
    const data = run2R.body.data || run2R.body;
    created.payrollRuns++;
    log('[hr-payroll] Created payroll run May 2026 uid=' + data.uid + ' status=' + data.status);
  } else if (run2R.status === 409) {
    log('[hr-payroll] Payroll run May-2026 already exists (409), skipping');
  } else {
    push('[hr-payroll] Payroll run May-2026 create failed', run2R);
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 12: Summary
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[hr-payroll] Done. created=' + JSON.stringify(created) + ' issues=' + issues.length);

  return { created, issues };
}


// ===== projects -> seedProjects =====
async function seedProjects(req, ctx, log) {
  const created = {
    projects: 0,
    tasks: 0,
    timesheets: 0,
    issues: 0,
    statusTransitions: 0,
  };
  const issues = [];

  // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function guard(label, r) {
    if (!r || r.status < 200 || r.status > 299) {
      const snippet = r ? String(r.raw || '').slice(0, 80) : 'no response';
      issues.push(`${label}: HTTP ${r ? r.status : '?'} â€” ${snippet}`);
      return false;
    }
    return true;
  }

  function data(r) {
    return r && r.body && r.body.data != null ? r.body.data : null;
  }

  // Safely extract a numeric Long id from a DTO field (may be returned as string per JacksonConfig)
  function toUserId(val) {
    return val != null ? Number(val) : null;
  }

  // â”€â”€ derive userId for timesheets â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // ctx.userIds is array of raw Long ids (numbers or numeric strings)
  const rawUserIds = Array.isArray(ctx.userIds) && ctx.userIds.length > 0 ? ctx.userIds : [];
  // We need a Long userId for CreateTimesheetRequest.userId
  // Fall back to 1 only as last resort â€” the seeder notes warn this may 404; we push to issues then.
  const tsUserId = rawUserIds.length > 0 ? Number(rawUserIds[0]) : 1;

  // â”€â”€ customerUid (optional on project) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const customerUids = Array.isArray(ctx.customerUids) && ctx.customerUids.length > 0
    ? ctx.customerUids : [];
  const customerUid0 = customerUids[0] || null;
  const customerUid1 = customerUids[1] || null;

  // â”€â”€ productUids for issue-to-project â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const productUids = Array.isArray(ctx.productUids) && ctx.productUids.length > 0
    ? ctx.productUids : [];

  // â”€â”€ Project definitions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // We create 5 projects and walk them through distinct lifecycle states so
  // every ProjectStatus (DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED) appears
  // on screen, plus one ARCHIVED (MasterStatus).
  //
  // [0] â†’ stays DRAFT (default)
  // [1] â†’ ACTIVE
  // [2] â†’ ACTIVE â†’ ON_HOLD
  // [3] â†’ ACTIVE â†’ COMPLETED
  // [4] â†’ ACTIVE â†’ CANCELLED
  // (we also archive project[0] via /archive at the end to show ARCHIVED master-status)

  const projectDefs = [
    {
      name: 'ERP System Implementation â€” Phase 1',
      customerUid: customerUid0,
      plannedStartDate: '2026-01-06',
      plannedEndDate: '2026-06-30',
      budgetAmount: '18500000.0000',
      notes: 'Full ERP rollout for head office. Phase 1 covers GL, AP, AR and procurement.',
    },
    {
      name: 'Warehouse Expansion â€” Mikocheni',
      customerUid: customerUid1,
      plannedStartDate: '2026-02-03',
      plannedEndDate: '2026-09-30',
      budgetAmount: '45000000.0000',
      notes: 'Civil works, racking installation and automation of the Mikocheni DC.',
    },
    {
      name: 'Annual Tax Compliance Review 2026',
      customerUid: customerUid0,
      plannedStartDate: '2026-03-01',
      plannedEndDate: '2026-05-31',
      budgetAmount: '3200000.0000',
      notes: 'TRA audit support, transfer-pricing documentation and TDS reconciliation.',
    },
    {
      name: 'Fibre Network Upgrade â€” Dar es Salaam CBD',
      customerUid: customerUid1,
      plannedStartDate: '2026-01-15',
      plannedEndDate: '2026-12-31',
      budgetAmount: '120000000.0000',
      notes: '24-strand fibre backbone; covers 14 exchange points across the CBD.',
    },
    {
      name: 'Training & Change Management â€” ERP Wave 2',
      customerUid: customerUid0,
      plannedStartDate: '2026-04-01',
      plannedEndDate: '2026-07-31',
      budgetAmount: '5000000.0000',
      notes: 'End-user training, super-user certification and hyper-care support.',
    },
  ];

  // â”€â”€ Create all 5 projects â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('projects: creating 5 projects');
  const projectUids = [];
  const projectIds  = [];

  for (let i = 0; i < projectDefs.length; i++) {
    const def = projectDefs[i];
    const body = {
      companyUid:       ctx.companyUid,
      branchUid:        ctx.branchUid,
      name:             def.name,
      customerUid:      def.customerUid,      // null is fine â€” field is optional
      managerUid:       null,
      plannedStartDate: def.plannedStartDate,
      plannedEndDate:   def.plannedEndDate,
      budgetAmount:     def.budgetAmount,
      notes:            def.notes,
    };

    const r = await req('POST', '/projects', ctx.token, body);
    if (!guard(`project[${i}] create "${def.name}"`, r)) {
      projectUids.push(null);
      projectIds.push(null);
      continue;
    }
    const d = data(r);
    if (!d) {
      issues.push(`project[${i}] create: missing data in response`);
      projectUids.push(null);
      projectIds.push(null);
      continue;
    }
    projectUids.push(d.uid);
    projectIds.push(d.id);
    created.projects++;
    log(`  created project[${i}]: ${d.projectNumber} â€” ${d.name}`);
  }

  // â”€â”€ Lifecycle transitions â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // project[1] â†’ ACTIVE
  if (projectUids[1]) {
    log('projects: activating project[1]');
    const r = await req('PATCH', `/projects/uid/${projectUids[1]}/status?targetStatus=ACTIVE`, ctx.token, null);
    if (guard('project[1] â†’ ACTIVE', r)) created.statusTransitions++;
  }

  // project[2] â†’ ACTIVE â†’ ON_HOLD
  if (projectUids[2]) {
    log('projects: activating project[2]');
    const r1 = await req('PATCH', `/projects/uid/${projectUids[2]}/status?targetStatus=ACTIVE`, ctx.token, null);
    if (guard('project[2] â†’ ACTIVE', r1)) {
      created.statusTransitions++;
      log('projects: holding project[2]');
      const r2 = await req('PATCH', `/projects/uid/${projectUids[2]}/status?targetStatus=ON_HOLD`, ctx.token, null);
      if (guard('project[2] â†’ ON_HOLD', r2)) created.statusTransitions++;
    }
  }

  // project[3] â†’ ACTIVE â†’ COMPLETED
  if (projectUids[3]) {
    log('projects: activating project[3]');
    const r1 = await req('PATCH', `/projects/uid/${projectUids[3]}/status?targetStatus=ACTIVE`, ctx.token, null);
    if (guard('project[3] â†’ ACTIVE', r1)) {
      created.statusTransitions++;
      log('projects: completing project[3]');
      const r2 = await req('PATCH', `/projects/uid/${projectUids[3]}/status?targetStatus=COMPLETED`, ctx.token, null);
      if (guard('project[3] â†’ COMPLETED', r2)) created.statusTransitions++;
    }
  }

  // project[4] â†’ ACTIVE â†’ CANCELLED
  if (projectUids[4]) {
    log('projects: activating project[4]');
    const r1 = await req('PATCH', `/projects/uid/${projectUids[4]}/status?targetStatus=ACTIVE`, ctx.token, null);
    if (guard('project[4] â†’ ACTIVE', r1)) {
      created.statusTransitions++;
      log('projects: cancelling project[4]');
      const r2 = await req('PATCH', `/projects/uid/${projectUids[4]}/status?targetStatus=CANCELLED`, ctx.token, null);
      if (guard('project[4] â†’ CANCELLED', r2)) created.statusTransitions++;
    }
  }

  // project[0] stays DRAFT; archive it (MasterStatus soft-delete) so ARCHIVED shows on screen
  if (projectUids[0]) {
    log('projects: archiving project[0] (MasterStatus â†’ ARCHIVED)');
    const r = await req('PATCH', `/projects/uid/${projectUids[0]}/archive`, ctx.token, null);
    guard('project[0] archive', r);
    // not counting as a statusTransition â€” this is MasterStatus, not ProjectStatus
  }

  // â”€â”€ Tasks â€” attach to projects that allow tagging (ACTIVE / ON_HOLD) â”€â”€â”€â”€â”€
  // project[1] = ACTIVE, project[2] = ON_HOLD â€” both allowed.
  // project[3] = COMPLETED, project[4] = CANCELLED â€” no tasks (tagging blocked).
  // We add tasks to project[1] (3 tasks) and project[2] (2 tasks).

  const taskDefs = {
    1: [
      { taskCode: 'WH-PLAN',  name: 'Site Survey & Planning',          plannedHours: '80.00',  billable: true  },
      { taskCode: 'WH-CIVIL', name: 'Civil Works & Foundation',        plannedHours: '320.00', billable: true  },
      { taskCode: 'WH-COORD', name: 'Project Coordination & Reporting', plannedHours: '40.00', billable: false },
    ],
    2: [
      { taskCode: 'TAX-PREP', name: 'Tax Workings Preparation',  plannedHours: '60.00', billable: true  },
      { taskCode: 'TAX-REV',  name: 'Partner / Manager Review',  plannedHours: '16.00', billable: false },
    ],
  };

  // Map: projectIndex â†’ array of task uids (for timesheets + issues)
  const taskUidsByProject = {};

  for (const [pIdx, tasks] of Object.entries(taskDefs)) {
    const pUid = projectUids[Number(pIdx)];
    if (!pUid) continue;

    taskUidsByProject[pIdx] = [];
    log(`projects: creating ${tasks.length} tasks for project[${pIdx}]`);

    for (const t of tasks) {
      const r = await req(
        'POST',
        `/project-tasks/project/uid/${pUid}`,
        ctx.token,
        {
          taskCode:     t.taskCode,
          name:         t.name,
          plannedHours: t.plannedHours,
          billable:     t.billable,
        }
      );
      if (!guard(`task "${t.taskCode}" on project[${pIdx}]`, r)) continue;
      const d = data(r);
      if (!d) { issues.push(`task "${t.taskCode}": missing data`); continue; }
      taskUidsByProject[pIdx].push(d.uid);
      created.tasks++;
      log(`  created task: ${d.taskCode} â€” ${d.name}`);
    }

    // Deactivate the last task in each project to show INACTIVE state on screen
    const deactivateUid = taskUidsByProject[pIdx][taskUidsByProject[pIdx].length - 1];
    if (deactivateUid) {
      log(`  deactivating last task of project[${pIdx}]`);
      const r = await req('PATCH', `/project-tasks/uid/${deactivateUid}/deactivate`, ctx.token, null);
      guard(`deactivate task on project[${pIdx}]`, r);
    }
  }

  // â”€â”€ Timesheets â€” record against ACTIVE (project[1]) and ON_HOLD (project[2]) â”€â”€
  // CreateTimesheetRequest.userId is a raw Long (NOT a uid).

  const timesheetDefs = [
    // project[1] timesheets
    {
      pIdx: 1,
      taskUidIdx: 0,  // WH-PLAN task
      workDate: '2026-02-10',
      hours: '8.00',
      billable: true,
      plannedRateAmount: '35000.0000',
      notes: 'Site survey â€” initial walkthrough and measurements',
    },
    {
      pIdx: 1,
      taskUidIdx: 0,
      workDate: '2026-02-11',
      hours: '6.50',
      billable: true,
      plannedRateAmount: '35000.0000',
      notes: 'Site survey â€” structural assessment report',
    },
    {
      pIdx: 1,
      taskUidIdx: 2,  // WH-COORD task (index 2 â€” but we deactivated it; timesheet on project level if absent)
      workDate: '2026-02-17',
      hours: '3.00',
      billable: false,
      plannedRateAmount: null,
      notes: 'Weekly status report and client meeting minutes',
    },
    // project[2] timesheets
    {
      pIdx: 2,
      taskUidIdx: 0,  // TAX-PREP task
      workDate: '2026-03-05',
      hours: '7.00',
      billable: true,
      plannedRateAmount: '45000.0000',
      notes: 'VAT reconciliation FY2025 â€” Q1 and Q2',
    },
    {
      pIdx: 2,
      taskUidIdx: null,  // project-level timesheet (no task)
      workDate: '2026-03-12',
      hours: '2.00',
      billable: false,
      plannedRateAmount: null,
      notes: 'TRA query call â€” general project admin',
    },
  ];

  log('projects: recording timesheets');
  for (const ts of timesheetDefs) {
    const pUid = projectUids[ts.pIdx];
    if (!pUid) continue;

    // Resolve task uid â€” if the taskUidIdx falls on the deactivated (last) task, use project-level
    const tasksForP = taskUidsByProject[ts.pIdx] || [];
    let taskUid = null;
    if (ts.taskUidIdx != null) {
      // Only attach to a task if it's not the last one (last was deactivated)
      const lastIdx = tasksForP.length - 1;
      taskUid = ts.taskUidIdx < lastIdx ? (tasksForP[ts.taskUidIdx] || null) : null;
    }

    const body = {
      projectTaskUid:    taskUid,
      userId:            tsUserId,
      workDate:          ts.workDate,
      hours:             ts.hours,
      billable:          ts.billable,
      plannedRateAmount: ts.plannedRateAmount,
      notes:             ts.notes,
    };

    const r = await req('POST', `/project-timesheets/project/uid/${pUid}`, ctx.token, body);
    if (!guard(`timesheet ${ts.workDate} on project[${ts.pIdx}]`, r)) continue;
    const d = data(r);
    if (!d) { issues.push(`timesheet ${ts.workDate}: missing data`); continue; }
    created.timesheets++;
    log(`  recorded timesheet: ${ts.workDate} ${ts.hours}h billable=${ts.billable}`);
  }

  // â”€â”€ Issue materials to project â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Only allowed when projectStatus IN (ACTIVE, ON_HOLD).
  // project[1] = ACTIVE, project[2] = ON_HOLD. project[3]/[4] are terminal.
  // Guard: skip if no productUids were provided by Tier-1 seeder.

  if (productUids.length === 0) {
    log('projects: skipping issue-to-project â€” no productUids in ctx (stock seeder may not have run)');
    issues.push('issue-to-project skipped: ctx.productUids is empty â€” run stock/products seeder first');
  } else {
    // Issue 1: two product lines â†’ project[1] (ACTIVE), task WH-CIVIL (index 1)
    const p1Uid = projectUids[1];
    if (p1Uid) {
      const tasksP1   = taskUidsByProject[1] || [];
      const civilTask = tasksP1[1] || null;  // WH-CIVIL (non-deactivated)

      const issueLines = [
        { productUid: productUids[0], qty: '50.0000' },
      ];
      if (productUids.length >= 2) {
        issueLines.push({ productUid: productUids[1], qty: '10.0000' });
      }

      log('projects: issuing materials to project[1] (ACTIVE)');
      const r = await req('POST', '/project-issues', ctx.token, {
        companyUid:     ctx.companyUid,
        branchUid:      ctx.branchUid,
        projectUid:     p1Uid,
        projectTaskUid: civilTask,
        lines:          issueLines,
        issueDate:      '2026-02-20',
        reason:         'Racking materials â€” first delivery',
      });
      if (guard('issue-to-project[1]', r)) {
        const d = data(r);
        if (d) {
          created.issues++;
          log(`  issue posted: ${d.issueNumber}, totalValue=${d.totalValue} ${d.currency}`);
        }
      }
    }

    // Issue 2: one product line â†’ project[2] (ON_HOLD)
    const p2Uid = projectUids[2];
    if (p2Uid) {
      log('projects: issuing materials to project[2] (ON_HOLD)');
      const r = await req('POST', '/project-issues', ctx.token, {
        companyUid:     ctx.companyUid,
        branchUid:      ctx.branchUid,
        projectUid:     p2Uid,
        projectTaskUid: null,
        lines:          [{ productUid: productUids[0], qty: '5.0000' }],
        issueDate:      '2026-03-10',
        reason:         'Consumables for tax compliance fieldwork',
      });
      if (guard('issue-to-project[2]', r)) {
        const d = data(r);
        if (d) {
          created.issues++;
          log(`  issue posted: ${d.issueNumber}, totalValue=${d.totalValue} ${d.currency}`);
        }
      }
    }
  }

  // â”€â”€ Costing reports â€” read-only, just verify they respond 2xx â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // P&L for project[1] (ACTIVE â€” most interesting; has tasks + potential issues)
  if (projectUids[1]) {
    log('projects: fetching P&L report for project[1]');
    const r = await req('GET', `/project-costing/projects/uid/${projectUids[1]}/pnl`, ctx.token, null);
    guard('project[1] P&L report', r);
  }

  // Cross-project WIP report
  if (ctx.companyId) {
    log('projects: fetching cross-project WIP report');
    const r = await req('GET', `/project-costing/wip?companyId=${ctx.companyId}`, ctx.token, null);
    guard('WIP report', r);
  }

  log(`projects seed complete â€” projects:${created.projects} tasks:${created.tasks} ` +
      `timesheets:${created.timesheets} issues:${created.issues} ` +
      `transitions:${created.statusTransitions} issues-list:${issues.length}`);

  return { created, issues };
}


// ===== budgeting -> seedBudgeting =====
async function seedBudgeting(req, ctx, log) {
  const created = {
    fiscalYears: 0,
    accounts: 0,
    costCentreValues: 0,
    budgets: 0,
    budgetVersions: 0,
    linesUpserted: 0,
    submits: 0,
    approvals: 0,
    rejections: 0,
    recalls: 0,
    replanVersions: 0,
    reportsFetched: 0,
  };
  const issues = [];

  function push(status, raw, label) {
    issues.push(`[${status}] ${label}: ${String(raw || '').slice(0, 80)}`);
  }

  // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function ok(r) { return r && r.status >= 200 && r.status < 300; }
  function data(r) { return r && r.body && r.body.data; }

  const companyId = ctx.companyId ? Number(ctx.companyId) : null;
  const companyUid = ctx.companyUid || null;

  if (!companyId || !companyUid) {
    issues.push('[BLOCKER] seedBudgeting: ctx.companyId or ctx.companyUid missing â€” cannot proceed');
    return { created, issues };
  }

  log('budgeting: resolving fiscal year for 2026...');

  // â”€â”€ 1. Fiscal year â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let fiscalYearUid = null;
  let fiscalYearId = null;

  const fyList = await req('GET', `/gl/periods/fiscal-years?companyId=${companyId}`, ctx.token, null);
  if (!ok(fyList)) {
    push(fyList.status, fyList.raw, 'list fiscal years');
  } else {
    const years = Array.isArray(data(fyList)) ? data(fyList) : [];
    // prefer an OPEN year that covers 2026
    const fy2026 = years.find(y => y.yearCode && y.yearCode.includes('2026') && y.status === 'OPEN')
      || years.find(y => y.yearCode && y.yearCode.includes('2026'))
      || years.find(y => y.status === 'OPEN')
      || years[0];
    if (fy2026) {
      fiscalYearUid = fy2026.uid;
      fiscalYearId = fy2026.id;
      log(`budgeting: using fiscal year uid=${fiscalYearUid} code=${fy2026.yearCode}`);
    }
  }

  if (!fiscalYearUid) {
    log('budgeting: no fiscal year found â€” creating FY2026...');
    const fyCreate = await req('POST', '/gl/periods/fiscal-years', ctx.token, {
      companyUid,
      yearCode: 'FY2026',
      startMonth: 1,
      calendarYear: 2026,
    });
    if (!ok(fyCreate)) {
      push(fyCreate.status, fyCreate.raw, 'create fiscal year FY2026');
      issues.push('[BLOCKER] seedBudgeting: cannot obtain fiscal year â€” aborting');
      return { created, issues };
    }
    fiscalYearUid = data(fyCreate).uid;
    fiscalYearId  = data(fyCreate).id;
    created.fiscalYears += 1;
    log(`budgeting: created fiscal year uid=${fiscalYearUid}`);
  }

  // â”€â”€ 2. Fiscal periods (need uids for DIRECT lines) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('budgeting: fetching fiscal periods...');
  const periodsList = await req('GET', `/gl/periods?companyId=${companyId}`, ctx.token, null);
  let allPeriods = [];
  if (!ok(periodsList)) {
    push(periodsList.status, periodsList.raw, 'list fiscal periods');
  } else {
    const raw = data(periodsList);
    allPeriods = Array.isArray(raw) ? raw : [];
  }

  // filter to this fiscal year; sort by periodNo
  const fyPeriods = allPeriods
    .filter(p => String(p.fiscalYearId) === String(fiscalYearId))
    .sort((a, b) => a.periodNo - b.periodNo);

  log(`budgeting: fiscal periods for FY: ${fyPeriods.length}`);
  const hasTwelvePeriods = fyPeriods.length === 12;

  // â”€â”€ 3. Chart of accounts â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('budgeting: fetching chart of accounts...');
  const acctList = await req('GET', `/gl/accounts?companyId=${companyId}&size=200`, ctx.token, null);
  let activeAccounts = [];
  if (!ok(acctList)) {
    push(acctList.status, acctList.raw, 'list accounts');
  } else {
    const raw = data(acctList);
    const arr = Array.isArray(raw) ? raw : (raw && Array.isArray(raw.content) ? raw.content : []);
    activeAccounts = arr.filter(a => a.active !== false);
  }

  // We need at least 4 accounts (ideally EXPENSE + INCOME types for a realistic budget)
  const expenseAccts = activeAccounts.filter(a => a.accountType === 'EXPENSE');
  const incomeAccts  = activeAccounts.filter(a => a.accountType === 'INCOME');

  const neededExpense = Math.max(0, 3 - expenseAccts.length);
  const neededIncome  = Math.max(0, 1 - incomeAccts.length);

  const expenseSeeds = [
    { accountCode: 'BUD-6010', name: 'Staff Costs',           accountType: 'EXPENSE' },
    { accountCode: 'BUD-6020', name: 'Rent and Utilities',    accountType: 'EXPENSE' },
    { accountCode: 'BUD-6030', name: 'Marketing and Events',  accountType: 'EXPENSE' },
  ];
  const incomeSeeds = [
    { accountCode: 'BUD-4010', name: 'Service Revenue',       accountType: 'INCOME' },
  ];

  for (let i = 0; i < neededExpense; i++) {
    const s = expenseSeeds[i];
    const r = await req('POST', '/gl/accounts', ctx.token, { companyUid, ...s });
    if (ok(r) && data(r)) {
      expenseAccts.push(data(r));
      activeAccounts.push(data(r));
      created.accounts += 1;
      log(`budgeting: created account ${s.accountCode}`);
    } else {
      push(r.status, r.raw, `create account ${s.accountCode}`);
    }
  }
  for (let i = 0; i < neededIncome; i++) {
    const s = incomeSeeds[i];
    const r = await req('POST', '/gl/accounts', ctx.token, { companyUid, ...s });
    if (ok(r) && data(r)) {
      incomeAccts.push(data(r));
      activeAccounts.push(data(r));
      created.accounts += 1;
      log(`budgeting: created account ${s.accountCode}`);
    } else {
      push(r.status, r.raw, `create account ${s.accountCode}`);
    }
  }

  if (expenseAccts.length === 0 && incomeAccts.length === 0) {
    issues.push('[BLOCKER] seedBudgeting: no active GL accounts available for budget lines â€” aborting');
    return { created, issues };
  }

  // pick up to 3 expense + 1 income for lines
  const lineAccts = [
    ...expenseAccts.slice(0, 3),
    ...incomeAccts.slice(0, 1),
  ].filter(Boolean);

  const spreadAcct = expenseAccts[0] || lineAccts[0];

  // â”€â”€ 4. Cost-centre dimension values â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('budgeting: resolving cost-centre dimension...');
  let costCentreDimUid = null;
  const dimList = await req('GET', `/dimensions?companyId=${companyId}`, ctx.token, null);
  if (!ok(dimList)) {
    push(dimList.status, dimList.raw, 'list dimensions');
  } else {
    const dims = Array.isArray(data(dimList)) ? data(dimList) : [];
    const ccDim = dims.find(d => d.slot === 'COST_CENTRE') || dims[0];
    if (ccDim) costCentreDimUid = ccDim.uid;
  }

  const ccValues = []; // will hold up to 3 { uid, name }

  if (costCentreDimUid) {
    // list existing values
    const cvList = await req('GET', `/dimension-values?dimensionUid=${costCentreDimUid}&size=50`, ctx.token, null);
    if (ok(cvList)) {
      const vals = Array.isArray(data(cvList)) ? data(cvList) : [];
      const active = vals.filter(v => v.active !== false);
      for (const v of active.slice(0, 3)) ccValues.push({ uid: v.uid, name: v.name });
    }

    const ccSeeds = [
      { code: 'CC-ADMIN',  name: 'Administration' },
      { code: 'CC-SALES',  name: 'Sales & Distribution' },
      { code: 'CC-OPS',    name: 'Operations' },
    ];

    let ci = 0;
    while (ccValues.length < 3 && ci < ccSeeds.length) {
      const s = ccSeeds[ci++];
      const r = await req('POST', '/dimension-values', ctx.token, {
        dimensionUid: costCentreDimUid,
        code: s.code,
        name: s.name,
        parentUid: null,
      });
      if (ok(r) && data(r)) {
        ccValues.push({ uid: data(r).uid, name: s.name });
        created.costCentreValues += 1;
        log(`budgeting: created cost-centre value ${s.code}`);
      } else {
        push(r.status, r.raw, `create cost-centre ${s.code}`);
        break;
      }
    }
  } else {
    log('budgeting: COST_CENTRE dimension not found â€” budgets will be company-wide only');
  }

  // â”€â”€ helper: build DIRECT line inputs for a version â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Uses the first N periods and all lineAccts
  function buildDirectLines(periodsToUse, amountBase) {
    const lines = [];
    for (const acct of lineAccts) {
      const period = periodsToUse[0]; // at least one period
      if (!period) continue;
      lines.push({
        accountUid: acct.uid,
        fiscalPeriodUid: period.uid,
        amount: amountBase + (lines.length * 500000),
        lineMemo: `Seeded line â€“ ${acct.name || acct.accountCode}`,
      });
    }
    return lines;
  }

  // â”€â”€ helper: upsert lines then submit â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  async function addDirectLinesAndSubmit(versionUid, periodsToUse, amountBase, label) {
    if (periodsToUse.length === 0) {
      issues.push(`[MEDIUM] seedBudgeting: no fiscal periods available for ${label} â€” cannot add lines or submit`);
      return false;
    }
    const lines = buildDirectLines(periodsToUse, amountBase);
    if (lines.length === 0) {
      issues.push(`[MEDIUM] seedBudgeting: no line accounts for ${label}`);
      return false;
    }
    const upsR = await req('PUT', `/budget-versions/uid/${versionUid}/lines`, ctx.token, {
      mode: 'DIRECT',
      lines,
    });
    if (!ok(upsR)) {
      push(upsR.status, upsR.raw, `upsert lines ${label}`);
      return false;
    }
    created.linesUpserted += 1;

    const subR = await req('POST', `/budget-versions/uid/${versionUid}/submit`, ctx.token, null);
    if (!ok(subR)) {
      push(subR.status, subR.raw, `submit ${label}`);
      return false;
    }
    created.submits += 1;
    return true;
  }

  // â”€â”€ Budget A: Company-wide â€” full DRAFTâ†’SUBMITâ†’APPROVE lifecycle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('budgeting: creating Budget A (company-wide, FY2026)...');
  const budgetAResp = await req('POST', '/budgets', ctx.token, {
    companyId,
    name: 'FY2026 Company-Wide Operating Budget',
    fiscalYearUid,
    costCentreValueUid: null,
    notes: 'Annual operating budget for the full company. Seeded by QA.',
    initialVersionLabel: 'Initial Plan',
  });

  let budgetAUid = null;
  let budgetAV1Uid = null;

  if (!ok(budgetAResp)) {
    push(budgetAResp.status, budgetAResp.raw, 'create Budget A');
  } else {
    budgetAUid = data(budgetAResp).uid;
    created.budgets += 1;
    // initial version is embedded in the response
    const versionsA = data(budgetAResp).versions || [];
    budgetAV1Uid = versionsA.length > 0 ? versionsA[0].uid : null;
    log(`budgeting: Budget A uid=${budgetAUid} v1=${budgetAV1Uid}`);
  }

  // If we did not get v1 uid from create response, fetch it
  if (budgetAUid && !budgetAV1Uid) {
    const fetchA = await req('GET', `/budgets/uid/${budgetAUid}`, ctx.token, null);
    if (ok(fetchA)) {
      const vlist = data(fetchA).versions || [];
      if (vlist.length > 0) budgetAV1Uid = vlist[0].uid;
    }
  }

  // Add DIRECT lines to v1 then submit+approve â†’ APPROVED
  if (budgetAV1Uid) {
    created.budgetVersions += 1;
    const submitted = await addDirectLinesAndSubmit(budgetAV1Uid, fyPeriods.slice(0, 6), 12000000, 'BudgetA-v1');
    if (submitted) {
      const appR = await req('POST', `/budget-versions/uid/${budgetAV1Uid}/approve`, ctx.token, {
        note: 'Board approved FY2026 company-wide operating budget.',
      });
      if (!ok(appR)) {
        push(appR.status, appR.raw, 'approve BudgetA-v1');
      } else {
        created.approvals += 1;
        log('budgeting: Budget A v1 â†’ APPROVED');
      }
    }
  }

  // â”€â”€ Budget A â€” replan: v2 using ANNUAL_SPREAD, then SUBMITTED (pending approval) â”€â”€
  if (budgetAUid && hasTwelvePeriods && spreadAcct) {
    log('budgeting: creating Budget A v2 (re-plan with annual spread)...');
    const v2Resp = await req('POST', `/budgets/uid/${budgetAUid}/versions`, ctx.token, {
      label: 'Revised Plan Q3',
      seedFromVersionUid: null,
    });
    if (!ok(v2Resp)) {
      push(v2Resp.status, v2Resp.raw, 'create BudgetA v2');
    } else {
      const v2Uid = data(v2Resp).uid;
      created.budgetVersions += 1;
      created.replanVersions += 1;
      log(`budgeting: Budget A v2 uid=${v2Uid}`);

      // ANNUAL_SPREAD mode â€” spreads across 12 periods
      const spreadR = await req('PUT', `/budget-versions/uid/${v2Uid}/lines`, ctx.token, {
        mode: 'ANNUAL_SPREAD',
        annualAmount: 180000000,
        accountUid: spreadAcct.uid,
      });
      if (!ok(spreadR)) {
        push(spreadR.status, spreadR.raw, 'annual spread BudgetA-v2');
      } else {
        created.linesUpserted += 1;
        // submit but do NOT approve â€” leaves it SUBMITTED so QA sees that state
        const subR = await req('POST', `/budget-versions/uid/${v2Uid}/submit`, ctx.token, null);
        if (!ok(subR)) {
          push(subR.status, subR.raw, 'submit BudgetA-v2');
        } else {
          created.submits += 1;
          log('budgeting: Budget A v2 â†’ SUBMITTED (pending approval)');
        }
      }
    }
  } else if (budgetAUid && !hasTwelvePeriods) {
    log('budgeting: skipping ANNUAL_SPREAD v2 â€” fiscal year does not have exactly 12 periods');
  }

  // â”€â”€ Budget B: Cost-centre scoped (Administration) â€” DRAFTâ†’SUBMITâ†’REJECT lifecycle â”€â”€
  let budgetBUid = null;
  const ccAdmin = ccValues[0];

  if (ccAdmin) {
    log(`budgeting: creating Budget B (cost-centre ${ccAdmin.name})...`);
    const budgetBResp = await req('POST', '/budgets', ctx.token, {
      companyId,
      name: `FY2026 Administration Department Budget`,
      fiscalYearUid,
      costCentreValueUid: ccAdmin.uid,
      notes: 'Departmental budget for Administration. Submitted for review.',
      initialVersionLabel: 'Draft for Review',
    });

    if (!ok(budgetBResp)) {
      push(budgetBResp.status, budgetBResp.raw, 'create Budget B');
    } else {
      budgetBUid = data(budgetBResp).uid;
      created.budgets += 1;
      const versionsB = data(budgetBResp).versions || [];
      let budgetBV1Uid = versionsB.length > 0 ? versionsB[0].uid : null;

      if (budgetBUid && !budgetBV1Uid) {
        const fetchB = await req('GET', `/budgets/uid/${budgetBUid}`, ctx.token, null);
        if (ok(fetchB)) {
          const vlist = data(fetchB).versions || [];
          if (vlist.length > 0) budgetBV1Uid = vlist[0].uid;
        }
      }

      if (budgetBV1Uid) {
        created.budgetVersions += 1;
        log(`budgeting: Budget B uid=${budgetBUid} v1=${budgetBV1Uid}`);
        // Add lines + submit
        const submitted = await addDirectLinesAndSubmit(budgetBV1Uid, fyPeriods.slice(0, 3), 5000000, 'BudgetB-v1');
        if (submitted) {
          // REJECT â€” so QA sees a terminal REJECTED state
          const rejR = await req('POST', `/budget-versions/uid/${budgetBV1Uid}/reject`, ctx.token, {
            reason: 'Budget figures exceed departmental ceiling. Please revise staffing line and resubmit.',
          });
          if (!ok(rejR)) {
            push(rejR.status, rejR.raw, 'reject BudgetB-v1');
          } else {
            created.rejections += 1;
            log('budgeting: Budget B v1 â†’ REJECTED');
          }
        }
      }
    }
  }

  // â”€â”€ Budget C: Cost-centre scoped (Sales) â€” DRAFTâ†’SUBMITâ†’RECALLâ†’RESUBMITâ†’APPROVE â”€â”€
  const ccSales = ccValues[1];
  let budgetCUid = null;

  if (ccSales) {
    log(`budgeting: creating Budget C (cost-centre ${ccSales.name})...`);
    const budgetCResp = await req('POST', '/budgets', ctx.token, {
      companyId,
      name: `FY2026 Sales and Distribution Budget`,
      fiscalYearUid,
      costCentreValueUid: ccSales.uid,
      notes: 'Sales budget including trade marketing allocations.',
      initialVersionLabel: 'Sales Plan v1',
    });

    if (!ok(budgetCResp)) {
      push(budgetCResp.status, budgetCResp.raw, 'create Budget C');
    } else {
      budgetCUid = data(budgetCResp).uid;
      created.budgets += 1;
      const versionsC = data(budgetCResp).versions || [];
      let budgetCV1Uid = versionsC.length > 0 ? versionsC[0].uid : null;

      if (budgetCUid && !budgetCV1Uid) {
        const fetchC = await req('GET', `/budgets/uid/${budgetCUid}`, ctx.token, null);
        if (ok(fetchC)) {
          const vlist = data(fetchC).versions || [];
          if (vlist.length > 0) budgetCV1Uid = vlist[0].uid;
        }
      }

      if (budgetCV1Uid) {
        created.budgetVersions += 1;
        log(`budgeting: Budget C uid=${budgetCUid} v1=${budgetCV1Uid}`);

        // Add lines + submit
        const submitted = await addDirectLinesAndSubmit(budgetCV1Uid, fyPeriods.slice(0, 4), 8500000, 'BudgetC-v1');
        if (submitted) {
          // RECALL â€” returns to DRAFT
          const recR = await req('POST', `/budget-versions/uid/${budgetCV1Uid}/recall`, ctx.token, null);
          if (!ok(recR)) {
            push(recR.status, recR.raw, 'recall BudgetC-v1');
          } else {
            created.recalls += 1;
            log('budgeting: Budget C v1 â†’ recalled back to DRAFT');

            // Add another line (simulate revision after recall) â€” upsert replaces wholesale
            if (fyPeriods.length > 0) {
              const revisedLines = [];
              for (const acct of lineAccts.slice(0, 2)) {
                revisedLines.push({
                  accountUid: acct.uid,
                  fiscalPeriodUid: fyPeriods[0].uid,
                  amount: 9200000,
                  lineMemo: 'Revised after recall',
                });
              }
              if (revisedLines.length > 0) {
                const revUpsR = await req('PUT', `/budget-versions/uid/${budgetCV1Uid}/lines`, ctx.token, {
                  mode: 'DIRECT',
                  lines: revisedLines,
                });
                if (!ok(revUpsR)) push(revUpsR.status, revUpsR.raw, 'revised upsert BudgetC-v1');
                else created.linesUpserted += 1;
              }
            }

            // Resubmit
            const resub = await req('POST', `/budget-versions/uid/${budgetCV1Uid}/submit`, ctx.token, null);
            if (!ok(resub)) {
              push(resub.status, resub.raw, 'resubmit BudgetC-v1');
            } else {
              created.submits += 1;
              log('budgeting: Budget C v1 â†’ SUBMITTED (after recall+revision)');

              // Approve
              const appC = await req('POST', `/budget-versions/uid/${budgetCV1Uid}/approve`, ctx.token, {
                note: 'Approved after revision. Allocations within ceiling.',
              });
              if (!ok(appC)) {
                push(appC.status, appC.raw, 'approve BudgetC-v1');
              } else {
                created.approvals += 1;
                log('budgeting: Budget C v1 â†’ APPROVED');
              }
            }
          }
        }
      }
    }
  }

  // â”€â”€ Budget D: Cost-centre scoped (Operations) â€” v1 APPROVED then v2 DRAFT (seeded) â”€â”€
  // This exercises the SUPERSEDED path: approve v2 â†’ v1 becomes SUPERSEDED
  const ccOps = ccValues[2];
  let budgetDUid = null;

  if (ccOps) {
    log(`budgeting: creating Budget D (cost-centre ${ccOps.name})...`);
    const budgetDResp = await req('POST', '/budgets', ctx.token, {
      companyId,
      name: `FY2026 Operations Budget`,
      fiscalYearUid,
      costCentreValueUid: ccOps.uid,
      notes: 'Operations department budget â€” includes logistics and facilities.',
      initialVersionLabel: 'Ops Plan v1',
    });

    if (!ok(budgetDResp)) {
      push(budgetDResp.status, budgetDResp.raw, 'create Budget D');
    } else {
      budgetDUid = data(budgetDResp).uid;
      created.budgets += 1;
      const versionsD = data(budgetDResp).versions || [];
      let budgetDV1Uid = versionsD.length > 0 ? versionsD[0].uid : null;

      if (budgetDUid && !budgetDV1Uid) {
        const fetchD = await req('GET', `/budgets/uid/${budgetDUid}`, ctx.token, null);
        if (ok(fetchD)) {
          const vlist = data(fetchD).versions || [];
          if (vlist.length > 0) budgetDV1Uid = vlist[0].uid;
        }
      }

      if (budgetDV1Uid) {
        created.budgetVersions += 1;
        log(`budgeting: Budget D uid=${budgetDUid} v1=${budgetDV1Uid}`);

        // Add lines + submit + approve v1
        const submitted = await addDirectLinesAndSubmit(budgetDV1Uid, fyPeriods.slice(0, 5), 15000000, 'BudgetD-v1');
        let dV1Approved = false;
        if (submitted) {
          const appD1 = await req('POST', `/budget-versions/uid/${budgetDV1Uid}/approve`, ctx.token, {
            note: 'Ops v1 approved as interim budget.',
          });
          if (!ok(appD1)) {
            push(appD1.status, appD1.raw, 'approve BudgetD-v1');
          } else {
            created.approvals += 1;
            dV1Approved = true;
            log('budgeting: Budget D v1 â†’ APPROVED');
          }
        }

        // Create v2 seeded from v1 â€” leave it as DRAFT
        if (dV1Approved) {
          const v2DResp = await req('POST', `/budgets/uid/${budgetDUid}/versions`, ctx.token, {
            label: 'Mid-Year Revision',
            seedFromVersionUid: budgetDV1Uid,
          });
          if (!ok(v2DResp)) {
            push(v2DResp.status, v2DResp.raw, 'create BudgetD v2 (seeded)');
          } else {
            const dV2Uid = data(v2DResp).uid;
            created.budgetVersions += 1;
            created.replanVersions += 1;
            log(`budgeting: Budget D v2 (seeded from v1) uid=${dV2Uid} â€” left as DRAFT`);
            // QA sees: v1=APPROVED, v2=DRAFT (seeded from v1) â€” demonstrates re-plan flow
          }
        }
      }
    }
  }

  // â”€â”€ 5. Fetch reports so they appear populated in the API response log â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('budgeting: fetching variance report...');
  const varR = await req(
    'GET',
    `/budgeting/variance?companyId=${companyId}&fiscalYearUid=${fiscalYearUid}&fromPeriodNo=1&toPeriodNo=12`,
    ctx.token,
    null
  );
  if (!ok(varR)) {
    push(varR.status, varR.raw, 'variance report');
  } else {
    created.reportsFetched += 1;
    const hdr = data(varR) && data(varR).header;
    log(`budgeting: variance report fetched â€” noApprovedBudget=${hdr ? hdr.noApprovedBudget : 'unknown'}`);
  }

  log('budgeting: fetching departmental-actuals report...');
  const deptR = await req(
    'GET',
    `/budgeting/departmental-actuals?companyId=${companyId}&fiscalYearUid=${fiscalYearUid}&fromPeriodNo=1&toPeriodNo=12`,
    ctx.token,
    null
  );
  if (!ok(deptR)) {
    push(deptR.status, deptR.raw, 'departmental-actuals report');
  } else {
    created.reportsFetched += 1;
    log('budgeting: departmental-actuals report fetched');
  }

  log(`budgeting: done. budgets=${created.budgets} versions=${created.budgetVersions} approvals=${created.approvals} issues=${issues.length}`);
  return { created, issues };
}


// ===== manufacturing -> seedManufacturing =====
async function seedManufacturing(req, ctx, log) {
  const created = {
    units: 0, products: 0, boms: 0, work_orders: 0,
    operations: 0, issued: 0, cost_applied: 0, completed: 0, closed: 0, cancelled: 0
  };
  const issues = [];

  function pushIssue(label, r) {
    const msg = `${label}: status=${r.status} â€” ${String(r.raw || '').slice(0, 80)}`;
    issues.push(msg);
    log(`  [WARN] ${msg}`);
  }

  // â”€â”€ helper: fetch first page of a list, return array or []
  async function listPage(path, token) {
    const r = await req('GET', path, token, null);
    if (r.status >= 300) return [];
    return (r.body && r.body.data) ? (Array.isArray(r.body.data) ? r.body.data : []) : [];
  }

  const token = ctx.token;
  const companyId = ctx.companyId;      // numeric string
  const companyUid = ctx.companyUid;
  const branchUid = ctx.branchUid;

  log('manufacturing: resolving unit of measure...');
  // â”€â”€ 1. Resolve or create a UoM (need a baseUnitUid for product creation)
  let unitUid = null;
  const unitList = await listPage(`/units?companyId=${companyId}&size=5`, token);
  if (unitList.length > 0) {
    unitUid = unitList[0].uid;
    log(`manufacturing: using existing UoM uid=${unitUid} (${unitList[0].symbol || unitList[0].name})`);
  } else {
    log('manufacturing: no UoM found â€” creating KG...');
    const uR = await req('POST', '/units', token, {
      companyUid,
      symbol: 'KG',
      name: 'Kilogram',
      description: 'Weight measure'
    });
    if (uR.status < 300 && uR.body && uR.body.data) {
      unitUid = uR.body.data.uid;
      created.units++;
      log(`manufacturing: created UoM uid=${unitUid}`);
    } else {
      pushIssue('create UoM KG', uR);
    }
  }

  if (!unitUid) {
    issues.push('manufacturing: no UoM available â€” cannot create products; aborting module');
    log('manufacturing: ABORT â€” no UoM');
    return { created, issues };
  }

  // â”€â”€ 2. Create raw-material / component products (BUY leaves)
  // These are the inputs to the BOM: flour, sugar, cocoa, milk-powder, packaging, stabiliser
  log('manufacturing: creating raw-material products...');
  const rawMaterials = [
    { name: 'Wheat Flour',      code: 'MFG-RM-001' },
    { name: 'Refined Sugar',    code: 'MFG-RM-002' },
    { name: 'Cocoa Powder',     code: 'MFG-RM-003' },
    { name: 'Milk Powder',      code: 'MFG-RM-004' },
    { name: 'Packaging Film',   code: 'MFG-RM-005' },
    { name: 'Vanilla Essence',  code: 'MFG-RM-006' },
  ];
  const rawUids = [];
  for (let i = 0; i < rawMaterials.length; i++) {
    const rm = rawMaterials[i];
    const r = await req('POST', '/products', token, {
      companyUid,
      code: rm.code,
      name: rm.name,
      type: 'GOODS',
      sellable: false,
      stockable: true,
      baseUnitUid: unitUid,
      cost: { amount: String(2500 + i * 300), currency: 'TZS' }
    });
    if (r.status < 300 && r.body && r.body.data) {
      rawUids.push(r.body.data.uid);
      created.products++;
      log(`manufacturing: created raw material ${rm.code} uid=${r.body.data.uid}`);
    } else {
      pushIssue(`create raw material ${rm.code}`, r);
      rawUids.push(null);
    }
  }

  // â”€â”€ 3. Create finished-goods products (the things work orders will produce)
  log('manufacturing: creating finished-goods products...');
  const fgDefs = [
    { name: 'Chocolate Biscuit 200g', code: 'MFG-FG-001' },
    { name: 'Vanilla Cookie 250g',    code: 'MFG-FG-002' },
    { name: 'Cocoa Cake Mix 500g',    code: 'MFG-FG-003' },
  ];
  const fgUids = [];
  for (let i = 0; i < fgDefs.length; i++) {
    const fg = fgDefs[i];
    const r = await req('POST', '/products', token, {
      companyUid,
      code: fg.code,
      name: fg.name,
      type: 'GOODS',
      sellable: true,
      stockable: true,
      baseUnitUid: unitUid,
      cost: { amount: String(8500 + i * 1500), currency: 'TZS' }
    });
    if (r.status < 300 && r.body && r.body.data) {
      fgUids.push(r.body.data.uid);
      created.products++;
      log(`manufacturing: created FG ${fg.code} uid=${r.body.data.uid}`);
    } else {
      pushIssue(`create FG ${fg.code}`, r);
      fgUids.push(null);
    }
  }

  // â”€â”€ 4. Create and activate BOMs for each finished-goods product
  // BOM layout:
  //   FG-001 (Chocolate Biscuit): flour(1.2kg) + sugar(0.5kg) + cocoa(0.3kg) + packaging(0.05kg)
  //   FG-002 (Vanilla Cookie):    flour(1.5kg) + sugar(0.6kg) + milk-powder(0.2kg) + vanilla(0.05kg)
  //   FG-003 (Cocoa Cake Mix):    flour(1.8kg) + sugar(0.8kg) + cocoa(0.5kg) + milk-powder(0.3kg)
  log('manufacturing: creating BOMs...');

  // Map raw material indices:  0=flour, 1=sugar, 2=cocoa, 3=milk, 4=pkg, 5=vanilla
  const bomComponentDefs = [
    [ // BOM for FG-001
      { rmIdx: 0, qty: '1.200000' },
      { rmIdx: 1, qty: '0.500000' },
      { rmIdx: 2, qty: '0.300000' },
      { rmIdx: 4, qty: '0.050000' },
    ],
    [ // BOM for FG-002
      { rmIdx: 0, qty: '1.500000' },
      { rmIdx: 1, qty: '0.600000' },
      { rmIdx: 3, qty: '0.200000' },
      { rmIdx: 5, qty: '0.050000' },
    ],
    [ // BOM for FG-003
      { rmIdx: 0, qty: '1.800000' },
      { rmIdx: 1, qty: '0.800000' },
      { rmIdx: 2, qty: '0.500000' },
      { rmIdx: 3, qty: '0.300000' },
    ],
  ];

  const bomUids = [];
  for (let b = 0; b < fgUids.length; b++) {
    const fgUid = fgUids[b];
    if (!fgUid) {
      bomUids.push(null);
      log(`manufacturing: skipping BOM for FG index ${b} â€” product creation failed`);
      continue;
    }

    // Create DRAFT BOM
    const bomR = await req('POST', `/boms?companyId=${companyId}`, token, {
      parentProductUid: fgUid,
      outputQty: 1,
      yieldPercent: 100,
      notes: `BOM v1 for ${fgDefs[b].name}`
    });
    if (bomR.status >= 300 || !bomR.body || !bomR.body.data) {
      pushIssue(`create BOM for FG-${b}`, bomR);
      bomUids.push(null);
      continue;
    }
    const bomUid = bomR.body.data.uid;
    created.boms++;
    log(`manufacturing: created DRAFT BOM uid=${bomUid} for ${fgDefs[b].code}`);

    // Add components
    const compDefs = bomComponentDefs[b];
    let addedComponents = 0;
    for (let c = 0; c < compDefs.length; c++) {
      const cd = compDefs[c];
      const rmUid = rawUids[cd.rmIdx];
      if (!rmUid) {
        log(`manufacturing: skipping BOM component â€” raw material idx ${cd.rmIdx} not available`);
        continue;
      }
      const compR = await req('POST', `/boms/uid/${bomUid}/components`, token, {
        componentProductUid: rmUid,
        qtyPer: cd.qty,
        sourcing: 'BUY',
        scrapPercent: 0
      });
      if (compR.status < 300) {
        addedComponents++;
      } else {
        pushIssue(`add BOM component rmIdx=${cd.rmIdx} to BOM ${bomUid}`, compR);
      }
    }

    if (addedComponents === 0) {
      pushIssue(`BOM ${bomUid} has 0 components â€” cannot activate`, { status: 0, raw: 'all component adds failed' });
      bomUids.push(null);
      continue;
    }

    // Activate BOM
    const actR = await req('POST', `/boms/uid/${bomUid}/activate`, token, {
      effectiveFrom: '2026-01-01'
    });
    if (actR.status < 300) {
      log(`manufacturing: BOM ${bomUid} ACTIVATED (${addedComponents} components)`);
      bomUids.push(bomUid);
    } else {
      pushIssue(`activate BOM ${bomUid}`, actR);
      bomUids.push(null);
    }
  }

  // â”€â”€ helper: create a work order safely
  async function createWO(fgIdx, qty, notes) {
    const fgUid = fgUids[fgIdx];
    if (!fgUid) return null;
    const r = await req('POST', '/work-orders', token, {
      finishedProductUid: fgUid,
      plannedQty: String(qty),
      branchUid,
      plannedDate: '2026-06-20',
      notes
    });
    if (r.status < 300 && r.body && r.body.data) {
      created.work_orders++;
      log(`manufacturing: created WO uid=${r.body.data.uid} status=${r.body.data.status} for ${fgDefs[fgIdx].code}`);
      return r.body.data;
    }
    pushIssue(`create WO for FG-${fgIdx}`, r);
    return null;
  }

  // â”€â”€ helper: release a work order
  async function releaseWO(woUid) {
    const r = await req('POST', `/work-orders/uid/${woUid}/release`, token, null);
    if (r.status < 300 && r.body && r.body.data) {
      log(`manufacturing: WO ${woUid} RELEASED (${(r.body.data.components || []).length} component lines)`);
      return r.body.data;
    }
    pushIssue(`release WO ${woUid}`, r);
    return null;
  }

  // â”€â”€ helper: add operation to WO
  async function addOp(woUid, seqNo, description, workCentre, labourAmount, overheadAmount) {
    const r = await req('POST', `/work-orders/uid/${woUid}/operations`, token, {
      seqNo,
      description,
      workCentre,
      labourAmount: String(labourAmount),
      overheadAmount: String(overheadAmount)
    });
    if (r.status < 300 && r.body && r.body.data) {
      created.operations++;
      log(`manufacturing: added operation seq=${seqNo} "${description}" to WO ${woUid} uid=${r.body.data.uid}`);
      return r.body.data;
    }
    pushIssue(`add operation seq=${seqNo} to WO ${woUid}`, r);
    return null;
  }

  // â”€â”€ helper: issue components (full=true)
  async function issueComponents(woUid, postingDate) {
    const r = await req('POST', `/work-orders/uid/${woUid}/issue-components`, token, {
      full: true,
      componentUids: [],
      postingDate
    });
    if (r.status < 300 && r.body && r.body.data) {
      created.issued++;
      log(`manufacturing: WO ${woUid} components ISSUED â†’ status=${r.body.data.status} wipDebit=${r.body.data.wipDebitTotal}`);
      return r.body.data;
    }
    pushIssue(`issue-components WO ${woUid}`, r);
    return null;
  }

  // â”€â”€ helper: apply-cost (header-level)
  async function applyCost(woUid, labourAmount, overheadAmount, postingDate) {
    const r = await req('POST', `/work-orders/uid/${woUid}/apply-cost`, token, {
      labourAmount: String(labourAmount),
      overheadAmount: String(overheadAmount),
      postingDate
    });
    if (r.status < 300 && r.body && r.body.data) {
      created.cost_applied++;
      log(`manufacturing: WO ${woUid} cost APPLIED labour=${labourAmount} overhead=${overheadAmount} â†’ wipDebit=${r.body.data.wipDebitTotal}`);
      return r.body.data;
    }
    pushIssue(`apply-cost WO ${woUid}`, r);
    return null;
  }

  // â”€â”€ helper: complete
  async function completeWO(woUid, goodQty, scrapQty, postingDate) {
    const r = await req('POST', `/work-orders/uid/${woUid}/complete`, token, {
      goodQty: String(goodQty),
      scrapQty: String(scrapQty),
      allowOverRun: false,
      postingDate
    });
    if (r.status < 300 && r.body && r.body.data) {
      created.completed++;
      log(`manufacturing: WO ${woUid} COMPLETED goodQty=${goodQty} unitCost=${r.body.data.computedUnitCost}`);
      return r.body.data;
    }
    pushIssue(`complete WO ${woUid}`, r);
    return null;
  }

  // â”€â”€ helper: close
  async function closeWO(woUid, postingDate) {
    const r = await req('POST', `/work-orders/uid/${woUid}/close`, token, {
      postingDate
    });
    if (r.status < 300 && r.body && r.body.data) {
      created.closed++;
      log(`manufacturing: WO ${woUid} CLOSED varianceAmount=${r.body.data.varianceAmount}`);
      return r.body.data;
    }
    pushIssue(`close WO ${woUid}`, r);
    return null;
  }

  // â”€â”€ helper: cancel
  async function cancelWO(woUid, reason) {
    const r = await req('POST', `/work-orders/uid/${woUid}/cancel?reason=${encodeURIComponent(reason)}`, token, null);
    if (r.status < 300 && r.body && r.body.data) {
      created.cancelled++;
      log(`manufacturing: WO ${woUid} CANCELLED`);
      return r.body.data;
    }
    pushIssue(`cancel WO ${woUid}`, r);
    return null;
  }

  // =========================================================================
  // WORK ORDER 1: FG-001 â€” full lifecycle PLANNEDâ†’RELEASEDâ†’IN_PROGRESSâ†’COMPLETEDâ†’CLOSED
  // =========================================================================
  log('manufacturing: WO-1 â€” Chocolate Biscuit, full lifecycle (CLOSED)...');
  const wo1 = await createWO(0, 50, 'Demo: full lifecycle â€” chocolate biscuit batch 50 units');
  if (wo1) {
    // Add two operations before release (operations can be added any time up to completion)
    await addOp(wo1.uid, 10, 'Mixing & Blending', 'Mixing Line A', 45000, 12000);
    await addOp(wo1.uid, 20, 'Baking & Cooling', 'Baking Oven 1', 38000, 18000);

    const wo1Released = await releaseWO(wo1.uid);
    if (wo1Released) {
      const wo1Issued = await issueComponents(wo1.uid, '2026-06-16');
      if (wo1Issued) {
        // Apply cost at header level (separate from operation â€” exercises header-level apply)
        await applyCost(wo1.uid, 83000, 30000, '2026-06-16');

        // Complete: good=48, scrap=2 (total=50 = plannedQty)
        const wo1Completed = await completeWO(wo1.uid, 48, 2, '2026-06-17');
        if (wo1Completed) {
          await closeWO(wo1.uid, '2026-06-17');

          // Fetch cost report for the closed order
          const crR = await req('GET', `/work-orders/uid/${wo1.uid}/cost-report`, token, null);
          if (crR.status < 300) {
            const cr = crR.body && crR.body.data;
            log(`manufacturing: WO-1 cost report â€” computedUnitCost=${cr && cr.computedUnitCost} varianceAmount=${cr && cr.varianceAmount} incompleteCost=${cr && cr.incompleteCost}`);
          } else {
            pushIssue(`cost-report WO ${wo1.uid}`, crR);
          }
        }
      }
    }
  }

  // =========================================================================
  // WORK ORDER 2: FG-002 â€” PLANNEDâ†’RELEASEDâ†’IN_PROGRESSâ†’COMPLETED (not closed)
  // =========================================================================
  log('manufacturing: WO-2 â€” Vanilla Cookie, issueâ†’apply-costâ†’complete (COMPLETED)...');
  const wo2 = await createWO(1, 100, 'Demo: completed but not yet closed â€” vanilla cookie run');
  if (wo2) {
    await addOp(wo2.uid, 10, 'Dough Preparation', 'Mixer B', 55000, 14000);

    const wo2Released = await releaseWO(wo2.uid);
    if (wo2Released) {
      const wo2Issued = await issueComponents(wo2.uid, '2026-06-17');
      if (wo2Issued) {
        await applyCost(wo2.uid, 55000, 14000, '2026-06-17');
        await completeWO(wo2.uid, 98, 2, '2026-06-18');
        // Intentionally leave as COMPLETED (no close call) so QA sees both states
      }
    }
  }

  // =========================================================================
  // WORK ORDER 3: FG-003 â€” PLANNEDâ†’RELEASEDâ†’IN_PROGRESS (issued; not completed)
  // =========================================================================
  log('manufacturing: WO-3 â€” Cocoa Cake Mix, released + issued (IN_PROGRESS)...');
  const wo3 = await createWO(2, 75, 'Demo: in-progress order â€” cocoa cake mix production run');
  if (wo3) {
    await addOp(wo3.uid, 10, 'Dry Ingredient Sifting', 'Prep Station 1', 25000, 8000);
    await addOp(wo3.uid, 20, 'Blending & Packaging',   'Packing Line 2', 32000, 9500);

    const wo3Released = await releaseWO(wo3.uid);
    if (wo3Released) {
      // Issue but do NOT apply cost or complete â€” leaves IN_PROGRESS for QA to see
      await issueComponents(wo3.uid, '2026-06-18');
    }
  }

  // =========================================================================
  // WORK ORDER 4: FG-001 â€” PLANNEDâ†’RELEASED (released, not yet issued)
  // =========================================================================
  log('manufacturing: WO-4 â€” Chocolate Biscuit, released only (RELEASED)...');
  const wo4 = await createWO(0, 30, 'Demo: released, awaiting component issue');
  if (wo4) {
    // Update notes while still PLANNED (exercises update endpoint)
    const upR = await req('PUT', `/work-orders/uid/${wo4.uid}`, token, {
      notes: 'Demo: released, awaiting component issue â€” updated',
      plannedDate: '2026-06-25'
    });
    if (upR.status >= 300) pushIssue(`update WO ${wo4.uid}`, upR);

    await releaseWO(wo4.uid);
    // Intentionally leave as RELEASED
  }

  // =========================================================================
  // WORK ORDER 5: FG-002 â€” PLANNEDâ†’RELEASEDâ†’CANCELLED
  // =========================================================================
  log('manufacturing: WO-5 â€” Vanilla Cookie, released then cancelled (CANCELLED)...');
  const wo5 = await createWO(1, 20, 'Demo: cancelled after release â€” material shortage');
  if (wo5) {
    const wo5Released = await releaseWO(wo5.uid);
    if (wo5Released) {
      await cancelWO(wo5.uid, 'Material shortage â€” cancelled for demo');
    }
  }

  // =========================================================================
  // EXTRA: a PLANNED-only order (never released) to show the initial state
  // =========================================================================
  log('manufacturing: WO-6 â€” FG-001, planned only (PLANNED)...');
  const wo6 = await createWO(0, 200, 'Demo: planned batch â€” pending BOM review before release');
  if (wo6) {
    // Leave as PLANNED â€” the simplest state QA needs to see
    log(`manufacturing: WO-6 ${wo6.uid} left in PLANNED state`);
  }

  // =========================================================================
  // WIP reconciliation report (company-wide, open orders)
  // =========================================================================
  log('manufacturing: fetching WIP reconciliation report...');
  const wipR = await req('GET', `/manufacturing/wip-reconciliation?companyId=${companyId}`, token, null);
  if (wipR.status < 300 && wipR.body && wipR.body.data) {
    const w = wipR.body.data;
    log(`manufacturing: WIP recon â€” computed=${w.computed} expected=${w.expected} ties=${w.ties}`);
    if (w.ties === false) {
      issues.push(`WIP reconciliation does NOT tie (computed=${w.computed} vs expected=${w.expected}) â€” finance-grade defect if non-zero`);
    }
  } else {
    pushIssue('GET /manufacturing/wip-reconciliation', wipR);
  }

  // =========================================================================
  // List all work orders to verify populated screen data
  // =========================================================================
  log('manufacturing: listing all work orders (verify screen population)...');
  const listR = await req('GET', `/work-orders?companyId=${companyId}&size=20`, token, null);
  if (listR.status < 300 && listR.body && listR.body.data) {
    const rows = Array.isArray(listR.body.data) ? listR.body.data : [];
    log(`manufacturing: list returned ${rows.length} work orders`);
    const statusCounts = {};
    for (const wo of rows) {
      statusCounts[wo.status] = (statusCounts[wo.status] || 0) + 1;
    }
    log(`manufacturing: status breakdown â€” ${JSON.stringify(statusCounts)}`);
  } else {
    pushIssue('GET /work-orders list', listR);
  }

  log(`manufacturing: done â€” created=${JSON.stringify(created)} issues=${issues.length}`);
  return { created, issues };
}

// ===== crm -> seedCrm =====
async function seedCrm(req, ctx, log) {
  const created = {};
  const issues = [];

  const note = (status, raw, label) => {
    issues.push(`${label}: status=${status} â€” ${String(raw || '').slice(0, 80)}`);
  };

  // Bare-object endpoints (not wrapped in ApiResponse) return the DTO directly.
  // Paginated list endpoints return ApiResponse<List<T>> with data + meta.
  const bareUid  = (r, label) => { if (r.status < 200 || r.status > 299) { note(r.status, r.raw, label); return null; } return r.body?.uid || null; };
  const bareData = (r, label) => { if (r.status < 200 || r.status > 299) { note(r.status, r.raw, label); return null; } return r.body || null; };

  const companyIdNum = Number(ctx.companyId);   // Long companyId for request bodies
  const companyId    = ctx.companyId;            // string for query params
  const branchId     = ctx.branchId;             // string for pipeline report params

  // ----------------------------------------------------------------
  // STEP 1 â€” Resolve a unit uid for opportunity lines.
  //           Use ctx.productUids if available; also need a unitUid.
  // ----------------------------------------------------------------
  log('[CRM] Resolving unit uid for opportunity lines...');
  let unitUid = null;
  const unitsR = await req('GET', `/units?companyId=${companyId}&page=0&size=5`, ctx.token);
  const unitsList = unitsR.body?.data || (Array.isArray(unitsR.body) ? unitsR.body : []);
  if (unitsList.length > 0) {
    unitUid = unitsList[0].uid;
    log(`[CRM] Using unit uid=${unitUid}`);
  } else {
    note(unitsR.status, unitsR.raw, 'fetch units (needed for opportunity lines)');
    log('[CRM] WARNING: no unit uid resolved â€” opportunity lines will be skipped');
  }

  // ----------------------------------------------------------------
  // STEP 2 â€” Resolve product uids for opportunity lines.
  // ----------------------------------------------------------------
  let productUids = (ctx.productUids && ctx.productUids.length > 0) ? ctx.productUids.slice(0, 4) : [];
  if (productUids.length === 0) {
    log('[CRM] ctx.productUids empty â€” fetching products from API...');
    const pR = await req('GET', `/products?companyId=${companyId}&page=0&size=4`, ctx.token);
    const pList = pR.body?.data || [];
    productUids = pList.map(p => p.uid).filter(Boolean);
    log(`[CRM] Fetched ${productUids.length} product uids`);
  }

  // ----------------------------------------------------------------
  // STEP 3 â€” Resolve customer uids.
  // ----------------------------------------------------------------
  let customerUids = (ctx.customerUids && ctx.customerUids.length > 0) ? ctx.customerUids.slice(0, 6) : [];
  if (customerUids.length === 0) {
    log('[CRM] ctx.customerUids empty â€” fetching customers from API...');
    const cR = await req('GET', `/customers?companyId=${companyId}&page=0&size=6`, ctx.token);
    const cList = cR.body?.data || [];
    customerUids = cList.map(c => c.uid).filter(Boolean);
    log(`[CRM] Fetched ${customerUids.length} customer uids`);
  }
  // We need at least 1 customer for opportunities. If still none, create a minimal one.
  if (customerUids.length === 0) {
    log('[CRM] No customers found â€” creating a minimal CRM seed customer...');
    const cR2 = await req('POST', '/customers', ctx.token, {
      companyId: companyIdNum,
      partyType: 'BUSINESS',
      displayName: 'CRM Seed Customer',
      customerKind: 'CREDIT_ACCOUNT'
    });
    if (cR2.status < 300 && (cR2.body?.data?.uid || cR2.body?.uid)) {
      customerUids.push(cR2.body?.data?.uid || cR2.body?.uid);
      log(`[CRM] Created fallback customer uid=${customerUids[0]}`);
    } else {
      note(cR2.status, cR2.raw, 'create fallback CRM customer');
      log('[CRM] WARNING: still no customer uid â€” opportunities will be skipped');
    }
  }

  // ----------------------------------------------------------------
  // STEP 4 â€” Create pipeline stages (5 stages across a typical sales funnel).
  // ----------------------------------------------------------------
  log('[CRM] Creating pipeline stages...');
  const stageSpecs = [
    { name: 'Prospecting',    displayOrder: 1, defaultProbability: 10 },
    { name: 'Qualification',  displayOrder: 2, defaultProbability: 25 },
    { name: 'Proposal',       displayOrder: 3, defaultProbability: 50 },
    { name: 'Negotiation',    displayOrder: 4, defaultProbability: 75 },
    { name: 'Closed Won',     displayOrder: 5, defaultProbability: 90 },
  ];
  const stageUids = [];
  created.pipelineStages = 0;
  for (const spec of stageSpecs) {
    const r = await req('POST', '/crm/pipeline-stages', ctx.token, {
      companyId: companyIdNum,
      name: spec.name,
      displayOrder: spec.displayOrder,
      defaultProbability: spec.defaultProbability
    });
    const uid = bareUid(r, `create pipeline stage "${spec.name}"`);
    if (uid) { stageUids.push(uid); created.pipelineStages++; }
  }
  log(`[CRM] Pipeline stages created: ${created.pipelineStages}`);

  if (stageUids.length === 0) {
    // Try to fetch existing stages for this company before giving up
    log('[CRM] No stages created â€” attempting to fetch existing stages...');
    const stR = await req('GET', `/crm/pipeline-stages?companyId=${companyId}`, ctx.token);
    const existing = Array.isArray(stR.body) ? stR.body : (stR.body?.data || []);
    for (const s of existing) { if (s.uid) stageUids.push(s.uid); }
    log(`[CRM] Found ${stageUids.length} existing stage uids`);
  }

  // ----------------------------------------------------------------
  // STEP 5 â€” Create leads (8 total with varied sources).
  // ----------------------------------------------------------------
  log('[CRM] Creating leads...');
  const leadSpecs = [
    { displayName: 'Amani Telecom Ltd',         leadSource: 'WEBSITE',           companyName: 'Amani Telecom',      contactPerson: 'John Mwanga',    phone: '+255711000001', email: 'jmwanga@amanitelecom.co.tz',  notes: 'Interested in enterprise software suite' },
    { displayName: 'Kilimanjaro Traders',        leadSource: 'REFERRAL',          companyName: 'Kilimanjaro Traders', contactPerson: 'Agnes Shirima',  phone: '+255711000002', email: 'ashirima@kilitraders.co.tz',  notes: 'Referred by existing client Serengeti Corp' },
    { displayName: 'Dar es Salaam Motors',       leadSource: 'COLD_CALL',         companyName: 'DSM Motors Ltd',     contactPerson: 'Hassan Ally',    phone: '+255711000003', email: 'hally@dsmmotors.co.tz',       notes: 'Cold outreach â€” fleet management interest' },
    { displayName: 'Zanzibar Spice Exports',     leadSource: 'CAMPAIGN',          companyName: 'ZSE Ltd',            contactPerson: 'Fatuma Juma',    phone: '+255711000004', email: 'fjuma@zse.co.tz',             notes: 'Came via April digital campaign' },
    { displayName: 'Moshi Coffee Cooperative',   leadSource: 'WALK_IN',           companyName: 'Moshi Coop',         contactPerson: 'Peter Kimaro',   phone: '+255711000005', email: 'pkimaro@moshicoop.co.tz',     notes: 'Walked in requesting ERP demo' },
    { displayName: 'Arusha Safari Lodges',       leadSource: 'EXISTING_CUSTOMER', companyName: 'ASL Holdings',       contactPerson: 'Mary Nkini',     phone: '+255711000006', email: 'mnkini@asl.co.tz',            notes: 'Upsell â€” existing customer interested in CRM module' },
    { displayName: 'Iringa Agricultural Hub',    leadSource: 'REFERRAL',          companyName: 'IAH Ltd',            contactPerson: 'Sylvester Mwale', phone: '+255711000007', email: 'smwale@iah.co.tz',            notes: 'Referred by partner agronomist' },
    { displayName: 'Tanga Port Logistics',       leadSource: 'OTHER',             companyName: 'TPL Co',             contactPerson: 'Daudi Hamisi',   phone: '+255711000008', email: 'dhamisi@tpl.co.tz',           notes: 'Inbound query via trade expo contact form' },
  ];

  const leadUids = [];
  created.leads = 0;
  for (const spec of leadSpecs) {
    const r = await req('POST', '/crm/leads', ctx.token, {
      companyId: companyIdNum,
      displayName: spec.displayName,
      leadSource: spec.leadSource,
      companyName: spec.companyName,
      contactPerson: spec.contactPerson,
      phone: spec.phone,
      email: spec.email,
      notes: spec.notes
    });
    const uid = bareUid(r, `create lead "${spec.displayName}"`);
    if (uid) { leadUids.push(uid); created.leads++; }
  }
  log(`[CRM] Leads created: ${created.leads}`);

  // ----------------------------------------------------------------
  // STEP 6 â€” Drive lead lifecycle: contact some, qualify some, disqualify one.
  //
  //   Leads index:
  //     [0] Amani Telecom      -> contact -> qualify (new customer) -> will convert via opportunity
  //     [1] Kilimanjaro        -> contact -> qualify (new customer) -> will convert via opportunity
  //     [2] DSM Motors         -> contact -> qualify (existing customer if any, else new)
  //     [3] Zanzibar Spice     -> contact (stays CONTACTED)
  //     [4] Moshi Coffee       -> contact -> disqualify ("Budget constraints, not a fit at this time")
  //     [5] Arusha Safari      -> stays NEW (no action)
  //     [6] Iringa Agri        -> stays NEW
  //     [7] Tanga Port         -> stays NEW
  // ----------------------------------------------------------------
  log('[CRM] Driving lead lifecycle...');
  created.leadsContacted   = 0;
  created.leadsQualified   = 0;
  created.leadsDisqualified = 0;

  // Helper: contact a lead (NEW -> CONTACTED)
  const contactLead = async (uid, label) => {
    const r = await req('PUT', `/crm/leads/uid/${uid}/contact`, ctx.token, null);
    if (r.status >= 200 && r.status < 300) {
      created.leadsContacted++;
      log(`[CRM] Lead contacted: ${label}`);
    } else {
      note(r.status, r.raw, `contact lead ${label}`);
    }
    return r.status < 300;
  };

  // Contact leads 0-4
  const toContact = leadUids.slice(0, 5);
  for (let i = 0; i < toContact.length; i++) {
    await contactLead(toContact[i], leadSpecs[i].displayName);
  }

  // Qualify leads 0, 1 with new customer details (CONTACTED -> QUALIFIED)
  const qualifyWithNew = async (uid, displayName, phone, email, label) => {
    const r = await req('POST', `/crm/leads/uid/${uid}/qualify`, ctx.token, {
      existingCustomerUid: null,
      newCustomerDetails: {
        displayName: displayName,
        customerKind: 'CREDIT_ACCOUNT',
        phone: phone,
        email: email,
        physicalAddress: 'Tanzania',
        region: 'Dar es Salaam',
        district: 'Kinondoni'
      }
    });
    const data = bareData(r, `qualify lead ${label} (new customer)`);
    if (data) {
      created.leadsQualified++;
      log(`[CRM] Lead qualified (new customer): ${label}`);
      return data;
    }
    return null;
  };

  let qualifiedLeadData = [];
  if (leadUids[0]) {
    const d = await qualifyWithNew(leadUids[0], 'Amani Telecom Ltd (Customer)', '+255711000001', 'accounts@amanitelecom.co.tz', leadSpecs[0].displayName);
    if (d) qualifiedLeadData.push({ leadUid: leadUids[0], leadData: d, specIndex: 0 });
  }
  if (leadUids[1]) {
    const d = await qualifyWithNew(leadUids[1], 'Kilimanjaro Traders (Customer)', '+255711000002', 'accounts@kilitraders.co.tz', leadSpecs[1].displayName);
    if (d) qualifiedLeadData.push({ leadUid: leadUids[1], leadData: d, specIndex: 1 });
  }

  // Qualify lead 2 with existing customer (if any), else new
  if (leadUids[2]) {
    let r2;
    if (customerUids.length > 0) {
      r2 = await req('POST', `/crm/leads/uid/${leadUids[2]}/qualify`, ctx.token, {
        existingCustomerUid: customerUids[0],
        newCustomerDetails: null
      });
    } else {
      r2 = await req('POST', `/crm/leads/uid/${leadUids[2]}/qualify`, ctx.token, {
        existingCustomerUid: null,
        newCustomerDetails: {
          displayName: 'Dar es Salaam Motors (Customer)',
          customerKind: 'CREDIT_ACCOUNT',
          phone: '+255711000003',
          email: 'accounts@dsmmotors.co.tz',
          physicalAddress: 'Dar es Salaam',
          region: 'Dar es Salaam',
          district: 'Ilala'
        }
      });
    }
    const data2 = bareData(r2, `qualify lead ${leadSpecs[2].displayName}`);
    if (data2) {
      created.leadsQualified++;
      log(`[CRM] Lead qualified: ${leadSpecs[2].displayName}`);
      qualifiedLeadData.push({ leadUid: leadUids[2], leadData: data2, specIndex: 2 });
    }
  }

  // Disqualify lead 4 (Moshi Coffee, CONTACTED)
  if (leadUids[4]) {
    const r = await req('POST', `/crm/leads/uid/${leadUids[4]}/disqualify`, ctx.token, {
      reason: 'Budget constraints â€” prospect cannot commit until Q1 2027; no active opportunity.'
    });
    if (r.status >= 200 && r.status < 300) {
      created.leadsDisqualified++;
      log(`[CRM] Lead disqualified: ${leadSpecs[4].displayName}`);
    } else {
      note(r.status, r.raw, `disqualify lead ${leadSpecs[4].displayName}`);
    }
  }

  // ----------------------------------------------------------------
  // STEP 7 â€” Create opportunities (varied stages, amounts, currencies).
  //
  //   We need stageUids and customerUids.
  //   Some opportunities are sourced from qualified leads (QUALIFIED -> CONVERTED).
  // ----------------------------------------------------------------
  log('[CRM] Creating opportunities...');

  if (stageUids.length === 0 || customerUids.length === 0) {
    log('[CRM] WARNING: no stages or no customers â€” skipping opportunities');
    note(0, 'no stages or customers', 'create opportunities (prerequisite missing)');
    return { created, issues };
  }

  const oppSpecs = [
    {
      title: 'Enterprise ERP Rollout â€” Amani Telecom',
      currency: 'TZS',
      estimatedValueAmount: 45000000,
      winProbability: 25,
      expectedCloseDate: '2026-09-30',
      stageIndex: 0,   // Prospecting
      customerIndex: 0,
      sourceLeadIndex: 0,  // from qualifiedLeadData[0] if available
      notes: 'Full suite deployment across 3 branches'
    },
    {
      title: 'Inventory Module Upgrade â€” Kilimanjaro Traders',
      currency: 'TZS',
      estimatedValueAmount: 12500000,
      winProbability: 50,
      expectedCloseDate: '2026-08-15',
      stageIndex: 2,   // Proposal
      customerIndex: 1,
      sourceLeadIndex: 1,  // from qualifiedLeadData[1] if available
      notes: 'Migrate from legacy stock system to ERP inventory'
    },
    {
      title: 'Fleet Management System â€” DSM Motors',
      currency: 'TZS',
      estimatedValueAmount: 8750000,
      winProbability: 75,
      expectedCloseDate: '2026-07-31',
      stageIndex: 3,   // Negotiation
      customerIndex: 2,
      sourceLeadIndex: 2,  // from qualifiedLeadData[2] if available
      notes: 'Vehicle tracking + maintenance module'
    },
    {
      title: 'HR & Payroll Platform â€” Arusha Safari Lodges',
      currency: 'TZS',
      estimatedValueAmount: 22000000,
      winProbability: 90,
      expectedCloseDate: '2026-07-15',
      stageIndex: 4,   // Closed Won (will be won)
      customerIndex: Math.min(3, customerUids.length - 1),
      sourceLeadIndex: null,
      notes: 'Payroll + leave + appraisals for 200-staff lodge group'
    },
    {
      title: 'CRM Module Subscription â€” Zanzibar Spice Exports',
      currency: 'USD',
      estimatedValueAmount: 5400,
      winProbability: 50,
      expectedCloseDate: '2026-10-31',
      stageIndex: 1,   // Qualification
      customerIndex: Math.min(4, customerUids.length - 1),
      sourceLeadIndex: null,
      notes: 'Annual CRM SaaS subscription + onboarding'
    },
    {
      title: 'Budgeting & Reporting Suite â€” Iringa Agricultural Hub',
      currency: 'TZS',
      estimatedValueAmount: 6800000,
      winProbability: 25,
      expectedCloseDate: '2026-11-30',
      stageIndex: 0,   // Prospecting
      customerIndex: Math.min(5, customerUids.length - 1),
      sourceLeadIndex: null,
      notes: 'Annual budgeting cycle + management reporting pack'
    },
  ];

  const oppUids = [];
  created.opportunities = 0;

  for (let i = 0; i < oppSpecs.length; i++) {
    const spec = oppSpecs[i];
    const stageUid    = stageUids[Math.min(spec.stageIndex, stageUids.length - 1)];
    const customerUid = customerUids[Math.min(spec.customerIndex, customerUids.length - 1)];

    if (!stageUid || !customerUid) {
      note(0, `stageUid=${stageUid} customerUid=${customerUid}`, `create opportunity "${spec.title}" (missing ref)`);
      continue;
    }

    // Determine if we should link a source lead (it must be QUALIFIED at this point)
    let sourceLeadUid = null;
    if (spec.sourceLeadIndex !== null && spec.sourceLeadIndex < qualifiedLeadData.length) {
      const ql = qualifiedLeadData[spec.sourceLeadIndex];
      // Only link if lead is currently QUALIFIED (not yet CONVERTED)
      if (ql && ql.leadData && ql.leadData.leadStatus === 'QUALIFIED') {
        sourceLeadUid = ql.leadUid;
      }
    }

    const body = {
      companyId: companyIdNum,
      customerUid: customerUid,
      title: spec.title,
      pipelineStageUid: stageUid,
      currency: spec.currency,
      estimatedValueAmount: spec.estimatedValueAmount,
      winProbability: spec.winProbability,
      expectedCloseDate: spec.expectedCloseDate,
      notes: spec.notes
    };
    if (sourceLeadUid) body.sourceLeadUid = sourceLeadUid;

    const r = await req('POST', '/crm/opportunities', ctx.token, body);
    const uid = bareUid(r, `create opportunity "${spec.title}"`);
    if (uid) {
      oppUids.push({ uid, spec, stageUid, customerUid });
      created.opportunities++;
      log(`[CRM] Opportunity created: "${spec.title}" uid=${uid}`);
      // Mark lead as now converted in our local data so we don't double-link
      if (sourceLeadUid) {
        const ql = qualifiedLeadData.find(q => q.leadUid === sourceLeadUid);
        if (ql) ql.leadData.leadStatus = 'CONVERTED';
      }
    }
  }
  log(`[CRM] Opportunities created: ${created.opportunities}`);

  // ----------------------------------------------------------------
  // STEP 8 â€” Add opportunity lines (where productUid + unitUid available).
  // ----------------------------------------------------------------
  log('[CRM] Adding opportunity lines...');
  created.opportunityLines = 0;

  if (productUids.length > 0 && unitUid) {
    const lineSpecs = [
      { productIndex: 0, qty: 5,   unitPrice: 2500000, discount: 0,       label: 'ERP Core Licence' },
      { productIndex: 1, qty: 2,   unitPrice: 1200000, discount: 100000,   label: 'Implementation Days' },
      { productIndex: 2, qty: 10,  unitPrice: 450000,  discount: 0,        label: 'Training Seats' },
      { productIndex: 3, qty: 1,   unitPrice: 800000,  discount: 0,        label: 'Annual Support' },
    ];

    for (let i = 0; i < Math.min(oppUids.length, 4); i++) {
      const opp = oppUids[i];
      // Opportunities must be OPEN to add lines
      const lineSpec = lineSpecs[i % lineSpecs.length];
      const productUid = productUids[Math.min(lineSpec.productIndex, productUids.length - 1)];
      if (!productUid) continue;

      const lineR = await req('POST', `/crm/opportunities/uid/${opp.uid}/lines`, ctx.token, {
        productUid: productUid,
        unitUid: unitUid,
        estimatedQty: lineSpec.qty,
        estimatedUnitPriceAmount: lineSpec.unitPrice,
        lineDiscountAmount: lineSpec.discount,
        lineDiscountPercent: 0
      });
      if (lineR.status >= 200 && lineR.status < 300) {
        created.opportunityLines++;
        log(`[CRM] Added line to opportunity "${opp.spec.title}"`);
      } else {
        note(lineR.status, lineR.raw, `add line to opportunity "${opp.spec.title}"`);
      }
    }
  } else {
    log('[CRM] Skipping opportunity lines â€” no productUids or unitUid available');
    issues.push('opportunity lines skipped: no productUids/unitUid in ctx');
  }

  // ----------------------------------------------------------------
  // STEP 9 â€” Advance stages on some open opportunities.
  //   Opp[0]: Prospecting -> Qualification
  //   Opp[1]: Proposal -> Negotiation
  // ----------------------------------------------------------------
  log('[CRM] Advancing pipeline stages...');
  created.stageAdvances = 0;

  if (stageUids.length >= 2) {
    // Advance opp[0] from stage[0] to stage[1]
    if (oppUids[0]) {
      const advR = await req('POST', `/crm/opportunities/uid/${oppUids[0].uid}/advance-stage`, ctx.token, {
        pipelineStageUid: stageUids[Math.min(1, stageUids.length - 1)],
        winProbabilityOverride: null
      });
      if (advR.status >= 200 && advR.status < 300) {
        created.stageAdvances++;
        log(`[CRM] Opportunity "${oppUids[0].spec.title}" advanced to stage index 1`);
      } else {
        note(advR.status, advR.raw, `advance stage opp[0]`);
      }
    }

    // Advance opp[1] one more stage if stages available
    if (oppUids[1] && stageUids.length >= 3) {
      const advR2 = await req('POST', `/crm/opportunities/uid/${oppUids[1].uid}/advance-stage`, ctx.token, {
        pipelineStageUid: stageUids[Math.min(3, stageUids.length - 1)],
        winProbabilityOverride: 80
      });
      if (advR2.status >= 200 && advR2.status < 300) {
        created.stageAdvances++;
        log(`[CRM] Opportunity "${oppUids[1].spec.title}" advanced to stage index 3`);
      } else {
        note(advR2.status, advR2.raw, `advance stage opp[1]`);
      }
    }
  }

  // ----------------------------------------------------------------
  // STEP 10 â€” Win one opportunity (opp[3] â€” Arusha Safari Lodges, already at Closed Won stage).
  //           Mark opp[5] as LOST.
  // ----------------------------------------------------------------
  log('[CRM] Applying win/lose actions...');
  created.opportunitiesWon  = 0;
  created.opportunitiesLost = 0;

  if (oppUids[3]) {
    const wonAt = new Date('2026-06-10T10:00:00Z').toISOString();
    const winR = await req('POST', `/crm/opportunities/uid/${oppUids[3].uid}/win`, ctx.token, {
      wonAt: wonAt
    });
    if (winR.status >= 200 && winR.status < 300) {
      created.opportunitiesWon++;
      log(`[CRM] Opportunity WON: "${oppUids[3].spec.title}"`);
    } else {
      note(winR.status, winR.raw, `win opportunity "${oppUids[3].spec.title}"`);
    }
  }

  if (oppUids[5]) {
    const loseR = await req('POST', `/crm/opportunities/uid/${oppUids[5].uid}/lose`, ctx.token, {
      lossReason: 'Client awarded contract to incumbent vendor after extended evaluation; price not competitive for current budget cycle.'
    });
    if (loseR.status >= 200 && loseR.status < 300) {
      created.opportunitiesLost++;
      log(`[CRM] Opportunity LOST: "${oppUids[5].spec.title}"`);
    } else {
      note(loseR.status, loseR.raw, `lose opportunity "${oppUids[5].spec.title}"`);
    }
  }

  // ----------------------------------------------------------------
  // STEP 11 â€” Convert won opportunity to SALES_ORDER (requires WON + >=1 line).
  //           Convert an open opportunity (opp[2] with a line) to QUOTATION.
  // ----------------------------------------------------------------
  log('[CRM] Converting opportunities...');
  created.conversions = 0;

  // Convert won opp[3] to SALES_ORDER if it has lines
  if (oppUids[3] && created.opportunitiesWon > 0) {
    // Add a line first if none exists (opp[3] is index 3, lines were added for index 0-3)
    // opp[3] line was attempted in step 8 (i=3 â†’ lineSpec index 3)
    const convertR = await req('POST', `/crm/opportunities/uid/${oppUids[3].uid}/convert`, ctx.token, {
      target: 'SALES_ORDER',
      validUntil: null
    });
    if (convertR.status >= 200 && convertR.status < 300) {
      created.conversions++;
      log(`[CRM] Opportunity converted to SALES_ORDER: "${oppUids[3].spec.title}" -> doc uid=${convertR.body?.convertedDocumentUid}`);
    } else {
      note(convertR.status, convertR.raw, `convert won opportunity to SALES_ORDER`);
    }
  }

  // Convert open opp[2] (Fleet Management) to QUOTATION â€” it has a line from step 8
  if (oppUids[2]) {
    const convertR2 = await req('POST', `/crm/opportunities/uid/${oppUids[2].uid}/convert`, ctx.token, {
      target: 'QUOTATION',
      validUntil: '2026-08-31'
    });
    if (convertR2.status >= 200 && convertR2.status < 300) {
      created.conversions++;
      log(`[CRM] Opportunity converted to QUOTATION: "${oppUids[2].spec.title}" -> doc uid=${convertR2.body?.convertedDocumentUid}`);
    } else {
      note(convertR2.status, convertR2.raw, `convert open opportunity to QUOTATION`);
    }
  }

  // ----------------------------------------------------------------
  // STEP 12 â€” Deactivate one pipeline stage (soft delete).
  //           Pick the last stage to keep meaningful stages active.
  // ----------------------------------------------------------------
  if (stageUids.length > 0) {
    const deactivateUid = stageUids[stageUids.length - 1];
    const delR = await req('DELETE', `/crm/pipeline-stages/uid/${deactivateUid}`, ctx.token, null);
    if (delR.status >= 200 && delR.status < 300) {
      created.pipelineStagesDeactivated = 1;
      log(`[CRM] Pipeline stage deactivated (soft): uid=${deactivateUid}`);
    } else {
      note(delR.status, delR.raw, `deactivate pipeline stage uid=${deactivateUid}`);
    }
  }

  // ----------------------------------------------------------------
  // STEP 13 â€” Read pipeline reports to verify data is queryable.
  // ----------------------------------------------------------------
  log('[CRM] Fetching pipeline reports (smoke-check)...');

  const pipelineR = await req('GET', `/crm/pipeline?companyId=${companyId}&branchId=${branchId}`, ctx.token);
  if (pipelineR.status >= 200 && pipelineR.status < 300) {
    const stagesInReport = pipelineR.body?.stages?.length || 0;
    log(`[CRM] Pipeline board: ${stagesInReport} stages returned`);
    created.pipelineReportStages = stagesInReport;
  } else {
    note(pipelineR.status, pipelineR.raw, 'GET /crm/pipeline (board report)');
  }

  const forecastR = await req('GET', `/crm/pipeline/forecast?companyId=${companyId}&branchId=${branchId}&from=2026-06-01&to=2026-12-31`, ctx.token);
  if (forecastR.status >= 200 && forecastR.status < 300) {
    log(`[CRM] Forecast report: weightedValue=${forecastR.body?.weightedValueAmount} openCount=${forecastR.body?.openCount}`);
    created.forecastReport = 1;
  } else {
    note(forecastR.status, forecastR.raw, 'GET /crm/pipeline/forecast');
  }

  const kpiR = await req('GET', `/crm/pipeline/kpis?companyId=${companyId}&branchId=${branchId}&from=2026-01-01&to=2026-12-31`, ctx.token);
  if (kpiR.status >= 200 && kpiR.status < 300) {
    log(`[CRM] KPI report: wonCount=${kpiR.body?.wonCount} lostCount=${kpiR.body?.lostCount} winRate=${kpiR.body?.winRatePercent}`);
    created.kpiReport = 1;
  } else {
    note(kpiR.status, kpiR.raw, 'GET /crm/pipeline/kpis');
  }

  log(`[CRM] Seed complete. Summary: stages=${created.pipelineStages} leads=${created.leads} (contacted=${created.leadsContacted} qualified=${created.leadsQualified} disqualified=${created.leadsDisqualified}) opps=${created.opportunities} lines=${created.opportunityLines} advances=${created.stageAdvances} won=${created.opportunitiesWon} lost=${created.opportunitiesLost} conversions=${created.conversions} issues=${issues.length}`);

  return { created, issues };
}


// ===== approvals -> seedApprovals =====
async function seedApprovals(req, ctx, log) {
  const created = {
    policies: 0,
    policiesDeactivated: 0,
    approverUsers: 0,
    purchaseSettings: 0,
    purchaseOrders: 0,
    approvalRequests: 0,
    approvalRequestsApproved: 0,
    approvalRequestsRejected: 0,
    approvalRequestsRecalled: 0,
    approvalRequestsCancelled: 0,
  };
  const issues = [];

  const push = (status, raw, label) => {
    issues.push(`${label}: HTTP ${status} â€” ${String(raw || '').slice(0, 80)}`);
  };

  // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  const ok = (r) => r.status >= 200 && r.status < 300;
  const data = (r) => (r.body && r.body.data) ? r.body.data : null;

  // Discover ORG_ADMIN role uid (always seeded by V1 migration)
  let orgAdminRoleUid = null;
  {
    const r = await req('GET', '/roles', ctx.token, null);
    if (ok(r)) {
      const list = Array.isArray(data(r)) ? data(r) : [];
      const found = list.find(x => x.code === 'ORG_ADMIN');
      if (found) orgAdminRoleUid = found.uid;
    }
    if (!orgAdminRoleUid) {
      push(0, 'could not resolve ORG_ADMIN role uid', 'approvals/setup/orgAdminRole');
      issues.push('approvals/setup: ORG_ADMIN role not found â€” approve/reject steps will be skipped');
    }
  }

  log('approvals: seeding approval policies...');

  // â”€â”€ 1. Approval Policies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Three bands for PURCHASE_ORDER (so QA sees band-matching UI)
  const poPolicies = [
    {
      name: 'PO Low Band (0 â€“ 500,000 TZS)',
      documentType: 'PURCHASE_ORDER',
      branchScope: 'COMPANY_WIDE',
      branchUid: null,
      minAmount: 0,
      maxAmount: 500000,
      notes: 'Single-step finance review for small POs',
      steps: [{ sequence: 1, approverRoleCode: 'ORG_ADMIN' }],
    },
    {
      name: 'PO Mid Band (500,000 â€“ 5,000,000 TZS)',
      documentType: 'PURCHASE_ORDER',
      branchScope: 'COMPANY_WIDE',
      branchUid: null,
      minAmount: 500000,
      maxAmount: 5000000,
      notes: 'Two-step: finance then procurement head',
      steps: [
        { sequence: 1, approverRoleCode: 'ORG_ADMIN' },
        { sequence: 2, approverRoleCode: 'ORG_ADMIN' },
      ],
    },
    {
      name: 'PO Top Band (5,000,000+ TZS)',
      documentType: 'PURCHASE_ORDER',
      branchScope: 'COMPANY_WIDE',
      branchUid: null,
      minAmount: 5000000,
      maxAmount: null,
      notes: 'Board-level sign-off for large capital POs',
      steps: [
        { sequence: 1, approverRoleCode: 'ORG_ADMIN' },
        { sequence: 2, approverRoleCode: 'ORG_ADMIN' },
        { sequence: 3, approverRoleCode: 'ORG_ADMIN' },
      ],
    },
  ];

  // One policy for EXPENSE_REPORT (fictional doc type â€” shows multi-doctype setup)
  const expensePolicy = {
    name: 'Expense Report Approval',
    documentType: 'EXPENSE_REPORT',
    branchScope: 'COMPANY_WIDE',
    branchUid: null,
    minAmount: 0,
    maxAmount: null,
    notes: 'All expense reports require manager sign-off',
    steps: [{ sequence: 1, approverRoleCode: 'ORG_ADMIN' }],
  };

  // One policy for HR_PAYROLL to demonstrate another document type
  const payrollPolicy = {
    name: 'Payroll Run Authorisation',
    documentType: 'HR_PAYROLL',
    branchScope: 'COMPANY_WIDE',
    branchUid: null,
    minAmount: 0,
    maxAmount: null,
    notes: 'Finance director must authorise every payroll disbursement',
    steps: [
      { sequence: 1, approverRoleCode: 'ORG_ADMIN' },
      { sequence: 2, approverRoleCode: 'ORG_ADMIN' },
    ],
  };

  const allPolicyDefs = [...poPolicies, expensePolicy, payrollPolicy];
  const createdPolicies = [];

  for (let i = 0; i < allPolicyDefs.length; i++) {
    const def = allPolicyDefs[i];
    const body = {
      companyId: Number(ctx.companyId),
      documentType: def.documentType,
      name: def.name,
      branchScope: def.branchScope,
      branchUid: def.branchUid || null,
      minAmount: def.minAmount,
      maxAmount: def.maxAmount !== null && def.maxAmount !== undefined ? def.maxAmount : null,
      notes: def.notes,
      steps: def.steps,
    };
    const r = await req('POST', '/approvals/policies', ctx.token, body);
    if (ok(r) && data(r)) {
      const pol = data(r);
      createdPolicies.push(pol);
      created.policies++;
      log(`approvals: policy created â€” "${pol.name}" (uid=${pol.uid})`);
    } else {
      push(r.status, r.raw, `approvals/policy/create[${i}] "${def.name}"`);
    }
  }

  // Deactivate the payroll policy to show INACTIVE state in the UI
  const payrollPol = createdPolicies.find(p => p.documentType === 'HR_PAYROLL');
  if (payrollPol) {
    const r = await req('POST', `/approvals/policies/uid/${payrollPol.uid}/deactivate`, ctx.token, null);
    if (ok(r)) {
      created.policiesDeactivated++;
      log(`approvals: policy "${payrollPol.name}" deactivated (INACTIVE)`);
    } else {
      push(r.status, r.raw, `approvals/policy/deactivate HR_PAYROLL`);
    }
  }

  // Update the expense policy to show update path (rename it slightly)
  const expPol = createdPolicies.find(p => p.documentType === 'EXPENSE_REPORT');
  if (expPol) {
    const updateBody = {
      documentType: 'EXPENSE_REPORT',
      name: 'Expense Report Approval (Updated)',
      branchScope: 'COMPANY_WIDE',
      branchUid: null,
      minAmount: 10000,
      maxAmount: null,
      notes: 'All expense reports â‰¥ 10,000 TZS require manager sign-off (updated)',
      active: true,
      steps: [
        { sequence: 1, approverRoleCode: 'ORG_ADMIN' },
        { sequence: 2, approverRoleCode: 'ORG_ADMIN' },
      ],
    };
    const r = await req('PUT', `/approvals/policies/uid/${expPol.uid}`, ctx.token, updateBody);
    if (!ok(r)) {
      push(r.status, r.raw, `approvals/policy/update EXPENSE_REPORT`);
    } else {
      log(`approvals: expense policy updated (2-step chain now)`);
    }
  }

  // â”€â”€ 2. Configure PurchaseSettings (enable approval gate + threshold) â”€â”€â”€â”€â”€â”€â”€â”€
  log('approvals: configuring purchase approval gate...');
  // Threshold: 500,000 TZS â€” POs at or above this will be sent to the engine
  const settingsBody = {
    companyUid: ctx.companyUid,
    poApprovalEnabled: true,
    poApprovalThresholdAmount: 500000,
    currency: 'TZS',
  };
  const settingsR = await req('PUT', '/purchase-settings', ctx.token, settingsBody);
  if (ok(settingsR)) {
    created.purchaseSettings++;
    log('approvals: purchase settings updated â€” approval enabled, threshold=500,000 TZS');
  } else {
    push(settingsR.status, settingsR.raw, 'approvals/purchase-settings/update');
    issues.push('approvals: PurchaseSettings update failed â€” PO approval trigger may not fire');
  }

  // â”€â”€ 3. Create approver users + assign branch + grant ORG_ADMIN â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('approvals: creating approver users...');

  const approverDefs = [
    { username: 'appr_mgr_qa', displayName: 'Approval Manager QA', email: 'appr.mgr@qa.erp.local', password: 'Approver12345!' },
    { username: 'appr_dir_qa', displayName: 'Approval Director QA', email: 'appr.dir@qa.erp.local', password: 'Approver12345!' },
  ];

  const approvers = [];
  for (let i = 0; i < approverDefs.length; i++) {
    const def = approverDefs[i];
    const r = await req('POST', '/users', ctx.token, {
      username: def.username,
      displayName: def.displayName,
      password: def.password,
      email: def.email,
    });
    if (!ok(r) || !data(r)) {
      push(r.status, r.raw, `approvals/user/create ${def.username}`);
      continue;
    }
    const userUid = data(r).uid;
    const userId  = data(r).id;

    // Assign to branch (required for branch-scoped token login and SoD step routing)
    const abR = await req('POST', '/user-branches', ctx.token, {
      userUid: userUid,
      branchUid: ctx.branchUid,
      makeDefault: true,
    });
    if (!ok(abR)) {
      push(abR.status, abR.raw, `approvals/user-branch/assign ${def.username}`);
    }

    // Grant ORG_ADMIN role (so they hold the approverRoleCode used in policy steps)
    if (orgAdminRoleUid) {
      const grR = await req('POST', '/user-roles', ctx.token, {
        userUid: userUid,
        roleUid: orgAdminRoleUid,
        companyUid: ctx.companyUid,
        branchUid: null,
      });
      if (!ok(grR)) {
        push(grR.status, grR.raw, `approvals/user-role/grant ${def.username}`);
      }
    }

    // Login to obtain a token for this approver
    const loginR = await req('POST', '/auth/login', null, {
      username: def.username,
      password: def.password,
    });
    let approverToken = null;
    if (ok(loginR) && loginR.body && loginR.body.data && loginR.body.data.accessToken) {
      approverToken = loginR.body.data.accessToken;
    } else {
      push(loginR.status, loginR.raw, `approvals/auth/login ${def.username}`);
      issues.push(`approvals: could not get token for ${def.username} â€” approve/reject steps using this user will be skipped`);
    }

    approvers.push({ ...def, uid: userUid, id: userId, token: approverToken });
    created.approverUsers++;
    log(`approvals: approver user "${def.displayName}" created (uid=${userUid}, hasToken=${!!approverToken})`);
  }

  // â”€â”€ 4. Seed POs to trigger approval requests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Requires ctx.supplierUids and ctx.productUids from tier-1 seeder
  const supplierUids = Array.isArray(ctx.supplierUids) ? ctx.supplierUids : [];
  const productUids  = Array.isArray(ctx.productUids)  ? ctx.productUids  : [];

  if (!supplierUids.length || !productUids.length) {
    issues.push('approvals: ctx.supplierUids or ctx.productUids empty â€” skipping PO-based approval request seeding; policies are seeded and visible');
    log('approvals: WARNING â€” no suppliers or products in ctx; skipping PO trigger phase');
  } else {
    log('approvals: seeding POs to trigger approval requests...');

    const supplierUid = supplierUids[0];
    const productUid  = productUids[0];

    // First discover a unit uid for PO lines
    let unitUid = null;
    {
      const r = await req('GET', `/units?companyId=${ctx.companyId}`, ctx.token, null);
      if (ok(r)) {
        const list = Array.isArray(data(r)) ? data(r) : (Array.isArray(r.body && r.body.data) ? r.body.data : []);
        if (list.length) unitUid = list[0].uid;
      }
      if (!unitUid) {
        issues.push('approvals: could not resolve unitUid for PO lines â€” PO seeding skipped');
        log('approvals: WARNING â€” no unit of measure found; skipping PO trigger phase');
        // jump to request list section
        return finalize();
      }
    }

    // PO scenarios:
    // A  â€” total 650,000 TZS  (mid band) â†’ 2-step approval â†’ APPROVE step 1 â†’ advance to step 2 â†’ APPROVE step 2 â†’ APPROVED
    // B  â€” total 750,000 TZS  (mid band) â†’ REJECT step 1 â†’ REJECTED
    // C  â€” total 820,000 TZS  (mid band) â†’ leave PENDING (inbox entry)
    // D  â€” total 100,000 TZS  (below threshold 500k, if low-band policy min=0 catches it)
    //       actually 100,000 < 500,000 threshold in settings â†’ auto-approved by gate (no policy match needed)
    //       NOTE: the settings threshold gates the PO gate; the engine still matches a policy if one covers the amount
    //       Low-band policy covers [0, 500,000) â€” so 100,000 gets policy match (1-step approval)
    //       But PO gate (requiresApproval) returns false for totals below threshold â†’ auto-approved at PO level
    //       So POs below settings threshold (500k) just get placed without engine submission.
    //       To exercise the engine we need amounts >= 500k.
    // D  â€” 6,500,000 TZS (top band, 3-step) â†’ CANCEL via admin (shows CANCELLED state)
    // E  â€” 580,000 TZS (mid band, 2-step) â†’ place, then recall (shows RECALLED â€” submitter recalls own)
    //       NOTE: recall service enforces caller must be submitter. rootadmin is the submitter here.
    // F  â€” 200,000 TZS (below threshold) â†’ placed as normal ORDERED PO, no approval request (shows auto-approved flow)

    const poScenarios = [
      { label: 'PO-APR-A', qty: 1, unitCost: 650000, currency: 'TZS', action: 'approve-chain', expectedNotes: 'Mid-band 2-step â€” fully approved' },
      { label: 'PO-APR-B', qty: 1, unitCost: 750000, currency: 'TZS', action: 'reject',        expectedNotes: 'Mid-band 2-step â€” rejected at step 1' },
      { label: 'PO-APR-C', qty: 1, unitCost: 820000, currency: 'TZS', action: 'leave-pending', expectedNotes: 'Mid-band 2-step â€” left PENDING in inbox' },
      { label: 'PO-APR-D', qty: 1, unitCost: 6500000, currency: 'TZS', action: 'cancel',       expectedNotes: 'Top-band 3-step â€” admin-cancelled' },
      { label: 'PO-APR-E', qty: 1, unitCost: 580000, currency: 'TZS', action: 'recall',        expectedNotes: 'Mid-band 2-step â€” recalled by submitter' },
      { label: 'PO-APR-F', qty: 1, unitCost: 200000, currency: 'TZS', action: 'below-threshold', expectedNotes: 'Below 500k threshold â€” PO placed directly (no engine submission)' },
    ];

    const approverToken1 = approvers.length > 0 ? approvers[0].token : null;

    for (let s = 0; s < poScenarios.length; s++) {
      const scenario = poScenarios[s];
      log(`approvals: creating PO scenario ${scenario.label} â€” ${scenario.expectedNotes}`);

      // Create DRAFT PO
      const poBody = {
        companyUid: ctx.companyUid,
        supplierUid: supplierUid,
        currency: scenario.currency,
        notes: `[SEED] ${scenario.label}: ${scenario.expectedNotes}`,
        expectedDate: '2026-07-15',
        lines: [],
      };
      const poR = await req('POST', '/purchase-orders', ctx.token, poBody);
      if (!ok(poR) || !data(poR)) {
        push(poR.status, poR.raw, `approvals/purchase-order/create ${scenario.label}`);
        continue;
      }
      const poUid = data(poR).uid;

      // Add a line (single line, total = unitCost)
      const lineBody = {
        productUid: productUid,
        unitUid: unitUid,
        orderedQty: scenario.qty,
        unitCostAmount: scenario.unitCost,
        note: `Seed line for ${scenario.label}`,
      };
      const lineR = await req('POST', `/purchase-orders/uid/${poUid}/lines`, ctx.token, lineBody);
      if (!ok(lineR)) {
        push(lineR.status, lineR.raw, `approvals/purchase-order/addLine ${scenario.label}`);
        // clean up: void the PO
        await req('POST', `/purchase-orders/uid/${poUid}/void`, ctx.token, { companyUid: ctx.companyUid, reason: 'seed cleanup â€” line add failed' });
        continue;
      }

      // Place the order â€” this triggers approval engine internally for above-threshold POs
      const placeR = await req('POST', `/purchase-orders/uid/${poUid}/place`, ctx.token, null);
      if (!ok(placeR)) {
        // Place may fail if PO requires pre-approval (settings gate): that means the gate
        // returned true but approval not yet granted. This should NOT happen here because
        // we call place FIRST and the gate submits for approval on place.
        // If it does fail, log and continue.
        push(placeR.status, placeR.raw, `approvals/purchase-order/place ${scenario.label}`);
        continue;
      }
      created.purchaseOrders++;
      const placedPo = data(placeR);
      log(`approvals: PO ${scenario.label} placed â€” status=${placedPo && placedPo.status}, approvalStatus=${placedPo && placedPo.approvalStatus}`);

      // For below-threshold scenario, no approval request was created â€” done
      if (scenario.action === 'below-threshold') {
        log(`approvals: ${scenario.label} â€” placed directly (below threshold), no approval request`);
        continue;
      }

      // Retrieve the approval request uid from the PO's approvalRequestUid field
      const approvalRequestUid = placedPo && placedPo.approvalRequestUid;
      if (!approvalRequestUid) {
        issues.push(`approvals: ${scenario.label} â€” PO placed but no approvalRequestUid on response (gate may not have triggered)`);
        continue;
      }
      created.approvalRequests++;
      log(`approvals: ${scenario.label} â€” approval request uid=${approvalRequestUid}`);

      // Verify request exists + get current state
      const arR = await req('GET', `/approvals/requests/uid/${approvalRequestUid}`, ctx.token, null);
      if (!ok(arR) || !data(arR)) {
        push(arR.status, arR.raw, `approvals/request/getByUid ${scenario.label}`);
      }
      const arData = arR && data(arR);
      const arStatus = arData && arData.status;
      log(`approvals: ${scenario.label} â€” request status=${arStatus}`);

      // Execute lifecycle action
      if (scenario.action === 'leave-pending') {
        // Nothing more to do â€” request stays PENDING for inbox display
        log(`approvals: ${scenario.label} â€” left PENDING in approvals inbox`);

      } else if (scenario.action === 'approve-chain') {
        // Approve each step sequentially using approver token (NOT rootadmin â€” SoD)
        if (!approverToken1) {
          issues.push(`approvals: ${scenario.label} â€” no approver token; skipping approve-chain`);
        } else {
          let chainDone = false;
          let maxSteps = 5;
          while (!chainDone && maxSteps-- > 0) {
            // Re-fetch request to see current open step
            const stateR = await req('GET', `/approvals/requests/uid/${approvalRequestUid}`, ctx.token, null);
            if (!ok(stateR) || !data(stateR)) {
              push(stateR.status, stateR.raw, `approvals/${scenario.label}/state-check`);
              break;
            }
            const st = data(stateR);
            if (st.status !== 'PENDING') {
              log(`approvals: ${scenario.label} â€” chain resolved to ${st.status}`);
              chainDone = true;
              break;
            }
            // Approve current open step
            const approveR = await req(
              'POST',
              `/approvals/requests/uid/${approvalRequestUid}/approve`,
              approverToken1,
              { action: 'APPROVE', comment: `[SEED] QA approval of ${scenario.label}` }
            );
            if (ok(approveR) && data(approveR)) {
              const newStatus = data(approveR).status;
              log(`approvals: ${scenario.label} â€” step approved, request now ${newStatus}`);
              if (newStatus !== 'PENDING') {
                chainDone = true;
                created.approvalRequestsApproved++;
              }
            } else {
              push(approveR.status, approveR.raw, `approvals/${scenario.label}/approve-step`);
              break;
            }
          }
          if (!chainDone) {
            issues.push(`approvals: ${scenario.label} â€” approval chain did not fully resolve (may need more approver steps)`);
          }
        }

      } else if (scenario.action === 'reject') {
        if (!approverToken1) {
          issues.push(`approvals: ${scenario.label} â€” no approver token; skipping reject`);
        } else {
          const rejectR = await req(
            'POST',
            `/approvals/requests/uid/${approvalRequestUid}/reject`,
            approverToken1,
            { action: 'REJECT', comment: `[SEED] QA rejection of ${scenario.label} â€” exceeds budget` }
          );
          if (ok(rejectR) && data(rejectR)) {
            created.approvalRequestsRejected++;
            log(`approvals: ${scenario.label} â€” request REJECTED, status=${data(rejectR).status}`);
          } else {
            push(rejectR.status, rejectR.raw, `approvals/${scenario.label}/reject`);
          }
        }

      } else if (scenario.action === 'recall') {
        // Recall by submitter (rootadmin submitted the PO, so rootadmin recalls)
        const recallR = await req(
          'POST',
          `/approvals/requests/uid/${approvalRequestUid}/recall`,
          ctx.token,
          null
        );
        if (ok(recallR) && data(recallR)) {
          created.approvalRequestsRecalled++;
          log(`approvals: ${scenario.label} â€” request RECALLED, status=${data(recallR).status}`);
        } else {
          push(recallR.status, recallR.raw, `approvals/${scenario.label}/recall`);
        }

      } else if (scenario.action === 'cancel') {
        // Admin cancel (rootadmin has APPROVALS.ADMIN via ORG_ADMIN)
        const cancelR = await req(
          'POST',
          `/approvals/requests/uid/${approvalRequestUid}/cancel`,
          ctx.token,
          null
        );
        if (ok(cancelR) && data(cancelR)) {
          created.approvalRequestsCancelled++;
          log(`approvals: ${scenario.label} â€” request CANCELLED, status=${data(cancelR).status}`);
        } else {
          push(cancelR.status, cancelR.raw, `approvals/${scenario.label}/cancel`);
        }
      }
    }

    // â”€â”€ 5. Spot-check: list all approval requests + inbox â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    log('approvals: listing approval requests (spot-check)...');
    const listR = await req('GET', `/approvals/requests?companyId=${ctx.companyId}&page=0&size=20`, ctx.token, null);
    if (ok(listR)) {
      const items = listR.body && listR.body.data;
      const count = Array.isArray(items) ? items.length : '?';
      log(`approvals: requests list returned ${count} items`);
    } else {
      push(listR.status, listR.raw, 'approvals/requests/list');
    }

    // List PENDING requests specifically
    const pendingR = await req('GET', `/approvals/requests?companyId=${ctx.companyId}&status=PENDING&page=0&size=20`, ctx.token, null);
    if (ok(pendingR)) {
      const items = pendingR.body && pendingR.body.data;
      const count = Array.isArray(items) ? items.length : '?';
      log(`approvals: PENDING requests: ${count}`);
    }

    // Check inbox using approver token
    if (approverToken1) {
      const inboxR = await req('GET', '/approvals/requests/inbox?page=0&size=20', approverToken1, null);
      if (ok(inboxR)) {
        const items = inboxR.body && inboxR.body.data;
        const count = Array.isArray(items) ? items.length : '?';
        log(`approvals: inbox (approver1) returned ${count} actionable items`);
      } else {
        push(inboxR.status, inboxR.raw, 'approvals/requests/inbox');
      }
    }
  }

  // â”€â”€ 6. Spot-check: list policies â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function finalize() {
    // nothing â€” just falls through
  }

  log('approvals: listing policies (spot-check)...');
  const polListR = await req('GET', `/approvals/policies?companyId=${ctx.companyId}&page=0&size=20`, ctx.token, null);
  if (ok(polListR)) {
    const items = polListR.body && polListR.body.data;
    const count = Array.isArray(items) ? items.length : '?';
    log(`approvals: policies list returned ${count} items`);
  } else {
    push(polListR.status, polListR.raw, 'approvals/policies/list');
  }

  // Filter by document type
  const poPolListR = await req('GET', `/approvals/policies?companyId=${ctx.companyId}&documentType=PURCHASE_ORDER&page=0&size=20`, ctx.token, null);
  if (ok(poPolListR)) {
    const items = poPolListR.body && poPolListR.body.data;
    const count = Array.isArray(items) ? items.length : '?';
    log(`approvals: PURCHASE_ORDER policies: ${count}`);
  }

  log('approvals: seeding complete.');

  return { created, issues };
}

// ===== documents -> seedDocuments =====
async function seedDocuments(req, ctx, log) {
  const created = {};
  const issues  = [];
  const push    = (k, n) => { created[k] = (created[k] || 0) + (n == null ? 1 : n); };
  const warn    = (msg, r) => issues.push(msg + ' | status=' + (r ? r.status : '?') + ' ' + ((r && r.raw) ? String(r.raw).slice(0, 80) : ''));

  // â”€â”€ helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const bodyData = (r) => (r && r.body && r.body.data) ? r.body.data : null;
  // Some endpoints (templates list, branding, credit-note) return the DTO directly
  // (ApiResponseAdvice wraps them at runtime, so body.data is the record/array).
  const bodyDirect = (r) => {
    if (!r || !r.body) return null;
    if (r.body.data !== undefined) return r.body.data;
    return r.body;
  };

  log('[documents] starting â€” branding + templates + render PO/invoice/GR/delivery/credit-note/AR-statement');

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 1 â€” update branding profile (singleton, already seeded by bootstrap)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[documents] fetching branding profile...');
  let brandingVersion = 0;
  let brandingId = null;
  const bGet = await req('GET', '/documents/branding', ctx.token);
  if (bGet.status >= 300) {
    warn('GET /documents/branding failed', bGet);
  } else {
    const bd = bodyDirect(bGet);
    brandingVersion = (bd && bd.version != null) ? Number(bd.version) : 0;
    brandingId      = bd ? (bd.id || null) : null;
    log('[documents] branding version=' + brandingVersion + ' id=' + brandingId);
  }

  const bPut = await req('PUT', '/documents/branding', ctx.token, {
    displayName:  'Zana Trading Co. Ltd',
    legalName:    'Zana Trading Company Limited',
    taxId:        '100-123-456',
    addressLine1: 'Plot 45, Samora Avenue',
    addressLine2: 'P.O. Box 7890',
    city:         'Dar es Salaam',
    region:       'Dar es Salaam',
    country:      'Tanzania',
    postalCode:   '11101',
    contactPhone: '+255 22 212 3456',
    contactEmail: 'accounts@zanatrading.co.tz',
    website:      'https://www.zanatrading.co.tz',
    logoRef:      null,
    footerTerms:  'Payment due within 30 days. Late payments subject to 2% monthly interest. Thank you for your business.',
    bankDetails:  'CRDB Bank | A/C 0150123456789 | Branch: Kariakoo | Swift: CORUTZTZ',
    version:      brandingVersion
  });
  if (bPut.status >= 300) {
    warn('PUT /documents/branding failed', bPut);
  } else {
    push('branding_updated');
    log('[documents] branding updated');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 2 â€” list templates, then rename two of them (INVOICE â†’ "TAX INVOICE", PO â†’ "PURCHASE ORDER")
  // and leave the rest, ensuring INVOICE + PO are ACTIVE for rendering
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[documents] fetching templates...');
  const tGet = await req('GET', '/documents/templates', ctx.token);
  let templates = [];
  if (tGet.status >= 300) {
    warn('GET /documents/templates failed', tGet);
  } else {
    const td = bodyDirect(tGet);
    templates = Array.isArray(td) ? td : [];
    log('[documents] found ' + templates.length + ' templates');
  }

  // We want to update a couple of templates for cosmetic variety on the QA screen
  const wantUpdate = [
    { documentType: 'INVOICE',        title: 'ZANA TAX INVOICE',        status: 'ACTIVE'    },
    { documentType: 'PURCHASE_ORDER', title: 'ZANA PURCHASE ORDER',      status: 'ACTIVE'    },
    { documentType: 'GOODS_RECEIPT',  title: 'GOODS RECEIVED NOTE',      status: 'ACTIVE'    },
    { documentType: 'DELIVERY_NOTE',  title: 'DELIVERY NOTE',            status: 'ACTIVE'    },
    { documentType: 'CREDIT_NOTE',    title: 'CREDIT / RETURN NOTE',     status: 'ACTIVE'    },
    { documentType: 'AR_STATEMENT',   title: 'CUSTOMER ACCOUNT STATEMENT', status: 'ACTIVE'  },
  ];

  for (const wish of wantUpdate) {
    const tmpl = templates.find(t => t.documentType === wish.documentType);
    if (!tmpl) {
      log('[documents] template ' + wish.documentType + ' not found â€” skipping update');
      continue;
    }
    const tmplVersion = tmpl.version != null ? Number(tmpl.version) : 0;
    const tPut = await req('PUT', '/documents/templates/' + tmpl.uid, ctx.token, {
      title:      wish.title,
      status:     wish.status,
      brandingId: brandingId ? Number(brandingId) : null,
      version:    tmplVersion
    });
    if (tPut.status >= 300) {
      warn('PUT /documents/templates/' + tmpl.uid + ' (' + wish.documentType + ') failed', tPut);
    } else {
      push('templates_updated');
      log('[documents] template ' + wish.documentType + ' updated â†’ "' + wish.title + '"');
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 3 â€” resolve or create minimal source documents for rendering
  //
  // Priority: use ctx.supplierUids / ctx.customerUids / ctx.productUids if present.
  // Fall back to creating minimal prerequisite records ourselves.
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

  // â”€â”€ 3a: resolve unit uid (needed for PO lines + invoice lines) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[documents] resolving unit uid...');
  let unitUid = null;
  const uList = await req('GET', '/units?companyId=' + ctx.companyId, ctx.token);
  if (uList.status < 300 && uList.body && uList.body.data) {
    const uArr = Array.isArray(uList.body.data) ? uList.body.data : (uList.body.data.content || []);
    unitUid = uArr.length > 0 ? uArr[0].uid : null;
  }
  if (!unitUid) {
    warn('Could not resolve any unit uid â€” line items will be omitted', uList);
  }

  // â”€â”€ 3b: resolve or create a supplier â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let supplierUid = (ctx.supplierUids && ctx.supplierUids.length > 0) ? ctx.supplierUids[0] : null;
  if (!supplierUid) {
    log('[documents] creating fallback supplier...');
    const sr = await req('POST', '/suppliers', ctx.token, {
      companyId:   Number(ctx.companyId),
      partyType:   'ORGANISATION',
      displayName: 'Docs Seed Supplier',
      supplierKind:'GOODS'
    });
    if (sr.status < 300 && bodyData(sr)) {
      supplierUid = bodyData(sr).uid;
      push('suppliers_created');
    } else {
      warn('create fallback supplier failed', sr);
    }
  }

  // â”€â”€ 3c: resolve or create a customer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let customerUid = (ctx.customerUids && ctx.customerUids.length > 0) ? ctx.customerUids[0] : null;
  if (!customerUid) {
    log('[documents] creating fallback customer...');
    const cr = await req('POST', '/customers', ctx.token, {
      companyId:   Number(ctx.companyId),
      partyType:   'ORGANISATION',
      displayName: 'Docs Seed Customer',
      customerKind:'CREDIT_ACCOUNT'
    });
    if (cr.status < 300 && bodyData(cr)) {
      customerUid = bodyData(cr).uid;
      push('customers_created');
    } else {
      warn('create fallback customer failed', cr);
    }
  }

  // â”€â”€ 3d: resolve or create a product â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let productUid = (ctx.productUids && ctx.productUids.length > 0) ? ctx.productUids[0] : null;
  if (!productUid && unitUid) {
    log('[documents] creating fallback product...');
    const pr = await req('POST', '/products', ctx.token, {
      companyUid:   ctx.companyUid,
      name:         'Docs Seed Product',
      type:         'GOODS',
      sellable:     true,
      stockable:    true,
      baseUnitUid:  unitUid,
      vatStatus:    'STANDARD'
    });
    if (pr.status < 300 && bodyData(pr)) {
      productUid = bodyData(pr).uid;
      push('products_created');
    } else {
      warn('create fallback product failed', pr);
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 4 â€” Create + advance a Purchase Order to ORDERED state
  //          (renderable when status != DRAFT)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let poUid        = null;
  let poLineUid    = null;
  let grUid        = null;

  if (supplierUid && productUid && unitUid) {
    log('[documents] creating Purchase Order for rendering...');
    const poBody = {
      companyUid:   ctx.companyUid,
      supplierUid:  supplierUid,
      currency:     'TZS',
      notes:        'Demo PO for documents module seed',
      expectedDate: '2026-07-15',
      lines: [{
        productUid:      productUid,
        unitUid:         unitUid,
        orderedQty:      '50',
        unitCostAmount:  '12000',
        note:            null
      }]
    };
    const poR = await req('POST', '/purchase-orders', ctx.token, poBody);
    if (poR.status < 300 && bodyData(poR)) {
      poUid = bodyData(poR).uid;
      push('purchase_orders_created');
      log('[documents] PO created uid=' + poUid);

      // place the order (DRAFT â†’ ORDERED â€” renderable from this state)
      const placeR = await req('POST', '/purchase-orders/uid/' + poUid + '/place', ctx.token);
      if (placeR.status >= 300) {
        warn('PO place failed for ' + poUid, placeR);
        // still renderable if ORDERED check is relaxed, but mark issue
      } else {
        log('[documents] PO placed (ORDERED)');
      }

      // fetch PO lines so we can create a GR
      const linesR = await req('GET', '/purchase-orders/uid/' + poUid + '/lines', ctx.token);
      if (linesR.status < 300) {
        const la = bodyData(linesR);
        const lArr = Array.isArray(la) ? la : (la && la.content ? la.content : []);
        poLineUid = lArr.length > 0 ? lArr[0].uid : null;
      }

      // â”€â”€ 4b: create a Goods Receipt (RECEIVED state â€” renderable) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
      if (poLineUid) {
        log('[documents] creating Goods Receipt...');
        const grR = await req('POST', '/goods-receipts', ctx.token, {
          purchaseOrderUid: poUid,
          notes:            'Demo GR for documents module seed',
          lines: [{
            purchaseOrderLineUid: poLineUid,
            receivedQty:          '50'
          }]
        });
        if (grR.status < 300 && bodyData(grR)) {
          grUid = bodyData(grR).uid;
          push('goods_receipts_created');
          log('[documents] GR created uid=' + grUid);
        } else {
          warn('create GR failed', grR);
        }
      }
    } else {
      warn('create PO failed', poR);
    }
  } else {
    log('[documents] skipping PO/GR creation â€” missing supplier/product/unit');
    issues.push('skipped PO/GR render: no supplier or product or unit available');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 5 â€” Create + finalise a Sales Invoice (FINALISED state â€” renderable)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let invoiceUid = null;

  if (customerUid && productUid && unitUid) {
    log('[documents] creating Sales Invoice for rendering...');
    const invR = await req('POST', '/sales-invoices', ctx.token, {
      companyUid:  ctx.companyUid,
      customerUid: customerUid,
      currency:    'TZS',
      notes:       'Demo invoice for documents module seed'
    });
    if (invR.status < 300 && bodyData(invR)) {
      invoiceUid = bodyData(invR).uid;
      log('[documents] invoice created uid=' + invoiceUid);

      // add a line
      const alR = await req('POST', '/sales-invoices/uid/' + invoiceUid + '/lines', ctx.token, {
        productUid: productUid,
        unitUid:    unitUid,
        quantity:   '3'
      });
      if (alR.status >= 300) {
        warn('invoice add-line failed for ' + invoiceUid, alR);
      }

      // read invoice to get gross total for payment
      const invGet = await req('GET', '/sales-invoices/uid/' + invoiceUid, ctx.token);
      const invDto = bodyDirect(invGet);
      const gross  = (invDto && invDto.grossTotalAmount) ? String(invDto.grossTotalAmount) : '50000';

      // add a cash payment covering the gross total
      const payR = await req('POST', '/sales-invoices/uid/' + invoiceUid + '/payments', ctx.token, {
        tenderType: 'CASH',
        amount:     gross,
        currency:   'TZS',
        reference:  'DOC-SEED-PAY'
      });
      if (payR.status >= 300) {
        warn('invoice payment failed for ' + invoiceUid, payR);
      }

      // finalise (DRAFT â†’ FINALISED)
      const finR = await req('PUT', '/sales-invoices/uid/' + invoiceUid + '/finalize', ctx.token, {});
      if (finR.status >= 300 && finR.status !== 204) {
        warn('invoice finalise failed for ' + invoiceUid, finR);
        invoiceUid = null; // can't render a DRAFT
      } else {
        push('invoices_finalised');
        log('[documents] invoice finalised uid=' + invoiceUid);
      }
    } else {
      warn('create invoice failed', invR);
    }
  } else {
    log('[documents] skipping invoice creation â€” missing customer/product/unit');
    issues.push('skipped invoice render: no customer or product or unit available');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 6 â€” Create an AR Credit Note (any posted state â€” always renderable)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let creditNoteUid = null;

  if (customerUid) {
    log('[documents] creating AR Credit Note for rendering...');
    const cnR = await req('POST', '/ar/credit-notes', ctx.token, {
      companyUid:  ctx.companyUid,
      customerUid: customerUid,
      arInvoiceUid: null,
      noteDate:    '2026-06-10',
      netAmount:   '25000',
      vatAmount:   '4500',
      currency:    'TZS',
      reason:      'Goods returned â€” demo seed'
    });
    if (cnR.status < 300) {
      // credit-note controller returns DTO directly (no data wrapper sometimes)
      const cnDto = bodyDirect(cnR);
      creditNoteUid = cnDto ? cnDto.uid : null;
      if (creditNoteUid) {
        push('credit_notes_created');
        log('[documents] credit note created uid=' + creditNoteUid);
      } else {
        warn('credit note created but uid not extractable', cnR);
      }
    } else {
      warn('create credit note failed', cnR);
    }
  } else {
    log('[documents] skipping credit note â€” no customer available');
    issues.push('skipped credit-note render: no customer available');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 7 â€” Create a Delivery Note source via Sales Order â†’ Delivery
  //          (delivery is created CONFIRMED â€” always renderable)
  // We need a sales order first. This is optional â€” if it fails, we skip.
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let deliveryUid = null;

  if (customerUid && productUid && unitUid) {
    log('[documents] attempting to create Sales Order â†’ Delivery for delivery-note render...');
    // Create a sales order
    const soR = await req('POST', '/sales-orders', ctx.token, {
      companyUid:  ctx.companyUid,
      customerUid: customerUid,
      currency:    'TZS',
      notes:       'Demo SO for documents module seed'
    });
    if (soR.status < 300 && bodyData(soR)) {
      const soUid = bodyData(soR).uid;
      log('[documents] SO created uid=' + soUid);

      // add a line to the SO
      const solR = await req('POST', '/sales-orders/uid/' + soUid + '/lines', ctx.token, {
        productUid: productUid,
        unitUid:    unitUid,
        quantity:   '5',
        unitPrice:  '15000'
      });
      if (solR.status >= 300) {
        warn('SO add-line failed for delivery seed', solR);
      }

      // confirm the SO if needed
      const soConfR = await req('POST', '/sales-orders/uid/' + soUid + '/confirm', ctx.token);
      if (soConfR.status >= 300 && soConfR.status !== 204) {
        // Some versions don't need explicit confirm â€” continue anyway
        log('[documents] SO confirm returned ' + soConfR.status + ' â€” continuing');
      }

      // create delivery
      const delR = await req('POST', '/deliveries', ctx.token, {
        salesOrderUid: soUid,
        notes:         'Demo delivery for documents module seed',
        lines: [{
          salesOrderLineUid: null, // service may accept auto-resolve from SO
          productUid:        productUid,
          unitUid:           unitUid,
          deliveredQty:      '5'
        }]
      });
      if (delR.status < 300) {
        const delDto = bodyDirect(delR);
        deliveryUid = delDto ? delDto.uid : null;
        if (deliveryUid) {
          push('deliveries_created');
          log('[documents] delivery created uid=' + deliveryUid);
        }
      } else {
        warn('create delivery failed', delR);
        // delivery render will be skipped
      }
    } else {
      warn('create sales order for delivery failed', soR);
      issues.push('skipped delivery-note render: could not create sales order');
    }
  } else {
    issues.push('skipped delivery-note render: no customer/product/unit');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 8 â€” Render documents (POST /documents/render)
  //          Each render creates a generated_documents log row (append-only)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const renderedUids = [];

  const renderDoc = async (documentType, sourceUid, sourceParams, label) => {
    if (!sourceUid && !sourceParams) {
      log('[documents] skipping render ' + label + ' â€” no source uid');
      issues.push('render ' + label + ' skipped: source uid not available');
      return null;
    }
    log('[documents] rendering ' + label + '...');
    const rr = await req('POST', '/documents/render', ctx.token, {
      documentType: documentType,
      sourceUid:    sourceUid || null,
      sourceParams: sourceParams || null
    });
    if (rr.status < 300) {
      const dto = bodyDirect(rr);
      const uid = dto ? dto.uid : null;
      if (uid) {
        renderedUids.push(uid);
        push('documents_rendered');
        log('[documents] rendered ' + label + ' â†’ doc uid=' + uid + ' number=' + (dto.documentNumber || '?'));
        return uid;
      } else {
        warn('render ' + label + ' returned 2xx but no uid', rr);
        return null;
      }
    } else {
      warn('render ' + label + ' failed', rr);
      return null;
    }
  };

  // Render PURCHASE_ORDER (needs ORDERED+ status)
  await renderDoc('PURCHASE_ORDER', poUid, null, 'PURCHASE_ORDER');

  // Render GOODS_RECEIPT (needs RECEIVED status â€” GR is created in RECEIVED state by createAndReceive)
  await renderDoc('GOODS_RECEIPT', grUid, null, 'GOODS_RECEIPT');

  // Render INVOICE (needs FINALISED status)
  await renderDoc('INVOICE', invoiceUid, null, 'INVOICE');

  // Render CREDIT_NOTE (any posted state)
  await renderDoc('CREDIT_NOTE', creditNoteUid, null, 'CREDIT_NOTE');

  // Render DELIVERY_NOTE (always CONFIRMED in v1)
  await renderDoc('DELIVERY_NOTE', deliveryUid, null, 'DELIVERY_NOTE');

  // Render AR_STATEMENT (parameterised â€” customerUid + asAt date)
  if (customerUid) {
    const stmtParams = JSON.stringify({ customerUid: customerUid, asAt: '2026-06-13' });
    await renderDoc('AR_STATEMENT', null, stmtParams, 'AR_STATEMENT');
  } else {
    issues.push('skipped AR_STATEMENT render: no customerUid');
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 9 â€” List the generated-documents log (exercises the GET / paginated endpoint)
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  log('[documents] listing generated documents log...');
  const listR = await req('GET', '/documents?companyId=' + ctx.companyId + '&page=0&size=20', ctx.token);
  if (listR.status < 300) {
    const listData = listR.body && listR.body.data ? listR.body.data : [];
    log('[documents] generated-docs list returned ' + listData.length + ' rows');
  } else {
    warn('GET /documents list failed', listR);
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 10 â€” Re-fetch one rendered doc by uid (exercises GET /{uid})
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  if (renderedUids.length > 0) {
    const firstUid = renderedUids[0];
    log('[documents] fetching single generated doc by uid=' + firstUid);
    const getR = await req('GET', '/documents/' + firstUid, ctx.token);
    if (getR.status >= 300) {
      warn('GET /documents/' + firstUid + ' failed', getR);
    } else {
      log('[documents] single doc fetch OK');
    }
  }

  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // STEP 11 â€” Second render pass: render a second INVOICE (void one to show VOID watermark)
  //           We create a second invoice, add a line, finalise it, then void it, then render.
  // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  if (customerUid && productUid && unitUid) {
    log('[documents] creating second invoice (will be voided) for VOID watermark demo...');
    const inv2R = await req('POST', '/sales-invoices', ctx.token, {
      companyUid:  ctx.companyUid,
      customerUid: customerUid,
      currency:    'TZS',
      notes:       'Demo invoice to be voided â€” documents seed'
    });
    if (inv2R.status < 300 && bodyData(inv2R)) {
      const inv2Uid = bodyData(inv2R).uid;

      // add a line
      await req('POST', '/sales-invoices/uid/' + inv2Uid + '/lines', ctx.token, {
        productUid: productUid,
        unitUid:    unitUid,
        quantity:   '1'
      });

      // read gross, pay, finalise
      const inv2Get = await req('GET', '/sales-invoices/uid/' + inv2Uid, ctx.token);
      const inv2Dto = bodyDirect(inv2Get);
      const gross2  = (inv2Dto && inv2Dto.grossTotalAmount) ? String(inv2Dto.grossTotalAmount) : '18000';

      await req('POST', '/sales-invoices/uid/' + inv2Uid + '/payments', ctx.token, {
        tenderType: 'CASH',
        amount:     gross2,
        currency:   'TZS',
        reference:  'DOC-SEED-VOID-PAY'
      });

      const fin2R = await req('PUT', '/sales-invoices/uid/' + inv2Uid + '/finalize', ctx.token, {});

      // void the invoice
      const void2R = await req('PUT', '/sales-invoices/uid/' + inv2Uid + '/void', ctx.token, {
        reason: 'Test void for document watermark demo'
      });

      // render it (VOID state is renderable â€” produces watermark)
      if (void2R.status < 300 || void2R.status === 204) {
        await renderDoc('INVOICE', inv2Uid, null, 'INVOICE(VOID-watermark)');
      } else {
        log('[documents] void invoice returned ' + void2R.status + ' â€” skipping void render');
      }
    }
  }

  log('[documents] seed complete. created=' + JSON.stringify(created) + ' issues=' + issues.length);
  return { created, issues };
}


// ===== notifications -> seedNotifications =====
async function seedNotifications(req, ctx, log) {
  const created = {};
  const issues = [];

  const fail = (status, raw, label) => {
    issues.push(`${label}: status=${status} ${String(raw || '').slice(0, 80)}`);
  };

  // -------------------------------------------------------------------------
  // STEP 1: List the notification type catalogue for this company.
  //         Types are seeded server-side on company bootstrap (NotificationTypeSeeder).
  //         We read them, then toggle some to exercise the admin state UI.
  // -------------------------------------------------------------------------
  log('notifications: fetching type catalogue');
  const typesR = await req('GET', '/admin/notifications/types', ctx.token, null);
  let typeKeys = [];
  if (typesR.status >= 300 || !typesR.body || !typesR.body.data) {
    fail(typesR.status, typesR.raw, 'list notification types');
    // Cannot proceed without knowing typeKeys â€” use the known canonical set as fallback.
    typeKeys = [
      'GOODS_RECEIVED',
      'DELIVERY_CONFIRMED',
      'PAYMENT_RECEIVED',
      'INVOICE_OVERDUE',
      'LOW_STOCK',
      'APPROVAL_PENDING'
    ];
    log('notifications: type catalogue unavailable, using canonical fallback list');
  } else {
    typeKeys = (typesR.body.data || []).map(t => t.typeKey).filter(Boolean);
    log(`notifications: found ${typeKeys.length} type(s) in catalogue`);
  }

  if (typeKeys.length === 0) {
    issues.push('type catalogue empty â€” no typeKeys to work with; skipping preferences and toggles');
    return { created, issues };
  }

  // -------------------------------------------------------------------------
  // STEP 2: Toggle company-type enabled state to show varied states in the UI.
  //         - Disable LOW_STOCK (if present) so the admin screen shows at least one
  //           SUPPRESSED/disabled type.
  //         - Re-enable INVOICE_OVERDUE (in case it was toggled off) so it is visibly ON.
  //         - Leave the rest as-is (already enabled by bootstrap).
  // -------------------------------------------------------------------------
  created.typeToggles = 0;

  const toggleType = async (typeKey, enabled) => {
    if (!typeKeys.includes(typeKey)) return;
    log(`notifications: setting ${typeKey} enabled=${enabled}`);
    const r = await req(
      'PUT',
      `/admin/notifications/types/${typeKey}/state`,
      ctx.token,
      { enabled }
    );
    if (r.status >= 300) {
      fail(r.status, r.raw, `toggle type ${typeKey} enabled=${enabled}`);
    } else {
      created.typeToggles += 1;
    }
  };

  // Disable LOW_STOCK â€” gives QA a row with companyEnabled=false to render.
  await toggleType('LOW_STOCK', false);

  // Disable INVOICE_OVERDUE briefly, then re-enable â€” exercises the toggle both ways.
  await toggleType('INVOICE_OVERDUE', false);
  await toggleType('INVOICE_OVERDUE', true);

  // Disable DELIVERY_CONFIRMED â€” second disabled type for a more interesting list.
  await toggleType('DELIVERY_CONFIRMED', false);

  // -------------------------------------------------------------------------
  // STEP 3: Set per-user notification preferences for several typeKeys.
  //         The endpoint is an upsert (creates on first call, updates on repeat).
  //         We create preferences that cover three scenarios QA cares about:
  //           a) muted=false  + explicit channels    â€” active, custom channels
  //           b) muted=false  + null channelsEnabled  â€” active, default channels
  //           c) muted=true   + channelsEnabled set   â€” muted (channels ignored)
  // -------------------------------------------------------------------------
  created.preferences = 0;

  const prefScenarios = [
    // typeKey               muted   channelsEnabled
    ['GOODS_RECEIVED',       false,  'IN_APP,EMAIL'],
    ['PAYMENT_RECEIVED',     false,  'IN_APP,EMAIL'],
    ['APPROVAL_PENDING',     false,  'IN_APP'],
    ['LOW_STOCK',            true,   'IN_APP'],       // muted â€” QA sees muted row
    ['INVOICE_OVERDUE',      false,  null],            // null = use type defaults
    ['DELIVERY_CONFIRMED',   true,   'IN_APP,EMAIL'],  // muted + disabled type combo
  ];

  for (const [typeKey, muted, channelsEnabled] of prefScenarios) {
    if (!typeKeys.includes(typeKey)) {
      log(`notifications: skipping preference for ${typeKey} (not in catalogue)`);
      continue;
    }
    log(`notifications: setting preference for ${typeKey} muted=${muted}`);
    const body = { muted, channelsEnabled };
    const r = await req(
      'PUT',
      `/notification-preferences/${typeKey}`,
      ctx.token,
      body
    );
    if (r.status >= 300) {
      fail(r.status, r.raw, `set preference for ${typeKey}`);
    } else {
      created.preferences += 1;
    }
  }

  // -------------------------------------------------------------------------
  // STEP 4: Read back preferences list to confirm they persisted.
  // -------------------------------------------------------------------------
  log('notifications: reading back preference list');
  const prefsListR = await req('GET', '/notification-preferences', ctx.token, null);
  const prefsCount = (prefsListR.status < 300 && prefsListR.body && prefsListR.body.data)
    ? (prefsListR.body.data || []).length
    : 0;
  log(`notifications: preference list returned ${prefsCount} row(s)`);

  // -------------------------------------------------------------------------
  // STEP 5: Fetch the in-app inbox.
  //         Notifications are system-generated; on a fresh QA box the inbox may be
  //         empty. We fetch it anyway to exercise the endpoint and exercise any
  //         notifications that do exist through the mark-read lifecycle.
  // -------------------------------------------------------------------------
  log('notifications: fetching inbox (all, page 0 size 50)');
  const inboxR = await req(
    'GET',
    '/notifications?page=0&size=50',
    ctx.token,
    null
  );
  let inboxItems = [];
  if (inboxR.status >= 300 || !inboxR.body) {
    fail(inboxR.status, inboxR.raw, 'fetch inbox');
  } else {
    inboxItems = inboxR.body.data || [];
    log(`notifications: inbox has ${inboxItems.length} item(s)`);
  }

  // -------------------------------------------------------------------------
  // STEP 6: Fetch unread count (exercises the badge endpoint).
  // -------------------------------------------------------------------------
  log('notifications: fetching unread count');
  const countR = await req('GET', '/notifications/unread-count', ctx.token, null);
  const unreadCount = (countR.status < 300 && countR.body && countR.body.data)
    ? countR.body.data.count
    : 'n/a';
  log(`notifications: unread count = ${unreadCount}`);

  // -------------------------------------------------------------------------
  // STEP 7: Fetch the unread-only slice of the inbox.
  // -------------------------------------------------------------------------
  log('notifications: fetching unread-only inbox slice');
  const unreadR = await req(
    'GET',
    '/notifications?unread=true&page=0&size=50',
    ctx.token,
    null
  );
  let unreadItems = [];
  if (unreadR.status < 300 && unreadR.body && unreadR.body.data) {
    unreadItems = unreadR.body.data || [];
    log(`notifications: unread inbox has ${unreadItems.length} item(s)`);
  }

  // -------------------------------------------------------------------------
  // STEP 8: Mark-read lifecycle.
  //         If there are unread notifications, mark the first one read individually
  //         (exercises POST /notifications/uid/{uid}/read), then mark-all-read to
  //         leave the inbox in a "clean" state that QA can observe.
  //         If the inbox is empty, mark-all-read is still safe (idempotent no-op).
  // -------------------------------------------------------------------------
  created.markedRead = 0;

  // Mark the first unread individually.
  const firstUnread = unreadItems.find(n => n && n.uid && !n.read);
  if (firstUnread) {
    log(`notifications: marking single notification read uid=${firstUnread.uid}`);
    const markR = await req(
      'POST',
      `/notifications/uid/${firstUnread.uid}/read`,
      ctx.token,
      null
    );
    // 204 = success; no body.
    if (markR.status === 204 || markR.status === 200) {
      created.markedRead += 1;
    } else {
      fail(markR.status, markR.raw, `mark-read uid=${firstUnread.uid}`);
    }
  }

  // Mark-all-read â€” idempotent, always safe.
  log('notifications: calling mark-all-read');
  const markAllR = await req('POST', '/notifications/read-all', ctx.token, null);
  if (markAllR.status === 204 || markAllR.status === 200) {
    // Count remaining unread items (all, minus the one already individually marked).
    const remaining = unreadItems.filter(n => n && !n.read && (!firstUnread || n.uid !== firstUnread.uid));
    created.markedRead += remaining.length;
  } else {
    fail(markAllR.status, markAllR.raw, 'mark-all-read');
  }

  // -------------------------------------------------------------------------
  // STEP 9: Verify inbox is now empty of unread items.
  // -------------------------------------------------------------------------
  log('notifications: verifying inbox unread count after mark-all-read');
  const postCountR = await req('GET', '/notifications/unread-count', ctx.token, null);
  const postUnread = (postCountR.status < 300 && postCountR.body && postCountR.body.data)
    ? postCountR.body.data.count
    : 'n/a';
  log(`notifications: unread count after mark-all = ${postUnread}`);

  // -------------------------------------------------------------------------
  // STEP 10: Admin â€” fetch the delivery log (paged audit trail).
  //          Exercises GET /admin/notifications/deliveries with no filters (all),
  //          then with outcome=FAILED filter (exercises the filter path).
  // -------------------------------------------------------------------------
  log('notifications: fetching delivery log (all outcomes, page 0 size 20)');
  const deliveriesR = await req(
    'GET',
    '/admin/notifications/deliveries?page=0&size=20',
    ctx.token,
    null
  );
  const deliveryCount = (deliveriesR.status < 300 && deliveriesR.body && deliveriesR.body.data)
    ? (deliveriesR.body.data || []).length
    : 0;
  log(`notifications: delivery log page-0 returned ${deliveryCount} row(s)`);

  log('notifications: fetching delivery log filtered by outcome=FAILED');
  const failedDeliveriesR = await req(
    'GET',
    '/admin/notifications/deliveries?outcome=FAILED&page=0&size=20',
    ctx.token,
    null
  );
  const failedCount = (failedDeliveriesR.status < 300 && failedDeliveriesR.body && failedDeliveriesR.body.data)
    ? (failedDeliveriesR.body.data || []).length
    : 0;
  log(`notifications: FAILED deliveries on page-0 = ${failedCount}`);

  log('notifications: fetching delivery log filtered by channel=EMAIL');
  const emailDeliveriesR = await req(
    'GET',
    '/admin/notifications/deliveries?channel=EMAIL&page=0&size=20',
    ctx.token,
    null
  );
  const emailCount = (emailDeliveriesR.status < 300 && emailDeliveriesR.body && emailDeliveriesR.body.data)
    ? (emailDeliveriesR.body.data || []).length
    : 0;
  log(`notifications: EMAIL deliveries on page-0 = ${emailCount}`);

  // -------------------------------------------------------------------------
  // Summary
  // -------------------------------------------------------------------------
  created.inboxItemsObserved = inboxItems.length;
  created.unreadObserved = unreadItems.length;
  created.deliveryLogRowsObserved = deliveryCount;

  log(`notifications: done â€” typeToggles=${created.typeToggles}, preferences=${created.preferences}, markedRead=${created.markedRead}, issues=${issues.length}`);

  return { created, issues };
}



const SUMMARY = {};
async function runSeed(name, fn) {
  console.log(`\n=== SEED: ${name} ===`);
  try {
    const r = await fn(req, CTX, log);
    SUMMARY[name] = r || { created: {}, issues: [] };
    const created = (r && r.created) ? r.created : {};
    const issues = (r && r.issues) ? r.issues : [];
    console.log(`  created: ${JSON.stringify(created)}`);
    if (issues.length) { console.log(`  issues (${issues.length}):`); issues.slice(0, 8).forEach(i => console.log('    - ' + String(i).slice(0, 140))); }
  } catch (e) {
    SUMMARY[name] = { created: {}, issues: ['THREW: ' + String(e && e.message || e).slice(0, 160)] };
    console.log('  ERROR (caught): ' + String(e && e.message || e).slice(0, 200));
  }
}

let CTX = {};
(async () => {
  console.log('=== Phase-B seeder against ' + B + ' ===');
  // 1. login
  const lr = await req('POST', '/auth/login', null, { username: ROOT_USER, password: ROOT_PASS });
  const token = dataOf(lr) && dataOf(lr).accessToken;
  if (!token) { console.error('LOGIN FAILED: ' + lr.status + ' ' + String(lr.raw).slice(0, 120)); process.exit(1); }
  log('logged in as ' + ROOT_USER);

  // 2. discover company / branch context
  const orgs = await req('GET', '/organisations', token, null);
  const org = (dataOf(orgs) || [])[0];
  const companiesR = await req('GET', '/companies?organisationUid=' + (org ? org.uid : ''), token, null);
  const company = (dataOf(companiesR) || [])[0];
  // Branches live at /branches?companyUid=… (NOT nested under /companies/{uid})
  let branchesR = await req('GET', '/branches?companyUid=' + (company ? company.uid : ''), token, null);
  if (!ok(branchesR) || !dataOf(branchesR)) branchesR = await req('GET', '/branches?companyId=' + (company ? company.id : ''), token, null);
  const branches = dataOf(branchesR) || [];
  const defBranch = branches.find(b => b.isDefault) || branches[0] || {};

  // 3. discover existing Tier-1 data (products/customers/suppliers/users) — best-effort, paginated
  async function collectUids(path, max) {
    const out = [];
    for (let page = 0; page < 5 && out.length < max; page++) {
      const r = await req('GET', `${path}${path.includes('?') ? '&' : '?'}companyId=${company.id}&page=${page}&size=100`, token, null);
      const rows = dataOf(r) || (r.body && Array.isArray(r.body) ? r.body : []);
      if (!Array.isArray(rows) || rows.length === 0) break;
      rows.forEach(x => { if (x && x.uid) out.push(x.uid); });
      if (rows.length < 100) break;
    }
    return out.slice(0, max);
  }
  const productUids  = await collectUids('/products', 60);
  const customerUids = await collectUids('/customers', 40);
  const supplierUids = await collectUids('/suppliers', 40);

  CTX = {
    token,
    companyId: String(company ? company.id : ''),
    companyUid: company ? company.uid : '',
    branchUid: defBranch.uid || '',
    branchId: String(defBranch.id || ''),
    branches,
    productUids, customerUids, supplierUids,
    userIds: [],
  };
  console.log(`  ctx: company=${CTX.companyUid}(id ${CTX.companyId}) branch=${CTX.branchUid} ` +
              `products=${productUids.length} customers=${customerUids.length} suppliers=${supplierUids.length}`);

  // 4. run each module seeder (order respects data dependencies)
  await runSeed('cost-centre', seedCostCentre);
  await runSeed('fixed-assets', seedFixedAssets);
  await runSeed('hr-payroll', seedHrPayroll);
  await runSeed('projects', seedProjects);
  await runSeed('budgeting', seedBudgeting);
  await runSeed('manufacturing', seedManufacturing);
  await runSeed('crm', seedCrm);
  await runSeed('approvals', seedApprovals);
  await runSeed('documents', seedDocuments);
  await runSeed('notifications', seedNotifications);

  // 5. summary
  console.log('\n================ PHASE-B SEED SUMMARY ================');
  let totalIssues = 0;
  for (const [m, r] of Object.entries(SUMMARY)) {
    const c = r.created || {}; const iss = (r.issues || []).length; totalIssues += iss;
    const counts = Object.entries(c).map(([k, v]) => `${k}=${v}`).join(', ');
    console.log(`  ${m}: ${counts || '(nothing)'}${iss ? `  [${iss} issue(s)]` : ''}`);
  }
  console.log(`\n  total module issues: ${totalIssues}`);
  require('fs').writeFileSync(require('os').tmpdir() + '/qa-phaseb-summary.json', JSON.stringify(SUMMARY, null, 2));
  console.log('  wrote ' + require('os').tmpdir() + '/qa-phaseb-summary.json');
})();
