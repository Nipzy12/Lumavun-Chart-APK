'use strict';
const {app,BrowserWindow,ipcMain,dialog,shell,clipboard} = require('electron');
const fs=require('node:fs/promises');
const path=require('node:path');
const market=require('./core/market.cjs');
const ai=require('./core/ai-analyst.cjs');
const modelCatalog=require('./core/model-catalog.cjs');
const performance=require('./core/performance.js');
const vision=require('./core/vision.cjs');
const {LocalAiManager}=require('./core/local-ai-manager.cjs');
const {MT5Client}=require('./core/mt5-client.cjs');
const {MT5TradeGate}=require('./core/mt5-trade-gate.cjs');
const {MT5McpHttpServer}=require('./core/mt5-mcp-http.cjs');
const {MT5McpRemote}=require('./core/mt5-mcp-client.cjs');
const hosts=['https://api.binance.com','https://api1.binance.com','https://data-api.binance.vision'];
let gateway={url:'',token:'',symbols:[]};
let aiConfig=null,aiBusy=false;
const verifiedCandleCache=new Map();
const mt5=new MT5Client({app});
const mt5McpServer=new MT5McpHttpServer({bridge:mt5});
const mt5McpRemote=new MT5McpRemote();
const mt5Gate=new MT5TradeGate();
let mt5Executing=false;
async function currentMt5Account(){
 const account=await mt5.call('account',{});
 const session=mt5Gate.getStatus();
 if(session.unlocked&&(session.account!==account.account||session.environment!==account.mode))
  mt5Gate.lock();
 return account;
}
const localAiManager=new LocalAiManager({
 notify:event=>{
  for(const window of BrowserWindow.getAllWindows()){
   if(!window.isDestroyed())window.webContents.send('lumavun:local-ai:event',event);
  }
 }
});
async function getJson(url,token='') {
 const controller=new AbortController();
 const timer=setTimeout(()=>controller.abort(),12000);
 try{
  const headers={'Accept':'application/json'};
  if(token)headers.Authorization='Bearer '+token;
  const response=await fetch(url,{method:'GET',headers,redirect:'error',signal:controller.signal});
  if(!response.ok)throw new Error('HTTP '+response.status+' from market provider');
  const payload=await response.json();
  if(!payload || typeof payload!=='object')throw new Error('Invalid JSON from market provider.');
  return payload;
 }finally{clearTimeout(timer);}
}
async function getBinance(endpoint,params){
 const query=new URLSearchParams(params).toString();
 let error;
 for(const base of hosts){
  try{return await getJson(base+endpoint+'?'+query);}
  catch(e){error=e;}
 }
 throw new Error('Binance unavailable: '+String(error?.message||'network error'));
}
async function getBiQuote(endpoint,params={}){
 const query=new URLSearchParams(params).toString();
 return getJson('https://biquote.io'+endpoint+(query?'?'+query:''));
}
function getGateway() {
 if(!gateway.url||gateway.symbols.length===0)throw new Error('Exness gateway belum dikonfigurasi / instrumen belum diverifikasi.');
 return gateway;
}
function allowExnessSymbol(sym){
 const config=getGateway();
 const found=config.symbols.find(x=>x.symbol===sym);
 if(!found)throw new Error('Simbol '+sym+' tidak ada pada katalog gateway Exness terverifikasi.');
 if(!['TRADING','ACTIVE','OPEN'].includes(found.status))throw new Error('Simbol Exness tidak berstatus trading: '+found.status);
 return config;
}
async function getExness(endpoint,params,config){
 const q=new URLSearchParams(params).toString();
 return getJson(config.url+endpoint+(q?'?'+q:''),config.token);
}
ipcMain.handle('lumavun:gateway',async (_event,input)=>{
 const url=market.validateGateway(input?.url);
 const token=String(input?.token||'').trim();
 if(token.length>4096||/[\r\n]/.test(token))throw new Error('Invalid gateway session token.');
 // Auth is kept in the desktop main-process memory only. No token in localStorage.
 const result=await getJson(url+'/api/symbols',token);
 const symbols=market.normalizeSymbols(result);
 if(!symbols.length)throw new Error('Gateway tidak mengembalikan katalog simbol terverifikasi.');
 gateway={url,token,symbols};
 return {url,count:symbols.length,symbols:symbols.filter(x=>['TRADING','ACTIVE','OPEN'].includes(x.status)).slice(0,150)};
});
ipcMain.handle('lumavun:symbols',async (_event,provider,query)=>{
 if(provider==='MT5')return mt5.call('symbols',{query:String(query||'').trim().slice(0,32)});
 if(provider==='MCP')return mt5McpRemote.call('symbols',{query:String(query||'').trim().slice(0,32)});
 if(provider==='EXNESS')return getGateway().symbols;
 if(provider==='BIQUOTE'){
  const q=String(query||'').trim().slice(0,40);
  if(!q||!/^[A-Za-z0-9 ._-]+$/.test(q))throw new Error('Masukkan simbol atau nama instrumen untuk dicari.');
  return market.normalizeSymbols(await getBiQuote('/api/symbols/search',{q}));
 }
 if(provider==='BINANCE')return [];
 throw new Error('Unknown provider.');
});
ipcMain.handle('lumavun:candles',async (_event,input)=>{
 const provider=String(input?.provider||'');
 const symbol=market.symbol(input?.symbol,provider);
 const interval=String(input?.interval||'');
 if(!market.INTERVALS.has(interval))throw new Error('Unsupported timeframe.');
 const limit=Math.max(30,Math.min(500,Math.floor(Number(input?.limit)||300)));
 let result;
 if(provider==='BINANCE')result=await getBinance('/api/v3/klines',{symbol,interval,limit});
 else if(provider==='BIQUOTE')result=await getBiQuote('/api/'+encodeURIComponent(symbol)+'/ohlc',{interval,limit});
 else if(provider==='MT5')result=await mt5.call('candles',{symbol,interval,limit});
 else if(provider==='MCP')result=await mt5McpRemote.call('candles',{symbol,interval,limit});
 else result=await getExness('/api/candles',{symbol,interval,limit},allowExnessSymbol(symbol));
 const candles=market.normalizeCandles(result);
 if(!candles.length)throw new Error(provider+' tidak mengembalikan candle OHLC yang valid.');
 const cacheKey=provider+'|'+symbol+'|'+interval;
 verifiedCandleCache.set(cacheKey,{data:candles,loadedAt:Date.now()});
 if(verifiedCandleCache.size>25)verifiedCandleCache.delete(verifiedCandleCache.keys().next().value);
 return candles;
});
ipcMain.handle('lumavun:ticker',async (_event,input)=>{
 const provider=String(input?.provider||'');
 const symbol=market.symbol(input?.symbol,provider);
 let result;
 if(provider==='BINANCE')result=await getBinance('/api/v3/ticker/24hr',{symbol});
 else if(provider==='BIQUOTE')result=await getBiQuote('/api/'+encodeURIComponent(symbol));
 else if(provider==='MCP'){
  const quote=await mt5McpRemote.call('ticker',{symbol});
  if(quote?.symbol!==symbol||!(Number(quote.bid)>0)||!(Number(quote.ask)>=Number(quote.bid)))
   throw Error('MCP server returned an invalid broker quote.');
  return {...quote,provider:'MCP'};
 }
 else if(provider==='MT5'){
  const quote=await mt5.call('ticker',{symbol});
  if(quote.provider!=='MT5'||quote.symbol!==symbol||!(quote.bid>0)||!(quote.ask>=quote.bid))
   throw Error('MT5 returned an invalid broker quote.');
  return quote;
 }
 else result=await getExness('/api/quote',{symbol},allowExnessSymbol(symbol));
 return market.normalizeQuote(result,symbol,provider);
});

