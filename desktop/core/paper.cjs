'use strict';
// Manual paper trades only. Spot USDT-quoted units, no broker execution and no leverage.
// Quotes are sampled; this is NOT a tick-accurate or exchange-grade backtest.
const FEES_BPS=10, SLIPPAGE_BPS=2;
const feeRate=FEES_BPS/10000,slip=SLIPPAGE_BPS/10000;
function createAccount(balance=1000){
 if(!(Number.isFinite(balance)&&balance>0&&balance<=1e7))throw new Error('Starting balance must be positive and realistic.');
 return {balance,startingBalance:balance,positions:[],history:[],seq:0};
}
function marginAvailable(a) {
 return Math.max(0,a.balance-a.positions.reduce((sum,p)=>sum+p.notional,0));
}
function open(a,{provider,symbol,side,price,stopPct=1,tpPct=2,riskPct=1}){
 if(provider!=='BINANCE')throw new Error('Paper trades in v0.2 are limited to Binance USDT pricing.');
 if(!/^[A-Z0-9]{5,20}$/.test(symbol)||!symbol.endsWith('USDT'))throw new Error('Invalid spot symbol.');
 if(!['LONG','SHORT'].includes(side)||!(Number.isFinite(price)&&price>0))throw new Error('Invalid trade direction or verified entry quote.');
 if(![stopPct,tpPct,riskPct].every(x=>Number.isFinite(x)&&x>0&&x<=10))throw new Error('SL, TP and risk percentages must be 0-10%.');
 if(a.positions.length>=3)throw new Error('Maximum three open positions.');
 if(a.positions.some(p=>p.symbol===symbol))throw new Error('This symbol already has an open position.');
 const available=marginAvailable(a);
 const entry=price*(side==='LONG'?1+slip:1-slip);
 const notional=Math.min(available/(1+feeRate),a.balance*riskPct/100/(stopPct/100+feeRate*2+slip*2));
 if(notional<1)throw new Error('Insufficient free virtual balance for a minimum $1 notional.');
 const qty=notional/entry, cost=notional*feeRate;
 const position={id:++a.seq,provider,symbol,side,entry,qty,notional,stop:entry*(side==='LONG'?1-stopPct/100:1+stopPct/100),
  target:entry*(side==='LONG'?1+tpPct/100:1-tpPct/100),last:price,openedAt:Date.now(),openFee:cost};
 a.balance-=cost;a.positions.push(position);
 return position;
}
function unrealized(p,price) {
 return (price-p.entry)*p.qty*(p.side==='LONG'?1:-1);
}
function close(a,id,quote,reason='MANUAL'){
 const index=a.positions.findIndex(p=>p.id===id);if(index===-1)throw new Error('Position not found.');
 const p=a.positions[index];
 if(!(Number.isFinite(quote)&&quote>0))throw new Error('Cannot close without valid quote.');
 const exit=quote*(p.side==='LONG'?1-slip:1+slip);
 const exitFee=exit*p.qty*feeRate;
 const gross=unrealized(p,exit);
 const pnl=gross-exitFee-p.openFee;
 a.balance+=gross-exitFee;
 a.positions.splice(index,1);
 const closed={...p,exit,closedAt:Date.now(),reason,pnl};
 a.history.unshift(closed);a.history=a.history.slice(0,100);
 return closed;
}
function mark(a,symbol,price){
 if(!(Number.isFinite(price)&&price>0))return [];
 const results=[];
 for(const p of [...a.positions]){
  if(p.symbol!==symbol)continue;
  p.last=price;
  if(p.side==='LONG'){
   if(price<=p.stop)results.push(close(a,p.id,price,'STOP'));
   else if(price>=p.target)results.push(close(a,p.id,price,'TARGET'));
  }else{
   if(price>=p.stop)results.push(close(a,p.id,price,'STOP'));
   else if(price<=p.target)results.push(close(a,p.id,price,'TARGET'));
  }
 }
 return results;
}
function equity(a){return a.balance+a.positions.reduce((sum,p)=>sum+unrealized(p,p.last),0);}
function restore(raw) {
 if (!raw || typeof raw!=='object' || !Number.isFinite(raw.balance)||raw.balance<=0 || !Number.isFinite(raw.startingBalance) || raw.startingBalance<=0 || !Array.isArray(raw.positions) || !Array.isArray(raw.history))return createAccount();
 // Untrusted saved data is never allowed to execute orders, but validate numeric account state.
 if (raw.positions.some(p=>!Number.isFinite(p.qty)||p.qty<=0||!Number.isFinite(p.entry)||p.entry<=0||!Number.isFinite(p.notional)||p.notional<=0||!Number.isFinite(p.last)||p.last<=0))return createAccount();
 return {balance:raw.balance,startingBalance:raw.startingBalance,positions:raw.positions.slice(0,3),history:raw.history.slice(0,100),seq:Math.max(0,Math.floor(Number(raw.seq)||0))};
}
module.exports={FEES_BPS,SLIPPAGE_BPS,createAccount,marginAvailable,open,close,mark,equity,restore};
