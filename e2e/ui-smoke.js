const { chromium } = require('playwright-core');
const fs = require('fs'); const path = require('path');
const EXE = fs.readdirSync('C:/Users/Godfrey/AppData/Local/ms-playwright').filter(d=>/^chromium-\d/.test(d)).sort().pop();
const CHROME = `C:/Users/Godfrey/AppData/Local/ms-playwright/${EXE}/chrome-win/chrome.exe`;
const CHROME2 = `C:/Users/Godfrey/AppData/Local/ms-playwright/${EXE}/chrome-win64/chrome.exe`;
const BIN = fs.existsSync(CHROME) ? CHROME : CHROME2;
const BASE=process.env.WEB_BASE||'http://127.0.0.1:4173'; const SHOTS=process.env.SHOTS_DIR||(require('os').tmpdir()+'/erp-verify-shots');
const RUSER=process.env.ROOT_USER||'rootadmin'; const RPASS=process.env.ROOT_PASS||'RootPass12345';
fs.mkdirSync(SHOTS,{recursive:true});
const res=[]; const cerr=[]; const nerr=[];
const log=(s,ok,d)=>{res.push({s,ok});console.log(`${ok?'PASS':'FAIL'} | ${s} | ${d}`);};
const shot=(p,n)=>p.screenshot({path:path.join(SHOTS,n+'.png'),fullPage:true});
(async()=>{
  const b=await chromium.launch({executablePath:BIN,headless:true});
  const pg=await b.newPage();
  pg.on('console',m=>{if(m.type()==='error')cerr.push(m.text());});
  pg.on('response',r=>{if(r.url().includes('/api/')&&r.status()>=500)nerr.push(`${r.status()} ${r.request().method()} ${r.url().replace(BASE,'')}`);});
  try{
    // login
    await pg.goto(BASE+'/login',{waitUntil:'networkidle'});
    await pg.fill('input[type="text"]',RUSER); await pg.fill('input[type="password"]',RPASS);
    await pg.click('button[type="submit"]'); await pg.waitForTimeout(1500);
    log('login', !pg.url().includes('/login'), pg.url().replace(BASE,''));

    // screenshot each NEW screen renders (200, no crash)
    for (const [route,name] of [['/admin/stock','01-stock'],['/admin/purchase-orders','02-purchase-orders'],['/admin/goods-receipts','03-goods-receipts'],['/admin/routes','04-routes']]) {
      await pg.goto(BASE+route,{waitUntil:'networkidle'}); await pg.waitForTimeout(900); await shot(pg,name);
      const crashed = await pg.locator('text=/cannot|undefined is not|ERROR TypeError/i').first().isVisible().catch(()=>false);
      log('screen renders '+route, !crashed, crashed?'page error text visible':'rendered');
    }

    // ROUTES flow: create a route via UI
    await pg.goto(BASE+'/admin/routes',{waitUntil:'networkidle'}); await pg.waitForTimeout(800);
    const compSel=pg.locator('#route-company-select');
    if (await compSel.isVisible().catch(()=>false)) { const o=await compSel.locator('option').count(); if(o>1) await compSel.selectOption({index:1}); await pg.waitForTimeout(500); }
    const newBtn=pg.locator('button',{hasText:/New Route/i}).first();
    if (await newBtn.isVisible().catch(()=>false)) { await newBtn.click(); await pg.waitForTimeout(300); }
    await pg.fill('#new-route-name','Kariakoo North').catch(()=>{});
    await pg.fill('#new-route-location','Kariakoo, Dar').catch(()=>{});
    await shot(pg,'05-route-create');
    await pg.locator('button',{hasText:/^\s*Save\s*$/}).first().click();
    await pg.waitForTimeout(1500);
    const routeShown = await pg.locator('text=Kariakoo North').first().isVisible().catch(()=>false);
    log('Routes: create route', routeShown, `route row visible=${routeShown}`);
    await shot(pg,'06-route-created');

    // open the route detail + assign customer + agent
    await pg.locator('a',{hasText:/Kariakoo North|ROUTE-/i}).first().click().catch(async()=>{await pg.locator('table a').first().click();});
    await pg.waitForLoadState('networkidle'); await pg.waitForTimeout(900);
    await shot(pg,'07-route-detail');
    log('Routes: detail opens', await pg.locator('text=/Customers|Agents|Branches/i').first().isVisible().catch(()=>false), 'assignment panels present');

  }catch(e){ log('FATAL',false,String(e&&e.stack||e)); await shot(pg,'ZZ-error'); }
  finally{
    console.log('\n== CONSOLE ERRORS =='); console.log(cerr.length?cerr.slice(0,8).join('\n'):'(none)');
    console.log('== API 5xx =='); console.log(nerr.length?nerr.join('\n'):'(none)');
    console.log(`== ${res.filter(r=>r.ok).length}/${res.length} PASS ==`);
    console.log('SHOTS='+SHOTS);
    await b.close(); process.exit(0);
  }
})();
