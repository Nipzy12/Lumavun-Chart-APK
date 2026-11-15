const test=require('node:test');
const assert=require('node:assert/strict');
const {paperTrades,mt5Trades,stats}=require('./performance.js');
test('no closed trades never fabricate win rate or profit factor',()=>{
 const s=stats([],{startingBalance:1000});
 assert.equal(s.count,0);assert.equal(s.winRate,null);
 assert.equal(s.profitFactor,null);assert.equal(s.maxDrawdown,0);
});
test('paper fees already included in net PNL, win rate excludes break even',()=>{
 const a={history:[
 {id:3,symbol:'BTCUSDT',closedAt:3000,pnl:0},
 {id:2,symbol:'ETHUSDT',closedAt:2000,pnl:-40},
 {id:1,symbol:'BTCUSDT',closedAt:1000,pnl:100}
 ]};
 const s=stats(paperTrades(a),{startingBalance:1000});
 assert.equal(s.count,3);assert.equal(s.wins,1);assert.equal(s.losses,1);assert.equal(s.breakeven,1);
 assert.equal(s.winRate,50);assert.equal(s.netPnl,60);
 assert.equal(s.profitFactor,2.5);assert.equal(s.expectancy,20);
 assert.ok(s.maxDrawdownPercent>3.6&&s.maxDrawdownPercent<3.7);
 assert.equal(s.curve.at(-1).value,1060);
});
test('MT5 broker history excludes open positions and records with missing cost details',()=>{
 const input=[
  {ticket:1,symbol:'XAUUSD',closed:false,time_close:1700000000,net_profit:10},
  {ticket:2,symbol:'BTCUSD',closed:true,time_close:1700000000,profit:100},
  {ticket:3,symbol:'BTCUSD',closed:true,time_close:1700000100,profit:25,commission:-1,swap:-2,fee:0},
  {ticket:4,symbol:'XAUUSD',closed:true,time_close:1700000200,net_profit:-5}
 ];
 const out=mt5Trades(input);
 assert.equal(out.length,2);assert.equal(out[0].net,22);assert.equal(out[1].net,-5);
 const s=stats(out);
 assert.equal(s.netPnl,17);assert.equal(s.maxDrawdownPercent,null);
});
test('lossless period returns unknown PF, no starting balance gives absolute drawdown only',()=>{
 const s=stats([{at:1000,net:12},{at:2000,net:15}]);
 assert.equal(s.winRate,100);assert.equal(s.profitFactor,null);
 assert.equal(s.startingBalance,null);
});
