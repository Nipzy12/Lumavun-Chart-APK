'use strict';
const INTERVALS = new Set(['1m','5m','15m','30m','1h','4h','1d']);
const PROVIDERS = new Set(['BINANCE','BIQUOTE','EXNESS','MT5']);
function symbol(input, provider) {
  if (!PROVIDERS.has(provider)) throw new Error('Unknown market provider.');
  if(provider==='MT5'){
    const raw=String(input||'').trim();
    if(!/^[A-Za-z0-9._-]{1,32}$/.test(raw))throw new Error('Invalid MT5 broker symbol.');
    return raw;
  }
  const text = String(input || '').toUpperCase().trim().replace(/[\/_\-\s]/g,'');
  if (!/^[A-Z0-9.]{1,20}$/.test(text)) throw new Error('Invalid market symbol.');
  if (provider === 'BINANCE' && (!text.endsWith('USDT') || text.length < 5 || text.includes('.')))
    throw new Error('Binance Spot only supports validated USDT pairs.');
  return text;
}
function timestamp(value) {
  const n = typeof value === 'number' ? value : Number(String(value ?? ''));
  if (Number.isFinite(n) && n > 100000000) return Math.floor(n < 10000000000 ? n : n/1000);
  if (typeof value === 'string') {
    const date = Date.parse(value);
    if (Number.isFinite(date)) return Math.floor(date/1000);
  }
  return null;
}
function positive(...values) {
  for (const val of values) { const n = Number(val); if (Number.isFinite(n) && n > 0) return n; }
  return null;
}
function numberOr(...values) {
  for (const val of values) {
    if (val === undefined || val === null || val === '') continue;
    const n = Number(val);
    if (Number.isFinite(n)) return n;
  }
  return null;
}
function normalizeCandles(payload) {
  const source = Array.isArray(payload) ? payload
    : (payload && ['candles','ohlc','data','bars','items','results'].map(k=>payload[k]).find(Array.isArray)) || [];
  const map=new Map();
  for (const item of source) {
    if (!item || typeof item!=='object') continue;
    const array=Array.isArray(item);
    const time=timestamp(array ? item[0] : (item.openTime ?? item.timestamp ?? item.time ?? item.t ?? item.date ?? item.datetime));
    const open=positive(array?item[1]:(item.open??item.o??item.Open));
    const high=positive(array?item[2]:(item.high??item.h??item.High));
    const low=positive(array?item[3]:(item.low??item.l??item.Low));
    const close=positive(array?item[4]:(item.close??item.c??item.Close));
    const volume=numberOr(array?item[5]:(item.volume??item.v??item.Volume??item.tickVolume)) ?? 0;
    if (!time || open===null || high===null || low===null || close===null || volume<0) continue;
    if(high<Math.max(open,close,low) || low>Math.min(open,close,high)) continue;
    map.set(time,{time,open,high,low,close,volume});
  }
  return [...map.values()].sort((a,b)=>a.time-b.time).slice(-500);
}
function normalizeQuote(raw, fallback, provider) {
  const data = raw && typeof raw==='object' ? raw : {};
  const entries = [];
  if (Array.isArray(data)) entries.push(...data);
  else {
    entries.push(data);
    for(const name of ['data','quote','quotes','items','results']){
      const value=data[name];
      if(Array.isArray(value)) entries.push(...value);
      else if (value && typeof value==='object') entries.push(value);
    }
  }
  for(const item of entries){
    if(!item || typeof item!=='object') continue;
    const candidate=String(item.symbol??item.s??item.ticker??item.instrument??item.pair??fallback).toUpperCase().replace(/[\/_\-\s]/g,'');
    if(candidate!==fallback) continue;
    const bid=positive(item.bid,item.b,item.bidPrice),ask=positive(item.ask,item.a,item.askPrice);
    const last=positive(item.lastPrice,item.last,item.price,item.mid,item.close,item.c,item.p,item.rate)
      ??(bid && ask ? (bid+ask)/2 : bid ?? ask);
    if(!(last>0)) continue;
    const change=numberOr(item.priceChangePercent,item.changePercent,item.changePct,item.pctChange,item.percentChange,item.cp,item.P) ?? 0;
    const high=positive(item.highPrice,item.high,item.h,item.dayHigh) ?? last;
    const low=positive(item.lowPrice,item.low,item.l,item.dayLow) ?? last;
    const volume=numberOr(item.volume,item.vol,item.v,item.tickVolume) ?? 0;
    const status = String(item.status??item.marketStatus??'').toUpperCase();
    const marketOpen = item.isMarketOpen === false || item.marketOpen === false || ['CLOSED','HALTED','OFFLINE'].includes(status) ? false : null;
    return {symbol:fallback,lastPrice:last,priceChangePercent:change,highPrice:high,lowPrice:low,volume,
      bid:bid??null,ask:ask??null,verifiedBidAsk:bid>0&&ask>=bid, marketOpen,provider};
  }
  throw new Error('Provider did not return a valid quote for '+fallback+'.');
}
function normalizeSymbols(raw) {
  const data=Array.isArray(raw)?raw : ['symbols','data','items','results'].map(k=>raw?.[k]).find(Array.isArray) || [];
  return data.filter(v=>v&&typeof v==='object').map(v=>({
    symbol:String(v.symbol??v.s??'').trim().toUpperCase().replace(/[\/_\-\s]/g,''),
    status:String(v.status??'UNKNOWN').toUpperCase(),
    name:String(v.displayName??v.name??v.symbol??'').slice(0,120),
    category:String(v.category??v.type??'').slice(0,40)
  })).filter(v=>/^[A-Z0-9.]{1,20}$/.test(v.symbol)).slice(0,3000);
}
function validateGateway(input) {
  let url;
  try {url=new URL(String(input||''));}catch{throw new Error('Gateway harus berupa HTTPS URL lengkap.');}
  if(url.protocol!=='https:' || url.username || url.password || url.search || url.hash || (url.port && url.port!=='443'))
    throw new Error('Gateway wajib HTTPS tanpa kredensial, query, fragment, atau port khusus.');
  const host=url.hostname.toLowerCase();
  if(host==='localhost'||host.endsWith('.localhost')||host.endsWith('.local')||host.endsWith('.internal')
    ||host.endsWith('.test')||host.endsWith('.invalid') || host.endsWith('.example')
    ||!host.includes('.') || host.includes(':') || /^\d+\.\d+\.\d+\.\d+$/.test(host))
    throw new Error('Gateway harus memakai domain publik, bukan alamat lokal atau IP.');
  if(url.pathname!=='/' && url.pathname!=='') throw new Error('Gateway URL harus mengarah ke root domain tanpa path.');
  return url.origin;
}
module.exports={INTERVALS,PROVIDERS,symbol,timestamp,normalizeCandles,normalizeQuote,normalizeSymbols,validateGateway};
