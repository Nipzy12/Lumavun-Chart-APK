const test=require('node:test');
const assert=require('node:assert/strict');
const {MT5McpHttpServer,TOOL_NAMES,correctSecret,validatePort}=require('./mt5-mcp-http.cjs');
const {MT5McpRemote,validateMcpUrl,validateApiKey}=require('./mt5-mcp-client.cjs');

function fake(){
 const calls=[];
 const bridge={async call(name,args){calls.push({name,args});
  if(name==='ticker')return {provider:'MT5',symbol:args.symbol,bid:99.99,ask:100.01,lastPrice:100,verifiedBidAsk:true};
  if(name==='candles')return Array.from({length:35},(_,i)=>({time:1700000000+i*900,open:100,high:102,low:99,close:101,volume:5}));
  if(name==='account')return {account:'DEMO-123',mode:'DEMO'};
  if(name==='positions')return [];
  if(name==='symbols')return [{symbol:'XAUUSDm',name:'Gold',status:'ACTIVE',category:'MT5'}];
  if(name==='preview_order')return {checked:true,symbol:args.symbol};
  throw Error('Unexpected bridge operation');
 }};
 return {calls,bridge};
}
const rpc=(method,params={},id=1)=>JSON.stringify({jsonrpc:'2.0',id,method,params});
function post(url,key,body,moreHeaders={}){
 return fetch(url,{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json, text/event-stream',
  ...(key?{'Authorization':'Bearer '+key}:{}),...moreHeaders},body});
}
test('server exposes an authenticated loopback-only MCP endpoint and no order_send tool',async()=>{
 const {calls,bridge}=fake();const server=new MT5McpHttpServer({bridge});
 const {url,apiKey}=await server.start();
 try{
  assert.match(url,/^http:\/\/127\.0\.0\.1:\d+\/mcp$/);
  assert.equal(apiKey.length>=40,true);
  assert.equal(TOOL_NAMES.has('execute_order'),false);
  const missing=await post(url,null,rpc('tools/list'));
  assert.equal(missing.status,401);
  const wrong=await post(url,'x'.repeat(43),rpc('tools/list'));
  assert.equal(wrong.status,401);
  const good=await post(url,apiKey,rpc('tools/list'));
  assert.equal(good.status,200);
  const tools=(await good.json()).result.tools.map(x=>x.name);
  assert.deepEqual(tools,['account','symbols','ticker','candles','positions','preview_order']);
  const call=await post(url,apiKey,rpc('tools/call',{name:'execute_order',arguments:{}}));
  assert.equal((await call.json()).error.code,-32601);
  assert.equal(calls.length,0);
 }finally{await server.stop();}
});
test('the app connects using entered internal URL and API key and reads broker data',async()=>{
 const {bridge,calls}=fake();const server=new MT5McpHttpServer({bridge});
 const {url,apiKey}=await server.start();
 const client=new MT5McpRemote();
 try{
  const connected=await client.connect(url,apiKey);
  assert.equal(connected.connected,true);
  assert.equal(connected.readOnly,true);
  const quote=await client.call('ticker',{symbol:'XAUUSDm'});
  assert.equal(quote.bid,99.99);
  const candles=await client.call('candles',{symbol:'XAUUSDm',interval:'15m',limit:100});
  assert.equal(candles.length,35);
  assert.equal(calls.length,2);
  await assert.rejects(client.call('execute_order',{}),/not permitted/);
  const disconnected=client.disconnect();
  assert.equal(disconnected.connected,false);
  await assert.rejects(client.call('ticker',{symbol:'XAUUSDm'}),/No MCP/);
 }finally{await server.stop();}
});
test('server rejects foreign Origin and nonstandard Host, key stays required for health',async()=>{
 const {bridge}=fake();const server=new MT5McpHttpServer({bridge});
 const {url,apiKey}=await server.start();
 try{
  const foreign=await post(url,apiKey,rpc('tools/list'),{Origin:'https://evil.example'});
  assert.equal(foreign.status,403);
  const health=await fetch(url.replace('/mcp','/health'));
  assert.equal(health.status,401);
  const accepted=await fetch(url.replace('/mcp','/health'),{headers:{Authorization:'Bearer '+apiKey}});
  assert.equal(accepted.status,200);
  assert.equal((await accepted.json()).readOnly,true);
 }finally{await server.stop();}
});
test('rotate key by restarting server and block invalid port and weak keys',async()=>{
 const {bridge}=fake();const server=new MT5McpHttpServer({bridge});
 const old=await server.start();
 await server.stop();
 const next=await server.start();
 try{
  assert.notEqual(old.apiKey,next.apiKey);
  assert.equal((await post(next.url,old.apiKey,rpc('ping'))).status,401);
  assert.equal((await post(next.url,next.apiKey,rpc('ping'))).status,200);
  assert.throws(()=>validatePort(80),/port/);
  assert.throws(()=>validateApiKey('short'),/API key/);
  assert.equal(correctSecret(next.apiKey,'Bearer '+next.apiKey),true);
 }finally{await server.stop();}
});
test('only loopback HTTP and HTTPS endpoints are valid for connecting MCP',()=>{
 assert.equal(validateMcpUrl('http://127.0.0.1:7878/mcp'),'http://127.0.0.1:7878/mcp');
 assert.equal(validateMcpUrl('https://mcp.internal.example/mcp'),'https://mcp.internal.example/mcp');
 for(const raw of ['http://192.168.1.2:7878/mcp','http://google.com/mcp',
  'http://127.0.0.1:7878/anything','https://user:pass@example.com/mcp',
  'https://example.com/mcp?key=secret','file:///C:/api','http://169.254.169.254/mcp']){
  assert.throws(()=>validateMcpUrl(raw));
 }
});
test('invalid parameters and null error never reach the MT5 bridge',async()=>{
 const {bridge,calls}=fake();const server=new MT5McpHttpServer({bridge});const {url,apiKey}=await server.start();
 try{
  const bad=await post(url,apiKey,rpc('tools/call',{name:'ticker',arguments:{symbol:'BTCUSDT',execute:true}}));
  assert.equal((await bad.json()).error.code,-32602);
  assert.equal(calls.length,0);
  const notification=await post(url,apiKey,JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'}));
  assert.equal(notification.status,202);
 }finally{await server.stop();}
});
