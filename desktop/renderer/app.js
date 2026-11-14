'use strict';
const core = window.LumavunIndicators;
const storageKey = 'lumavun-chart-desktop-v1';
const defaults = ['BTCUSDT','ETHUSDT','SOLUSDT','BNBUSDT','XRPUSDT'];
function readSaved() {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || '{}');
    const watchlist = Array.isArray(value.watchlist) ? value.watchlist.map(core.normalizeSymbol).filter(Boolean).slice(0,40) : defaults;
    const symbol = core.normalizeSymbol(value.symbol);
    return {
      watchlist: watchlist.length ? [...new Set(watchlist)] : defaults,
      symbol: symbol && watchlist.includes(symbol) ? symbol : 'BTCUSDT',
      interval: ['1m','5m','15m','30m','1h','4h','1d'].includes(value.interval) ? value.interval : '15m'
    };
  } catch { return { watchlist: defaults, symbol: 'BTCUSDT', interval: '15m' }; }
}
const state = readSaved();
const byId = id => document.getElementById(id);
const chartNode = byId('chart');
const chart = LightweightCharts.createChart(chartNode, {
  autoSize: true,
  layout: {background:{type:'solid',color:'#0e1727'},textColor:'#94a9c8',fontFamily:'Segoe UI, Arial'},
  grid: {vertLines:{color:'#18253b'},horzLines:{color:'#18253b'}},
  timeScale: {timeVisible:true,secondsVisible:false,borderColor:'#26344d',rightOffset:10},
  rightPriceScale: {borderColor:'#26344d'},
  crosshair: {mode:LightweightCharts.CrosshairMode.Normal}
});
const candles = chart.addSeries(LightweightCharts.CandlestickSeries,{
  upColor:'#43cda4', downColor:'#f07180', borderVisible:false,
  wickUpColor:'#43cda4', wickDownColor:'#f07180',
  priceFormat:{type:'price',precision:4,minMove:0.0001}
});
const fastEma = chart.addSeries(LightweightCharts.LineSeries,{
  color:'#f0bc78',lineWidth:2,lastValueVisible:false,priceLineVisible:false
});
const slowEma = chart.addSeries(LightweightCharts.LineSeries,{
  color:'#9e95ff',lineWidth:2,lastValueVisible:false,priceLineVisible:false
});
let requestId = 0, busy = false;
function save() { localStorage.setItem(storageKey,JSON.stringify(state)); }
function fmt(value) {
  if (!Number.isFinite(Number(value)) || !(Number(value) > 0)) return '—';
  const n = Number(value);
  return new Intl.NumberFormat('en-US',{minimumFractionDigits:n>=100?2:n>=1?4:6,maximumFractionDigits:n>=100?2:n>=1?4:6}).format(n);
}
function setStatus(text, kind) { const el=byId('status');el.textContent=text;el.className='status '+(kind||''); }
function showError(message) {
  const el=byId('error');el.hidden=false;el.textContent=String(message).slice(0,320);
  setStatus('ERROR','warn');byId('chartState').textContent='Data tidak tersedia';
}
function clearError(){ byId('error').hidden=true;byId('error').textContent=''; }
function renderWatchlist() {
  const root=byId('watchlist');root.replaceChildren();
  byId('watchCount').textContent=state.watchlist.length+' PAIRS';
  for (const symbol of state.watchlist) {
    const row=document.createElement('div');row.className='watchItem'+(symbol===state.symbol?' active':'');
    const pair=document.createElement('button');pair.className='pairButton';pair.type='button';
    pair.textContent=symbol.slice(0,-4)+'/USDT';
    const sub=document.createElement('small');sub.textContent='BINANCE · SPOT';pair.append(sub);
    pair.addEventListener('click',()=>{state.symbol=symbol;save();renderWatchlist();refresh(true);});
    const remove=document.createElement('button');remove.className='remove';remove.type='button';
    remove.title='Hapus dari watchlist';remove.textContent='×';
    remove.addEventListener('click',()=>{
      if (state.watchlist.length<=1) return showError('Watchlist harus memiliki paling sedikit satu pair.');
      state.watchlist=state.watchlist.filter(s=>s!==symbol);
      if(state.symbol===symbol) state.symbol=state.watchlist[0];
      save();renderWatchlist();refresh(true);
    });
    row.append(pair,remove);root.append(row);
  }
}
function renderTimeframes() {
  document.querySelectorAll('#timeframes button').forEach(b=>b.classList.toggle('active',b.dataset.interval===state.interval));
}
async function refresh(fit=false) {
  if (!window.lumavun || !core) return showError('Desktop bridge or indicator engine is missing.');
  const id=++requestId, symbol=state.symbol, interval=state.interval;
  busy=true;clearError();setStatus('LOADING');byId('chartState').textContent='Mengambil OHLC terverifikasi…';
  byId('pairTitle').textContent=symbol.slice(0,-4)+' / USDT';
  byId('chartLabel').textContent=symbol+' · '+interval;
  try {
    const [rows,ticker]=await Promise.all([window.lumavun.candles(symbol,interval,300),window.lumavun.ticker(symbol)]);
    if (id!==requestId) return;
    const data=core.normalizeKlines(rows);
    if (data.length<30) throw new Error('Tidak cukup candle valid untuk membuat grafik.');
    candles.setData(data.map(c=>({time:c.time,open:c.open,high:c.high,low:c.low,close:c.close})));
    const closing=data.map(c=>c.close);
    const ema20=core.ema(closing,20),ema50=core.ema(closing,50);
    fastEma.setData(data.flatMap((c,i)=>ema20[i]===null?[]:[{time:c.time,value:ema20[i]}]));
    slowEma.setData(data.flatMap((c,i)=>ema50[i]===null?[]:[{time:c.time,value:ema50[i]}]));
    fastEma.applyOptions({visible:byId('toggleEma20').checked});
    slowEma.applyOptions({visible:byId('toggleEma50').checked});
    if(fit)chart.timeScale().fitContent();
    const last=data[data.length-1], lastRsi=core.rsi(closing,14).at(-1);
    const sr=core.levels(data, last.close);
    byId('ema20Value').textContent=fmt(ema20.at(-1));byId('ema50Value').textContent=fmt(ema50.at(-1));
    byId('rsiValue').textContent=lastRsi===null?'—':lastRsi.toFixed(2);
    byId('supportValue').textContent=fmt(sr.support[0]);byId('resistanceValue').textContent=fmt(sr.resistance[0]);
    byId('lastPrice').textContent=fmt(Number(ticker.lastPrice));
    const change=Number(ticker.priceChangePercent);
    const el=byId('priceChange');el.textContent=Number.isFinite(change)?(change>0?'+':'')+change.toFixed(2)+'% (24h)':'—';
    el.className='change '+(change>0?'up':change<0?'down':'');
    byId('lastUpdate').textContent='Terakhir diperbarui '+new Date().toLocaleTimeString('id-ID')+' · REST polling';
    byId('chartState').textContent=data.length+' candle valid · live refresh 45s';
    setStatus('ONLINE','online');
  } catch(error){if(id===requestId)showError(error && error.message || 'Gagal memuat data pasar.');}
  finally {if(id===requestId) busy=false;}
}
document.querySelectorAll('#timeframes button').forEach(button=>button.addEventListener('click',()=>{
  state.interval=button.dataset.interval;save();renderTimeframes();refresh(true);
}));
byId('toggleEma20').addEventListener('change',event=>fastEma.applyOptions({visible:event.target.checked}));
byId('toggleEma50').addEventListener('change',event=>slowEma.applyOptions({visible:event.target.checked}));
byId('refresh').addEventListener('click',()=>refresh(false));
byId('searchForm').addEventListener('submit',async event=>{
  event.preventDefault();
  const field=byId('symbolInput');const symbol=core.normalizeSymbol(field.value);
  if(!symbol)return showError('Masukkan simbol Binance Spot USDT seperti BTCUSDT atau SOLUSDT.');
  clearError();setStatus('VERIFYING');
  try {
    await window.lumavun.ticker(symbol);
    if(!state.watchlist.includes(symbol))state.watchlist.push(symbol);
    state.symbol=symbol;field.value='';save();renderWatchlist();await refresh(true);
  } catch(error){showError(error && error.message || 'Symbol tidak dapat diverifikasi.');}
});
renderWatchlist();renderTimeframes();refresh(true);
setInterval(()=>{if(!busy && !document.hidden)refresh(false);},45000);
