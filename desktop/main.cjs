'use strict';
const {app,BrowserWindow,ipcMain} = require('electron');
const path=require('node:path');
const market=require('./core/market.cjs');
const ai=require('./core/ai-analyst.cjs');
const {LocalAiManager}=require('./core/local-ai-manager.cjs');
const hosts=['https://api.binance.com','https://api1.binance.com','https://data-api.binance.vision'];
let gateway={url:'',token:'',symbols:[]};
let aiConfig=null,aiBusy=false;
const verifiedCandleCache=new Map();
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
 else result=await getExness('/api/quote',{symbol},allowExnessSymbol(symbol));
 return market.normalizeQuote(result,symbol,provider);
});

ipcMain.handle('lumavun:ai:models',async ()=>ai.listOllamaModels());
ipcMain.handle('lumavun:ai:configure',async (_event,raw)=>{
 const config=ai.validateConfig(raw);
 if(config.provider==='OLLAMA'){
  const names=await ai.listOllamaModels();
  if(!names.includes(config.model) && !names.includes(config.model+':latest')){
   throw new Error('Model Ollama belum terinstal. Cek ollama list atau jalankan ollama pull '+config.model);
  }
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

app.whenReady().then(()=>{
 app.setAppUserModelId('com.lumavun.chart.desktop');
 const window=new BrowserWindow({
  width:1510,height:980,minWidth:970,minHeight:700,title:'LUMAVUN Chart',
  backgroundColor:'#080e1b',autoHideMenuBar:true,
  webPreferences:{
   preload:path.join(__dirname,'preload.cjs'),nodeIntegration:false,
   contextIsolation:true,sandbox:true,webSecurity:true
  }
 });
 window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
 window.webContents.on('will-navigate',event=>event.preventDefault());
 window.loadFile(path.join(__dirname,'renderer','index.html'));
});
app.on('window-all-closed',()=>{if(process.platform!=='darwin')app.quit();});
