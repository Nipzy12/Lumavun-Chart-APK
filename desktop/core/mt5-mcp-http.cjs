'use strict';
// Local authenticated MCP Streamable HTTP JSON-RPC endpoint.
// Deliberately exposes only MT5 read operations and non-executing broker checks.
const http=require('node:http');
const {randomBytes,timingSafeEqual}=require('node:crypto');

const PROTOCOL='2025-06-18';
const MAX_BYTES=32768;
const DESCRIPTION=Object.freeze([
 {name:'account',description:'Read the logged-in MetaTrader 5 account status, balance, equity, and broker.',inputSchema:{type:'object',properties:{},additionalProperties:false}},
 {name:'symbols',description:'Search symbols offered by the currently connected MT5 broker.',inputSchema:{type:'object',properties:{query:{type:'string',maxLength:32}},additionalProperties:false}},
 {name:'ticker',description:'Read actual MT5 bid, ask, spread, and tick freshness for an exact broker symbol.',inputSchema:{type:'object',properties:{symbol:{type:'string'}},required:['symbol'],additionalProperties:false}},
 {name:'candles',description:'Read broker OHLC candles in 1m/5m/15m/30m/1h/4h/1d intervals.',inputSchema:{type:'object',properties:{symbol:{type:'string'},interval:{type:'string'},limit:{type:'integer',minimum:30,maximum:500}},required:['symbol'],additionalProperties:false}},
 {name:'positions',description:'Read current MT5 positions and floating P/L; never modify positions.',inputSchema:{type:'object',properties:{},additionalProperties:false}},
 {name:'preview_order',description:'Broker order_check and estimated margin only; NEVER sends an order.',inputSchema:{type:'object',properties:{symbol:{type:'string'},side:{type:'string',enum:['BUY','SELL']},volume:{type:'number'},sl:{type:'number'},tp:{type:'number'}},required:['symbol','side','volume','sl','tp'],additionalProperties:false}}
]);
const TOOL_NAMES=new Set(DESCRIPTION.map(item=>item.name));
function correctSecret(actual,provided){
 if(typeof provided!=='string'||!provided.startsWith('Bearer '))return false;
 const a=Buffer.from(actual),b=Buffer.from(provided.slice(7));
 return a.length===b.length&&timingSafeEqual(a,b);
}
function protocolError(code,message,id=null){return {jsonrpc:'2.0',id,error:{code,message}};}
function response(result,id){return {jsonrpc:'2.0',id,result};}
function validatePort(value){
 const n=Number(value);
 if(!Number.isInteger(n)||n<0||n>65535||(n!==0&&n<1024))
  throw Error('MCP port must be 0 (automatic) or 1024..65535.');
 return n;
}
function validateArguments(name,args){
 if(!args||typeof args!=='object'||Array.isArray(args))throw Error('MCP tool arguments must be a JSON object.');
 const data={};
 const fields={
  account:[],symbols:['query'],ticker:['symbol'],
  candles:['symbol','interval','limit'],positions:[],preview_order:['symbol','side','volume','sl','tp']
 };
 if(!Object.prototype.hasOwnProperty.call(fields,name))throw Error('Tool not exposed: '+String(name));
 const allowed=fields[name];
 for(const key of Object.keys(args)){
  if(!allowed.includes(key))throw Error('Unknown argument '+key);
 }
 for(const key of allowed){
  if(Object.prototype.hasOwnProperty.call(args,key))data[key]=args[key];
 }
 return data;
}
class MT5McpHttpServer{
 constructor({bridge,httpModule=http,randomKey=()=>randomBytes(32).toString('base64url'),clock=Date.now}={}){
  if(!bridge||typeof bridge.call!=='function')throw TypeError('MT5 bridge required.');
  this.bridge=bridge;this.httpModule=httpModule;this.randomKey=randomKey;this.clock=clock;
  this.server=null;this.key=null;this.url=null;this.startPromise=null;
  this.active=0;this.requests=[];
 }
 status(){return {running:!!this.server,url:this.url,host:'127.0.0.1',apiKeyCreated:!!this.key,readOnly:true,tools:[...TOOL_NAMES]};}
 revealKey(){if(!this.key||!this.server)throw Error('Start internal MCP server first.');return this.key;}
 async start(port=0){
  if(this.server)return {...this.status(),apiKey:this.revealKey()};
  if(this.startPromise)return this.startPromise;
  const selected=validatePort(port);
  this.startPromise=new Promise((resolve,reject)=>{
   const key=this.randomKey();
   if(typeof key!=='string'||key.length<40){reject(Error('Cannot create sufficiently strong MCP API key.'));return;}
   const server=this.httpModule.createServer((req,res)=>{this.handle(req,res,key).catch(()=>{
    this.send(res,500,protocolError(-32603,'Internal MCP server error'));
   });});
   server.requestTimeout=15000;server.headersTimeout=12000;server.keepAliveTimeout=4000;
   server.on('clientError',(_error,socket)=>{try{socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');}catch{}});
   server.once('error',err=>{try{server.close();}catch{}reject(Error('Cannot start internal MCP server: '+err.message));});
   server.listen(selected,'127.0.0.1',()=>{
    const address=server.address();
    this.server=server;this.key=key;
    this.url='http://127.0.0.1:'+address.port+'/mcp';
    resolve({...this.status(),apiKey:key});
   });
  }).finally(()=>{this.startPromise=null;});
  return this.startPromise;
 }
 async stop(){
  const server=this.server;
  this.server=null;this.url=null;this.key=null;this.requests=[];
  if(!server)return {running:false};
  server.closeAllConnections?.();
  await new Promise(resolve=>server.close(()=>resolve()));
  return {running:false};
 }
 send(res,code,body,extra={}){
  if(res.headersSent||res.writableEnded)return;
  const payload=typeof body==='string'?body:JSON.stringify(body);
  res.writeHead(code,{'Content-Type':'application/json; charset=utf-8',
   'Cache-Control':'no-store','X-Content-Type-Options':'nosniff',
   'Content-Length':Buffer.byteLength(payload),...extra});
  res.end(payload);
 }
 async handle(req,res,key){
  const address=this.server?.address();
  const port=address&&typeof address==='object'?address.port:0;
  const allowedHost=new Set(['127.0.0.1:'+port,'localhost:'+port]);
  const remote=req.socket.remoteAddress;
  if(remote!=='127.0.0.1'&&remote!=='::ffff:127.0.0.1'){
   return this.send(res,403,{error:'Loopback access only.'});
  }
  if(!allowedHost.has(String(req.headers.host||'').toLowerCase())){
   return this.send(res,403,{error:'Untrusted Host header.'});
  }
  const origin=req.headers.origin;
  if(origin){
   let valid=false;
   try{
    const o=new URL(origin);
    valid=['127.0.0.1','localhost'].includes(o.hostname)&&o.protocol==='http:'&&
      (!o.port||Number(o.port)===port);
   }catch{}
   if(!valid)return this.send(res,403,{error:'Origin not allowed.'});
  }
  if(!correctSecret(key,req.headers.authorization)){
   return this.send(res,401,{error:'Invalid MCP API key.'},{'WWW-Authenticate':'Bearer'});
  }
  if(req.url==='/health'&&req.method==='GET'){
   return this.send(res,200,{ok:true,readOnly:true,service:'LUMAVUN MT5 MCP'});
  }
  if(req.url!=='/mcp')return this.send(res,404,{error:'Not found.'});
  if(req.method!=='POST')return this.send(res,405,{error:'Use MCP JSON-RPC POST.'},{Allow:'POST'});
  const now=this.clock();this.requests=this.requests.filter(t=>now-t<60000);
  if(this.requests.length>=120)return this.send(res,429,{error:'MCP rate limit.'});
  this.requests.push(now);
  if(req.headers['content-type']?.split(';')[0].toLowerCase()!=='application/json'){
   return this.send(res,415,{error:'Expected application/json.'});
  }
  const chunks=[];let length=0;
  try{
   for await(const chunk of req){
    length+=chunk.length;if(length>MAX_BYTES)return this.send(res,413,{error:'Payload too large.'});
    chunks.push(chunk);
   }
  }catch{return;}
  let data;try{data=JSON.parse(Buffer.concat(chunks).toString('utf8'));}
  catch{return this.send(res,400,protocolError(-32700,'Invalid JSON.'));}
  if(!data||Array.isArray(data)||data.jsonrpc!=='2.0'||typeof data.method!=='string')
   return this.send(res,400,protocolError(-32600,'Invalid JSON-RPC request.',data?.id??null));
  if(!Object.prototype.hasOwnProperty.call(data,'id')){
   // JSON-RPC notifications have no response (MCP uses 202 Accepted).
   return this.send(res,202,'');
  }
  const id=data.id;
  if(id===null||!(typeof id==='string'||typeof id==='number'))
   return this.send(res,400,protocolError(-32600,'Invalid request id.'));
  if(data.method==='initialize'){
   return this.send(res,200,response({protocolVersion:PROTOCOL,
    capabilities:{tools:{listChanged:false}},
    serverInfo:{name:'LUMAVUN MT5 Read-Only Gateway',version:'0.9.0'},
    instructions:'Read-only MetaTrader 5 data and broker order previews. No trade execution tools.'},id));
  }
  if(data.method==='ping')return this.send(res,200,response({},id));
  if(data.method==='tools/list')return this.send(res,200,response({tools:DESCRIPTION},id));
  if(data.method!=='tools/call')return this.send(res,200,protocolError(-32601,'Method not found.',id));
  const name=data.params?.name;
  if(typeof name!=='string'||!TOOL_NAMES.has(name))
   return this.send(res,200,protocolError(-32601,'Tool not found.',id));
  let args;
  try{args=validateArguments(name,data.params?.arguments||{});}
  catch(error){return this.send(res,200,protocolError(-32602,error.message,id));}
  if(this.active>=4)return this.send(res,503,{error:'MCP bridge is busy.'});
  this.active++;
  try{
   const result=await this.bridge.call(name,args,name==='preview_order'?22000:16000);
   return this.send(res,200,response({
    content:[{type:'text',text:JSON.stringify(result)}],
    structuredContent:{result},
    isError:false
   },id));
  }catch(error){
   return this.send(res,200,response({content:[{type:'text',text:String(error.message||error).slice(0,360)}],isError:true},id));
  }finally{this.active--;}
 }
}
module.exports={MT5McpHttpServer,DESCRIPTION,TOOL_NAMES,correctSecret,validatePort,validateArguments,PROTOCOL};
