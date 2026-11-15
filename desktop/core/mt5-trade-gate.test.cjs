const test=require('node:test');
const assert=require('node:assert/strict');
const {MT5TradeGate,PREVIEW_TTL}=require('./mt5-trade-gate.cjs');
const demo={account:'123',mode:'DEMO',connected:true};
const real={account:'987',mode:'REAL',connected:true};
const preview={checked:true,account:'123',environment:'DEMO',symbol:'XAUUSD',side:'BUY',volume:.01,sl:98,tp:105,price:100};
test('trading is locked by default and requires exact demo phrase',()=>{
 const gate=new MT5TradeGate();
 assert.throws(()=>gate.createPreview(demo,preview),/locked/);
 assert.throws(()=>gate.unlock(demo,'ENABLE 123'),/phrase/);
 assert.equal(gate.unlock(demo,'ENABLE DEMO 123').unlocked,true);
});
test('real unlock requires extra risk acknowledgment',()=>{
 const gate=new MT5TradeGate();
 assert.throws(()=>gate.unlock(real,'ENABLE REAL 987'),/risk/);
 assert.equal(gate.unlock(real,'ENABLE REAL 987',true).environment,'REAL');
});
test('preview token is single-use and bound to account, price and direction',()=>{
 let now=1000;
 const gate=new MT5TradeGate({clock:()=>now,createToken:()=> 'private-token'});
 gate.unlock(demo,'ENABLE DEMO 123');
 const envelope=gate.createPreview(demo,preview);
 const order=gate.consume(demo,envelope.token,'CONFIRM DEMO BUY 123');
 assert.equal(order.symbol,'XAUUSD');
 assert.equal(order.expected_account,'123');
 assert.equal(order.expected_price,100);
 assert.throws(()=>gate.consume(demo,envelope.token,'CONFIRM DEMO BUY 123'),/missing|expired/);
});
test('incorrect execution phrase consumes token and forces a new preview',()=>{
 const gate=new MT5TradeGate();
 gate.unlock(demo,'ENABLE DEMO 123');
 const envelope=gate.createPreview(demo,preview);
 assert.throws(()=>gate.consume(demo,envelope.token,'BUY'),/phrase/);
 assert.throws(()=>gate.consume(demo,envelope.token,'CONFIRM DEMO BUY 123'),/missing/);
});
test('account switch locks trading and prevents consuming stale token',()=>{
 const gate=new MT5TradeGate();gate.unlock(demo,'ENABLE DEMO 123');
 const envelope=gate.createPreview(demo,preview);
 assert.throws(()=>gate.consume(real,envelope.token,'CONFIRM REAL BUY 987'),/changed/);
 assert.equal(gate.getStatus().unlocked,false);
});
test('expired preview cannot be executed and locked session expires',()=>{
 let now=3000;const gate=new MT5TradeGate({clock:()=>now});
 gate.unlock(demo,'ENABLE DEMO 123');const envelope=gate.createPreview(demo,preview);
 now+=PREVIEW_TTL+1;
 assert.throws(()=>gate.consume(demo,envelope.token,'CONFIRM DEMO BUY 123'),/expired/);
 now+=1000000;assert.equal(gate.getStatus().unlocked,false);
});
