import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { once } from 'node:events';
const upstream=http.createServer((req,res)=>{assert.equal(req.headers.authorization,undefined);res.setHeader('Content-Type','application/json');res.end('{"counter":1,"pets":{},"sections":{}}');});
upstream.listen(0,'127.0.0.1');await once(upstream,'listening');
const portHolder=http.createServer();portHolder.listen(0,'127.0.0.1');await once(portHolder,'listening');const bridgePort=portHolder.address().port;await new Promise(r=>portHolder.close(r));
const child=spawn(process.execPath,['scripts/dsh-phone-bridge.mjs','--port',String(bridgePort),'--upstream',`http://127.0.0.1:${upstream.address().port}`],{stdio:['ignore','pipe','pipe']});
try{
  await Promise.race([once(child.stdout,'data'),new Promise((_,reject)=>setTimeout(()=>reject(new Error('Bridge startup timeout')),10000).unref())]);
  const token=(await readFile(new URL('../.local/bridge-token.txt',import.meta.url),'utf8')).trim();
  const base=`http://127.0.0.1:${bridgePort}`;const headers={Authorization:`Bearer ${token}`};
  assert.equal((await fetch(base+'/dsh-pet-7340/state')).status,401);
  assert.equal((await fetch(base+'/shutdown',{method:'POST',headers})).status,403);
  assert.equal((await fetch(base+'/dsh-pet-7340/state',{headers:{...headers,Origin:'https://example.com'}})).status,403);
  const response=await fetch(base+'/dsh-pet-7340/state',{headers});assert.equal(response.status,200);assert.deepEqual(await response.json(),{counter:1,pets:{},sections:{}});
  assert.equal((await fetch(base+'/dsh-pet-7340/chat',{method:'POST',headers,body:'x'.repeat(65537)})).status,413);
  console.log('PASS: bridge authentication, route allowlist, browser-origin rejection, upstream forwarding without token leakage, request size limit.');
}finally{child.kill();upstream.close();}
