const {test}=require('node:test');
const assert=require('node:assert/strict');
const {validateChartPng,buildVisionMessages}=require('./vision.cjs');
const ai=require('./ai-analyst.cjs');
const candles=Array.from({length:80},(_,i)=>({time:1700000000+i*900,open:99+i,high:101+i,low:98+i,close:100+i,volume:10+i}));
function fakeChartPng(w=1200,h=600){
 const buffer=Buffer.alloc(50,0);
 Buffer.from('89504e470d0a1a0a','hex').copy(buffer,0);
 buffer.writeUInt32BE(13,8);buffer.write('IHDR',12,'ascii');
 buffer.writeUInt32BE(w,16);buffer.writeUInt32BE(h,20);buffer[24]=8;buffer[25]=6;
 return 'data:image/png;base64,'+buffer.toString('base64');
}
test('accepts bounded chart PNG but rejects oversized or unexpected formats',()=>{
 const image=validateChartPng(fakeChartPng());
 assert.equal(image.width,1200);assert.equal(image.height,600);assert.ok(image.base64.length>20);
 for(const bad of ['data:image/jpeg;base64,AA==','file:///C:/Users/Desktop/private.png','data:image/png;base64,AAAA',fakeChartPng(50,50),fakeChartPng(50000,50000),'data:image/png;base64,'+'A'.repeat(6*1024*1024)])
  assert.throws(()=>validateChartPng(bad));
});
test('Ollama vision sends base64 in user images only and preserves typed snapshot',async()=>{
 const snapshot=ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles});
 const messages=buildVisionMessages(ai.buildMessages(snapshot),'OLLAMA',fakeChartPng());
 assert.equal(messages[1].role,'user');assert.ok(messages[1].images[0].length>0);
 assert.ok(!messages[0].images);assert.equal(typeof messages[1].content,'string');
});
test('OpenAI-compatible vision sends typed image_url array, not image in system prompt',()=>{
 const snapshot=ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles});
 const messages=buildVisionMessages(ai.buildMessages(snapshot),'CUSTOM',fakeChartPng());
 assert.equal(messages[1].content[1].type,'image_url');
 assert.ok(messages[1].content[1].image_url.url.startsWith('data:image/png;base64,'));
 assert.equal(typeof messages[0].content,'string');
});
test('AI inference vision sends PNG only when explicitly provided',async()=>{
 const answer=JSON.stringify({decision:'WAIT',bias:'NEUTRAL',confidence:0,summary:'wait',reasoning:[],entry:null,stopLoss:null,takeProfit:[],invalidation:'No setup'});
 const snap=ai.createSnapshot({provider:'BINANCE',symbol:'BTCUSDT',interval:'15m',candles});
 let request;
 const result=await ai.analyze({provider:'OLLAMA',model:'qwen2.5vl:3b'},snap,async(url,opts)=>{
  request=JSON.parse(opts.body);
  return {ok:true,json:async()=>({message:{content:answer}})};
 },fakeChartPng());
 assert.equal(result.decision,'WAIT');
 assert.equal(result.visionAssisted,true);
 assert.equal(typeof request.messages[1].images[0],'string');
 const plain=await ai.analyze({provider:'OLLAMA',model:'qwen2.5vl:3b'},snap,async(url,opts)=>{
  request=JSON.parse(opts.body);return {ok:true,json:async()=>({message:{content:answer}})};
 });
 assert.equal(plain.visionAssisted,false);assert.equal(request.messages[1].images,undefined);
});
