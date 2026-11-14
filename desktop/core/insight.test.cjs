const {test}=require('node:test');const assert=require('node:assert/strict');const insight=require('./insight.js');
const core=require('./indicators.js');
test('insight refuses to invent a signal on sparse data',()=>{
 assert.equal(insight.calculate([],core).state,'UNAVAILABLE');
});
test('insight emits descriptive technical state for valid candles',()=>{
 const candles=Array.from({length:80},(_,i)=>({time:i+1,open:90+i,high:93+i,low:89+i,close:91+i,volume:3}));
 const result=insight.calculate(candles,core);
 assert.equal(result.trend,'BULLISH');assert.match(result.summary,/Bukan prediksi/);
});
