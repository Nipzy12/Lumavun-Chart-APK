(function(root){
'use strict';
const finite=v=>typeof v==='number'&&Number.isFinite(v);
function paperTrades(account){
 if(!account||!Array.isArray(account.history))return [];
 return account.history.filter(x=>x&&finite(x.pnl)&&finite(x.closedAt)).map((x,i)=>({
  id:String(x.id??i),symbol:String(x.symbol||''),side:String(x.side||''),
  at:x.closedAt,net:x.pnl,source:'PAPER',reason:String(x.reason||''),
  costsComplete:true
 }));
}
// MT5 reconstructed CLOSED positions only. Profit fields are broker-provided.
// If fees are not reported, omit the record instead of assuming zero commissions.
function mt5Trades(records){
 if(!Array.isArray(records))throw Error('MT5 history is not an array.');
 const out=[];
 for(const p of records.slice(0,1000)){
  if(!p||typeof p!=='object'||p.open===true||p.is_open===true||p.closed===false)continue;
  const id=String(p.ticket??p.position_id??p.position??p.id??'').slice(0,80);
  const closeTime=p.closedAt??p.time_close??p.close_time??p.closed_time??p.closed_at;
  if(closeTime==null||closeTime==='')continue; // open-time data must never be treated as a close.
  const at=Number(closeTime);
  const time=at>1e12?at:at>1e9?at*1000:Date.parse(String(closeTime));
  const netCandidate=p.net_profit??p.net_pnl??p.netProfit;
  const gross=p.profit??p.pnl??p.realized_profit;
  const net=netCandidate!=null?Number(netCandidate):
   gross!=null&&['commission','swap','fee'].every(k=>p[k]!=null)?
    Number(gross)+Number(p.commission)+Number(p.swap)+Number(p.fee):NaN;
  if(!id||!Number.isFinite(time)||!Number.isFinite(net))continue;
  out.push({id,symbol:String(p.symbol||''),side:String(p.type??p.side??''),
   at:time,net,source:'MT5',reason:'BROKER_HISTORY',costsComplete:true});
 }
 return out;
}
function stats(trades,{startingBalance=null,currency='USD'}={}){
 if(!Array.isArray(trades))throw Error('Trades must be an array.');
 const all=trades.filter(t=>t&&finite(t.net)&&finite(t.at)).slice().sort((a,b)=>a.at-b.at);
 const winners=all.filter(t=>t.net>0),losers=all.filter(t=>t.net<0),
  breakeven=all.length-winners.length-losers.length;
 const gain=winners.reduce((s,t)=>s+t.net,0),loss=-losers.reduce((s,t)=>s+t.net,0);
 const net=all.reduce((s,t)=>s+t.net,0),expectancy=all.length?net/all.length:null;
 const capital=finite(startingBalance)&&startingBalance>0?startingBalance:null;
 let current=capital??0,peak=current,maxDD=0,maxDDPercent=capital?0:null;
 const curve=[{at:all.length?all[0].at-1:Date.now(),value:current}];
 for(const t of all){
  current+=t.net;peak=Math.max(peak,current);
  const dd=Math.max(0,peak-current);maxDD=Math.max(maxDD,dd);
  if(capital&&peak>0)maxDDPercent=Math.max(maxDDPercent,dd/peak*100);
  curve.push({at:t.at,value:current});
 }
 return {
  count:all.length,wins:winners.length,losses:losers.length,breakeven,
  netPnl:net,grossProfit:gain,grossLoss:loss,
  winRate:winners.length+losers.length?100*winners.length/(winners.length+losers.length):null,
  profitFactor:loss>0?gain/loss:null,
  expectancy,maxDrawdown:maxDD,
  maxDrawdownPercent:capital?maxDDPercent:null,
  curve:capital?curve:curve.map(x=>({...x,relative:true})),
  currency,startingBalance:capital,
  note:capital?'Closed-trade balance path; intratrade drawdown not measured.':
    'Cumulative realized P&L only; starting balance and percentage drawdown unavailable.'
 };
}
const api={paperTrades,mt5Trades,stats};
if(typeof module!=='undefined'&&module.exports)module.exports=api;
if(root)root.LumavunPerformance=api;
})(typeof window==='undefined'?null:window);
