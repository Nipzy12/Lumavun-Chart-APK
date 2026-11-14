'use strict';
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('lumavun',Object.freeze({
 candles:(provider,symbol,interval,limit=300)=>ipcRenderer.invoke('lumavun:candles',{provider,symbol,interval,limit}),
 ticker:(provider,symbol)=>ipcRenderer.invoke('lumavun:ticker',{provider,symbol}),
 configureGateway:(url,token)=>ipcRenderer.invoke('lumavun:gateway',{url,token}),
 symbols:(provider,query='')=>ipcRenderer.invoke('lumavun:symbols',provider,query),
 listAiModels:()=>ipcRenderer.invoke('lumavun:ai:models'),
 configureAi:(config)=>ipcRenderer.invoke('lumavun:ai:configure',config),
 analyzeAi:(provider,symbol,interval)=>ipcRenderer.invoke('lumavun:ai:analyze',{provider,symbol,interval}),
 analyzeVision:(provider,symbol,interval,imageDataUrl)=>ipcRenderer.invoke('lumavun:ai:analyze',{provider,symbol,interval,imageDataUrl}),
 localAiStatus:()=>ipcRenderer.invoke('lumavun:local-ai:status'),
 installLocalRuntime:()=>ipcRenderer.invoke('lumavun:local-ai:install-runtime'),
 startLocalRuntime:()=>ipcRenderer.invoke('lumavun:local-ai:start-runtime'),
 pullLocalModel:(model)=>ipcRenderer.invoke('lumavun:local-ai:pull-model',model),
 cancelLocalDownload:()=>ipcRenderer.invoke('lumavun:local-ai:cancel-download'),
 onLocalAiEvent:(listener)=>{
  if(typeof listener!=='function')throw new TypeError('Listener must be a function.');
  const handler=(_event,data)=>listener(data);
  ipcRenderer.on('lumavun:local-ai:event',handler);
  return ()=>ipcRenderer.removeListener('lumavun:local-ai:event',handler);
 }
}));
