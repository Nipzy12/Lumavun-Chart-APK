(function(root){
'use strict';
function ensure(values){if(!Array.isArray(values)||values.some(x=>!Number.isFinite(x)))throw Error('Numeric series must contain finite values.');}
function sma(values,period){ensure(values);const out=Array(values.length).fill(null);if(!Number.isInteger(period)||period<1)return out;let sum=0;for(let i=0;i<values.length;i++){sum+=values[i];if(i>=period)sum-=values[i-period];if(i+1>=period)out[i]=sum/period;}return out;}
function bollinger(values,period=20,multiplier=2){ensure(values);const mean=sma(values,period),upper=Array(values.length).fill(null),lower=Array(values.length).fill(null);for(let i=period-1;i<values.length;i++){let variance=0;for(let j=i-period+1;j<=i;j++)variance+=(values[j]-mean[i])**2;const std=Math.sqrt(variance/period);upper[i]=mean[i]+multiplier*std;lower[i]=mean[i]-multiplier*std;}return {upper,lower,middle:mean};}
function vwap(candles){if(!Array.isArray(candles))return [];let pv=0,volume=0;return candles.map(c=>{const h=Number(c.high),l=Number(c.low),close=Number(c.close),v=Number(c.volume);if(![h,l,close,v].every(Number.isFinite)||h<l||v<0)return null;pv+=((h+l+close)/3)*v;volume+=v;return volume>0?pv/volume:null;});}
function ema(values,p){ensure(values);const out=Array(values.length).fill(null);if(!Number.isInteger(p)||p<=0||values.length<p)return out;let e=values.slice(0,p).reduce((a,b)=>a+b,0)/p;out[p-1]=e;for(let i=p;i<values.length;i++){e+=(values[i]-e)*2/(p+1);out[i]=e;}return out;}
function backtest(candles,{fast=9,slow=21,feeBps=10,capital=1000}={}){
 if(!Array.isArray(candles)||candles.length<slow+2)throw Error('Not enough historical bars.');
 if(!Number.isInteger(fast)||!Number.isInteger(slow)||fast>=slow||fast<1||slow>150||!Number.isFinite(feeBps)||feeBps<0||feeBps>100||!Number.isFinite(capital)||capital<=0)throw Error('Invalid strategy parameters.');
 const closes=candles.map(c=>Number(c.close));ensure(closes);if(closes.some(x=>x<=0))throw Error('Price must be positive.');
 const f=ema(closes,fast),s=ema(closes,slow),fee=feeBps/10000;
 let cash=capital,units=0,entry=0,enterAt=0,trades=[],peaks=capital,maxDD=0;
 for(let i=slow;i<closes.length;i++){
  const up=f[i]>s[i]&&f[i-1]<=s[i-1],down=f[i]<s[i]&&f[i-1]>=s[i-1];
  if(up&&units===0){units=cash*(1-fee)/closes[i];entry=closes[i];enterAt=i;cash=0;}
  else if(down&&units>0){cash=units*closes[i]*(1-fee);trades.push({entry,exit:closes[i],entryIndex:enterAt,exitIndex:i,returnPct:(closes[i]*(1-fee)**2/entry-1)*100});units=0;}
  const equity=cash+units*closes[i]*(1-fee);peaks=Math.max(peaks,equity);maxDD=Math.max(maxDD,(peaks-equity)/peaks*100);
 }
 if(units>0){const i=closes.length-1;cash=units*closes[i]*(1-fee);trades.push({entry,exit:closes[i],entryIndex:enterAt,exitIndex:i,returnPct:(closes[i]*(1-fee)**2/entry-1)*100,markedAtEnd:true});}
 const wins=trades.filter(t=>t.returnPct>0).length;
 return {initial:capital,final:cash,pnl:cash-capital,returnPct:(cash/capital-1)*100,trades:trades.length,wins,winRate:trades.length?wins/trades.length*100:0,maxDrawdown:maxDD,history:trades,method:'EMA close cross. Next trade at signal candle close. Fee per side. No spread, slippage, intrabar execution, or margin.'};
}
const api=Object.freeze({sma,bollinger,vwap,ema,backtest});
if(typeof module!=='undefined'&&module.exports)module.exports=api;
if(root)root.LumaStudies=api;
})(typeof window==='undefined'?null:window);
