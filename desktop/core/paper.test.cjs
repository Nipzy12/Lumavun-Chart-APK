const {test}=require('node:test');const assert=require('node:assert/strict');const paper=require('./paper.cjs');
test('paper trading opens a risk-sized virtual position and closes at target',()=>{
 const a=paper.createAccount(1000);
 const p=paper.open(a,{provider:'BINANCE',symbol:'BTCUSDT',side:'LONG',price:100,stopPct:1,tpPct:2,riskPct:1});
 assert.ok(p.notional>0&&p.notional<=1000);assert.equal(a.positions.length,1);
 assert.ok(p.stop<p.entry&&p.target>p.entry);
 assert.equal(paper.mark(a,'BTCUSDT',p.target+1).length,1);
 assert.equal(a.positions.length,0);assert.equal(a.history[0].reason,'TARGET');assert.ok(a.history[0].pnl>0);
});
test('paper trading rejects fake quotes, unverified providers and duplicate pairs',()=>{
 const a=paper.createAccount(1000);
 assert.throws(()=>paper.open(a,{provider:'EXNESS',symbol:'XAUUSD',side:'LONG',price:3000}));
 assert.throws(()=>paper.open(a,{provider:'BINANCE',symbol:'BTCUSDT',side:'LONG',price:0}));
 paper.open(a,{provider:'BINANCE',symbol:'ETHUSDT',side:'SHORT',price:100});
 assert.throws(()=>paper.open(a,{provider:'BINANCE',symbol:'ETHUSDT',side:'SHORT',price:101}));
});
test('stop loss closes short and equity marks unrealized PnL',()=>{
 const a=paper.createAccount(1000);
 const p=paper.open(a,{provider:'BINANCE',symbol:'SOLUSDT',side:'SHORT',price:100});
 paper.mark(a,'SOLUSDT',99);
 assert.ok(paper.equity(a)>a.balance);
 paper.mark(a,'SOLUSDT',p.stop+2);
 assert.equal(a.history[0].reason,'STOP');assert.ok(a.history[0].pnl<0);
});
