(function (root) {
  'use strict';
  const pattern = /^[A-Z0-9]{5,20}$/;
  function normalizeSymbol(input) {
    const symbol = String(input || '').trim().toUpperCase();
    return pattern.test(symbol) && symbol.endsWith('USDT') ? symbol : null;
  }
  function normalizeKlines(rows) {
    if (!Array.isArray(rows)) return [];
    const unique = new Map();
    for (const row of rows) {
      if (!Array.isArray(row) || row.length < 7) continue;
      const time = Math.floor(Number(row[0]) / 1000);
      const open = Number(row[1]), high = Number(row[2]), low = Number(row[3]), close = Number(row[4]), volume = Number(row[5]);
      if (![time, open, high, low, close, volume].every(Number.isFinite)) continue;
      if (!(time > 0 && low > 0 && high >= low && open > 0 && close > 0 && high >= Math.max(open, close) && low <= Math.min(open, close) && volume >= 0)) continue;
      unique.set(time, {time, open, high, low, close, volume});
    }
    return [...unique.values()].sort((a, b) => a.time - b.time);
  }
  function sma(values, period) {
    const out = Array(values.length).fill(null);
    if (!(period > 0)) return out;
    let sum = 0;
    for (let i = 0; i < values.length; i++) {
      sum += values[i];
      if (i >= period) sum -= values[i - period];
      if (i >= period - 1) out[i] = sum / period;
    }
    return out;
  }
  // Matches the SMA-seeded EMA algorithm used by the Android app.
  function ema(values, period) {
    const out = Array(values.length).fill(null);
    if (!(period > 0) || values.length < period) return out;
    let current = values.slice(0, period).reduce((a, b) => a + b, 0) / period;
    out[period - 1] = current;
    const alpha = 2 / (period + 1);
    for (let i = period; i < values.length; i++) {
      current = (values[i] - current) * alpha + current;
      out[i] = current;
    }
    return out;
  }
  function rsi(values, period = 14) {
    const out = Array(values.length).fill(null);
    if (values.length <= period) return out;
    let gains = 0, losses = 0;
    for (let i = 1; i <= period; i++) {
      const change = values[i] - values[i - 1];
      gains += Math.max(0, change);
      losses += Math.max(0, -change);
    }
    let averageGain = gains / period, averageLoss = losses / period;
    const toRsi = () => averageLoss === 0 ? (averageGain === 0 ? 50 : 100) : 100 - 100 / (1 + averageGain / averageLoss);
    out[period] = toRsi();
    for (let i = period + 1; i < values.length; i++) {
      const change = values[i] - values[i - 1];
      averageGain = (averageGain * (period - 1) + Math.max(0, change)) / period;
      averageLoss = (averageLoss * (period - 1) + Math.max(0, -change)) / period;
      out[i] = toRsi();
    }
    return out;
  }
  function levels(candles, price, atr) {
    if (candles.length < 10 || price <= 0) return {support: [], resistance: []};
    const window = candles.slice(-200);
    const tolerance = atr > 0 ? Math.max(atr * .45, price * .0025) : price * .004;
    const candidates = [];
    for (let i = 3; i < window.length - 3; i++) {
      let upper = true, lower = true;
      for (let k = 1; k <= 3; k++) {
        if (window[i-k].high > window[i].high || window[i+k].high > window[i].high) upper = false;
        if (window[i-k].low < window[i].low || window[i+k].low < window[i].low) lower = false;
      }
      if (upper) candidates.push(window[i].high);
      if (lower) candidates.push(window[i].low);
    }
    candidates.push(Math.max(...window.map(c => c.high)), Math.min(...window.map(c => c.low)));
    const clusters = [];
    for (const value of candidates) {
      const group = clusters.find(c => Math.abs(c.price - value) <= tolerance);
      if (group) {
        group.price = (group.price * group.count + value) / (group.count + 1);
        group.count += 1;
      } else clusters.push({price: value, count: 1});
    }
    return {
      support: clusters.filter(c => c.price < price - tolerance * .15).sort((a,b) => b.price - a.price).slice(0,3).map(c => c.price),
      resistance: clusters.filter(c => c.price > price + tolerance * .15).sort((a,b) => a.price - b.price).slice(0,3).map(c => c.price)
    };
  }
  const api = Object.freeze({normalizeSymbol, normalizeKlines, sma, ema, rsi, levels});
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.LumavunIndicators = api;
})(typeof window === 'undefined' ? null : window);
