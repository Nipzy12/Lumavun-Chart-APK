'use strict';
const path=require('node:path');
const {spawn}=require('node:child_process');
const {EventEmitter}=require('node:events');
class MT5Client extends EventEmitter{
 constructor({app=null,spawnFn=spawn,platform=process.platform}={}){
  super();this.app=app;this.spawnFn=spawnFn;this.platform=platform;
  this.child=null;this.pending=new Map();this.counter=0;this.stdout='';
 }
 scriptPath(){
  return this.app?.isPackaged?
   path.join(process.resourcesPath,'mt5_bridge','bridge.py'):
   path.join(__dirname,'..','mt5_bridge','bridge.py');
 }
 mcpCommand(){
  return {command:'py',args:['-3',this.scriptPath(),'--mcp']};
 }
 start(){
  if(this.child)return;
  if(this.platform!=='win32')throw Error('MT5 terminal bridge requires Windows.');
  const child=this.spawnFn('py',['-3','-u',this.scriptPath(),'--jsonl'],{
   windowsHide:true,shell:false,stdio:['pipe','pipe','pipe']
  });
  this.child=child;
  let stderr='';
  child.stdout.setEncoding('utf8');
  child.stdout.on('data',chunk=>{
   this.stdout+=chunk;
   if(this.stdout.length>1024*1024){this.failAll('Invalid MT5 bridge response.');return;}
   let i;
   while((i=this.stdout.indexOf('\n'))>=0){
    const line=this.stdout.slice(0,i);this.stdout=this.stdout.slice(i+1);
    let packet;try{packet=JSON.parse(line);}catch{continue;}
    const pending=this.pending.get(packet.id);if(!pending)continue;
    this.pending.delete(packet.id);clearTimeout(pending.timer);
    if(packet.ok)pending.resolve(packet.result);
    else pending.reject(Error('MT5: '+String(packet.error||'Unknown bridge error').slice(0,350)));
   }
  });
  child.stderr?.on('data',chunk=>{stderr=(stderr+String(chunk)).slice(-1000);});
  child.on('error',()=>this.failAll('Could not launch Python. Install Python 3 x64 and MetaTrader5 package.'));
  child.on('exit',()=>this.failAll('MT5 bridge stopped. Ensure Python x64, MetaTrader5 and the MT5 terminal are installed. '+(stderr.includes('No module named')?'Missing Python module.':'')));
 }
 failAll(message){
  const child=this.child;this.child=null;this.stdout='';
  for(const item of this.pending.values()){clearTimeout(item.timer);item.reject(Error(message));}
  this.pending.clear();
  if(child&&!child.killed){try{child.kill();}catch{}}
 }
 call(method,params={},timeoutMs=16000){
  const allowed=new Set(['account','symbols','ticker','candles','positions','preview_order','execute_order']);
  if(!allowed.has(method))return Promise.reject(Error('Unsupported MT5 command.'));
  if(!params||typeof params!=='object'||Array.isArray(params))return Promise.reject(Error('Invalid MT5 params.'));
  try{this.start();}catch(err){return Promise.reject(err);}
  const id=++this.counter;
  return new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>{
    this.pending.delete(id);
    reject(Error(method==='execute_order'?
     'MT5 execution timed out. Check MT5 trade history BEFORE considering another order.':
     'MT5 terminal did not respond in time.'));
   },timeoutMs);
   this.pending.set(id,{resolve,reject,timer});
   try{this.child.stdin.write(JSON.stringify({id,method,params})+'\n',err=>{
    if(err&&this.pending.has(id)){this.pending.delete(id);clearTimeout(timer);reject(Error('MT5 bridge input failed.'));}
   });}
   catch(err){this.pending.delete(id);clearTimeout(timer);reject(Error('MT5 bridge is not available.'));}
  });
 }
 shutdown(){
  this.failAll('MT5 bridge closed by application.');
 }
}
module.exports={MT5Client};
