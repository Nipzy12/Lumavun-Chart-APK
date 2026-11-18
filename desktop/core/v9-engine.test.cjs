const {test}=require('node:test');
const assert=require('node:assert/strict');
const engine=require('./v9-engine.js'),ai=require('./ai-analyst.cjs'),core=require('./indicators.js');
const cfg={provider:'OLLAMA',model:'test'};
const signal={decision:'LONG',bias:'BULLISH',confidence:80,entry:100,stopLoss:90,takeProfit:[120,130]};
test('V9 exact minimum and sequential targets fail closed',()=>{
 const parse=p=>ai.parseReply(JSON.stringify({...signal,...p}),cfg,100);
 assert.equal(parse({}).decision,'LONG');
 assert.equal(parse({takeProfit:[113.49]}).decision,'WAIT');
 assert.equal(parse({takeProfit:[130,120]}).decision,'WAIT');
 assert.equal(parse({takeProfit:[120,'bad']}).decision,'WAIT');
 assert.equal(parse({confidence:59}).decision,'WAIT');
 assert.equal(parse({decision:'SHORT',stopLoss:110,takeProfit:[80,70]}).decision,'SHORT');
});
test('missing evidence is not renormalized to 100',()=>{
 const r=engine.score({ema20:102,ema50:99,rsi14:65},'LONG',2);
 assert.equal(r.score,22);assert.equal(r.coverage,30);
 assert.equal(engine.score({},'WAIT',2),null);
});
test('flat closed replay does not fabricate trades and excludes live candle',()=>{
 const candles=Array.from({length:80},(_,i)=>({time:1000+i*60,open:100,high:101,low:99,close:100,volume:10}));
 const r=engine.replay(candles,'1m',core,1000+79*60);
 assert.equal(r.bars,79);assert.equal(r.winRate,null);assert.equal(r.netR,0);
 assert.equal(engine.replay(candles.slice(0,30),'1m',core,100000),null);
});
