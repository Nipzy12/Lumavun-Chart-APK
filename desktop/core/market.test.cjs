const {test}=require('node:test');
const assert=require('node:assert/strict');
const m=require('./market.cjs');
test('does not permit invalid symbols, providers, or local/insecure gateway URLs',()=>{
  assert.equal(m.symbol(' xau/usd ','BIQUOTE'),'XAUUSD');
  assert.equal(m.symbol('btcusdt','BINANCE'),'BTCUSDT');
  assert.throws(()=>m.symbol('EURUSD','BINANCE'));
  assert.throws(()=>m.symbol('USD; DROP','BIQUOTE'));
  for (const u of ['http://quote.example.com','https://localhost','https://127.0.0.1','https://node.local','https://example.com/admin','https://example.com:3000','https://user:pass@example.com'])
    assert.throws(()=>m.validateGateway(u),u);
  assert.equal(m.validateGateway('https://gateway.example.org'),'https://gateway.example.org');
});
test('normalizes object-based OHLC and epoch/ISO timestamps without fabricating price',()=>{
 const d=m.normalizeCandles({candles:[
  {openTime:1730000000000,open:100,high:104,low:99,close:103,volume:8},
  {time:'2024-10-26T00:00:00Z',open:'100',high:'110',low:'90',close:'99',volume:0},
  {time:1730000500000,open:100,high:1,low:99,close:103},
  {time:1730000000000,open:100,high:104,low:99,close:103,volume:8}
 ]});
 assert.equal(d.length,2);assert.ok(d[0].time<d[1].time);
});
test('normalizes gateway and BiQuote quotes; rejects unavailable price',()=>{
 const quote=m.normalizeQuote({symbol:'XAUUSD',bid:3020,ask:3020.3},'XAUUSD','BIQUOTE');
 assert.equal(quote.lastPrice,3020.15);assert.equal(quote.verifiedBidAsk,true);
 assert.throws(()=>m.normalizeQuote({symbol:'XAUUSD'},'XAUUSD','EXNESS'));
 assert.equal(m.normalizeSymbols({symbols:[{symbol:'XAUUSD',status:'TRADING'},{foo:'bar'}]}).length,1);
});
