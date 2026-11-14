'use strict';
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const cp=require('node:child_process');

const OLLAMA='http://127.0.0.1:11434';
const PACKAGE='Ollama.Ollama';
const CATALOG=Object.freeze([
 {model:'qwen2.5:1.5b',name:'Qwen 2.5 1.5B',type:'text',note:'Model teks lebih ringan untuk komputer RAM terbatas'},
 {model:'qwen2.5:3b',name:'Qwen 2.5 3B',type:'text',note:'Analisis teks dengan model lokal berukuran sedang'},
 {model:'qwen2.5vl:3b',name:'Qwen 2.5 VL 3B',type:'vision',note:'Model Vision untuk membaca screenshot chart'}
]);
const NAME=/^[a-z0-9][a-z0-9._:/-]{0,99}$/i;
function validateModel(value){
 const model=String(value||'').trim();
 if(!NAME.test(model)||model.includes('..'))throw new Error('Nama model Ollama tidak valid.');
 return model;
}
function parseProgressLine(line){
 let d;try{d=JSON.parse(line);}catch{return null;}
 if(!d||typeof d!=='object')return null;
 const status=String(d.status||'').slice(0,140);
 const total=Number(d.total),completed=Number(d.completed);
 const percent=Number.isFinite(total)&&total>0&&Number.isFinite(completed)?
  Math.max(0,Math.min(100,Math.round(100*completed/total))):null;
 return {status,percent,completed:Number.isFinite(completed)?completed:null,total:Number.isFinite(total)?total:null};
}
class LocalAiManager{
 constructor({fetchFn=globalThis.fetch,spawnFn=cp.spawn,existsFn=fs.existsSync,
  platform=process.platform,env=process.env,home=os.homedir(),notify=()=>{}}={}){
  this.fetchFn=fetchFn;this.spawnFn=spawnFn;this.existsFn=existsFn;
  this.platform=platform;this.env=env;this.home=home;this.notify=notify;
  this.installing=false;this.download=null;
 }
 binaryPath(){
  const folder=this.env.LOCALAPPDATA||path.join(this.home,'AppData','Local');
  return path.join(folder,'Programs','Ollama','ollama.exe');
 }
 emit(type,data={}){this.notify({type,...data});}
 async request(url,options={},timeout=9000){
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout);
  try{return await this.fetchFn(url,{...options,redirect:'error',signal:controller.signal});}
  finally{clearTimeout(timer);}
 }
 async status(){
  let online=false,version=null,models=[];
  try{
   const response=await this.request(OLLAMA+'/api/version');
   if(response.ok){const json=await response.json();online=true;version=String(json.version||'').slice(0,70);}
  }catch{}
  if(online){
   try{
    const response=await this.request(OLLAMA+'/api/tags');
    if(response.ok){const json=await response.json();if(Array.isArray(json.models))
      models=json.models.map(m=>String(m?.name||m?.model||'')).filter(n=>NAME.test(n)).slice(0,200);}
   }catch{}
  }
  return {platform:this.platform,installed:online||this.existsFn(this.binaryPath()),
   online,version,models,installing:this.installing,downloading:this.download?.model||null,
   catalog:CATALOG};
 }
 async command(binary,args,{timeout=600000}={}){
  return new Promise((resolve,reject)=>{
   let child;try{child=this.spawnFn(binary,args,{windowsHide:true,shell:false,stdio:['ignore','pipe','pipe']});}
   catch{reject(new Error('Gagal menjalankan pengelola paket Windows.'));return;}
   let settled=false,tail='';
   const timer=setTimeout(()=>{if(!settled){try{child.kill();}catch{} }},timeout);
   const finish=(err,code)=>{
    if(settled)return;settled=true;clearTimeout(timer);
    if(err)reject(err);else resolve(code);
   };
   child.on('error',()=>finish(new Error('WinGet tidak tersedia. Pasang App Installer dari Microsoft terlebih dahulu.')));
   const record=data=>{
    tail=(tail+String(data)).slice(-500);
    this.emit('runtime-progress',{message:'Menginstal runtime Ollama lewat WinGet…',detail:tail.slice(-180)});
   };
   child.stdout?.on('data',record);
   child.stderr?.on('data',record);
   child.on('close',code=>finish(code===0?null:new Error(code===null?
    'Instalasi runtime dihentikan atau melewati batas waktu.':
    'Instalasi Ollama belum berhasil (kode '+code+'). Periksa WinGet atau hak akses Windows.'),code));
  });
 }
 async installRuntime(){
  if(this.platform!=='win32')throw new Error('Instalasi runtime ini hanya untuk Windows.');
  if(this.installing)throw new Error('Instalasi Ollama sedang berlangsung.');
  const existing=await this.status();
  if(existing.online){this.emit('runtime-installed',{message:'Ollama sudah berjalan.'});return {ok:true,alreadyRunning:true};}
  this.installing=true;
  this.emit('runtime-progress',{message:'Memulai instalasi paket resmi Ollama.Ollama lewat WinGet.'});
  try{
   await this.command('winget.exe',['install','--id',PACKAGE,'--exact','--source','winget',
    '--silent','--accept-package-agreements','--accept-source-agreements'],{timeout:900000});
   this.emit('runtime-installed',{message:'WinGet selesai menginstal Ollama. Periksa status server lokal.'});
   return {ok:true,installedByWinget:true};
  }catch(error){
   this.emit('runtime-error',{message:error.message});
   throw error;
  }finally{this.installing=false;}
 }
 async startRuntime(){
  if(this.platform!=='win32')throw new Error('Ollama runtime hanya tersedia pada Windows dalam aplikasi ini.');
  const check=await this.status();
  if(check.online)return {ok:true,alreadyRunning:true};
  const binary=this.binaryPath();
  if(!this.existsFn(binary))throw new Error('Ollama belum ditemukan di lokasi instalasi standar. Instal runtime terlebih dahulu.');
  let child;
  try{
   child=this.spawnFn(binary,['serve'],{detached:true,windowsHide:true,shell:false,stdio:'ignore'});
   child.on('error',()=>{this.emit('runtime-error',{message:'Ollama gagal dijalankan.'});});
   child.unref?.();
  }catch{throw new Error('Tidak dapat memulai Ollama. Coba jalankan Ollama dari Start Menu.');}
  this.emit('runtime-progress',{message:'Mencoba menjalankan layanan Ollama lokal…'});
  return {ok:true,starting:true};
 }
 async pullModel(raw){
  const model=validateModel(raw);
  if(this.download)throw new Error('Unduhan model lain masih berjalan.');
  const status=await this.status();
  if(!status.online)throw new Error('Jalankan Ollama terlebih dahulu sebelum memasang model.');
  if(status.models.includes(model)||status.models.includes(model+':latest'))
    return {ok:true,alreadyInstalled:true,model};
  const controller=new AbortController();
  this.download={model,controller};
  this.emit('model-progress',{model,status:'Menghubungkan ke registry Ollama…',percent:0});
  try{
   let response;
   try{
    response=await this.fetchFn(OLLAMA+'/api/pull',{method:'POST',headers:{'Content-Type':'application/json'},
     body:JSON.stringify({model,stream:true}),signal:controller.signal,redirect:'error'});
   }catch(e){
    if(controller.signal.aborted)throw new Error('Unduhan dibatalkan pengguna.');
    throw new Error('Tidak dapat menghubungi Ollama atau registry model.');
   }
   if(!response.ok)throw new Error('Ollama menolak unduhan model (HTTP '+response.status+').');
   if(!response.body)throw new Error('Unduhan tidak memiliki aliran data.');
   const decoder=new TextDecoder();
   let buffer='',success=false,lastEvent=0;
   const onLine=line=>{
    if(!line.trim())return;
    const value=parseProgressLine(line);
    if(!value)return;
    if(value.status==='success')success=true;
    if(value.status==='error')throw new Error('Ollama melaporkan kesalahan saat mengunduh model.');
    if(Date.now()-lastEvent>150||success){
     this.emit('model-progress',{model,...value});
     lastEvent=Date.now();
    }
   };
   for await (const chunk of response.body){
    buffer+=decoder.decode(chunk,{stream:true});
    if(buffer.length>128000)throw new Error('Protokol progres model tidak valid.');
    let index;
    while((index=buffer.indexOf('\n'))!==-1){
     const line=buffer.slice(0,index);buffer=buffer.slice(index+1);
     onLine(line);
    }
   }
   buffer+=decoder.decode();if(buffer.trim())onLine(buffer);
   if(controller.signal.aborted)throw new Error('Unduhan dibatalkan pengguna.');
   if(!success)throw new Error('Ollama belum mengonfirmasi model berhasil dipasang.');
   this.emit('model-installed',{model,message:'Model '+model+' berhasil diunduh ke Ollama lokal.'});
   return {ok:true,model};
  }catch(e){
   const msg=controller.signal.aborted?'Unduhan model dibatalkan.':e.message;
   this.emit('model-error',{model,message:msg});
   throw new Error(msg);
  }finally{this.download=null;}
 }
 cancelDownload(){
  if(!this.download)return {cancelled:false};
  const model=this.download.model;
  this.download.controller.abort();
  this.emit('model-progress',{model,status:'Membatalkan unduhan…',percent:null});
  return {cancelled:true,model};
 }
}
module.exports={LocalAiManager,CATALOG,validateModel,parseProgressLine};
