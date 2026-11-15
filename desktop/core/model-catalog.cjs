'use strict';
const ai=require('./ai-analyst.cjs');
const NAME=/^[A-Za-z0-9][A-Za-z0-9._:/+-]{0,100}$/;
function validateEndpoint(baseUrl,key=''){
 const config=ai.validateConfig({provider:'CUSTOM',model:'model-placeholder',baseUrl,apiKey:key});
 return {baseUrl:config.baseUrl,apiKey:config.apiKey};
}
function normalizeModels(payload){
 if(!payload||!Array.isArray(payload.data))throw Error('Provider tidak menyediakan format daftar model /v1/models.');
 return [...new Set(payload.data.map(x=>typeof x==='string'?x:x?.id)
  .filter(id=>typeof id==='string'&&NAME.test(id)))].sort((a,b)=>a.localeCompare(b)).slice(0,300);
}
async function customModels(baseUrl,apiKey='',fetchFn=globalThis.fetch){
 const settings=validateEndpoint(baseUrl,apiKey);
 const controller=new AbortController();
 const timer=setTimeout(()=>controller.abort(),12000);
 try{
  const headers={'Accept':'application/json'};
  if(settings.apiKey)headers.Authorization='Bearer '+settings.apiKey;
  const response=await fetchFn(settings.baseUrl+'/models',{
   method:'GET',headers,redirect:'error',signal:controller.signal
  });
  if(!response.ok)throw Error(response.status===401||response.status===403?
   'API key ditolak oleh provider (HTTP '+response.status+').':
   'Daftar model tidak tersedia (HTTP '+response.status+').');
  const raw=await response.text();
  if(raw.length>2000000)throw Error('Daftar model terlalu besar.');
  let payload;try{payload=JSON.parse(raw);}catch{throw Error('Respons /models bukan JSON yang valid.');}
  const names=normalizeModels(payload);
  if(!names.length)throw Error('Provider tidak mengembalikan model yang kompatibel.');
  return names;
 }catch(error){
  if(error.name==='AbortError')throw Error('Sinkronisasi model melewati batas waktu.');
  throw error;
 }finally{clearTimeout(timer);}
}
module.exports={customModels,normalizeModels,validateEndpoint};
