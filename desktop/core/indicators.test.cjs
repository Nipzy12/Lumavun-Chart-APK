const {test} = require('node:test');
const assert = require('node:assert/strict');
const {normalizeSymbol,normalizeKlines,sma,ema,rsi,levels} = require('./indicators.js');
test('only permits known-safe symbols in the market gateway', () => {
  assert.equal(normalizeSymbol(' btcusdt '), 'BTCUSDT');
  assert.equal(normalizeSymbol('EURUSD'), null);
  assert.equal(normalizeSymbol('../../BTCUSDT'), null);
});
test('filters invalid candles, deduplicates and sorts Binance OHLC', () => {
  const row = (t, price) => [t, '' + price, '' + (price+2), '' + (price-2), '' + (price+1), '23', t+59999];
  const candles = normalizeKlines([row(120000, 103), row(60000, 101), row(120000, 103), row(240000, -1)]);
  assert.equal(candles.length, 2);
  assert.deepEqual(candles.map(c => c.time), [60,120]);
});
test('EMA seeds from initial SMA exactly as Android implementation', () => {
  const data = [1,2,3,4,5,6];
  assert.deepEqual(ema(data,3),[null,null,2,3,4,5]);
  assert.deepEqual(sma(data,3),[null,null,2,3,4,5]);
});
test('RSI is neutral on a flat series', () => {
  const result = rsi(Array(20).fill(100),14);
  assert.equal(result[14], 50);
  assert.equal(result[19], 50);
});
test('support resistance is unavailable on insufficient data', () => {
  assert.deepEqual(levels([],100),{support:[],resistance:[]});
});
