'use strict';
const {app, BrowserWindow, ipcMain} = require('electron');
const path = require('node:path');

const hosts = ['https://api.binance.com', 'https://api1.binance.com', 'https://data-api.binance.vision'];
const intervals = new Set(['1m', '5m', '15m', '30m', '1h', '4h', '1d']);
const symbolPattern = /^[A-Z0-9]{5,20}$/;

function ensureSymbol(value) {
  const symbol = String(value || '').trim().toUpperCase();
  if (!symbolPattern.test(symbol) || !symbol.endsWith('USDT')) {
    throw new Error('Only validated Binance spot USDT pairs are supported in this Windows preview.');
  }
  return symbol;
}

async function getBinance(resource, query) {
  const params = new URLSearchParams(query);
  let lastError = 'Market data unavailable';
  for (const host of hosts) {
    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 9500);
      let response;
      try {
        response = await fetch(host + resource + '?' + params.toString(), {
          headers: {'Accept': 'application/json'},
          signal: controller.signal
        });
      } finally {
        clearTimeout(timeout);
      }
      if (!response.ok) {
        const err = await response.text();
        if (response.status === 400 || response.status === 404) {
          throw new Error('Symbol or timeframe rejected by Binance (' + response.status + ').');
        }
        lastError = 'Binance service returned HTTP ' + response.status + ' ' + err.slice(0, 100);
        continue;
      }
      return await response.json();
    } catch (error) {
      if (error.message.startsWith('Symbol or timeframe rejected')) throw error;
      lastError = error.name === 'AbortError' ? 'Market data request timed out.' : String(error.message).slice(0, 180);
    }
  }
  throw new Error('Cannot retrieve verified Binance market data. ' + lastError);
}

ipcMain.handle('lumavun:candles', async (_event, input) => {
  const symbol = ensureSymbol(input && input.symbol);
  const interval = String(input && input.interval || '');
  if (!intervals.has(interval)) throw new Error('Unsupported timeframe.');
  const limit = Math.max(30, Math.min(500, Math.floor(Number(input && input.limit) || 300)));
  const data = await getBinance('/api/v3/klines', {symbol, interval, limit});
  if (!Array.isArray(data) || !data.length) throw new Error('No verified candles returned.');
  return data;
});

ipcMain.handle('lumavun:ticker', async (_event, input) => {
  const symbol = ensureSymbol(input && input.symbol);
  const data = await getBinance('/api/v3/ticker/24hr', {symbol});
  if (!data || data.symbol !== symbol || !(Number(data.lastPrice) > 0)) {
    throw new Error('Ticker unavailable for the selected market.');
  }
  return {symbol: data.symbol, lastPrice: data.lastPrice, priceChangePercent: data.priceChangePercent, highPrice: data.highPrice, lowPrice: data.lowPrice};
});

app.whenReady().then(() => {
  app.setAppUserModelId('com.lumavun.chart.desktop');
  const window = new BrowserWindow({
    width: 1440, height: 920, minWidth: 880, minHeight: 620,
    title: 'LUMAVUN Chart', backgroundColor: '#080e1b',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true
    }
  });
  window.webContents.setWindowOpenHandler(() => ({action: 'deny'}));
  window.webContents.on('will-navigate', (event) => event.preventDefault());
  window.loadFile(path.join(__dirname, 'renderer', 'index.html'));
});
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit(); });