ipcMain.handle('lumavun:ai:models',async ()=>ai.listOllamaModels());
ipcMain.handle('lumavun:ai:available-models',async (_event,input)=>{
 const provider=String(input?.provider||'').toUpperCase();
 if(provider==='OLLAMA')return ai.listOllamaModels();
 if(provider==='CUSTOM')return modelCatalog.customModels(input?.baseUrl,input?.apiKey||'');
 throw Error('Unknown AI model provider.');
});
ipcMain.handle('lumavun:office:run',async (_event,input)=>{
 const desk=String(input?.desk||'');
 if(!['SCOUT','RISK','RESEARCH','BRIEFING'].includes(desk))
  throw Error('Unknown virtual office desk.');
 if(!aiConfig)throw Error('Connect an AI model first in AI Analyst.');
 const provider=String(input?.provider||''),symbol=market.symbol(input?.symbol,provider);
 const interval=String(input?.interval||'');
 if(!market.INTERVALS.has(interval))throw Error('Unsupported timeframe.');
 const cached=verifiedCandleCache.get(provider+'|'+symbol+'|'+interval);
 if(!cached||Date.now()-cached.loadedAt>120000||cached.data.length<60)
  throw Error('Chart snapshot is missing or stale. Refresh chart before running a desk.');
 if(aiBusy)throw Error('AI provider is already analyzing another request.');
 aiBusy=true;
 try{
  const snap=ai.createSnapshot({provider,symbol,interval,candles:cached.data});
  snap.officeDesk=desk;
  const result=await ai.analyze(aiConfig,snap);
  return {desk,provider,symbol,interval,generatedAt:new Date().toISOString(),
   result,model:aiConfig.model,execution:'ON_DEMAND_READ_ONLY'};
 }finally{aiBusy=false;}
});
ipcMain.handle('lumavun:performance:mt5-history',async ()=>{
 if(mt5McpRemote.status().mode!=='MT5_NATIVE')
  throw Error('Connect the native MT5 MCP server to read closed-position history.');
 const raw=await mt5McpRemote.call('history',{days:90});
 return {source:'MT5_NATIVE_HISTORY',trades:performance.mt5Trades(raw),requestedDays:90};
});
ipcMain.handle('lumavun:ai:configure',async (_event,raw)=>{
 const config=ai.validateConfig(raw);
 if(config.provider==='OLLAMA'){
  const names=await ai.listOllamaModels();
  if(!names.includes(config.model)&&!names.includes(config.model+':latest'))
   throw Error('Model Ollama belum terinstal. Sinkronkan daftar model lokal.');
 }else{
  const names=await modelCatalog.customModels(config.baseUrl,config.apiKey);
  if(!names.includes(config.model))throw Error('Model tidak terdaftar pada /v1/models provider.');
 }
 // Secrets are in main-process RAM only, never returned to renderer or persisted.
 aiConfig=config;
 return {provider:config.provider,model:config.model,configured:true,
  note:config.provider==='OLLAMA'?'Model ditemukan pada Ollama lokal.':'Pengaturan disimpan sementara; koneksi API diuji saat Analyze.'};
});
ipcMain.handle('lumavun:ai:analyze',async (_event,input)=>{
 if(!aiConfig)throw Error('Konfigurasikan model AI terlebih dahulu.');
 const provider=String(input?.provider||'');
 const symbol=market.symbol(input?.symbol,provider);
 const interval=String(input?.interval||'');
 if(!market.INTERVALS.has(interval))throw Error('Timeframe tidak didukung.');
 const key=provider+'|'+symbol+'|'+interval;
 const cache=verifiedCandleCache.get(key);
 if(!cache||Date.now()-cache.loadedAt>120000||cache.data.length<60)
  throw Error('Candle terbaru tidak tersedia. Refresh grafik dan ulangi analisis.');
 if(aiBusy)throw Error('Analisis sebelumnya masih berlangsung.');
 aiBusy=true;
 try{
  const snapshot=ai.createSnapshot({provider,symbol,interval,candles:cache.data});
  const hasImage=Object.prototype.hasOwnProperty.call(input||{},'imageDataUrl');
  if(hasImage&&typeof input.imageDataUrl!=='string')throw Error('Vision harus menerima screenshot PNG chart.');
  const result=await ai.analyze(aiConfig,snapshot,globalThis.fetch,hasImage?input.imageDataUrl:null);
  return {...result,symbol,interval,asOfUtc:snapshot.asOfUtc};
 }finally{aiBusy=false;}
});


