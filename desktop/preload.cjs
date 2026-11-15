'use strict';
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('lumavun',Object.freeze({
 candles:(provider,symbol,interval,limit=300)=>ipcRenderer.invoke('lumavun:candles',{provider,symbol,interval,limit}),
 ticker:(provider,symbol)=>ipcRenderer.invoke('lumavun:ticker',{provider,symbol}),
 configureGateway:(url,token)=>ipcRenderer.invoke('lumavun:gateway',{url,token}),
 symbols:(provider,query='')=>ipcRenderer.invoke('lumavun:symbols',provider,query),
 mt5Account:()=>ipcRenderer.invoke('lumavun:mt5:account'),
 mt5Positions:()=>ipcRenderer.invoke('lumavun:mt5:positions'),
 mt5McpConfig:()=>ipcRenderer.invoke('lumavun:mt5:mcp-config'),
 mcpStatus:()=>ipcRenderer.invoke('lumavun:mcp:status'),
 mcpStart:(port=0)=>ipcRenderer.invoke('lumavun:mcp:start',port),
 mcpRevealKey:()=>ipcRenderer.invoke('lumavun:mcp:reveal-key'),
 mcpStop:()=>ipcRenderer.invoke('lumavun:mcp:stop'),
 mcpConnectLocal:()=>ipcRenderer.invoke('lumavun:mcp:connect-local'),
 mcpConnect:(url,apiKey)=>ipcRenderer.invoke('lumavun:mcp:connect',{url,apiKey}),
 mcpDisconnect:()=>ipcRenderer.invoke('lumavun:mcp:disconnect'),
 mcpAccount:()=>ipcRenderer.invoke('lumavun:mcp:account'),
 mcpPositions:()=>ipcRenderer.invoke('lumavun:mcp:positions'),
 mt5GateStatus:()=>ipcRenderer.invoke('lumavun:mt5:gate-status'),
 mt5Unlock:(phrase,acceptReal)=>ipcRenderer.invoke('lumavun:mt5:unlock',{phrase,acceptReal}),
 mt5Lock:()=>ipcRenderer.invoke('lumavun:mt5:lock'),
 mt5Preview:(args)=>ipcRenderer.invoke('lumavun:mt5:preview',args),
 mt5Execute:(token,phrase)=>ipcRenderer.invoke('lumavun:mt5:execute',{token,phrase}),
 listAiModels:()=>ipcRenderer.invoke('lumavun:ai:models'),
 configureAi:(config)=>ipcRenderer.invoke('lumavun:ai:configure',config),
 analyzeAi:(provider,symbol,interval)=>ipcRenderer.invoke('lumavun:ai:analyze',{provider,symbol,interval}),
 analyzeVision:(provider,symbol,interval,imageDataUrl)=>ipcRenderer.invoke('lumavun:ai:analyze',{provider,symbol,interval,imageDataUrl}),
 saveChartPng:(dataUrl)=>ipcRenderer.invoke('lumavun:chart:save-png',dataUrl),
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
