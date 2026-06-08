// Throwaway static + /api proxy server for browser-verify.
const http = require('http'); const fs = require('fs'); const path = require('path');
const ROOT = path.resolve(process.argv[2] || 'web/dist/web/browser'); const PORT = Number(process.argv[3] || 4173);
const API = { host: '127.0.0.1', port: Number(process.env.API_PORT || 8088) };
const MIME = { '.html':'text/html','.js':'text/javascript','.css':'text/css','.json':'application/json','.ico':'image/x-icon','.svg':'image/svg+xml','.woff':'font/woff','.woff2':'font/woff2','.ttf':'font/ttf','.png':'image/png','.map':'application/json' };
http.createServer((req,res)=>{
  if (req.url.startsWith('/api')) {
    const chunks=[]; req.on('data',c=>chunks.push(c)); req.on('end',()=>{
      const body=Buffer.concat(chunks); const headers={...req.headers, host:`${API.host}:${API.port}`};
      const pr=http.request({host:API.host,port:API.port,path:req.url,method:req.method,headers},pres=>{res.writeHead(pres.statusCode,pres.headers);pres.pipe(res);});
      pr.on('error',e=>{res.writeHead(502);res.end('proxy error: '+e.message);}); if(body.length)pr.write(body); pr.end();
    }); return;
  }
  let urlPath=decodeURIComponent(req.url.split('?')[0]);
  let filePath=path.normalize(path.join(ROOT,urlPath));
  if(!filePath.startsWith(ROOT)){res.writeHead(403);res.end('forbidden');return;}
  fs.stat(filePath,(err,st)=>{ if(err||!st.isFile())filePath=path.join(ROOT,'index.html');
    fs.readFile(filePath,(e2,data)=>{ if(e2){res.writeHead(404);res.end('not found');return;}
      res.writeHead(200,{'Content-Type':MIME[path.extname(filePath)]||'application/octet-stream'}); res.end(data); }); });
}).listen(PORT,'127.0.0.1',()=>console.log(`VERIFY_WEB_UP http://127.0.0.1:${PORT} root=${ROOT}`));