ipcMain.handle('lumavun:local-ai:status',()=>localAiManager.status());
ipcMain.handle('lumavun:local-ai:install-runtime',()=>localAiManager.installRuntime());
ipcMain.handle('lumavun:local-ai:start-runtime',()=>localAiManager.startRuntime());
ipcMain.handle('lumavun:local-ai:pull-model',(_event,model)=>localAiManager.pullModel(model));
ipcMain.handle('lumavun:local-ai:cancel-download',()=>localAiManager.cancelDownload());



ipcMain.handle('lumavun:mcp:status',()=>({
 local:mt5McpServer.status(),
 connected:mt5McpRemote.status()
}));
ipcMain.handle('lumavun:mcp:start',async (_event,port)=>{
 // Starts only on explicit user action, only on 127.0.0.1, with a new random key.
 const result=await mt5McpServer.start(port==null?0:port);
 return {running:result.running,url:result.url,readOnly:true,tools:result.tools};
});
ipcMain.handle('lumavun:mcp:reveal-key',()=>mt5McpServer.revealKey());
ipcMain.handle('lumavun:mcp:copy-key',()=>{clipboard.writeText(mt5McpServer.revealKey());return {copied:true};});
ipcMain.handle('lumavun:mcp:stop',async ()=>{
 const old=mt5McpServer.status().url;
 if(old&&mt5McpRemote.status().url===old)mt5McpRemote.disconnect();
 await mt5McpServer.stop();
 return mt5McpServer.status();
});
ipcMain.handle('lumavun:mcp:connect-local',async ()=>{
 const local=mt5McpServer.status();
 if(!local.running)throw Error('Start the local MCP server first.');
 mt5McpRemote.disconnect();
 return mt5McpRemote.connect(local.url,mt5McpServer.revealKey());
});
ipcMain.handle('lumavun:mcp:connect',async (_event,settings)=>{
 // User-supplied URL and key never leave Electron main-process RAM after validation.
 mt5McpRemote.disconnect();
 return mt5McpRemote.connect(settings?.url,settings?.apiKey);
});
ipcMain.handle('lumavun:mcp:disconnect',()=>mt5McpRemote.disconnect());
ipcMain.handle('lumavun:mcp:account',()=>mt5McpRemote.call('account',{}));
ipcMain.handle('lumavun:mcp:positions',()=>mt5McpRemote.call('positions',{}));

