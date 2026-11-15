'use strict';
// Connect a user-selected Streamable HTTP MCP endpoint with a per-session Bearer key.
// Keys stay in Electron main-process RAM, never in localStorage, a URL, or logs.
const {TOOL_NAMES,PROTOCOL}=require('./mt5-mcp-http.cjs');

function validateMcpUrl(raw){
 let u;
 try{u=new URL(String(raw||''));}catch{throw Error('MCP requires an absolute http(s) URL.');}
 if(u.username||u.password||u.hash||u.search)
  throw Error('MCP URL must not contain credentials, query parameters or a fragment.');
 if(u.pathname!=='/mcp'||u.protocol!=='https:'&&u.protocol!=='http:')
  throw Error('Use a Streamable HTTP endpoint ending in /mcp.');
 const loopback=['127.0.0.1','localhost','[::1]'].includes(u.hostname.toLowerCase());
 if(u.protocol==='http:'&&!loopback)
  throw Error('Non-loopback MCP servers require HTTPS to protect your API key.');
 if(u.hostname==='0.0.0.0'||u.hostname==='169.254.169.254')
  throw Error('Invalid MCP destination.');
 return u.origin+'/mcp';
}
function validateApiKey(value){
 if(typeof value!=='string'||value.length<16||value.length>512||!/^[\x21-\x7e]+$/.test(value))
  throw Error('MCP API key must be 16..512 printable characters without spaces.');
 return value;
}
function decodeMcpPayload(payload){
 if(typeof payload!=='string'||payload.length>1000000)throw Error('MCP response too large.');
 let source=payload.trim();
 if(source.startsWith('event:')||source.startsWith('data:')){
  const chunks=source.split(/\r?\n/).filter(x=>x.startsWith('data:')).map(x=>x.slice(5).trim());
  if(!chunks.length)throw Error('MCP server did not return a JSON-RPC event.');
  source=chunks.join('');
 }
 let value;
 try{value=JSON.parse(source);}catch{throw Error('MCP server returned invalid JSON-RPC content.');}
 if(!value||value.jsonrpc!=='2.0'||typeof value!=='object')throw Error('Invalid MCP JSON-RPC response.');
 if(value.error)throw Error('Remote MCP error: '+String(value.error.message||'unknown error').slice(0,300));
 return value.result;
}
class MT5McpRemote{
 constructor({fetchFn=globalThis.fetch}={}){this.fetchFn=fetchFn;this.url='';this.apiKey='';this.tools=[];}
 status(){return {connected:!!this.url,url:this.url||null,tools:[...this.tools],readOnly:true};}
 disconnect(){this.apiKey='';this.url='';this.tools=[];return this.status();}
 async request(url,key,method,params={},timeout=12000){
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),timeout);
  try{
   const reply=await this.fetchFn(url,{
    method:'POST',redirect:'error',signal:controller.signal,
    headers:{'Authorization':'Bearer '+key,'Content-Type':'application/json',
      'Accept':'application/json, text/event-stream','MCP-Protocol-Version':PROTOCOL},
    body:JSON.stringify({jsonrpc:'2.0',id:1,method,params})
   });
   if(!reply.ok)throw Error(reply.status===401?
    'MCP rejected API key (401).':'MCP returned HTTP '+reply.status+'.');
   return decodeMcpPayload(await reply.text());
  }catch(error){
   if(controller.signal.aborted)throw Error('MCP connection timed out.');
   throw error;
  }finally{clearTimeout(timer);}
 }
 async connect(rawUrl,rawKey){
  const url=validateMcpUrl(rawUrl),key=validateApiKey(rawKey);
  const initial=await this.request(url,key,'initialize',{
   protocolVersion:PROTOCOL,capabilities:{},clientInfo:{name:'LUMAVUN Chart',version:'0.9.0'}
  });
  if(!initial?.serverInfo||!initial?.capabilities)
   throw Error('Endpoint did not respond as a compatible MCP server.');
  const listing=await this.request(url,key,'tools/list',{});
  const tools=Array.isArray(listing?.tools)?listing.tools.map(x=>x?.name).filter(x=>TOOL_NAMES.has(x)):[];
  if(!tools.includes('ticker')||!tools.includes('candles'))
   throw Error('Connected MCP server does not expose ticker and candles read-only tools.');
  this.url=url;this.apiKey=key;this.tools=[...new Set(tools)];
  return this.status();
 }
 async call(name,args={}){
  if(!this.url||!this.apiKey)throw Error('No MCP server is connected to LUMAVUN Chart.');
  if(!TOOL_NAMES.has(name)||!this.tools.includes(name))throw Error('Remote MCP tool not permitted: '+name);
  const result=await this.request(this.url,this.apiKey,'tools/call',{name,arguments:args},name==='preview_order'?23000:18000);
  if(result?.isError)throw Error('MCP tool failed: '+String(result.content?.[0]?.text||'unknown error').slice(0,300));
  if(result&&Object.prototype.hasOwnProperty.call(result.structuredContent||{},'result'))
   return result.structuredContent.result;
  const text=result?.content?.find(x=>x.type==='text')?.text;
  if(typeof text!=='string')throw Error('MCP tool did not return JSON data.');
  try{return JSON.parse(text);}catch{throw Error('MCP tool result is not JSON.');}
 }
}
module.exports={MT5McpRemote,validateMcpUrl,validateApiKey,decodeMcpPayload};
