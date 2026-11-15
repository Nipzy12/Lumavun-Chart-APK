'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {MT5McpRemote}=require('./mt5-mcp-client.cjs');
const n=require('./mt5-native-adapter.cjs');
function fakeTerminal(){
 const calls=[];
 const descriptions=[
  {name:'get_marketwatch_symbols',inputSchema:{type:'object',properties:{}}},
  {name:'get_trading_account_info',inputSchema:{type:'object',properties:{}}},
  {name:'get_trading_open_positions',inputSchema:{type:'object',properties:{}}},
  {name:'get_chart_history',inputSchema:{type:'object',properties:{
   symbol:{type:'string'},period:{type:'string',enum:['M5','M15','H1']},count:{type:'integer'}},
   required:['symbol','period']}},
  {name:'trade_send_market_order',inputSchema:{type:'object',properties:{symbol:{type:'string'}}}},
  {name:'shell',inputSchema:{type:'object',properties:{command:{type:'string'}}}}
 ];
 const apiKey='native-test-key-0123456789abcdef';
 const responses={get_marketwatch_symbols:[
  {symbol:'BTCUSDc',bid:65432.1,ask:65433.5,description:'Bitcoin CFDs'},
  {symbol:'XAUUSDm',bid:4066.8,ask:4067.1,description:'Gold CFDs'}
 ],get_trading_account_info:{login:3337,trade_mode:'DEMO',balance:1000.1,
    equity:998.8,margin_free:900,currency:'USD',server:'Broker-Demo'},
 get_trading_open_positions:{positions:[
  {ticket:557,symbol:'BTCUSDc',side:'BUY',volume:.01,price_open:65400,price_current:65433,profit:1.2}
 ]},
 get_chart_history:{bars:Array.from({length:70},(_,i)=>({
  time:1700000000+i*900,open:65000+i,high:65005+i,low:64995+i,close:65002+i,tick_volume:200+i
 }))}};
 const server=http.createServer(async (req,res)=>{
  let chunks='';for await(const c of req)chunks+=c;
  const parsed=JSON.parse(chunks);
  res.setHeader('Content-Type','application/json');
  res.setHeader('Mcp-Session-Id','testing-session');
  if(req.headers.authorization!=='Bearer '+apiKey){
   res.writeHead(401);res.end(JSON.stringify({error:'unauthorized'}));return;
  }
  if(parsed.method==='notifications/initialized'){res.writeHead(202);res.end();return;}
  if(parsed.method==='initialize'){
   res.end(JSON.stringify({jsonrpc:'2.0',id:parsed.id,result:{
    protocolVersion:'2025-06-18',capabilities:{tools:{}},serverInfo:{name:'MetaTrader 5 Terminal',version:'6060'}
   }}));return;
  }
  if(parsed.method==='tools/list'){
   res.end(JSON.stringify({jsonrpc:'2.0',id:parsed.id,result:{tools:descriptions}}));return;
  }
  if(parsed.method==='tools/call'){
   calls.push(parsed.params);
   const result=responses[parsed.params.name];
   res.end(JSON.stringify({jsonrpc:'2.0',id:parsed.id,result:{
    content:[{type:'text',text:JSON.stringify(result)}],isError:false
   }}));return;
  }
  res.writeHead(404);res.end('{}');
 });
 return {server,calls,apiKey,descriptions};
}
test('native tool discovery is read-only and does not include shell or trading actions',()=>{
 const {descriptions}=fakeTerminal();
 const selected=n.selectNativeTools(descriptions);
 assert.deepEqual(Object.keys(selected).sort(),['account','candles','positions','symbols']);
 assert.equal(Object.values(selected).some(v=>v.name.includes('trade_send')),false);
});
test('native MT5 MCP URL and API key connect, discover schema and feed chart/account read-only',async()=>{
 const {server,calls,apiKey}=fakeTerminal();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const url='http://127.0.0.1:'+server.address().port+'/mcp';
 const client=new MT5McpRemote();
 try{
  const status=await client.connect(url,apiKey);
  assert.equal(status.connected,true);assert.equal(status.mode,'MT5_NATIVE');
  assert.equal(status.serverName,'MetaTrader 5 Terminal');
  assert.equal(status.tools.includes('ticker'),true);
  const account=await client.call('account');
  assert.equal(account.mode,'DEMO');assert.equal(account.account,'3337');
  const quote=await client.call('ticker',{symbol:'BTCUSDc'});
  assert.equal(quote.bid,65432.1);assert.equal(quote.provider,'MT5');
  const candles=await client.call('candles',{symbol:'BTCUSDc',interval:'15m',limit:70});
  assert.equal(candles.length,70);assert.equal(candles[0].time,1700000000);
  const positions=await client.call('positions');
  assert.equal(positions.length,1);assert.equal(positions[0].ticket,'557');
  const syms=await client.call('symbols',{query:'XAU'});
  assert.equal(syms[0].symbol,'XAUUSDm');
  assert.deepEqual(calls.filter(c=>c.name==='get_chart_history')[0].arguments,{
   symbol:'BTCUSDc',period:'M15',count:70
  });
  await assert.rejects(client.call('execute_order',{symbol:'BTCUSDc'}),/not permitted/);
  await assert.rejects(client.call('trade_send_market_order',{symbol:'BTCUSDc'}),/not permitted/);
  await assert.rejects(client.call('shell',{command:'whoami'}),/not permitted/);
  assert.equal(calls.some(x=>x.name.startsWith('trade_')||x.name==='shell'),false);
 }finally{client.disconnect();await new Promise(resolve=>server.close(resolve));}
});
test('unknown required native parameters fail closed without calling the tool',()=>{
 const tool={name:'get_chart_history',inputSchema:{
  properties:{symbol:{type:'string'},timeframe:{type:'string'},unsafe_command:{type:'string'}},
  required:['symbol','timeframe','unsafe_command']}};
 assert.throws(()=>n.buildArgs(tool,{symbol:'BTCUSDc',interval:'15m'}),/unsupported parameter/);
 assert.throws(()=>n.buildArgs(tool,{symbol:'BTCUSDc;DROP',interval:'15m'}),/Invalid MT5/);
});
test('native parser rejects malformed candles and unverified quotes instead of fabricating prices',()=>{
 assert.throws(()=>n.normalizeBars({bars:[{time:1700000000,open:1,high:0,low:1,close:1}]}),/no usable OHLC/);
 assert.throws(()=>n.normalizeQuote([{symbol:'BTCUSDc',last:65432}], 'BTCUSDc'),/valid bid\/ask/);
 const data=n.normalizeBars({bars:[{time:1700000000,open:100,high:105,low:99,close:102,tick_volume:10}]});
 assert.deepEqual(data,[{time:1700000000,open:100,high:105,low:99,close:102,volume:10}]);
});
test('server key is not stored after disconnect',async()=>{
 const {server,apiKey}=fakeTerminal();await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const client=new MT5McpRemote();
 try{
  await client.connect('http://127.0.0.1:'+server.address().port+'/mcp',apiKey);
  client.disconnect();
  assert.equal(client.apiKey,'');
  assert.equal(client.status().connected,false);
 }finally{await new Promise(resolve=>server.close(resolve));}
});
