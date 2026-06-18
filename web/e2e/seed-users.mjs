/**
 * seed-users.mjs — realistic app-user population for the massive-data e2e gate.
 *
 *   "users should create users": root creates a few admins; an admin (NOT root)
 *   then creates the rest of the users — so the USER.CREATE / ROLE.GRANT / BRANCH.ASSIGN
 *   paths are exercised by a non-root user and created_by varies on app_users.
 *   Each scoped user then authors a slice of business data so created_by varies there too.
 *
 * Usage:  cd web && node e2e/seed-users.mjs
 */
import http from 'http';

const BASE   = process.env.API_BASE || 'http://localhost:8081/api/v1';
const ROOT_U = process.env.ROOT_USER || 'rootadmin';
const ROOT_P = process.env.ROOT_PASS || 'RootPass12345';
const PASS   = process.env.USER_PASS || 'UserPass12345';
const TAG    = Date.now().toString().slice(-6);

// discovered from the fresh bootstrap (override via env if needed)
const COMPANY_UID = process.env.COMPANY_UID || '01KVD5RDJMXT317JHFNR49SS13';
const BRANCH_UID  = process.env.BRANCH_UID  || '01KVD5RFKNA8P70KNFV4GNDB3H';
const ADMIN_ROLE  = process.env.ADMIN_ROLE_UID || '0000000000XVKF7J9FAGX51RMQ'; // Organisation Administrator
const COMPANY_ID  = +(process.env.COMPANY_ID || 1);

const N = {
  admins:        +(process.env.N_ADMINS || 3),
  SALES_OFFICER: +(process.env.N_SALES  || 12),
  PROC_OFFICER:  +(process.env.N_PROC   || 10),
  ACCOUNTANT:    +(process.env.N_ACCT   || 10),
  STORE_CTRL:    +(process.env.N_STORE  || 8),
  HR_OFFICER:    +(process.env.N_HR     || 4),
  BRANCH_MGR:    +(process.env.N_MGR    || 3),
};
const DATA = { sales:+(process.env.D_SALES||8), proc:+(process.env.D_PROC||8), acct:+(process.env.D_ACCT||6), store:+(process.env.D_STORE||6) };

const ROLES = [
  { code:'SALES_OFFICER', name:'Sales Officer',       modules:['sales','crm','ar','parties','products','reporting','bi'] },
  { code:'PROC_OFFICER',  name:'Procurement Officer', modules:['purchases','ap','parties','products','stock','reporting','bi'] },
  { code:'ACCOUNTANT',    name:'Accountant',          modules:['gl','ar','ap','tax','cashbank','fx','reporting','bi'] },
  { code:'STORE_CTRL',    name:'Store Controller',    modules:['stock','products','reporting','bi'] },
  { code:'HR_OFFICER',    name:'HR Officer',          modules:['hr','reporting','bi'] },
  { code:'BRANCH_MGR',    name:'Branch Manager',      modules:'VIEW_ALL' },
];
const FIRST=['Asha','Juma','Neema','Baraka','Zawadi','Tumaini','Imani','Saidi','Rehema','Mosi','Pendo','Kito','Amani','Furaha','Bahati'];
const LAST =['Mushi','Kimaro','Massawe','Mwakikagile','Shirima','Mlay','Swai','Nyerere','Mwanga','Komba','Sanga','Mtui'];

function req(method, path, token, body){
  return new Promise(res=>{
    const data = body?JSON.stringify(body):null;
    const u = new URL(BASE+path);
    const r = http.request({hostname:u.hostname,port:u.port,path:u.pathname+u.search,method,
      headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(data?{'Content-Length':Buffer.byteLength(data)}:{})}},
      resp=>{let b='';resp.on('data',c=>b+=c);resp.on('end',()=>{let j;try{j=JSON.parse(b);}catch{j=b;} res({status:resp.statusCode,body:j});});});
    r.on('error',e=>res({status:0,body:String(e)}));
    if(data)r.write(data); r.end();
  });
}
const uidOf=r=>r?.body?.data?.uid||r?.body?.uid;
const listOf=r=>r?.body?.data?.content||r?.body?.data||[];
const snip=r=>JSON.stringify(r?.body)?.slice(0,160);
const rnd=a=>a[Math.floor(Math.random()*a.length)];
const ri=(a,b)=>Math.floor(Math.random()*(b-a+1))+a;
async function login(u,p){const r=await req('POST','/auth/login',null,{username:u,password:p});return r.body?.data?.accessToken;}

