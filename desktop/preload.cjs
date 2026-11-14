'use strict';
const {contextBridge,ipcRenderer}=require('electron');
contextBridge.exposeInMainWorld('lumavun',Object.freeze({
 candles:(provider,symbol,interval,limit=300)=>ipcRenderer.invoke('lumavun:candles',{provider,symbol,interval,limit}),
 ticker:(provider,symbol)=>ipcRenderer.invoke('lumavun:ticker',{provider,symbol}),
 configureGateway:(url,token)=>ipcRenderer.invoke('lumavun:gateway',{url,token}),
 symbols:(provider,query='')=>ipcRenderer.invoke('lumavun:symbols',provider,query)
}));
