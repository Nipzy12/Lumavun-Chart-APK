'use strict';
const core=window.LumavunIndicators,insights=window.LumavunInsight,paper=window.LumavunPaper;
const byId=id=>document.getElementById(id);
const storageKey='lumavun-chart-desktop-v2',paperKey='lumavun-chart-paper-v2';
const defaultWatchlist=[{provider:'BINANCE',symbol:'BTCUSDT'},{provider:'BINANCE',symbol:'ETHUSDT'},{provider:'BINANCE',symbol:'SOLUSDT'},{provider:'BIQUOTE',symbol:'XAUUSD'}];
const providers=['BINANCE','BIQUOTE','EXNESS','MT5','MCP'];
function normalizeWatchItem(v) {
 const item=typeof v==='string'?{symbol:v,provider:'BINANCE'}:v;
 if(!item||!providers.includes(item.provider))return null;
 if(item.provider==='MT5'||item.provider==='MCP'){
  const sym=String(item.symbol||'').trim();
  return /^[A-Za-z0-9._-]{1,32}$/.test(sym)?{provider:'MT5',symbol:sym}:null;
 }
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
const aiUi={configured:false,ready:false,loadedKey:null,renderedKey:null,running:false,visionShot:null};
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
const watchQuotes=new Map();
let watchUpdateBusy=false;
function rememberQuote(provider,symbol,q){
 const last=Number(q?.lastPrice),change=q?.priceChangePercent==null?null:Number(q.priceChangePercent);
 if(!Number.isFinite(last)||last<=0)return;
 watchQuotes.set(provider+':'+symbol,{last,change:Number.isFinite(change)?change:null,at:Date.now()});
}
async function refreshWatchlistPrices(){
 if(watchUpdateBusy||currentPage!=='chart')return;
 watchUpdateBusy=true;
 try{
  const items=state.watchlist.slice(0,12).filter(item=>{
   const cached=watchQuotes.get(key(item));
   return !cached||Date.now()-cached.at>120000;
  });
  for(let i=0;i<items.length;i+=3){
   await Promise.allSettled(items.slice(i,i+3).map(async item=>{
    try{
     const q=await window.lumavun.ticker(item.provider,item.symbol);
     rememberQuote(item.provider,item.symbol,q);
    }catch{
     watchQuotes.set(key(item),{last:null,change:null,at:Date.now()});
    }
   }));
   renderWatchlist();
  }
 }finally{watchUpdateBusy=false;}
}
function renderWatchlist(){
 const root=byId('watchlist');root.replaceChildren();byId('watchCount').textContent=state.watchlist.length+' PAIRS';
 byId('marketProvider').value=state.provider;
 byId('providerBadge').textContent=state.provider==='BINANCE'?'BINANCE SPOT':state.provider==='BIQUOTE'?'BIQUOTE':state.provider==='MT5'?'MT5 TERMINAL':state.provider==='MCP'?'MCP MT5 SERVER':'EXNESS GATEWAY';
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
  const q=watchQuotes.get(key(item));
  const price=document.createElement('span');price.className='tvWatchPrice';
  price.textContent=q?.last>0?fmt(q.last):'—';
  const delta=document.createElement('span');delta.className='tvWatchChange';
  delta.textContent=q?.change!=null&&Number.isFinite(q.change)?(q.change>0?'+':'')+q.change.toFixed(2)+'%':'—';
  if(q?.change>0)delta.classList.add('positive');
  else if(q?.change<0)delta.classList.add('negative');
  row.append(pair,price,delta,remove);root.append(row);
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
 if(window.LumavunV9){
  const r=window.LumavunV9.replay(data,state.interval,core);
  const li=document.createElement('li');
  li.textContent=r?'V9 EMA20/50 replay ('+r.bars+' closed bars, 6 bps/side): '+r.wins+' TP, '+r.losses+' SL, '+r.open+' open; net '+r.netR.toFixed(2)+'R, drawdown '+r.maxDrawdownR.toFixed(2)+'R. Fixed-rule simulation, not AI forecast.':'V9 replay unavailable: insufficient closed history.';
  root.append(li);
 }
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
 busy=true;lastMarket=null;aiUi.ready=false;resetVisionPreview();updateAiButton();if(aiUi.renderedKey&&aiUi.renderedKey!==marketAiKey())resetAiOutput();clearError();setStatus('LOADING');byId('chartState').textContent='Mengambil candle dari provider…';
 byId('pairTitle').textContent=displaySymbol(symbol);byId('chartLabel').textContent=provider+' · '+symbol+' · '+interval;
 renderWatchlist();
 try{
  const [data,quote]=await Promise.all([window.lumavun.candles(provider,symbol,interval,300),window.lumavun.ticker(provider,symbol)]);
  if(id!==requestId)return;
  rememberQuote(provider,symbol,quote);
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
  const percent=quote.priceChangePercent==null?NaN:Number(quote.priceChangePercent);
  byId('priceChange').textContent=Number.isFinite(percent)?(percent>0?'+':'')+percent.toFixed(2)+'% (provider)':'—';
  byId('priceChange').className='change '+(percent>0?'up':percent<0?'down':'');
  byId('lastUpdate').textContent='Diperbarui '+new Date().toLocaleTimeString('id-ID')+' · '+provider+(quote.marketOpen===false?' · MARKET CLOSED':'');
  byId('chartState').textContent=data.length+' candle valid · REST refresh 45s';
  lastMarket={provider,symbol,price:quote.lastPrice,time:Date.now(),marketOpen:quote.marketOpen};
  renderInsight(data);
  if(window.LumaWorkspace)window.LumaWorkspace.onCandles(data,quote);
  aiUi.loadedKey=marketAiKey();aiUi.ready=data.length>=60;updateAiButton();
  if(provider==='BINANCE'){
   const closed=paper.mark(account,symbol,quote.lastPrice);
   savePaper();
   if(closed.length)byId('paperNotice').textContent=closed.map(p=>p.symbol+' '+p.reason+' '+dollars(p.pnl)).join(' · ');
   renderPaper();
  }
  setStatus('ONLINE','online');
  renderWatchlist();
  refreshWatchlistPrices().catch(()=>{});
 }catch(e){if(id===requestId){lastMarket=null;if(window.LumaWorkspace)window.LumaWorkspace.onMarketError();aiUi.ready=false;aiUi.loadedKey=null;updateAiButton();resetAiOutput();clearData();showError(e.message||'Provider gagal memuat data.');}}
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

function renderAiResponse(result,key,provider,symbol,interval){
  aiUi.renderedKey=key;
  byId('aiResult').hidden=false;
  byId('aiDecision').textContent=result.decision+' · '+result.bias+' · '+result.confidence+'% (klaim model)';
  byId('aiMeta').textContent=result.model+' · '+provider+' · '+symbol+' '+interval+' · data '+result.asOfUtc+(result.visionAssisted?' · VISION':' · OHLC');
  byId('aiSummary').textContent=result.summary;
  byId('aiLevels').textContent=result.decision==='WAIT'?
   'WAIT: tidak ada entry, SL, atau TP yang divalidasi.':
   'Skenario hipotetis: Entry '+fmt(result.entry)+' | SL '+fmt(result.stopLoss)+' | TP '+result.takeProfit.map(fmt).join(', ')+' | RR '+result.riskReward;
  const root=byId('aiReasoning');root.replaceChildren();
  for(const reason of result.reasoning){const li=document.createElement('li');li.textContent=reason;root.append(li);}
  if(result.technicalEvidence){const e=result.technicalEvidence,li=document.createElement('li');li.textContent='V9 evidence '+e.score+'/100 · coverage '+e.coverage+'% (EMA/RSI and R:R only; MTF/SMC unavailable). Not a win probability.';root.append(li);}
  if(result.invalidation){const li=document.createElement('li');li.textContent='Invalidasi: '+result.invalidation;root.append(li);}
  byId('aiWarning').textContent=result.warning||'Periksa validitas feed dan risiko sebelum mengambil keputusan apa pun.';
}

function resetVisionPreview(){
 aiUi.visionShot=null;
 byId('aiPreview').hidden=true;
 byId('aiPreviewImage').removeAttribute('src');
 byId('aiPreviewInfo').textContent='Gambar belum diambil.';
 updateAiButton();
}
function marketAiKey(){return state.provider+'|'+state.symbol+'|'+state.interval;}
function setAiStatus(message){byId('aiStatus').textContent=String(message).slice(0,500);}
function resetAiOutput(){
 byId('aiResult').hidden=true;
 byId('aiDecision').textContent='WAIT';byId('aiSummary').textContent='';
 byId('aiLevels').textContent='';byId('aiReasoning').replaceChildren();
 byId('aiWarning').textContent='';aiUi.renderedKey=null;
}
function updateAiButton(){
 const active=aiUi.configured&&aiUi.ready&&aiUi.loadedKey===marketAiKey()&&!aiUi.running;
 byId('aiRun').disabled=!active;
 byId('aiCapture').disabled=!active;
 byId('aiVision').disabled=!active||!aiUi.visionShot||
  aiUi.visionShot.key!==marketAiKey()||Date.now()-aiUi.visionShot.at>120000;
}
let aiModelSyncTimer=null,aiModelSyncId=0;
function updateModelOptions(models){
 const select=byId('aiModel'),previous=select.value;
 select.replaceChildren();
 const placeholder=document.createElement('option');placeholder.value='';
 placeholder.textContent=models.length?'Pilih model ('+models.length+' tersedia)':'Tidak ada model yang ditemukan';
 select.append(placeholder);
 for(const id of models){
  const option=document.createElement('option');option.value=id;option.textContent=id;
  select.append(option);
 }
 if(models.length)select.value=models.includes(previous)?previous:models[0];
 aiUi.configured=false;updateAiButton();
}
function toggleAiFields(){
 const custom=byId('aiMode').value==='CUSTOM';
 byId('aiRemote').hidden=!custom;
 byId('aiEndpoint').required=custom;
 byId('aiDiscover').textContent=custom?'Sinkronkan model provider':'Sinkronkan model Ollama';
 byId('aiModelSyncHint').textContent=custom?
  'Daftar diambil dari /v1/models setelah API URL dan key tersedia; tidak perlu mengetik model.':
  'Daftar diambil dari Ollama /api/tags yang sedang berjalan.';
}
async function syncModelCatalog(){
 const mode=byId('aiMode').value,endpoint=byId('aiEndpoint').value.trim(),
  key=byId('aiToken').value;
 if(mode==='CUSTOM'&&!endpoint)return setAiStatus('Isi URL HTTPS /v1 provider dahulu untuk sinkronisasi otomatis.');
 const requestId=++aiModelSyncId,button=byId('aiDiscover');
 button.disabled=true;setAiStatus('Sinkronisasi model dari '+(mode==='CUSTOM'?endpoint:'Ollama lokal')+'…');
 try{
  const models=await window.lumavun.discoverAiModels({provider:mode,baseUrl:endpoint,apiKey:key});
  if(requestId!==aiModelSyncId)return;
  updateModelOptions(models);
  setAiStatus(models.length+' model ditemukan langsung dari '+(mode==='CUSTOM'?'provider API':'Ollama')+
   '. Pilih model lalu Hubungkan model.');
 }catch(error){
  if(requestId!==aiModelSyncId)return;
  updateModelOptions([]);
  setAiStatus('Sinkronisasi gagal: '+String(error.message||error).slice(0,260)+
   '. Provider harus mendukung GET /v1/models.');
 }finally{if(requestId===aiModelSyncId)button.disabled=false;}
}
function scheduleModelSync(){
 clearTimeout(aiModelSyncTimer);
 const mode=byId('aiMode').value;
 if(mode==='CUSTOM'&&!byId('aiEndpoint').value.trim())return;
 aiModelSyncTimer=setTimeout(()=>syncModelCatalog(),850);
}
byId('aiMode').addEventListener('change',()=>{
 clearTimeout(aiModelSyncTimer);aiModelSyncId++;
 toggleAiFields();updateModelOptions([]);resetAiOutput();resetVisionPreview();
 setAiStatus('Provider berubah. Daftar model disinkronkan dari sumber yang dipilih.');
 scheduleModelSync();
});
byId('aiModel').addEventListener('change',()=>{aiUi.configured=false;updateAiButton();});
byId('aiEndpoint').addEventListener('input',()=>{
 aiUi.configured=false;updateAiButton();scheduleModelSync();
});
byId('aiToken').addEventListener('input',scheduleModelSync);
byId('aiEndpoint').addEventListener('blur',scheduleModelSync);
byId('aiToken').addEventListener('blur',scheduleModelSync);
byId('aiDiscover').addEventListener('click',()=>{clearTimeout(aiModelSyncTimer);syncModelCatalog();});
byId('aiSettings').addEventListener('submit',async event=>{
 event.preventDefault();
 const submit=byId('aiSettings').querySelector('button[type="submit"]');
 const config={provider:byId('aiMode').value,model:byId('aiModel').value.trim(),baseUrl:byId('aiEndpoint').value.trim(),apiKey:byId('aiToken').value};
 aiUi.configured=false;resetAiOutput();updateAiButton();submit.disabled=true;setAiStatus('Memvalidasi model dan endpoint…');
 try{
  const result=await window.lumavun.configureAi(config);
  aiUi.configured=Boolean(result?.configured);updateAiButton();
  window.dispatchEvent(new CustomEvent('lumavun-ai-connected',{detail:{provider:result.provider,model:result.model}}));
  setAiStatus(result.provider+' · '+result.model+' · '+result.note+(aiUi.ready?' Klik Analisis chart saat ini.':' Refresh chart sebelum analisis.'));
 }catch(error){setAiStatus('Gagal menghubungkan AI: '+String(error.message||error));}
 finally{
  byId('aiToken').value='';submit.disabled=false;
 }
});
byId('aiRun').addEventListener('click',async()=>{
 if(!aiUi.configured||!aiUi.ready||aiUi.loadedKey!==marketAiKey())return setAiStatus('Refresh market lalu hubungkan model dahulu.');
 const key=marketAiKey(),{provider,symbol,interval}=state;
 aiUi.running=true;updateAiButton();setAiStatus('Model sedang membaca candle dan indikator. Tidak ada order yang dikirim.');
 try{
  const result=await window.lumavun.analyzeAi(provider,symbol,interval);
  if(key!==marketAiKey()||!aiUi.ready||aiUi.loadedKey!==key){
   setAiStatus('Grafik berubah saat model menganalisis. Hasil lama diabaikan.');
   return;
  }
  renderAiResponse(result,key,provider,symbol,interval);
  setAiStatus('Analisis selesai menggunakan '+result.provider+' / '+result.model+'. AI tidak bisa mengirim order.');
 }catch(error){
  resetAiOutput();setAiStatus('Gagal menganalisis: '+String(error.message||error));
 }finally{aiUi.running=false;updateAiButton();}
});
toggleAiFields();

byId('aiCapture').addEventListener('click',()=>{
 if(!aiUi.configured||!aiUi.ready||aiUi.loadedKey!==marketAiKey())
  return setAiStatus('Hubungkan model dan tunggu data OHLC terbaru terlebih dahulu.');
 try{
  const canvas=captureChartCanvas();
  if(!canvas||canvas.width<200||canvas.height<180)throw Error('Ukuran chart terlalu kecil.');
  const dataUrl=canvas.toDataURL('image/png');
  if(!dataUrl.startsWith('data:image/png;base64,')||dataUrl.length>5_600_000)
   throw Error('Gambar PNG terlalu besar.');
  aiUi.visionShot={key:marketAiKey(),dataUrl,at:Date.now()};
  byId('aiPreviewImage').src=dataUrl;
  byId('aiPreview').hidden=false;
  byId('aiPreviewInfo').textContent='Pratinjau '+canvas.width+' × '+canvas.height+' px · '+state.provider+' '+state.symbol+' '+state.interval+' · belum dikirim ke penyedia AI.';
  setAiStatus('Gambar chart ditangkap secara lokal. Periksa pratinjau, lalu klik Analisis dengan Vision untuk mengirim.');
  updateAiButton();
 }catch(error){resetVisionPreview();setAiStatus('Gagal menangkap grafik: '+String(error.message||error));}
});
byId('aiVision').addEventListener('click',async()=>{
 const shot=aiUi.visionShot,key=marketAiKey();
 if(!shot||shot.key!==key||Date.now()-shot.at>120000||!aiUi.configured||!aiUi.ready||
  aiUi.loadedKey!==key)return setAiStatus('Pratinjau lama/kedaluwarsa. Refresh chart dan ambil gambar baru.');
 const {provider,symbol,interval}=state;
 aiUi.running=true;updateAiButton();
 setAiStatus(byId('aiMode').value==='OLLAMA'?
  'Gambar chart sedang dianalisis Ollama lokal…':
  'Mengirim gambar chart ke custom API yang dipilih untuk analisis Vision…');
 try{
  const result=await window.lumavun.analyzeVision(provider,symbol,interval,shot.dataUrl);
  if(key!==marketAiKey()||!aiUi.ready||aiUi.loadedKey!==key||aiUi.visionShot!==shot){
   setAiStatus('Grafik atau pratinjau berubah saat proses berjalan. Hasil lama diabaikan.');
   return;
  }
  renderAiResponse(result,key,provider,symbol,interval);
  setAiStatus('Analisis Vision selesai. Level hanya skenario hipotetis, tidak ada order yang dikirim.');
 }catch(error){
  resetAiOutput();
  setAiStatus('Gagal Vision: '+String(error.message||error)+'. Pastikan model mendukung input gambar (misalnya qwen2.5vl:3b).');
 }finally{aiUi.running=false;updateAiButton();}
});

renderWatchlist();renderTimeframes();renderPaper();refresh(true);
setInterval(()=>{if(!busy&&!document.hidden)refresh(false);},45000);


/* v0.5 navigable pages and in-app local model installer */
const ROUTES=new Set(['chart','ai','models','paper','mt5','office','performance','settings']);
let currentPage='chart';
function showPage(page) {
 if(!ROUTES.has(page))return;
 currentPage=page;
 document.querySelector('.workspace').classList.toggle('nonChart',page!=='chart');
 for(const route of ROUTES) {
  const node=byId('page-'+route);
  node.hidden=route!==page;
 }
 document.querySelectorAll('#pageNav .navTab').forEach(button=>{
  const selected=button.dataset.route===page;
  button.classList.toggle('active',selected);
  if(selected)button.setAttribute('aria-current','page');
  else button.removeAttribute('aria-current');
 });
 if(page==='chart'){
  refreshWatchlistPrices().catch(()=>{});
  requestAnimationFrame(()=>{
  const rect=byId('chart').getBoundingClientRect();
  if(rect.width>=200&&rect.height>=180)chart.resize(Math.round(rect.width),Math.round(rect.height));
 });
 }
 if(page==='models')refreshLocalAiStatus();
 window.LumaDash?.onPage(page);
}
document.querySelectorAll('#pageNav .navTab').forEach(button=>{
 button.addEventListener('click',()=>showPage(button.dataset.route));
});
function captureChartCanvas() {
 const chartRoute=byId('page-chart');
 const wasHidden=chartRoute.hidden;
 try {
  if(wasHidden)chartRoute.hidden=false;
  const rect=byId('chart').getBoundingClientRect();
  if(rect.width<200||rect.height<180)throw Error('Grafik terlalu kecil. Buka halaman Chart dahulu.');
  chart.resize(Math.round(rect.width),Math.round(rect.height));
  return chart.takeScreenshot(true,false);
 }finally{
  if(wasHidden)chartRoute.hidden=true;
 }
}
const localUi={busy:false,online:false,installed:false,models:[],catalog:[],downloading:null};
function setLocalStatus(message,percent=null) {
 byId('localModelStatus').textContent=String(message).slice(0,400);
 const bar=byId('localModelProgress');
 if(percent===null||percent===undefined){bar.hidden=true;bar.value=0;}
 else{bar.hidden=false;bar.value=Math.max(0,Math.min(100,Number(percent)||0));}
}
function renderLocalModels(){
 const root=byId('localModelCatalog');root.replaceChildren();
 byId('modelInstalledCount').textContent=localUi.models.length+' installed';
 for(const item of localUi.catalog) {
  const card=document.createElement('article');card.className='modelCard';
  const head=document.createElement('div');head.className='modelCardHeader';
  const chip=document.createElement('span');chip.className='modelChip'+(item.type==='vision'?' vision':'');
  chip.textContent=item.type==='vision'?'VISION':'TEXT';
  const installed=localUi.models.includes(item.model)||localUi.models.includes(item.model+':latest');
  const status=document.createElement('small');status.textContent=installed?'INSTALLED':'NOT INSTALLED';
  head.append(chip,status);
  const title=document.createElement('h3');title.textContent=item.name;
  const id=document.createElement('small');id.textContent=item.model;
  const note=document.createElement('p');note.textContent=item.note;
  const controls=document.createElement('div');controls.className='modelCardActions';
  const install=document.createElement('button');install.type='button';install.className='installModel';
  install.textContent=installed?'Installed':'Install Model';
  install.disabled=installed||!localUi.online||localUi.busy;
  install.addEventListener('click',()=>installLocalModel(item.model));
  const use=document.createElement('button');use.type='button';use.className='chooseModel';
  use.textContent='Use in Analyst';use.disabled=!installed;
  use.addEventListener('click',()=>{
   byId('aiMode').value='OLLAMA';
   byId('aiMode').dispatchEvent(new Event('change'));
   if(!Array.from(byId('aiModel').options).some(x=>x.value===item.model)){
    const opt=document.createElement('option');opt.value=item.model;opt.textContent=item.model;byId('aiModel').append(opt);
   }
   byId('aiModel').value=item.model;
   byId('aiModel').dispatchEvent(new Event('change'));
   showPage('ai');
   setAiStatus('Model '+item.model+' dipilih. Klik Hubungkan model untuk memulai analisis.');
  });
  controls.append(install,use);card.append(head,title,id,note,controls);root.append(card);
 }
}
function renderLocalStatus() {
 byId('localRuntimeTitle').textContent=localUi.online?'Ollama Online':
  localUi.installed?'Ollama Installed · Offline':'Ollama Not Installed';
 byId('localRuntimeDetail').textContent=localUi.online?
  'Server lokal aktif di 127.0.0.1:11434. Model bisa diunduh dan dipakai dari aplikasi.':
  localUi.installed?'Runtime ditemukan. Jalankan server Ollama untuk mulai mengunduh model.':
   'Belum menemukan runtime Ollama pada Windows ini. Instal melalui tombol di sebelah kanan.';
 byId('localRuntimeInstall').disabled=localUi.installed||localUi.busy;
 byId('localRuntimeStart').disabled=!localUi.installed||localUi.online||localUi.busy;
 byId('localRuntimeRefresh').disabled=localUi.busy;
 byId('localModelCancel').disabled=!localUi.downloading;
 renderLocalModels();
}
async function refreshLocalAiStatus(){
 if(!window.lumavun||!window.lumavun.localAiStatus)return;
 try{
  const result=await window.lumavun.localAiStatus();
  localUi.online=Boolean(result.online);
  localUi.installed=Boolean(result.installed);
  localUi.models=Array.isArray(result.models)?result.models:[];
  localUi.catalog=Array.isArray(result.catalog)?result.catalog:[];
  localUi.downloading=result.downloading||null;
  renderLocalStatus();
 }catch(error){setLocalStatus('Gagal memeriksa Ollama: '+String(error.message||error));}
}
byId('localRuntimeRefresh').addEventListener('click',()=>refreshLocalAiStatus());
byId('localRuntimeInstall').addEventListener('click',async()=>{
 if(localUi.busy)return;
 if(!window.confirm('Pasang runtime Ollama untuk Windows melalui WinGet? Installer dan model memerlukan ruang disk serta koneksi internet.'))return;
 localUi.busy=true;renderLocalStatus();setLocalStatus('WinGet sedang memasang paket Ollama.Ollama. Jangan tutup aplikasi.');
 try{
  await window.lumavun.installLocalRuntime();
  setLocalStatus('Instalasi runtime selesai. Jika server belum aktif, klik Jalankan Server.');
 }catch(error){setLocalStatus('Instalasi gagal: '+String(error.message||error));}
 finally{localUi.busy=false;await refreshLocalAiStatus();}
});
byId('localRuntimeStart').addEventListener('click',async()=>{
 if(localUi.busy)return;
 localUi.busy=true;renderLocalStatus();setLocalStatus('Mencoba memulai layanan Ollama...');
 try{
  await window.lumavun.startLocalRuntime();
  setLocalStatus('Server Ollama mulai dijalankan. Tekan Refresh Status setelah proses siap.');
 }catch(error){setLocalStatus('Gagal memulai Ollama: '+String(error.message||error));}
 finally{localUi.busy=false;await refreshLocalAiStatus();}
});
async function installLocalModel(model){
 if(localUi.busy||!localUi.online)return;
 if(!window.confirm('Unduh model lokal '+model+' dari library Ollama? File dapat berukuran beberapa GB dan memerlukan internet.'))return;
 localUi.busy=true;localUi.downloading=model;
 renderLocalStatus();setLocalStatus('Memulai unduhan '+model+'...',0);
 try{
  const result=await window.lumavun.pullLocalModel(model);
  setLocalStatus(result.alreadyInstalled?'Model '+model+' telah terpasang.':'Model '+model+' telah berhasil diunduh.',100);
 }catch(error){setLocalStatus('Unduhan tidak berhasil: '+String(error.message||error));}
 finally{localUi.busy=false;localUi.downloading=null;await refreshLocalAiStatus();}
}
byId('localModelCancel').addEventListener('click',async()=>{
 if(!localUi.downloading)return;
 try{
  await window.lumavun.cancelLocalDownload();
  setLocalStatus('Membatalkan unduhan '+localUi.downloading+'...');
 }catch(error){setLocalStatus('Gagal membatalkan: '+String(error.message||error));}
});
if(window.lumavun&&typeof window.lumavun.onLocalAiEvent==='function'){
 window.lumavun.onLocalAiEvent(event=>{
  if(!event||typeof event!=='object')return;
  if(event.type==='model-progress'){
   localUi.downloading=event.model||localUi.downloading;
   setLocalStatus((event.model||'Model')+': '+String(event.status||'Downloading')+
    (event.percent===null||event.percent===undefined?'':' · '+event.percent+'%'),event.percent);
  }else if(event.type==='runtime-progress'){
   setLocalStatus(event.message||'Ollama install progress…');
  }else if(['model-installed','runtime-installed','model-error','runtime-error'].includes(event.type)){
   setLocalStatus(event.message||event.type);
  }
  renderLocalStatus();
 });
}
showPage('chart');
setInterval(()=>refreshWatchlistPrices().catch(()=>{}),120000);
