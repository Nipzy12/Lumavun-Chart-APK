'use strict';
// LUMAVUN Superview: independently implemented TradingView-inspired workspace.
// This module uses only public Lightweight Charts v5 APIs and verified data from
// the existing Electron market gateway. It never creates live broker orders.
(function(){
const $=id=>document.getElementById(id);
const study=window.LumaStudies;
const chartApi=window.LightweightCharts;
const KEY='lumavun-superview-v06';
function saved(){
 try{return JSON.parse(localStorage.getItem(KEY)||'{}');}catch{return {};}
}
const initial=saved();
const cfg={type:['candles','line','area'].includes(initial.type)?initial.type:'candles',
 theme:initial.theme==='light'?'light':'dark',study:{sma200:false,bollinger:false,vwap:false,volume:true,...(initial.study||{})},
 favoriteKeys:Array.isArray(initial.favoriteKeys)?initial.favoriteKeys.filter(x=>typeof x==='string').slice(0,60):[],
 range:initial.range||'all',
 dockCollapsed:initial.dockCollapsed!==false,
 dockHeight:Number.isFinite(initial.dockHeight)?Math.max(160,Math.min(460,initial.dockHeight)):230,
 watchWidth:Number.isFinite(initial.watchWidth)?Math.max(235,Math.min(480,initial.watchWidth)):292,
 settings:{
  grid:initial.settings?.grid!==false,watermark:initial.settings?.watermark===true,
  logScale:initial.settings?.logScale===true,localTime:initial.settings?.localTime===true,
  upColor:/^#[0-9A-Fa-f]{6}$/.test(initial.settings?.upColor)?initial.settings.upColor:'#26a69a',
  downColor:/^#[0-9A-Fa-f]{6}$/.test(initial.settings?.downColor)?initial.settings.downColor:'#ef5350'
 }};
let marketBars=[],quoteData=null,activeTool='cursor',pending=null,drawn=[],drawObjects=[],redoStack=[],compareSeries=null,compareSymbol=null,requestId=0;
const objectStorage='lumavun-superview-objects-v06',alertStorage='lumavun-superview-alerts-v06',notesStorage='lumavun-superview-notes-v06';
function readStore(k,def){try{const value=JSON.parse(localStorage.getItem(k)||'null');return value===null?def:value;}catch{return def;}}
let allDrawings=readStore(objectStorage,{}),alerts=readStore(alertStorage,[]),notes=readStore(notesStorage,{});
if(!allDrawings||typeof allDrawings!=='object'||Array.isArray(allDrawings))allDrawings={};
if(!Array.isArray(alerts))alerts=[];
if(!notes||typeof notes!=='object'||Array.isArray(notes))notes={};
function persist(){
 localStorage.setItem(KEY,JSON.stringify(cfg));
 localStorage.setItem(objectStorage,JSON.stringify(allDrawings));
 localStorage.setItem(alertStorage,JSON.stringify(alerts));
 localStorage.setItem(notesStorage,JSON.stringify(notes));
}
function marketKey(){return state.provider+':'+state.symbol+':'+state.interval;}
function toast(message){
 const node=$('tvToast');node.textContent=String(message).slice(0,220);node.hidden=false;
 clearTimeout(toast._timeout);toast._timeout=setTimeout(()=>node.hidden=true,5000);
}
const primary=chart.addSeries(chartApi.LineSeries,{color:'#60a5fa',lineWidth:2,visible:false,priceLineVisible:false});
const area=chart.addSeries(chartApi.AreaSeries,{lineColor:'#60a5fa',topColor:'rgba(41,98,255,.27)',bottomColor:'rgba(41,98,255,.01)',visible:false,priceLineVisible:false});
const overlays={
 sma200:chart.addSeries(chartApi.LineSeries,{color:'#c997fb',lineWidth:2,visible:false,priceLineVisible:false,lastValueVisible:false}),
 upper:chart.addSeries(chartApi.LineSeries,{color:'#64a3f5',lineWidth:1,visible:false,priceLineVisible:false,lastValueVisible:false}),
 lower:chart.addSeries(chartApi.LineSeries,{color:'#64a3f5',lineWidth:1,visible:false,priceLineVisible:false,lastValueVisible:false}),
 vwap:chart.addSeries(chartApi.LineSeries,{color:'#f1bf69',lineWidth:2,visible:false,priceLineVisible:false,lastValueVisible:false}),
 volume:chart.addSeries(chartApi.HistogramSeries,{priceScaleId:'volume',visible:true,lastValueVisible:false,priceLineVisible:false,priceFormat:{type:'volume'}})
};
overlays.volume.priceScale().applyOptions({scaleMargins:{top:0.82,bottom:0}});
function lineData(bars,vals){return bars.flatMap((bar,i)=>Number.isFinite(vals[i])?[{time:bar.time,value:vals[i]}]:[]);}
function setStyle(type){
 cfg.type=type;
 candleSeries.applyOptions({visible:type==='candles'});
 primary.applyOptions({visible:type==='line'});
 area.applyOptions({visible:type==='area'});
 $('tvChartType').value=type;persist();if(marketBars.length)renderDrawings();
}
function setTheme(theme){
 cfg.theme=theme;document.documentElement.dataset.tvTheme=theme;
 const light=theme==='light';
 chart.applyOptions({
  layout:{background:{type:'solid',color:light?'#ffffff':'#111722'},textColor:light?'#526079':'#9ca5b7'},
  grid:{vertLines:{color:light?'#edf0f5':'#202937'},horzLines:{color:light?'#edf0f5':'#202937'}},
  timeScale:{borderColor:light?'#d8dce5':'#293446'},
  rightPriceScale:{borderColor:light?'#d8dce5':'#293446'}
 });
 $('tvTheme').title=light?'Switch to dark chart':'Switch to light chart';applyAppearance();persist();
}

function formatDate(seconds){
 const date=new Date(seconds*1000);
 if(cfg.settings.localTime){
  return new Intl.DateTimeFormat('en-GB',{day:'2-digit',month:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(date)+' local';
 }
 return date.toISOString().slice(0,16).replace('T',' ')+' UTC';
}
function applyAppearance(){
 const options=cfg.settings,light=cfg.theme==='light';
 const gridColor=light?'#e9edf4':'#202937';
 chart.applyOptions({grid:{
  vertLines:{visible:options.grid,color:gridColor},
  horzLines:{visible:options.grid,color:gridColor}
 }});
 try{
  chart.priceScale('right').applyOptions({mode:options.logScale?chartApi.PriceScaleMode.Logarithmic:chartApi.PriceScaleMode.Normal});
 }catch{toast('Price scale change unavailable in current chart engine.');}
 candleSeries.applyOptions({
  upColor:options.upColor,wickUpColor:options.upColor,borderUpColor:options.upColor,
  downColor:options.downColor,wickDownColor:options.downColor,borderDownColor:options.downColor
 });
 $('tvWatermark').hidden=!options.watermark;
 $('tvShowGrid').checked=options.grid;
 $('tvShowWatermark').checked=options.watermark;
 $('tvScaleMode').value=options.logScale?'log':'normal';
 $('tvTimeLabel').value=options.localTime?'local':'utc';
 $('tvUpColor').value=options.upColor;
 $('tvDownColor').value=options.downColor;
 $('tvTimeZoneLabel').textContent=options.localTime?'Device':'UTC';
 if(marketBars.length){
  $('tvCrosshairDate').textContent=formatDate(marketBars.at(-1).time);
  overlays.volume.setData(marketBars.map(c=>({time:c.time,value:c.volume,color:c.close>=c.open?
   options.upColor+'66':options.downColor+'66'})));
 }
 persist();
}
$('tvShowGrid').addEventListener('change',e=>{cfg.settings.grid=e.target.checked;applyAppearance();});
$('tvShowWatermark').addEventListener('change',e=>{cfg.settings.watermark=e.target.checked;applyAppearance();});
$('tvScaleMode').addEventListener('change',e=>{cfg.settings.logScale=e.target.value==='log';applyAppearance();});
$('tvTimeLabel').addEventListener('change',e=>{cfg.settings.localTime=e.target.value==='local';applyAppearance();});
$('tvUpColor').addEventListener('change',e=>{cfg.settings.upColor=e.target.value;applyAppearance();});
$('tvDownColor').addEventListener('change',e=>{cfg.settings.downColor=e.target.value;applyAppearance();});
$('tvResetAppearance').addEventListener('click',()=>{
 cfg.settings={grid:true,watermark:false,logScale:false,localTime:false,upColor:'#26a69a',downColor:'#ef5350'};
 cfg.theme='dark';setTheme('dark');applyAppearance();toast('Chart appearance reset.');
});

function applyStudyFlags(){
 overlays.sma200.applyOptions({visible:cfg.study.sma200});
 overlays.upper.applyOptions({visible:cfg.study.bollinger});
 overlays.lower.applyOptions({visible:cfg.study.bollinger});
 overlays.vwap.applyOptions({visible:cfg.study.vwap});
 overlays.volume.applyOptions({visible:cfg.study.volume});
 for(const el of document.querySelectorAll('#tvStudyMenu [data-study]')){
  el.checked=Boolean(cfg.study[el.dataset.study]);
 }
 persist();
}
function onCandles(bars,quote){
 marketBars=bars.slice();quoteData=quote;
 $('tvWatermarkSymbol').textContent=state.symbol;
 $('tvWatermarkInterval').textContent=state.interval+' · '+state.provider;
 const close=bars.map(x=>x.close);
 const points=bars.map(c=>({time:c.time,value:c.close}));
 primary.setData(points);area.setData(points);
 overlays.sma200.setData(lineData(bars,study.sma(close,200)));
 const bb=study.bollinger(close,20,2);
 overlays.upper.setData(lineData(bars,bb.upper));overlays.lower.setData(lineData(bars,bb.lower));
 overlays.vwap.setData(lineData(bars,study.vwap(bars)));
 overlays.volume.setData(bars.map(c=>({time:c.time,value:c.volume,
  color:c.close>=c.open?'rgba(38,166,154,.34)':'rgba(239,83,80,.34)'})));
 renderOhlc(bars.at(-1));$('tvCrosshairDate').textContent=formatDate(bars.at(-1).time);
 $('tvDetailsSymbol').textContent=state.symbol;
 $('tvSymbolLabel').textContent=state.symbol;
 $('tvSideChange').textContent=quote.priceChangePercent!=null&&Number.isFinite(Number(quote.priceChangePercent))?Number(quote.priceChangePercent).toFixed(2)+'%':'—';
 $('tvSideHigh').textContent=fmt(quote.highPrice);
 $('tvSideLow').textContent=fmt(quote.lowPrice);
 $('tvSideVolume').textContent=Number.isFinite(Number(quote.volume))?new Intl.NumberFormat('en-US',{notation:'compact',maximumFractionDigits:2}).format(Number(quote.volume)):'—';
 updateFavorite();
 renderDrawings();
 renderAlerts();
 checkAlerts(quote);
 applyRange(cfg.range);
 updateNotes();
 if(compareSymbol)refreshCompare();
}
function onMarketError(){
 marketBars=[];quoteData=null;
 primary.setData([]);area.setData([]);
 for(const part of Object.values(overlays))part.setData([]);
 if(compareSeries)compareSeries.setData([]);
 clearRenderedDrawings();drawObjects=[];
 $('tvOpen').textContent='—';$('tvHigh').textContent='—';$('tvLow').textContent='—';$('tvClose').textContent='—';
 $('tvSideHigh').textContent='—';$('tvSideLow').textContent='—';$('tvSideChange').textContent='—';$('tvSideVolume').textContent='—';
}
function renderOhlc(b){
 if(!b)return;
 for(const [field,id] of [['open','tvOpen'],['high','tvHigh'],['low','tvLow'],['close','tvClose']]){
  const el=$(id);el.textContent=fmt(b[field]);el.className=b.close>=b.open?'tvUp':'tvDown';
 }
}
chart.subscribeCrosshairMove(param=>{
 if(!param||typeof param.time!=='number')return;
 const b=marketBars.find(x=>x.time===param.time);
 if(b){renderOhlc(b);$('tvCrosshairDate').textContent=formatDate(b.time);}
});
function applyRange(input){
 const size=marketBars.length;if(!size)return;
 const n=input==='all'?size:Math.max(10,Math.min(size,Math.floor(Number(input))||size));
 cfg.range=input;$('tvRanges').querySelectorAll('button').forEach(b=>b.classList.toggle('active',b.dataset.bars===String(input)));
 try{chart.timeScale().setVisibleLogicalRange({from:Math.max(0,size-n),to:size+4});}catch{chart.timeScale().fitContent();}
 persist();
}
function setDockOpen(open){
 cfg.dockCollapsed=!open;
 $('tvDock').classList.toggle('collapsed',!open);
 $('tvDockHandle').hidden=!open;
 $('tvDockToggle').title=open?'Collapse bottom panel':'Expand bottom panel';
 document.documentElement.style.setProperty('--tv-dock-height',cfg.dockHeight+'px');
 persist();
}
function changeDock(panel,open=true){
 document.querySelectorAll('[data-dock]').forEach(b=>b.classList.toggle('active',b.dataset.dock===panel));
 document.querySelectorAll('[data-dock-pane]').forEach(node=>node.hidden=node.dataset.dockPane!==panel);
 setDockOpen(open);
}
$('tvDockToggle').addEventListener('click',()=>setDockOpen(cfg.dockCollapsed));
function toggleMenu(id){
 const target=$(id),shown=!target.hidden;
 $('tvStudyMenu').hidden=true;$('tvCompareMenu').hidden=true;$('tvSettingsMenu').hidden=true;
 target.hidden=shown;
}
$('tvIndicatorsOpen').addEventListener('click',()=>toggleMenu('tvStudyMenu'));
$('tvCompareOpen').addEventListener('click',()=>toggleMenu('tvCompareMenu'));
$('tvSettingsOpen').addEventListener('click',()=>toggleMenu('tvSettingsMenu'));
$('tvChartType').addEventListener('change',e=>setStyle(e.target.value));
$('tvStudyMenu').addEventListener('change',e=>{
 const k=e.target.dataset.study;if(!k)return;
 cfg.study[k]=e.target.checked;applyStudyFlags();saveIndicators();
});
function saveIndicators(){persist();}
$('tvRanges').querySelectorAll('button').forEach(button=>button.addEventListener('click',()=>applyRange(button.dataset.bars)));
document.querySelectorAll('[data-dock]').forEach(b=>b.addEventListener('click',()=>changeDock(b.dataset.dock)));
$('tvTheme').addEventListener('click',()=>setTheme(cfg.theme==='dark'?'light':'dark'));
$('tvSymbolOpen').addEventListener('click',()=>openSymbolDialog());
$('tvWatchAdd').addEventListener('click',()=>openSymbolDialog());
$('tvToggleRight').addEventListener('click',()=>{
 document.querySelector('.workspace').classList.toggle('tvRightClosed');
 updateRightRail();persist();
});
$('tvFullscreen').addEventListener('click',async()=>{
 try{
  if(document.fullscreenElement)await document.exitFullscreen();
  else await $('tvWorkspace').requestFullscreen();
 }catch{toast('Fullscreen tidak diizinkan oleh sistem Windows.');}
});
$('tvSaveLayout').addEventListener('click',()=>{persist();toast('Layout, drawing, alert, and note settings saved locally.');});
$('tvSortWatchlist').addEventListener('click',()=>{state.watchlist.sort((a,b)=>(a.symbol||'').localeCompare(b.symbol||''));save();renderWatchlist();});
$('tvCreateAlert').addEventListener('click',()=>{changeDock('alerts');if(quoteData?.lastPrice)$('tvAlertPrice').value=quoteData.lastPrice;$('tvAlertPrice').focus();});
$('tvFavorite').addEventListener('click',()=>{
 const k=state.provider+':'+state.symbol,idx=cfg.favoriteKeys.indexOf(k);
 if(idx===-1)cfg.favoriteKeys.push(k);else cfg.favoriteKeys.splice(idx,1);
 updateFavorite();persist();
});
function updateFavorite(){
 const active=cfg.favoriteKeys.includes(state.provider+':'+state.symbol);
 $('tvFavorite').textContent=active?'★':'☆';$('tvFavorite').title=active?'Remove from favorites':'Add to favorites';
}
function updateNotes(){$('tvNotes').value=String(notes[marketKey()]||'').slice(0,10000);}
$('tvNotes').addEventListener('input',()=>{notes[marketKey()]=$('tvNotes').value.slice(0,10000);persist();});
$('tvCapture').addEventListener('click',async()=>{
 if(!marketBars.length)return toast('Belum ada chart yang bisa disimpan.');
 try{
  const img=chart.takeScreenshot(true,false).toDataURL('image/png');
  const res=await window.lumavun.saveChartPng(img);
  toast(res.saved?'Chart PNG saved to '+res.fileName:'Screenshot dibatalkan.');
 }catch(e){toast('Gagal menyimpan screenshot: '+String(e.message||e));}
});
let compareRequest=0;
async function refreshCompare(){
 if(!compareSymbol)return;
 const symbol=compareSymbol,request=++compareRequest;
 if(state.provider!=='BINANCE'){
  toast('Compare overlay saat ini hanya mendukung grafik Binance USDT.');
  clearCompare();return;
 }
 try{
  const rows=await window.lumavun.candles('BINANCE',symbol,state.interval,300);
  if(request!==compareRequest||symbol!==compareSymbol)return;
  const base=rows[0]?.close;
  if(!Number.isFinite(base)||base<=0)throw Error('No valid compare candles.');
  if(!compareSeries)compareSeries=chart.addSeries(chartApi.LineSeries,{color:'#f2bc53',lineWidth:2,priceScaleId:'compare',priceFormat:{type:'percent'},priceLineVisible:false});
  compareSeries.setData(rows.map(c=>({time:c.time,value:(c.close/base-1)*100})));
  $('tvCompareActive').textContent='Overlay '+symbol+' · % from first loaded bar';
  const remove=document.createElement('button');remove.type='button';remove.textContent='Remove';remove.addEventListener('click',clearCompare);$('tvCompareActive').append(remove);
 }catch(e){$('tvCompareActive').textContent='Compare unavailable: '+String(e.message||e);}
}
function clearCompare(){
 compareSymbol=null;compareRequest++;
 if(compareSeries){chart.removeSeries(compareSeries);compareSeries=null;}
 $('tvCompareActive').replaceChildren();
}
$('tvCompareForm').addEventListener('submit',async e=>{
 e.preventDefault();
 const candidate=String($('tvCompareInput').value||'').trim().toUpperCase();
 if(!/^[A-Z0-9]{5,20}$/.test(candidate)||!candidate.endsWith('USDT'))return toast('Only valid Binance USDT symbols supported.');
 try{await window.lumavun.ticker('BINANCE',candidate);}catch{return toast('Compare symbol not verified by provider.');}
 compareSymbol=candidate;
 const box=$('tvCompareActive');box.replaceChildren();
 const clear=document.createElement('button');clear.type='button';clear.textContent='Remove compare';clear.addEventListener('click',clearCompare);
 box.append(clear);
 await refreshCompare();
});
function drawKey(){return marketKey();}
function drawings(){return Array.isArray(allDrawings[drawKey()])?allDrawings[drawKey()]:[];}
function saveDrawings(){
 allDrawings[drawKey()]=drawObjects.slice(-80);persist();renderDrawings();
}
function showDrawingHint(){
 $('tvDrawHint').textContent=activeTool==='cursor'?'Cursor · choose drawing tool on left':
  activeTool.toUpperCase()+(pending?' · click second anchor on chart':' · click chart to place'+(activeTool==='trend'||activeTool==='fib'?' first anchor':''));
}
function selectTool(name){
 activeTool=name;pending=null;
 document.querySelectorAll('[data-draw]').forEach(b=>b.classList.toggle('active',b.dataset.draw===name));
 showDrawingHint();
}
document.querySelectorAll('[data-draw]').forEach(button=>button.addEventListener('click',()=>selectTool(button.dataset.draw)));
function clearRenderedDrawings(){
 for(const item of drawn){
  try{if(item.kind==='line')chart.removeSeries(item.ref);
    else item.owner.removePriceLine(item.ref);}catch{}
 }
 drawn=[];
}
function drawRecord(record){
 if(!record||!['horizontal','trend','fib','note'].includes(record.type)||!Array.isArray(record.anchors))return;
 const anchors=record.anchors;
 if(!anchors.length)return;
 const [a,b]=anchors;
 if(!Array.isArray(a)||a.length<2||!Number.isFinite(a[1])||a[1]<=0)return;
 const color=record.type==='fib'?'#ecb879':record.type==='note'?'#aa9afc':'#7aa6f8';
 const priceOwner=cfg.type==='candles'?candleSeries:cfg.type==='line'?primary:area;
 if(record.type==='trend'){
  if(!b||!Number.isFinite(b[1])||a[0]===b[0])return;
  const s=chart.addSeries(chartApi.LineSeries,{color,lineWidth:2,lastValueVisible:false,priceLineVisible:false});
  const pts=[a,b].sort((x,y)=>x[0]-y[0]);
  s.setData(pts.map(pt=>({time:pt[0],value:pt[1]})));
  drawn.push({kind:'line',ref:s});return;
 }
 const levels=record.type==='fib'&&b?
  [0,.236,.382,.5,.618,1].map(f=>({price:a[1]+(b[1]-a[1])*f,title:'Fib '+(f*100).toFixed(1)+'%'})):
  [{price:a[1],title:record.type==='note'?String(record.title||'Note').slice(0,20):'H-Line'}];
 for(const level of levels){
  if(!Number.isFinite(level.price)||level.price<=0)continue;
  const line=priceOwner.createPriceLine({price:level.price,color,lineWidth:1,lineStyle:chartApi.LineStyle.Dashed,axisLabelVisible:true,title:level.title});
  drawn.push({kind:'price',ref:line,owner:priceOwner});
 }
}
function renderDrawings(){
 clearRenderedDrawings();
 drawObjects=drawings().filter(x=>x&&Array.isArray(x.anchors)).slice(-80);
 for(const item of drawObjects)drawRecord(item);
 const list=$('tvObjects');list.replaceChildren();
 if(!drawObjects.length){list.textContent='No drawings yet. Pick a tool on the left and click the chart.';return;}
 for(let i=0;i<drawObjects.length;i++){
  const item=drawObjects[i],row=document.createElement('div');row.className='tvObjectRow';
  const label=document.createElement('span');label.textContent=(i+1)+'. '+item.type.toUpperCase()+' '+(item.title||'')+' @ '+fmt(item.anchors[0][1]);
  const remove=document.createElement('button');remove.type='button';remove.textContent='Remove';
  remove.addEventListener('click',()=>{drawObjects.splice(i,1);redoStack=[];saveDrawings();});
  row.append(label,remove);list.append(row);
 }
}
function undo(){if(!drawObjects.length)return;redoStack.push(drawObjects.pop());saveDrawings();}
function redo(){if(!redoStack.length)return;drawObjects.push(redoStack.pop());saveDrawings();}
$('tvUndo').addEventListener('click',undo);$('tvRedo').addEventListener('click',redo);
$('tvClearDrawings').addEventListener('click',()=>{
 if(!drawObjects.length)return;if(!confirm('Remove all drawings for this symbol and timeframe?'))return;
 drawObjects=[];redoStack=[];saveDrawings();selectTool('cursor');
});
chart.subscribeClick(param=>{
 if(activeTool==='cursor'||!marketBars.length||!param?.point||typeof param.time!=='number')return;
 const price=candleSeries.coordinateToPrice(param.point.y);
 if(!Number.isFinite(price)||price<=0)return;
 const anchor=[param.time,price],tool=activeTool;
 if(tool==='trend'||tool==='fib'){
  if(!pending){pending=anchor;showDrawingHint();return;}
  if(pending[0]===anchor[0]){toast('Choose another candle for second point.');return;}
 }
 let title='';
 if(tool==='note'){const value=prompt('Annotation text (max 24 characters)','Chart note');if(value===null)return;title=value.trim().slice(0,24);}
 const anchors=tool==='trend'||tool==='fib'?[pending,anchor]:[anchor];
 const record={type:tool,anchors,title,id:Date.now()+'_'+Math.random().toString(36).slice(2,8)};
 if(drawObjects.length>=80)drawObjects.shift();
 drawObjects.push(record);redoStack=[];pending=null;saveDrawings();selectTool('cursor');
 toast('Drawing added. Undo, Object Tree, and local save are available.');
});
function renderAlerts(){
 const root=$('tvAlerts');root.replaceChildren();
 const selected=alerts.filter(a=>a&&a.provider===state.provider&&a.symbol===state.symbol).slice(-30).reverse();
 if(!selected.length){root.textContent='No price alerts configured for this symbol.';return;}
 for(const item of selected){
  const row=document.createElement('div');row.className='tvAlertRow';
  const label=document.createElement('span');label.textContent=(item.fired?'TRIGGERED':'ACTIVE')+' · '+item.direction.toUpperCase()+' '+fmt(item.price);
  const remove=document.createElement('button');remove.type='button';remove.textContent='Delete';
  remove.addEventListener('click',()=>{alerts=alerts.filter(a=>a.id!==item.id);persist();renderAlerts();});
  row.append(label,remove);root.append(row);
 }
}
$('tvAlertForm').addEventListener('submit',e=>{
 e.preventDefault();
 const price=Number($('tvAlertPrice').value);
 if(!Number.isFinite(price)||price<=0)return toast('Enter a valid target price.');
 alerts.push({id:Date.now()+'-'+Math.random().toString(36).slice(2,7),provider:state.provider,symbol:state.symbol,direction:$('tvAlertCondition').value,price,fired:false});
 alerts=alerts.slice(-100);persist();renderAlerts();toast('Local price alert created.');
});
function checkAlerts(quote){
 const current=Number(quote.lastPrice);if(!(current>0))return;
 let changed=false;
 for(const a of alerts){
  if(!a||a.fired||a.provider!==state.provider||a.symbol!==state.symbol)continue;
  if((a.direction==='above'&&current>=a.price)||(a.direction==='below'&&current<=a.price)){
   a.fired=true;changed=true;toast('PRICE ALERT · '+state.symbol+' '+a.direction+' '+fmt(a.price)+' · current '+fmt(current));
  }
 }
 if(changed){persist();renderAlerts();}
}
$('tvBacktest').addEventListener('click',()=>{
 const result=$('tvBacktestResult');result.replaceChildren();
 if(marketBars.length<30){result.textContent='Not enough actual OHLC candles to run backtest.';return;}
 try{
  const b=study.backtest(marketBars,{fast:9,slow:21,feeBps:10,capital:1000});
  const items=[['Starting balance','$'+b.initial.toFixed(2)],['Ending balance','$'+b.final.toFixed(2)],
   ['Return',b.returnPct.toFixed(2)+'%'],['Closed trades',String(b.trades)],['Win rate',b.winRate.toFixed(1)+'%'],['Max drawdown',b.maxDrawdown.toFixed(2)+'%']];
  for(const [label,value] of items){
   const el=document.createElement('div');el.className='tvBacktestMetric';
   const name=document.createElement('small');name.textContent=label;const n=document.createElement('strong');n.textContent=value;el.append(name,n);result.append(el);
  }
  const caption=document.createElement('p');caption.textContent='Historical close-only indicator simulation, not a verified execution or profitability forecast. No broker spread, slippage or margin. Last open position marked at last loaded close.';
  result.append(caption);
 }catch(err){result.textContent=String(err.message||err);}
});

function updateRightRail(){
 const visible=!document.querySelector('.workspace').classList.contains('tvRightClosed');
 $('tvRailWatch').classList.toggle('active',visible);
}
$('tvRailWatch').addEventListener('click',()=>{showPage('chart');$('tvToggleRight').click();});
$('tvRailAlerts').addEventListener('click',()=>{showPage('chart');changeDock('alerts');});
$('tvRailObjects').addEventListener('click',()=>{showPage('chart');changeDock('objects');});
$('tvRailAi').addEventListener('click',()=>showPage('ai'));
$('tvRailPaper').addEventListener('click',()=>showPage('paper'));
$('tvRailModels').addEventListener('click',()=>showPage('models'));
$('tvRailMt5').addEventListener('click',()=>showPage('mt5'));
$('tvRailSettings').addEventListener('click',()=>showPage('settings'));
function enableResizable(handle,axis){
 handle.addEventListener('pointerdown',event=>{
  if(event.button!==0)return;
  event.preventDefault();
  const x=event.clientX,y=event.clientY,starting=axis==='x'?cfg.watchWidth:cfg.dockHeight;
  handle.classList.add('dragging');
  document.body.style.cursor=axis==='x'?'col-resize':'row-resize';
  document.body.style.userSelect='none';
  const move=e=>{
   if(axis==='x'){
    cfg.watchWidth=Math.round(Math.max(235,Math.min(Math.min(480,window.innerWidth*.5),starting+x-e.clientX)));
    document.documentElement.style.setProperty('--tv-right-width',cfg.watchWidth+'px');
   }else{
    cfg.dockHeight=Math.round(Math.max(160,Math.min(Math.min(460,window.innerHeight*.75),starting+y-e.clientY)));
    document.documentElement.style.setProperty('--tv-dock-height',cfg.dockHeight+'px');
   }
  };
  const done=()=>{
   window.removeEventListener('pointermove',move);window.removeEventListener('pointerup',done);
   handle.classList.remove('dragging');document.body.style.cursor='';document.body.style.userSelect='';
   persist();
  };
  window.addEventListener('pointermove',move);window.addEventListener('pointerup',done,{once:true});
 });
}
enableResizable($('tvSidebarResize'),'x');
enableResizable($('tvDockHandle'),'y');
const searchModal=$('tvSymbolDialog');
let searchProvider='BINANCE',searchBusy=false;
const candidates={
 BINANCE:['BTCUSDT','ETHUSDT','SOLUSDT','BNBUSDT','XRPUSDT','DOGEUSDT','ADAUSDT','LINKUSDT'],
 BIQUOTE:['XAUUSD','EURUSD','GBPUSD','USDJPY'],
 EXNESS:[],MT5:['XAUUSD','EURUSD','GBPUSD','USDJPY']
};
function renderSymbolSuggestions(){
 const query=$('tvSearchQuery').value.trim().toUpperCase().replace(/[\/_\-\s]/g,'');
 const collection=[...new Set([
  ...state.watchlist.filter(i=>i.provider===searchProvider).map(i=>i.symbol),
  ...candidates[searchProvider],
  ...(query&&/^[A-Z0-9.]{1,20}$/.test(query)?[query]:[])
 ])].filter(v=>!query||v.includes(query)).slice(0,30);
 const root=$('tvSearchResults');root.replaceChildren();
 if(!collection.length){root.textContent='No matching local suggestions. Enter a valid symbol for provider verification.';return;}
 for(const symbol of collection){
  const row=document.createElement('button');row.type='button';
  const left=document.createElement('div'),name=document.createElement('strong'),meta=document.createElement('small');
  name.textContent=symbol;meta.textContent='Select to verify market quote';left.append(name,meta);
  const source=document.createElement('small');source.textContent=searchProvider;
  row.append(left,source);row.addEventListener('click',()=>chooseSymbol(symbol,searchProvider));
  root.append(row);
 }
}
function setSearchProvider(provider){
 if(!['BINANCE','BIQUOTE','EXNESS','MT5'].includes(provider))return;
 searchProvider=provider;
 document.querySelectorAll('[data-search-provider]').forEach(btn=>btn.classList.toggle('active',btn.dataset.searchProvider===provider));
 renderSymbolSuggestions();
}
function openSymbolDialog(){
 if(searchModal.open)return;
 setSearchProvider(state.provider);
 $('tvSearchQuery').value='';
 renderSymbolSuggestions();
 searchModal.showModal();
 $('tvSearchQuery').focus();
}
$('tvSearchClose').addEventListener('click',()=>searchModal.close());
$('tvSearchQuery').addEventListener('input',renderSymbolSuggestions);
document.querySelectorAll('[data-search-provider]').forEach(btn=>btn.addEventListener('click',()=>setSearchProvider(btn.dataset.searchProvider)));
searchModal.addEventListener('click',e=>{if(e.target===searchModal)searchModal.close();});
$('tvSearchForm').addEventListener('submit',e=>{
 e.preventDefault();chooseSymbol($('tvSearchQuery').value,searchProvider);
});
async function chooseSymbol(raw,provider){
 if(searchBusy)return;
 const symbol=provider==='MT5'?String(raw||'').trim():String(raw||'').trim().toUpperCase().replace(/[\/_\-\s]/g,'');
 const item=normalizeWatchItem({provider,symbol});
 if(!item){toast('Invalid symbol for selected provider.');return;}
 searchBusy=true;
 try{
  const quote=await window.lumavun.ticker(provider,item.symbol);
  if(!(Number(quote?.lastPrice)>0))throw Error('Provider returned no verified quote.');
  if(!state.watchlist.some(v=>v.provider===provider&&v.symbol===item.symbol)){
   if(state.watchlist.length>=40)throw Error('Watchlist is full (40 pairs).');
   state.watchlist.push(item);
  }
  state.provider=provider;state.symbol=item.symbol;
  save();renderWatchlist();searchModal.close();showPage('chart');refresh(true);
 }catch(error){
  toast('Symbol not available: '+String(error.message||error));
 }finally{searchBusy=false;}
}

document.addEventListener('keydown',e=>{
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='k'){e.preventDefault();openSymbolDialog();return;}
 if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='s'){e.preventDefault();persist();toast('Workspace saved locally.');return;}
 if(e.key==='Escape'&&$('tvSymbolDialog').open){$('tvSymbolDialog').close();return;}
 const active=document.activeElement?.tagName;
 if(['INPUT','TEXTAREA','SELECT'].includes(active))return;
 if(e.ctrlKey&&e.key.toLowerCase()==='z'){e.preventDefault();undo();}
 else if(e.ctrlKey&&e.key.toLowerCase()==='y'){e.preventDefault();redo();}
 else if(e.key==='Escape'){selectTool('cursor');$('tvStudyMenu').hidden=true;$('tvCompareMenu').hidden=true;$('tvSettingsMenu').hidden=true;}

});
setStyle(cfg.type);setTheme(cfg.theme);applyStudyFlags();updateFavorite();renderAlerts();changeDock('overview',!cfg.dockCollapsed);showDrawingHint();
document.documentElement.style.setProperty('--tv-right-width',cfg.watchWidth+'px');
applyAppearance();updateRightRail();
window.LumaWorkspace={onCandles,onMarketError,selectTool};
})();
