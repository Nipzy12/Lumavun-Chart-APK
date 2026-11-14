(function(root) {
'use strict';
function calculate(candles, core) {
 if(!Array.isArray(candles)||candles.length<55)return {state:'UNAVAILABLE',summary:'Minimal 55 candle valid diperlukan untuk menilai tren.',details:[]};
 const closes=candles.map(c=>c.close);
 const ema20=core.ema(closes,20).at(-1), ema50=core.ema(closes,50).at(-1), rsi=core.rsi(closes,14).at(-1);
 if(!Number.isFinite(ema20)||!Number.isFinite(ema50)||!Number.isFinite(rsi))return {state:'UNAVAILABLE',summary:'Data indikator belum memadai.',details:[]};
 const price=closes.at(-1);
 const trend=ema20>ema50?'BULLISH':ema20<ema50?'BEARISH':'NETRAL';
 const momentum=rsi>=70?'RSI overbought':rsi<=30?'RSI oversold':rsi>50?'RSI di atas 50':'RSI di bawah atau sama dengan 50';
 const sr=core.levels(candles,price);
 const support=sr.support[0],resistance=sr.resistance[0];
 const state = (trend==='BULLISH'&&rsi>50&&rsi<70) ? 'BULLISH'
  : (trend==='BEARISH'&&rsi<50&&rsi>30) ? 'BEARISH' : 'MIXED';
 return {state,trend,rsi,summary:'Analisis teknikal lokal: tren '+trend.toLowerCase()+' (EMA 20/50), '+momentum+'. Bukan prediksi harga atau rekomendasi entry.',details:[
  'Harga '+(price>ema20?'di atas':'di bawah/tepat pada')+' EMA 20',
  support?'Support fractal terdekat: '+support.toFixed(4):'Support belum terdeteksi',
  resistance?'Resistance fractal terdekat: '+resistance.toFixed(4):'Resistance belum terdeteksi'
 ]};
}
const api={calculate};
if(typeof module!=='undefined'&&module.exports)module.exports=api;
if(root)root.LumavunInsight=api;
})(typeof window==='undefined'?null:window);
