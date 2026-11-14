const {test}=require('node:test');
const assert=require('node:assert/strict');
const ai=require('./ai-analyst.cjs');
const candles=Array.from({length:80},(_,i)=>({time:1700000000+i*900,open:99+i,high:101+i,low:98+i,close:100+i,volume:10+i}));
const answer=JSON.stringify({decision:'LONG',bias:'BULLISH',confidence:64,summary:'Positive trend',reasoning:['EMA20 > EMA50'],entry:179,stopLoss:175,takeProfit:[187],invalidation:'Below stop'});
test('Ollama is pinned to localhost and custom URL must be public HTTPS',()=>{
 assert.deepEqual(ai.validateConfig({provider:'OLLAMA',model:'qwen2.5:3b'}),{provider:'OLLAMA',model:'qwen2.5:3b',baseUrl:'http://127.0.0.1:11434',apiKey:''});
 for(const url of ['http://provider.example.com/v1','https://localhost/v1','https://127.0.0.1/v1','https://user:pass@example.com/v1','https://api.example.com/v1?q=1','https://example.com:444/v1'])
  assert.throws(()=>ai.validateConfig({provider:'CUSTOM',model:'test',baseUrl:url,apiKey:'key'}),url);
 assert.equal(ai.validateConfig({provider:'CUSTOM',model:'my-model',baseUrl:'https://api.example.com/v1',apiKey:'SECRET'}).apiKey,'SECRET');
});
test('snapshot uses verified ordered OHLC and bounds model context to 50 candles',()=>{
 const snap=ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles});
 assert.equal(snap.candles.length,50);assert.equal(snap.symbol,'BTCUSDT');assert.equal(typeof snap.indicators.rsi14,'number');
 assert.throws(()=>ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles:[]}));
 const invalid=[...candles];invalid[70]={...invalid[70],high:0};
 assert.throws(()=>ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles:invalid}));
});
test('invalid model trade levels produce WAIT not invented execution',()=>{
 const model={provider:'OLLAMA',model:'qwen2.5:3b'},fence=String.fromCharCode(96).repeat(3);
 assert.equal(ai.parseReply(fence+'json\n'+answer+'\n'+fence,model,179).decision,'LONG');
 const bad=JSON.stringify({decision:'LONG',entry:179,stopLoss:185,takeProfit:[180]});
 const out=ai.parseReply(bad,model,179);
 assert.equal(out.decision,'WAIT');assert.equal(out.entry,null);assert.deepEqual(out.takeProfit,[]);
 assert.throws(()=>ai.parseReply('not json',model,179));
});
test('model discovery uses fixed Ollama loopback URL',async()=>{
 let target;const models=await ai.listOllamaModels(async(url)=>{target=url;return {ok:true,json:async()=>({models:[{name:'qwen2.5:3b'}]})};});
 assert.equal(target,'http://127.0.0.1:11434/api/tags');assert.deepEqual(models,['qwen2.5:3b']);
});
test('Ollama chat request disables stream and avoids bearer',async()=>{
 let req;const snap=ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles});
 const out=await ai.analyze({provider:'OLLAMA',model:'qwen2.5:3b'},snap,async(url,opts)=>{req={url,opts};return {ok:true,json:async()=>({message:{content:answer}})};});
 assert.equal(out.decision,'LONG');assert.equal(req.url,'http://127.0.0.1:11434/api/chat');
 assert.equal(JSON.parse(req.opts.body).stream,false);assert.equal(req.opts.redirect,'error');
 assert.ok(!req.opts.headers.Authorization);
});
test('custom bearer token scoped to selected provider and sensitive errors redacted',async()=>{
 let req;const snap=ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles});
 const cfg=ai.validateConfig({provider:'CUSTOM',model:'m',baseUrl:'https://api.example.com/v1',apiKey:'SUPERSECRET'});
 await assert.rejects(()=>ai.analyze(cfg,snap,async(url,opts)=>{req={url,opts};return {ok:false,status:401};}),/HTTP 401/);
 assert.equal(req.url,'https://api.example.com/v1/chat/completions');assert.equal(req.opts.headers.Authorization,'Bearer SUPERSECRET');
 await assert.rejects(()=>ai.analyze(cfg,snap,async()=>{throw Error('SUPERSECRET leaked');}),e=>!e.message.includes('SUPERSECRET'));
});
