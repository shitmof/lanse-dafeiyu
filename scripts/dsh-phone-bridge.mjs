// Optional LAN bridge. Only the narrow companion API is forwarded; never the DSH admin API.
// Usage: node scripts/dsh-phone-bridge.mjs --host 192.168.1.10 --upstream http://127.0.0.1:3000
import http from 'node:http';
import { randomBytes, timingSafeEqual } from 'node:crypto';
import { mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const args=process.argv.slice(2);
const option=(key,fallback)=>args.includes(key)?args[args.indexOf(key)+1]:fallback;
const host=option('--host','127.0.0.1');
if(!/^(127\.0\.0\.1|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+)$/.test(host))throw new Error('Only a specific loopback or private LAN IPv4 address is allowed.');
const port=Number(option('--port','7341'));
const upstream=new URL(option('--upstream','http://127.0.0.1:3000'));
if(!['localhost','127.0.0.1','[::1]'].includes(upstream.hostname)||upstream.protocol!=='http:'||upstream.username||upstream.password||upstream.search||upstream.hash)throw new Error('The DSH upstream must be a local HTTP service.');
const folder=resolve(root,'.local');mkdirSync(folder,{recursive:true});
const tokenPath=resolve(folder,'bridge-token.txt');
if(!existsSync(tokenPath))writeFileSync(tokenPath,randomBytes(32).toString('hex'),{mode:0o600,flag:'wx'});
const expected=Buffer.from('Bearer '+readFileSync(tokenPath,'utf8').trim());
const allowed=new Set(['GET /dsh-pet-7340/state','POST /dsh-pet-7340/chat','POST /dsh-pet-7340/whisper','POST /dsh-pet-7340/balance']);
const server=http.createServer(async(req,res)=>{
  res.setHeader('Cache-Control','no-store');res.setHeader('Content-Type','application/json; charset=utf-8');
  const supplied=Buffer.from(req.headers.authorization??'');
  if(supplied.length!==expected.length||!timingSafeEqual(supplied,expected)){res.writeHead(401);res.end('{"error":"unauthorized"}');return;}
  const url=new URL(req.url,'http://localhost');
  if(!allowed.has(req.method+' '+url.pathname)||req.headers.origin){res.writeHead(403);res.end('{"error":"route-not-allowed"}');return;}
  try{
    const chunks=[];let size=0;for await(const chunk of req){size+=chunk.length;if(size>65536){res.writeHead(413);res.end('{"error":"too-large"}');return;}chunks.push(chunk);}
    const response=await fetch(new URL(url.pathname+url.search,upstream),{method:req.method,headers:{'Content-Type':'application/json'},body:req.method==='GET'?undefined:Buffer.concat(chunks),signal:AbortSignal.timeout(65000),redirect:'error'});
    const body=await response.text();if(body.length>2097152)throw new Error('too-large');res.writeHead(response.status);res.end(body);
  }catch{res.writeHead(502);res.end('{"error":"upstream-unavailable"}');}
});
server.requestTimeout=70000;server.headersTimeout=15000;
server.listen(port,host,()=>console.log(`DSH phone bridge: http://${host}:${port}\nThe connection token is stored only in ${tokenPath}\nUse on a trusted LAN; keep this port off the public internet.`));
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>server.close(()=>process.exit(0)));
