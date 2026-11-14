'use strict';
const core=window.LumavunIndicators,insights=window.LumavunInsight,paper=window.LumavunPaper;
const byId=id=>document.getElementById(id);
const storageKey='lumavun-chart-desktop-v2',paperKey='lumavun-chart-paper-v2';
const defaultWatchlist=[{provider:'BINANCE',symbol:'BTCUSDT'},{provider:'BINANCE',symbol:'ETHUSDT'},{provider:'BINANCE',symbol:'SOLUSDT'},{provider:'BIQUOTE',symbol:'XAUUSD'}];
const providers=['BINANCE','BIQUOTE','EXNESS'];
function normalizeWatchItem(v) {
 const item=typeof v==='string'?{symbol:v,provider:'BINANCE'}:v;
 if(!item||!providers.includes(item.provider))return null;
 const symbol=String(item.symbol||'').trim().toUpperCase().replace(/[\/_\-\s]/g,'');
 if(!/^[A-Z0-9.]{1,20}$/.test(symbol))return null;
 if(item.provider==='BINANCE'&&(!symbol.endsWith('USDT')||symbol.length<5||symbol.includes('.')))return null;
 return {provider:item.provider,symbol};
}
function key(item){return item.provider+':'+item.symbol;}
function readSaved(){
 try{
  const old=JSON.parse(localStorage.getItem(storageKey)||localStorage.getItem('lumavun-chart-desktop-v1')||'{}');
  const raw=Array.isArray(old.watchlist)?old.watchlist:defaultWatchlist;
  const watchlist=[...new Map(raw.map(normalizeWatchItem).filter(Boolean).map(item=>[key(item),item])).values()].slice(0,40);
  if(!watchlist.length)watchlist.push(...defaultWatchlist);
  const active=normalizeWatchItem({provider:old.provider||'BINANCE',symbol:old.symbol||'BTCUSDT'});
  return {watchlist,provider:active?.provider||'BINANCE',symbol:active?.symbol||'BTCUSDT',
   interval:['1m','5m','15m','30m','1h','4h','1d'].includes(old.interval)?old.interval:'15m'};
 }catch{return {watchlist:[...defaultWatchlist],provider:'BINANCE',symbol:'BTCUSDT',interval:'15m'};}
}
const state=readSaved();
let account;try{account=paper.restore(JSON.parse(localStorage.getItem(paperKey)||'null'));}catch{account=paper.createAccount();}
let requestId=0,busy=false,lastMarket=null;
const chart=LightweightCharts.createChart(byId('chart'),{
 autoSize:true,layout:{background:{type:'solid',color:'#0e1727'},textColor:'#94a9c8',fontFamily:'Segoe UI, Arial'},
 grid:{vertLines:{color:'#18253b'},horzLines:{color:'#18253b'}},
 timeScale:{timeVisible:true,secondsVisible:false,borderColor:'#26344d',rightOffset:10},
 rightPriceScale:{borderColor:'#26344d'},crosshair:{mode:LightweightCharts.CrosshairMode.Normal}
});
const candleSeries=chart.addSeries(LightweightCharts.CandlestickSeries,{
 upColor:'#43cda4',downColor:'#f07180',borderVisible:false,wickUpColor:'#43cda4',wickDownColor:'#f07180'
});
const fast=chart.addSeries(LightweightCharts.LineSeries,{color:'#f0bc78',lineWidth:2,lastValueVisible:false,priceLineVisible:false});
const slow=chart.addSeries(LightweightCharts.LineSeries,{color:'#9e95ff',lineWidth:2,lastValueVisible:false,priceLineVisible:false});
function save(){localStorage.setItem(storageKey,JSON.stringify(state));}
function savePaper(){localStorage.setItem(paperKey,JSON.stringify(account));}
function fmt(value){
 const n=Number(value);
 if(!Number.isFinite(n)||!(n>0))return '—';
 const digits=n>=100?2:n>=1?4:6;
 return new Intl.NumberFormat('en-US',{minimumFractionDigits:digits,maximumFractionDigits:digits}).format(n);
}
function dollars(n){return new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(n);}
function setStatus(label,kind=''){const el=byId('status');el.textContent=label;el.className='status '+kind;}
function showError(error){const e=byId('error');e.hidden=false;e.textContent=String(error).slice(0,330);setStatus('ERROR','warn');byId('chartState').textContent='Data tidak tersedia';}
function clearError(){const e=byId('error');e.hidden=true;e.textContent='';}
function displaySymbol(symbol){if(symbol.endsWith('USDT'))return symbol.slice(0,-4)+' / USDT';if(symbol.length===6)return symbol.slice(0,3)+' / '+symbol.slice(3);return symbol;}
function renderWatchlist(){
 const root=byId('watchlist');root.replaceChildren();byId('watchCount').textContent=state.watchlist.length+' PAIRS';
 byId('marketProvider').value=state.provider;
 byId('providerBadge').textContent=state.provider==='BINANCE'?'BINANCE SPOT':state.provider==='BIQUOTE'?'BIQUOTE':'EXNESS GATEWAY';
 for(const item of state.watchlist){
  const row=document.createElement('div');row.className='watchItem'+(item.provider===state.provider&&item.symbol===state.symbol?' active':'');
  const pair=document.createElement('button');pair.type='button';pair.className='pairButton';pair.textContent=displaySymbol(item.symbol);
  const sub=document.createElement('small');sub.textContent=item.provider;pair.append(sub);
  pair.addEventListener('click',()=>{state.provider=item.provider;state.symbol=item.symbol;save();renderWatchlist();refresh(true);});
  const remove=document.createElement('button');remove.type='button';remove.className='remove';remove.title='Remove';remove.textContent='×';
  remove.addEventListener('click',()=>{
   if(state.watchlist.length===1)return showError('Watchlist minimal satu simbol.');
   state.watchlist=state.watchlist.filter(x=>key(x)!==key(item));
   if(state.provider===item.provider&&state.symbol===item.symbol){state.provider=state.watchlist[0].provider;state.symbol=state.watchlist[0].symbol;}
   save();renderWatchlist();refresh(true);
  });
  row.append(pair,remove);root.append(row);
 }
}
function renderTimeframes(){document.querySelectorAll('#timeframes button').forEach(b=>b.classList.toggle('active',b.dataset.interval===state.interval));}
function clearData(){
 for(const id of ['rsiValue','ema20Value','ema50Value','supportValue','resistanceValue','lastPrice','priceChange'])byId(id).textContent='—';
 candleSeries.setData([]);fast.setData([]);slow.setData([]);
 byId('insightState').textContent='DATA UNAVAILABLE';byId('insightSummary').textContent='Belum tersedia OHLC terverifikasi untuk analisis.';
 byId('insightDetails').replaceChildren();
}
function renderInsight(data) {
 const result=insights.calculate(data,core);
 byId('insightState').textContent=result.state;
 byId('insightSummary').textContent=result.summary;
 const root=byId('insightDetails');root.replaceChildren();
 for(const part of result.details){const li=document.createElement('li');li.textContent=part;root.append(li);}
}
function renderPaper(){
 byId('paperBalance').textContent=dollars(account.balance);
 byId('paperEquity').textContent=dollars(paper.equity(account));
 byId('paperCount').textContent=String(account.positions.length);
 const root=byId('paperPositions');root.replaceChildren();
 for(const position of account.positions){
  const row=document.createElement('div');row.className='positionRow';
  const description=document.createElement('div');
  const title=document.createElement('strong');title.textContent=position.symbol+' '+position.side;
  const info=document.createElement('small');info.textContent='Entry '+fmt(position.entry)+' · SL '+fmt(position.stop)+' · TP '+fmt(position.target);
  const floating=document.createElement('small');floating.textContent='Unrealized '+dollars((position.last-position.entry)*position.qty*(position.side==='LONG'?1:-1))+' · '+dollars(position.notional)+' notional';
  description.append(title,info,floating);
  const close=document.createElement('button');close.type='button';close.className='subtleButton';close.textContent='Close';
  close.addEventListener('click',async()=>{
   close.disabled=true;byId('paperNotice').textContent='Memverifikasi quote sebelum close…';
   try{
    const quote=await window.lumavun.ticker('BINANCE',position.symbol);
    if(!Number.isFinite(quote.lastPrice)||quote.lastPrice<=0)throw new Error('Quote invalid.');
    paper.close(account,position.id,quote.lastPrice,'MANUAL');
    savePaper();byId('paperNotice').textContent='Position closed dengan quote baru (simulasi).';renderPaper();
   }catch(error){byId('paperNotice').textContent=String(error.message||error);}
   finally{close.disabled=false;}
  });
  row.append(description,close);root.append(row);
 }
 byId('paperHistory').textContent=account.history.length?account.history.length+' closed · terakhir '+account.history[0].symbol+' '+dollars(account.history[0].pnl):'Belum ada riwayat close.';
}
async function refresh(fit=false){
 if(!core||!insights||!paper||!window.lumavun)return showError('Desktop engine tidak lengkap.');
 const id=++requestId,provider=state.provider,symbol=state.symbol,interval=state.interval;
 busy=true;lastMarket=null;clearError();setStatus('LOADING');byId('chartState').textContent='Mengambil candle dari provider…';
 byId('pairTitle').textContent=displaySymbol(symbol);byId('chartLabel').textContent=provider+' · '+symbol+' · '+interval;
 renderWatchlist();
 try{
  const [data,quote]=await Promise.all([window.lumavun.candles(provider,symbol,interval,300),window.lumavun.ticker(provider,symbol)]);
  if(id!==requestId)return;
  if(!Array.isArray(data)||data.length<30)throw new Error('Kurang dari 30 candle valid dari '+provider+'.');
  candleSeries.setData(data.map(c=>({time:c.time,open:c.open,high:c.high,low:c.low,close:c.close})));
  const closes=data.map(c=>c.close),ema20=core.ema(closes,20),ema50=core.ema(closes,50);
  fast.setData(data.flatMap((c,i)=>ema20[i]===null?[]:[{time:c.time,value:ema20[i]}]));
  slow.setData(data.flatMap((c,i)=>ema50[i]===null?[]:[{time:c.time,value:ema50[i]}]));
  fast.applyOptions({visible:byId('toggleEma20').checked});
  slow.applyOptions({visible:byId('toggleEma50').checked});
  if(fit)chart.timeScale().fitContent();
  const sr=core.levels(data,data.at(-1).close),rsi=core.rsi(closes,14).at(-1);
  byId('ema20Value').textContent=fmt(ema20.at(-1));byId('ema50Value').textContent=fmt(ema50.at(-1));
  byId('rsiValue').textContent=rsi===null?'—':rsi.toFixed(2);
  byId('supportValue').textContent=fmt(sr.support[0]);byId('resistanceValue').textContent=fmt(sr.resistance[0]);
  byId('lastPrice').textContent=fmt(quote.lastPrice);
  const percent=Number(quote.priceChangePercent);
  byId('priceChange').textContent=Number.isFinite(percent)?(percent>0?'+':'')+percent.toFixed(2)+'% (provider)':'—';
  byId('priceChange').className='change '+(percent>0?'up':percent<0?'down':'');
  byId('lastUpdate').textContent='Diperbarui '+new Date().toLocaleTimeString('id-ID')+' · '+provider+(quote.marketOpen===false?' · MARKET CLOSED':'');
  byId('chartState').textContent=data.length+' candle valid · REST refresh 45s';
  lastMarket={provider,symbol,price:quote.lastPrice,time:Date.now(),marketOpen:quote.marketOpen};
  renderInsight(data);
  if(provider==='BINANCE'){
   const closed=paper.mark(account,symbol,quote.lastPrice);
   savePaper();
   if(closed.length)byId('paperNotice').textContent=closed.map(p=>p.symbol+' '+p.reason+' '+dollars(p.pnl)).join(' · ');
   renderPaper();
  }
  setStatus('ONLINE','online');
 }catch(e){if(id===requestId){lastMarket=null;clearData();showError(e.message||'Provider gagal memuat data.');}}
 finally{if(id===requestId)busy=false;}
}
document.querySelectorAll('#timeframes button').forEach(b=>b.addEventListener('click',()=>{state.interval=b.dataset.interval;save();renderTimeframes();refresh(true);}));
byId('toggleEma20').addEventListener('change',e=>fast.applyOptions({visible:e.target.checked}));
byId('toggleEma50').addEventListener('change',e=>slow.applyOptions({visible:e.target.checked}));
byId('refresh').addEventListener('click',()=>refresh(false));
byId('marketProvider').addEventListener('change',e=>{
 state.provider=e.target.value;
 const matched=state.watchlist.find(v=>v.provider===state.provider);
 state.symbol=matched?.symbol||(state.provider==='BINANCE'?'BTCUSDT':'XAUUSD');
 save();renderWatchlist();refresh(true);
});
byId('searchForm').addEventListener('submit',async e=>{
 e.preventDefault();const field=byId('symbolInput');
 const candidate=normalizeWatchItem({provider:state.provider,symbol:field.value});
 if(!candidate)return showError('Format simbol tidak valid untuk provider tersebut.');
 try{
  clearError();setStatus('VERIFYING');
  await window.lumavun.ticker(candidate.provider,candidate.symbol);
  if(!state.watchlist.some(v=>key(v)===key(candidate))){
   if(state.watchlist.length>=40)throw new Error('Watchlist maksimal 40 pair.');
   state.watchlist.push(candidate);
  }
  state.provider=candidate.provider;state.symbol=candidate.symbol;
  field.value='';save();renderWatchlist();await refresh(true);
 }catch(error){showError(error.message||'Simbol belum terverifikasi.');}
});
byId('gatewayForm').addEventListener('submit',async e=>{
 e.preventDefault();const url=byId('gatewayUrl').value,token=byId('gatewayToken').value;
 const el=byId('gatewayState');el.textContent='Verifikasi katalog gateway…';
 try{
  const response=await window.lumavun.configureGateway(url,token);
  byId('gatewayToken').value='';
  el.textContent='Gateway aktif: '+response.count+' instrumen terverifikasi. Token hanya di memori proses aplikasi.';
  if(state.provider==='EXNESS')refresh(true);
 }catch(error){el.textContent='Gagal: '+String(error.message||error);}
});
byId('paperForm').addEventListener('submit',e=>{
 e.preventDefault();
 try{
  const side=e.submitter?.value;
  if(!lastMarket||lastMarket.provider!=='BINANCE'||lastMarket.symbol!==state.symbol||Date.now()-lastMarket.time>60000)
   throw new Error('Paper entry hanya saat quote Binance terbaru tersedia.');
  if(lastMarket.marketOpen===false)throw new Error('Market dilaporkan tutup.');
  const position=paper.open(account,{provider:'BINANCE',symbol:state.symbol,side,price:lastMarket.price,
   stopPct:Number(byId('paperSL').value),tpPct:Number(byId('paperTP').value),riskPct:Number(byId('paperRisk').value)});
  savePaper();renderPaper();byId('paperNotice').textContent='Virtual '+position.side+' dibuka pada '+fmt(position.entry)+' (bukan order asli).';
 }catch(error){byId('paperNotice').textContent=String(error.message||error);}
});
byId('paperReset').addEventListener('click',()=>{
 if(!window.confirm('Reset seluruh saldo, posisi, dan histori paper trading?'))return;
 account=paper.createAccount();savePaper();renderPaper();byId('paperNotice').textContent='Simulasi direset.';
});
renderWatchlist();renderTimeframes();renderPaper();refresh(true);
setInterval(()=>{if(!busy&&!document.hidden)refresh(false);},45000);
