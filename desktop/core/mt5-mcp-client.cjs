'use strict';
// Authenticated MCP HTTP client with support for LUMAVUN tools AND native MetaQuotes MT5.
// Only known read-only tools can be invoked. Never forwards trade, shell or file tools.
const {TOOL_NAMES,PROTOCOL}=require('./mt5-mcp-http.cjs');
const native=require('./mt5-native-adapter.cjs');

function validateMcpUrl(raw){
 let u;try{u=new URL(String(raw||''));}catch{throw Error('MCP requires an absolute http(s) URL.');}
 if(u.username||u.password||u.hash||u.search)throw Error('MCP URL must not contain credentials, query parameters or fragment.');
 if(u.pathname!=='/mcp'||!['http:','https:'].includes(u.protocol))throw Error('MCP endpoint must end in /mcp.');
 const loop=['127.0.0.1','localhost','[::1]'].includes(u.hostname.toLowerCase());
 if(u.protocol==='http:'&&!loop)throw Error('Non-loopback MCP servers require HTTPS.');
 if(['0.0.0.0','169.254.169.254'].includes(u.hostname))throw Error('Invalid MCP destination.');
 return u.origin+'/mcp';
}
function validateApiKey(value){
 if(typeof value!=='string'||value.length<16||value.length>512||!/^[\x21-\x7e]+$/.test(value))
  throw Error('MCP API key must be 16..512 printable characters, no spaces.');
 return value;
}
function decodeMcpPayload(payload){
 if(typeof payload!=='string'||payload.length>4e6)throw Error('MCP response too large.');
 let src=payload.trim();
 if(src.startsWith('event:')||src.startsWith('data:')){
  const chunks=src.split(/\r?\n/).filter(x=>x.startsWith('data:')).map(x=>x.slice(5).trim()).filter(x=>x!=='[DONE]');
  if(!chunks.length)throw Error('MCP server returned no JSON-RPC events.');
  src=chunks[chunks.length-1];
 }
 let value;try{value=JSON.parse(src);}catch{throw Error('MCP returned invalid JSON-RPC.');}
 if(!value||value.jsonrpc!=='2.0'||typeof value!=='object')throw Error('Invalid MCP JSON-RPC response.');
 if(value.error)throw Error('MCP server: '+String(value.error.message||'Unknown RPC error').slice(0,260));
 return value.result;
}
class MT5McpRemote{
 constructor({fetchFn=globalThis.fetch}={}){
  this.fetchFn=fetchFn;this.disconnect();
 }
 status(){return {connected:!!this.url,url:this.url||null,tools:[...this.tools],
  readOnly:true,mode:this.mode,serverName:this.serverName,nativeTools:Object.values(this.nativeTools).map(x=>x.name)};}
 disconnect(){
  this.url='';this.apiKey='';this.tools=[];this.mode=null;this.serverName=null;
  this.sessionId='';this.nativeTools={};return this.status();
 }
 async request(url,key,method,params={},timeout=12000,{notification=false,sessionId=''}={}){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout);
  try{
   const headers={'Authorization':'Bearer '+key,'Content-Type':'application/json',
    'Accept':'application/json, text/event-stream','MCP-Protocol-Version':PROTOCOL};
   if(sessionId)headers['Mcp-Session-Id']=sessionId;
   const message={jsonrpc:'2.0',method,...(notification?{}:{id:1}),params};
   const res=await this.fetchFn(url,{method:'POST',redirect:'error',signal:controller.signal,
    headers,body:JSON.stringify(message)});
   if(!res.ok){
    if(res.status===401)throw Error('MT5 MCP unauthorized (401). Regenerate the key in MT5 Options > MCP, click OK, then Copy fresh settings.');
    throw Error('MCP server returned HTTP '+res.status+'.');
   }
   if(notification)return {result:null,sessionId:null};
   const session=res.headers?.get?.('mcp-session-id')||'';
   if(session&&!/^[a-zA-Z0-9_\-]{1,160}$/.test(session))throw Error('Invalid MCP session header.');
   return {result:decodeMcpPayload(await res.text()),sessionId:session||null};
  }catch(error){
   if(controller.signal.aborted)throw Error('MCP request timed out.');
   throw error;
  }finally{clearTimeout(timer);}
 }
 async connect(rawUrl,rawKey){
  const url=validateMcpUrl(rawUrl),key=validateApiKey(rawKey);
  const initialization=await this.request(url,key,'initialize',{
   protocolVersion:PROTOCOL,capabilities:{},clientInfo:{name:'LUMAVUN Chart',version:'1.0.0'}
  });
  if(!initialization.result?.serverInfo||!initialization.result?.capabilities)
   throw Error('Endpoint is not a compatible MCP server.');
  const sid=initialization.sessionId||'';
  // Some MT5 MCP builds require notifications/initialized; other stateless
  // servers may not implement it. Failed optional notification cannot grant access.
  try{await this.request(url,key,'notifications/initialized',{},4000,{notification:true,sessionId:sid});}catch{}
  const listing=await this.request(url,key,'tools/list',{},12000,{sessionId:sid});
  const descriptions=Array.isArray(listing.result?.tools)?listing.result.tools:[];
  const found=new Set(descriptions.map(d=>d?.name));
  const builtin=found.has('ticker')&&found.has('candles');
  const mapping=native.selectNativeTools(descriptions);
  if(!builtin&&!Object.keys(mapping).length)
   throw Error('MCP server has no supported read-only MT5 tools. Check its terminal permissions.');
  this.url=url;this.apiKey=key;this.sessionId=sid;
  this.serverName=String(initialization.result.serverInfo.name||'MCP');
  this.mode=builtin?'LUMAVUN_BRIDGE':'MT5_NATIVE';
  if(builtin){
   this.tools=descriptions.map(d=>d?.name).filter(n=>TOOL_NAMES.has(n));
  }else{
   this.nativeTools=mapping;
   this.tools=Object.keys(mapping).filter(k=>k!=='ticks'&&k!=='quote');
   if(mapping.quote||mapping.symbols||mapping.ticks)this.tools.push('ticker');
   this.tools=[...new Set(this.tools)];
  }
  return this.status();
 }
 async rawTool(name,args){
  const result=(await this.request(this.url,this.apiKey,'tools/call',{name,arguments:args},
   24000,{sessionId:this.sessionId})).result;
  if(result?.isError)throw Error('MT5 MCP tool error: '+String(result.content?.[0]?.text||'unknown').slice(0,350));
  if(Object.prototype.hasOwnProperty.call(result?.structuredContent||{},'result'))
   return result.structuredContent.result;
  if(result?.structuredContent&&typeof result.structuredContent==='object')
   return result.structuredContent;
  const messages=Array.isArray(result?.content)?result.content.filter(x=>x.type==='text'):[],
   message=messages.map(x=>x.text).join('\n');
  if(!message)throw Error('MT5 MCP tool returned no readable result.');
  try{return JSON.parse(message);}catch{
   // MT5 may return ordinary text. Do not interpret it as verified candles.
   throw Error('MT5 MCP tool did not return structured JSON: '+name);
  }
 }
 async nativeTool(logical,args){
  const tool=this.nativeTools[logical];
  if(!tool)throw Error('Native MT5 MCP missing read-only tool for '+logical);
  const adapted=native.buildArgs(tool,args);
  return this.rawTool(tool.name,adapted);
 }
 async call(logical,args={}){
  if(!this.url||!this.apiKey)throw Error('No MCP server connected to LUMAVUN.');
  if(!TOOL_NAMES.has(logical))throw Error('MCP tool not permitted: '+logical);
  if(this.mode==='LUMAVUN_BRIDGE'){
   if(!this.tools.includes(logical))throw Error('Remote MCP tool unavailable: '+logical);
   return this.rawTool(logical,args);
  }
  switch(logical){
   case 'account':return native.normalizeAccount(await this.nativeTool('account',args));
   case 'positions':return native.normalizePositions(await this.nativeTool('positions',args));
   case 'symbols':return native.normalizeSymbols(await this.nativeTool('symbols',args),args.query||'');
   case 'candles':return native.normalizeBars(await this.nativeTool('candles',args),args.limit||300);
   case 'ticker':{
    const symbol=native.buildArgs({name:'ticker-check',inputSchema:{properties:{symbol:{type:'string'}},required:['symbol']}},args).symbol;
    const errors=[];
    for(const type of ['quote','symbols','ticks']){
     if(!this.nativeTools[type])continue;
     try{
      const raw=await this.nativeTool(type,{symbol,query:symbol,limit:100});
      return native.normalizeQuote(raw,symbol);
     }catch(error){errors.push(type+': '+String(error.message).slice(0,90));}
    }
    throw Error('Native MT5 MCP has no valid bid/ask quote for '+symbol+'. '+errors.join(' | '));
   }
   default:throw Error('Native MT5 MCP does not offer a safe read-only equivalent for '+logical);
  }
 }
}
module.exports={MT5McpRemote,validateMcpUrl,validateApiKey,decodeMcpPayload};
