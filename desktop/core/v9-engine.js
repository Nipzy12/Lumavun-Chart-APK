(function(root){
'use strict';
const weights={structure:25,mtf:20,liquidity:20,momentum:15,risk:15,session:5};
const periods={'1m':60,'5m':300,'15m':900,'30m':1800,'1h':3600,'4h':14400,'1d':86400};
function score(indicators,decision,rr){
 if(!['LONG','SHORT'].includes(decision))return null;
 const long=decision==='LONG', i=indicators||{}, states={};
 if(Number.isFinite(i.ema20)&&Number.isFinite(i.ema50)&&Number.isFinite(i.rsi14)&&i.rsi14>=0&&i.rsi14<=100){
  const aligned=long?i.ema20>i.ema50:i.ema20<i.ema50;
  const opposed=long?i.ema20<i.ema50:i.ema20>i.ema50;
  states.momentum=opposed?'CONTRADICTS':aligned&&(long?i.rsi14>=55:i.rsi14<=45)?'PARTIAL':'PARTIAL';
 }
 states.risk=!Number.isFinite(rr)||rr<=0?'MISSING':rr<1.35?'CONTRADICTS':rr>=2?'SUPPORTS':'PARTIAL';
 const components=Object.entries(weights).map(([category,weight])=>{
  const state=states[category]||'MISSING';
  return {category,weight,state,points:state==='SUPPORTS'?weight:state==='PARTIAL'?Math.floor(weight/2):0};
 });
 return {score:components.reduce((s,x)=>s+x.points,0),coverage:components.reduce((s,x)=>s+(x.state==='MISSING'?0:x.weight),0),components};
}
function replay(input,interval,core,nowSeconds=Math.floor(Date.now()/1000),bps=6){
 if(!Array.isArray(input)||!periods[interval]||!Number.isFinite(nowSeconds)||!Number.isFinite(bps)||bps<0||bps>100)return null;
 const bars=input.filter(c=>Number.isFinite(c?.time)&&c.time+periods[interval]<=nowSeconds).slice(-500);
 if(bars.length<60||bars.some((c,i)=>![c.open,c.high,c.low,c.close].every(x=>Number.isFinite(x)&&x>0)||c.high<Math.max(c.open,c.close)||c.low>Math.min(c.open,c.close)||(i&&c.time<=bars[i-1].time)))return null;
 const closes=bars.map(c=>c.close),f=core.ema(closes,20),s=core.ema(closes,50);
 let position=null, pending=0,wins=0,losses=0,gross=0,net=0,peak=0,drawdown=0;
 for(let k=50;k<bars.length;k++){
  const b=bars[k];
  const existing=!!position;
  if(!position&&pending){
   const entry=b.open,side=pending;
   position={entry,side,stop:entry*(1-side*.01),tp:entry*(1+side*.02)};
  }
  pending=0;
  if(position){
   const p=position, buy=p.side===1, stopHit=buy?b.low<=p.stop:b.high>=p.stop, targetHit=buy?b.high>=p.tp:b.low<=p.tp;
   if(stopHit||(existing&&targetHit)){
    const exit=stopHit?(buy?Math.min(b.open,p.stop):Math.max(b.open,p.stop)):p.tp;
    const risk=Math.abs(p.entry-p.stop),r=p.side*(exit-p.entry)/risk;
    const cost=(p.entry+exit)*bps/10000/risk;
    if(!Number.isFinite(r)||!Number.isFinite(cost))return null;
    if(stopHit)losses++;else wins++;
    gross+=r;net+=r-cost;peak=Math.max(peak,net);drawdown=Math.max(drawdown,peak-net);
    position=null;
   }
  }
  if(!position&&Number.isFinite(f[k-1])&&Number.isFinite(s[k-1])){
   if(f[k]>s[k]&&f[k-1]<=s[k-1])pending=1;
   else if(f[k]<s[k]&&f[k-1]>=s[k-1])pending=-1;
  }
 }
 return {bars:bars.length,wins,losses,open:position?1:0,pending:pending?1:0,grossR:gross,netR:net,maxDrawdownR:drawdown,winRate:wins+losses?wins*100/(wins+losses):null,lastTime:bars.at(-1).time,bps};
}
const api={score,replay};
if(typeof module!=='undefined'&&module.exports)module.exports=api;
if(root)root.LumavunV9=api;
})(typeof window==='undefined'?null:window);
