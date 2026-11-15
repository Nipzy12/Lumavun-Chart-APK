const test=require('node:test');
const assert=require('node:assert/strict');
const {customModels,normalizeModels,validateEndpoint}=require('./model-catalog.cjs');
test('OpenAI-compatible model list parses id and sorts unique entries',()=>{
 assert.deepEqual(normalizeModels({data:[{id:'gpt-beta'},{id:'gpt-alpha'},{id:'gpt-alpha'},{id:' bad'}]}),
 ['gpt-alpha','gpt-beta']);
});
test('custom models calls GET /v1/models with bearer token without logging secrets',async()=>{
 let seen;
 const models=await customModels('https://api.example-provider.com/v1','abc123',async(url,opts)=>{
  seen={url,opts};return {ok:true,async text(){return JSON.stringify({data:[{id:'custom/vision-v1'}]});}};
 });
 assert.deepEqual(models,['custom/vision-v1']);
 assert.equal(seen.url,'https://api.example-provider.com/v1/models');
 assert.equal(seen.opts.headers.Authorization,'Bearer abc123');
 assert.equal(seen.opts.redirect,'error');
});
test('invalid custom API endpoints are rejected, no plain HTTP or loopback',()=>{
 for(const x of ['http://api.provider.com/v1','http://127.0.0.1:9876/v1',
  'https://api.test/v1','https://example.com/unsafe','https://abc.com/v1?secret=a']){
  assert.throws(()=>validateEndpoint(x,'token'));
 }
});
test('provider 401 and empty model list surface explicit errors',async()=>{
 await assert.rejects(customModels('https://test-provider.net/v1','bad',async()=>({ok:false,status:401})),/ditolak/);
 await assert.rejects(customModels('https://test-provider.net/v1','ok',async()=>({
  ok:true,async text(){return JSON.stringify({data:[]});}
 })),/tidak mengembalikan model/);
});
