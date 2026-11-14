'use strict';
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('lumavun', Object.freeze({
  candles: (symbol, interval, limit) => ipcRenderer.invoke('lumavun:candles', {symbol, interval, limit}),
  ticker: (symbol) => ipcRenderer.invoke('lumavun:ticker', {symbol})
}));
