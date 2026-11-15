'use strict';
// Dedicated compatibility layer for MetaQuotes' native terminal MCP tool names.
// Only queries a strictly enumerated READ-ONLY allowlist; never trade_send_*,
// chart mutation, shell, code editing, or arbitrary MCP tool names.
const NATIVE_READ=Object.freeze({
 account:'get_trading_account_info',
 positions:'get_trading_open_positions',
 history:'get_trading_history_positions',
 symbols:'get_marketwatch_symbols',
 candles:'get_chart_history',
 ticks:'get_chart_ticks_history'
});
const OPTIONAL_QUOTE=['marketdata_symbol_get'];
const INTERVAL=Object.freeze({'1m':'M1','3m':'M3','5m':'M5','15m':'M15','30m':'M30',
 '1h':'H1','4h':'H4','1d':'D1'});
const VALID_SYMBOL=/^[A-Za-z0-9._-]{1,32}$/;
const has=(obj,key)=>Object.prototype.hasOwnProperty.call(obj||{},key);
const obj=x=>!!x&&typeof x==='object'&&!Array.isArray(x);
function safeSymbol(s){if(typeof s!=='string'||!VALID_SYMBOL.test(s))throw Error('Invalid MT5 broker symbol.');return s;}
function unwrap(payload,depth=0){
 if(depth>5)return payload;
 if(Array.isArray(payload))return payload;
 if(typeof payload==='string'){
  try{return unwrap(JSON.parse(payload),depth+1);}catch{return payload;}
 }
 if(!obj(payload))return payload;
 for(const key of ['result','data','bars','candles','rates','history','symbols','positions','items','values','quotes']){
  if(has(payload,key)&&(Array.isArray(payload[key])||obj(payload[key])))
   return unwrap(payload[key],depth+1);
 }
 return payload;
}
function readNumber(input){if(input==null||input==='')return null;const n=Number(input);return Number.isFinite(n)?n:null;}
function readTime(t){
 if(typeof t==='number'&&Number.isFinite(t))return Math.floor(t>1e12?t/1000:t);
 if(typeof t==='string'){
  if(/^\d{10,13}$/.test(t))return readTime(Number(t));
  const ms=Date.parse(t.replace(/^(\d{4})\.(\d{2})\.(\d{2}) /,'$1-$2-$3T')+(t.includes('T')||t.endsWith('Z')?'':'Z'));
  if(Number.isFinite(ms))return Math.floor(ms/1000);
 }
 return null;
}
function extractList(raw,keys=[]){
 const value=unwrap(raw);
 if(Array.isArray(value))return value;
 if(obj(value)){
  for(const k of keys){if(Array.isArray(value[k]))return value[k];}
  if(obj(value.data))return extractList(value.data,keys);
 }
 return [];
}
function normalizeSymbols(raw,query=''){
 let list=extractList(raw,['marketwatch','symbols','instruments']);
 const data=unwrap(raw);
 if(!list.length&&obj(data)&&Object.values(data).every(v=>obj(v)))list=Object.entries(data).map(([symbol,value])=>({symbol,...value}));
 const result=[];
 for(const item of list){
  const x=typeof item==='string'?{symbol:item}:item;
  if(!obj(x))continue;
  const symbol=String(x.symbol??x.name??x.ticker??'');
  if(!VALID_SYMBOL.test(symbol)||query&&!symbol.toLowerCase().includes(query.toLowerCase()))continue;
  result.push({symbol,name:String(x.description??x.display_name??symbol).slice(0,100),
   status:x.visible===false?'AVAILABLE':'ACTIVE',category:'MT5'});
 }
 return result.slice(0,150);
}
function normalizeQuote(raw,symbol){
 const root=unwrap(raw);
 let value=root;
 if(Array.isArray(root))value=root.find(r=>obj(r)&&String(r.symbol??r.name??r.ticker)===symbol);
 if(obj(root)&&!has(root,'bid')&&obj(root[symbol]))value=root[symbol];
 if(!obj(value))throw Error('MT5 native MCP returned no quote for '+symbol);
 if(value.symbol&&String(value.symbol)!==symbol)throw Error('MT5 quote symbol mismatch.');
 const bid=readNumber(value.bid??value.bid_price??value.bidPrice??value.Bid??value.tick?.bid);
 const ask=readNumber(value.ask??value.ask_price??value.askPrice??value.Ask??value.tick?.ask);
 if(!(bid>0&&ask>=bid))throw Error('MT5 native MCP did not supply valid bid/ask. Check Market Watch.');
 const stamp=readTime(value.time??value.tick_time??value.tickTime??value.timestamp) || null;
 return {symbol,provider:'MT5',bid,ask,lastPrice:(bid+ask)/2,
  verifiedBidAsk:true,spreadPoints:readNumber(value.spread??value.spread_points),tickTime:stamp,
  ageSeconds:stamp?Math.max(0,Math.round(Date.now()/1000-stamp)):null,
  priceChangePercent:null,highPrice:null,lowPrice:null,volume:null};
}
function normalizeBars(raw,requestedLimit=300){
 const records=extractList(raw,['rates','ohlcv','bars','candles','history']);
 const out=[];
 for(const x of records){
  if(!obj(x))continue;
  const t=readTime(x.time??x.timestamp??x.datetime??x.date??x.Time);
  const open=readNumber(x.open??x.o??x.Open),high=readNumber(x.high??x.h??x.High);
  const low=readNumber(x.low??x.l??x.Low),close=readNumber(x.close??x.c??x.Close);
  const volume=readNumber(x.tick_volume??x.tickVolume??x.volume??x.v??x.real_volume??0);
  if(t>0&&open>0&&high>0&&low>0&&close>0&&high>=Math.max(open,close,low)&&low<=Math.min(open,close,high)&&volume>=0)
   out.push({time:t,open,high,low,close,volume});
 }
 out.sort((a,b)=>a.time-b.time);
 const distinct=out.filter((bar,i)=>i===0||bar.time!==out[i-1].time);
 if(!distinct.length)throw Error('MT5 native MCP returned no usable OHLC candles for this timeframe.');
 return distinct.slice(-Math.max(30,Math.min(500,requestedLimit)));
}
function normalizeAccount(raw){
 const x=unwrap(raw);
 if(!obj(x))throw Error('MT5 native MCP account data is not an object.');
 const account=String(x.login??x.account??x.account_number??x.accountNumber??x.number??'');
 if(!account)throw Error('MT5 native MCP did not provide an account login.');
 const modeText=String(x.trade_mode??x.account_type??x.mode??x.type??'').toUpperCase();
 const mode=/REAL|LIVE/.test(modeText)?'REAL':/DEMO/.test(modeText)?'DEMO':'OTHER';
 return {account,mode,server:String(x.server??x.broker_server??x.broker??''),
   currency:String(x.currency??'USD'),balance:readNumber(x.balance),equity:readNumber(x.equity),
   freeMargin:readNumber(x.margin_free??x.free_margin??x.freeMargin),
   connected:true};
}
function normalizePositions(raw){
 const records=extractList(raw,['positions','open_positions','trades']);
 return records.slice(0,100).filter(obj).map(p=>({
  ticket:String(p.ticket??p.position??p.id??''),
  symbol:String(p.symbol??''),
  side:String(p.side??p.type??'').toUpperCase().includes('BUY')?'BUY':
   String(p.side??p.type??'').toUpperCase().includes('SELL')?'SELL':'UNKNOWN',
  volume:readNumber(p.volume??p.lots),entry:readNumber(p.price_open??p.entry??p.open_price),
  current:readNumber(p.price_current??p.current),
  sl:readNumber(p.sl??p.stop_loss),tp:readNumber(p.tp??p.take_profit),
  profit:readNumber(p.profit??p.pnl)
 }));
}
function buildArgs(tool,provided={},now=()=>Date.now()){
 const schema=obj(tool?.inputSchema)?tool.inputSchema:{};
 const props=obj(schema.properties)?schema.properties:{};
 const required=Array.isArray(schema.required)?schema.required:[];
 const output={};
 const symbol=provided.symbol;
 const interval=provided.interval||'15m';
 if(symbol!==undefined)safeSymbol(symbol);
 if(provided.query!==undefined&&typeof provided.query!=='string')throw Error('Invalid symbol search query.');
 if(!INTERVAL[interval])throw Error('Unsupported MT5 timeframe '+interval);
 const limit=Math.max(30,Math.min(500,Number(provided.limit)||300));
 const millisPerBar={'1m':60000,'3m':180000,'5m':300000,'15m':900000,'30m':1800000,'1h':3600000,'4h':14400000,'1d':86400000}[interval];
 const to=new Date(now()),from=new Date(to.getTime()-(provided.days?Math.min(365,Math.max(1,Math.floor(Number(provided.days)||90)))*86400000:limit*millisPerBar*1.5));
 for(const [key,property] of Object.entries(props)){
  const k=key.toLowerCase(),type=property?.type,enumValues=property?.enum;
  if(['symbol','symbol_name','ticker','instrument'].includes(k)&&symbol!==undefined){output[key]=symbol;continue;}
  if(['query','search','filter'].includes(k)&&provided.query!==undefined){output[key]=provided.query;continue;}
  if(['timeframe','period','interval','tf'].includes(k)){
   const wanted=INTERVAL[interval];
   const candidate=(Array.isArray(enumValues)&&enumValues.length)?
    [wanted,wanted.toLowerCase(),interval,String(millisPerBar/60000)].find(x=>enumValues.includes(x)):
    type==='integer'||type==='number'?Math.round(millisPerBar/60000):wanted;
   if(candidate===undefined)throw Error('MT5 MCP timeframe '+wanted+' is not supported by server schema.');
   output[key]=candidate;continue;
  }
  if(['count','limit','bars','num_bars','number_of_bars','max_bars'].includes(k)){
   output[key]=limit;continue;
  }
  if(['start_time','start_date','date_from','from_date','from','start','from_time'].includes(k)){
   output[key]=type==='integer'||type==='number'?Math.floor(from.getTime()/1000):from.toISOString();continue;
  }
  if(['end_time','end_date','date_to','to_date','to','end','to_time'].includes(k)){
   output[key]=type==='integer'||type==='number'?Math.floor(to.getTime()/1000):to.toISOString();continue;
  }
 }
 for(const key of required){
  if(!has(output,key)){
   // Fail closed. Never guess nonstandard required tool arguments.
   throw Error('Native MT5 MCP requires unsupported parameter "'+String(key).slice(0,70)+'" for '+tool.name+'.');
  }
 }
 return output;
}
function selectNativeTools(list){
 const map=new Map((Array.isArray(list)?list:[]).filter(obj).map(t=>[t.name,t]));
 const result={};
 for(const [logical,native] of Object.entries(NATIVE_READ)){
  if(map.has(native))result[logical]=map.get(native);
 }
 if(map.has('marketdata_symbol_get'))result.quote=map.get('marketdata_symbol_get');
 return result;
}
module.exports={NATIVE_READ,OPTIONAL_QUOTE,selectNativeTools,buildArgs,
 normalizeSymbols,normalizeQuote,normalizeBars,normalizeAccount,normalizePositions,unwrap};