async function makeUser(adminTok, username, displayName, roleUid){
  const cu = await req('POST','/users',adminTok,{username,displayName,password:PASS,email:`${username}@erp.local`,phone:`07${ri(10000000,99999999)}`});
  const uid = uidOf(cu);
  if(!uid){ return {ok:false, err:snip(cu)}; }
  await req('POST','/user-branches',adminTok,{userUid:uid,branchUid:BRANCH_UID,makeDefault:true});
  const g = await req('POST','/user-roles',adminTok,{userUid:uid,roleUid,companyUid:COMPANY_UID,branchUid:BRANCH_UID});
  return {ok:true, uid, username, grantOk:(g.status>=200&&g.status<300)};
}

(async()=>{
  const root = await login(ROOT_U,ROOT_P);
  if(!root){console.error('FATAL: root login failed');process.exit(1);}
  console.log(`logged in as ${ROOT_U}`);

  // ── 1. Create roles + set module-scoped permissions ──
  const perms = listOf(await req('GET','/roles/permissions',root));
  console.log(`permission catalogue: ${perms.length}`);
  const roleUid = {};
  for(const role of ROLES){
    const cr = await req('POST','/roles',root,{code:`${role.code}_${TAG}`,name:role.name,description:`${role.name} (e2e seed ${TAG})`});
    const uid = uidOf(cr);
    if(!uid){console.error(`  role ${role.code} FAILED: ${snip(cr)}`);continue;}
    let codes;
    if(role.modules==='VIEW_ALL') codes = perms.filter(p=>p.code.endsWith('.VIEW')||['approvals','reporting','bi'].includes(p.module)).map(p=>p.code);
    else codes = perms.filter(p=>role.modules.includes(p.module)).map(p=>p.code);
    const sp = await req('PUT',`/roles/uid/${uid}/permissions`,root,{permissionCodes:codes});
    roleUid[role.code]=uid;
    console.log(`  role ${role.code}: ${codes.length} perms ${sp.status<300?'ok':'PERM-FAIL '+snip(sp)}`);
  }

  // ── 2. Admin chain: root creates admins; admin#1 creates everyone else ──
  const admins=[];
  for(let i=1;i<=N.admins;i++){
    const r=await makeUser(root,`admin.${rnd(LAST).toLowerCase()}${i}.${TAG}`,`Admin ${rnd(FIRST)} ${rnd(LAST)}`,ADMIN_ROLE);
    if(r.ok)admins.push(r); else console.error(`  admin FAIL: ${r.err}`);
  }
  console.log(`admins created by root: ${admins.length}/${N.admins}`);
  if(!admins.length){console.error('FATAL: no admins');process.exit(1);}
  const adminTok = await login(admins[0].username, PASS);
  if(!adminTok){console.error('FATAL: admin login failed');process.exit(1);}
  console.log(`>>> now acting as admin '${admins[0].username}' — admin creates the rest (users create users)`);

  // ── 3. Admin creates scoped users ──
  const users={SALES_OFFICER:[],PROC_OFFICER:[],ACCOUNTANT:[],STORE_CTRL:[],HR_OFFICER:[],BRANCH_MGR:[]};
  for(const role of ROLES){
    const n=N[role.code]||0;
    for(let i=1;i<=n;i++){
      const uname=`${role.code.toLowerCase()}.${rnd(LAST).toLowerCase()}${i}.${TAG}`;
      const r=await makeUser(adminTok,uname,`${rnd(FIRST)} ${rnd(LAST)} (${role.name})`,roleUid[role.code]);
      if(r.ok)users[role.code].push(r); else console.error(`  ${role.code} FAIL: ${r.err}`);
    }
    console.log(`  ${role.code}: ${users[role.code].length}/${n} (created by admin)`);
  }

  // ── 4. Discover refs for the data slice ──
  const units=listOf(await req('GET',`/units?companyId=${COMPANY_ID}&size=10`,root));
  const unitUid=units[0]?.uid;
  const accs=listOf(await req('GET',`/gl/accounts?companyId=${COMPANY_ID}&size=100`,root));
  const drAcc=accs.find(a=>a.accountCode?.startsWith('5')&&!a.controlType)||accs.find(a=>a.accountCode==='5100');
  const crAcc=accs.find(a=>a.accountCode==='4100')||accs.find(a=>a.accountCode?.startsWith('4')&&!a.controlType);

  // ── 5. Users author a slice of data (created_by varies) ──
  let dc={customers:0,suppliers:0,journals:0,products:0};
  async function asUser(u,fn){const t=await login(u.username,PASS);if(t)await fn(t);}
  for(const u of users.SALES_OFFICER) await asUser(u,async t=>{for(let i=0;i<DATA.sales;i++){const r=await req('POST','/customers',t,{companyId:COMPANY_ID,partyType:'INDIVIDUAL',displayName:`${rnd(FIRST)} ${rnd(LAST)} ${TAG}-${i}`,customerKind:i%3===0?'CASH_WALK_IN':'CREDIT_ACCOUNT',phone:`07${ri(10000000,99999999)}`,email:`c${TAG}${i}@ex.com`});if(uidOf(r))dc.customers++;}});
  for(const u of users.PROC_OFFICER)  await asUser(u,async t=>{for(let i=0;i<DATA.proc;i++){const r=await req('POST','/suppliers',t,{companyId:COMPANY_ID,partyType:'INDIVIDUAL',displayName:`${rnd(FIRST)} ${rnd(LAST)} Supp ${TAG}-${i}`,supplierKind:'GOODS',phone:`06${ri(10000000,99999999)}`});if(uidOf(r))dc.suppliers++;}});
  if(unitUid) for(const u of users.STORE_CTRL) await asUser(u,async t=>{for(let i=0;i<DATA.store;i++){const r=await req('POST','/products',t,{companyUid:COMPANY_UID,name:`Item ${TAG}-${u.username.slice(-3)}-${i}`,type:'GOODS',sellable:true,stockable:true,purchasable:true,baseUnitUid:unitUid,vatStatus:'STANDARD'});if(uidOf(r))dc.products++;}});
  if(drAcc&&crAcc) for(const u of users.ACCOUNTANT) await asUser(u,async t=>{for(let i=0;i<DATA.acct;i++){const amt=ri(50000,2000000)+'';const r=await req('POST','/gl/journals',t,{companyUid:COMPANY_UID,postingDate:new Date(2026,ri(0,5),ri(1,27)).toISOString().slice(0,10),description:`Manual journal by ${u.username} #${i}`,sourceType:'MANUAL',sourceRef:`MJE-${TAG}-${u.username.slice(-3)}-${i}`,lines:[{accountUid:drAcc.uid,debitAmount:amt,creditAmount:0,lineMemo:'DR'},{accountUid:crAcc.uid,debitAmount:0,creditAmount:amt,lineMemo:'CR'}]});if(uidOf(r)||r.status<300)dc.journals++;}});

  // ── Summary ──
  const total=Object.values(users).reduce((a,b)=>a+b.length,0)+admins.length;
  console.log('\n=== SEED-USERS SUMMARY ===');
  console.log(`roles created: ${Object.keys(roleUid).length}`);
  console.log(`users: ${total} total = ${admins.length} admins (by root) + ${total-admins.length} scoped (by admin)`);
  for(const k of Object.keys(users)) console.log(`  ${k}: ${users[k].length}`);
  console.log(`data authored by users: customers=${dc.customers} suppliers=${dc.suppliers} products=${dc.products} journals=${dc.journals}`);
  console.log(`refs: unitUid=${unitUid||'NONE'} drAcc=${drAcc?.accountCode||'NONE'} crAcc=${crAcc?.accountCode||'NONE'}`);
})();
