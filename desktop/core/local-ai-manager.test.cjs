const {test}=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {Readable}=require('node:stream');
const {LocalAiManager,validateModel,parseProgressLine}=require('./local-ai-manager.cjs');

function offlineFetch(){return Promise.reject(Error('offline'));}
test('catalog input blocks command injection and accepts model identifiers',()=>{
 assert.equal(validateModel('qwen2.5vl:3b'),'qwen2.5vl:3b');
 for(const name of ['','../malicious','qwen;del C:\\','evil model','model\n--delete',{}])
  assert.throws(()=>validateModel(name));
 const d=parseProgressLine(JSON.stringify({status:'downloading',completed:50,total:100}));
 assert.equal(d.percent,50);assert.equal(parseProgressLine('bad'),null);
});
test('runtime installer is an explicit WinGet package install with shell disabled',async()=>{
 let command;
 const spawnFn=(binary,args,options)=>{
  command={binary,args,options};
  const child=new EventEmitter();child.stdout=new EventEmitter();child.stderr=new EventEmitter();
  queueMicrotask(()=>child.emit('close',0));
  return child;
 };
 const mgr=new LocalAiManager({platform:'win32',fetchFn:offlineFetch,existsFn:()=>false,spawnFn,notify:()=>{}});
 const result=await mgr.installRuntime();
 assert.equal(result.ok,true);assert.equal(command.binary,'winget.exe');
 assert.ok(command.args.includes('Ollama.Ollama'));assert.ok(command.args.includes('--exact'));
 assert.equal(command.options.shell,false);
});
test('runtime install is disabled on non-Windows hosts',async()=>{
 const mgr=new LocalAiManager({platform:'linux',fetchFn:offlineFetch});
 await assert.rejects(mgr.installRuntime(),/Windows/);
});
test('local model download uses Ollama loopback and streams download percentage',async()=>{
 const seen=[],calls=[];
 const fetchFn=async(url,opts={})=>{
  calls.push({url,opts});
  if(url.endsWith('/api/version'))return {ok:true,json:async()=>({version:'0.15.0'})};
  if(url.endsWith('/api/tags'))return {ok:true,json:async()=>({models:[]})};
  if(url.endsWith('/api/pull'))return {ok:true,body:Readable.from([
   Buffer.from('{"status":"downloading","completed":40,"total":100}\n'),
   Buffer.from('{"status":"success"}\n')
  ])};
  throw Error('unexpected fetch '+url);
 };
 const mgr=new LocalAiManager({fetchFn,platform:'win32',notify:x=>seen.push(x)});
 const result=await mgr.pullModel('qwen2.5:1.5b');
 assert.equal(result.ok,true);
 assert.equal(calls.at(-1).url,'http://127.0.0.1:11434/api/pull');
 assert.equal(JSON.parse(calls.at(-1).opts.body).model,'qwen2.5:1.5b');
 assert.ok(seen.some(e=>e.type==='model-installed'));
});
test('pull cannot proceed while local server is offline and no fake success is reported',async()=>{
 const mgr=new LocalAiManager({fetchFn:offlineFetch});
 await assert.rejects(mgr.pullModel('qwen2.5:3b'),/Jalankan Ollama/);
 assert.equal(mgr.download,null);
});
test('model name sent to server cannot contain URL or command options',async()=>{
 const mgr=new LocalAiManager({fetchFn:offlineFetch});
 await assert.rejects(mgr.pullModel('rm -rf /'),/tidak valid/);
});