ipcMain.handle('lumavun:mt5:account',async ()=>currentMt5Account());
ipcMain.handle('lumavun:mt5:positions',async ()=>mt5.call('positions',{}));
ipcMain.handle('lumavun:mt5:mcp-config',()=>mt5.mcpCommand());
ipcMain.handle('lumavun:mt5:gate-status',()=>mt5Gate.getStatus());
ipcMain.handle('lumavun:mt5:unlock',async (_event,input)=>{
 const account=await currentMt5Account();
 return mt5Gate.unlock(account,input?.phrase,input?.acceptReal===true);
});
ipcMain.handle('lumavun:mt5:lock',()=>{
 mt5Gate.lock();return mt5Gate.getStatus();
});
ipcMain.handle('lumavun:mt5:preview',async (_event,input)=>{
 const account=await currentMt5Account();
 mt5Gate.assertUnlocked(account);
 if(mt5Executing)throw Error('Previous MT5 order execution still in progress.');
 const args={
  symbol:market.symbol(input?.symbol,'MT5'),
  side:input?.side,volume:input?.volume,sl:input?.sl,tp:input?.tp
 };
 const preview=await mt5.call('preview_order',args,20000);
 return mt5Gate.createPreview(account,preview);
});
ipcMain.handle('lumavun:mt5:execute',async (_event,input)=>{
 if(mt5Executing)throw Error('Previous MT5 order execution still in progress.');
 mt5Executing=true;
 try{
  const account=await currentMt5Account();
  const args=mt5Gate.consume(account,input?.token,input?.phrase);
  // At most one request. If the result is unknown, never retry automatically.
  return await mt5.call('execute_order',args,90000);
 }finally{mt5Executing=false;}
});

ipcMain.handle('lumavun:chart:save-png',async (_event,dataUrl)=>{
 const image=vision.validateChartPng(dataUrl);
 const result=await dialog.showSaveDialog({
  title:'Save LUMAVUN chart image',
  defaultPath:'LUMAVUN-Chart-'+new Date().toISOString().slice(0,10)+'.png',
  filters:[{name:'PNG Image',extensions:['png']}],
  properties:['showOverwriteConfirmation']
 });
 if(result.canceled||!result.filePath)return {saved:false};
 await fs.writeFile(result.filePath,Buffer.from(image.base64,'base64'));
 return {saved:true,fileName:path.basename(result.filePath)};
});

app.whenReady().then(()=>{
 app.setAppUserModelId('com.lumavun.chart.desktop');
 const window=new BrowserWindow({
  width:1510,height:980,minWidth:970,minHeight:700,title:'LUMAVUN Chart',
  backgroundColor:'#080e1b',autoHideMenuBar:true,
  icon:path.join(__dirname,'assets','app-icon.png'),
  webPreferences:{
   preload:path.join(__dirname,'preload.cjs'),nodeIntegration:false,
   contextIsolation:true,sandbox:true,webSecurity:true
  }
 });
 const openAttribution=url=>{
  if(url==='https://www.tradingview.com/'||url.startsWith('https://www.tradingview.com/?'))
   shell.openExternal('https://www.tradingview.com/').catch(()=>{});
 };
 window.webContents.setWindowOpenHandler(({url})=>{openAttribution(url);return {action:'deny'};});
 window.webContents.on('will-navigate',(event,url)=>{event.preventDefault();openAttribution(url);});
 window.loadFile(path.join(__dirname,'renderer','index.html'));
});
app.on('before-quit',()=>{
 mt5Gate.lock();
 mt5McpRemote.disconnect();
 mt5McpServer.stop().catch(()=>{});
 mt5.shutdown();
});
app.on('window-all-closed',()=>{if(process.platform!=='darwin')app.quit();});
