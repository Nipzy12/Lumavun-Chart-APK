'use strict';
const { _electron: electron }=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
async function main(){
 let app=null;
 try{
  app=await electron.launch({args:['.'],cwd:root,timeout:45000});
  const page=await app.firstWindow({timeout:45000});
  const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.locator('#page-chart').waitFor({state:'visible',timeout:30000});
  assert.equal(await page.locator('#pageNav button').count(),5);
  assert.equal(await page.locator('#page-ai').isVisible(),false);
  assert.match(await page.title(),/LUMAVUN Chart/);
  assert.equal(await page.locator('#aiCapture').count(),1);
  assert.equal(await page.locator('#aiVision').count(),1);
  assert.equal(await page.locator('#aiPreview').isVisible(),false);
  assert.equal(await page.locator('#aiVision').isDisabled(),true);
  await page.locator('[data-route="ai"]').click();
  await page.locator('#aiHeading').waitFor({state:'visible',timeout:15000});
  assert.equal(await page.locator('#page-chart').isVisible(),false);
  assert.ok((await page.locator('#watchlist .watchItem').count())>=1,'Watchlist should render.');
  assert.ok((await page.locator('#chart canvas').count())>=1,'Local chart bundle should paint a canvas.');
  await page.locator('#aiMode').selectOption('CUSTOM');
  assert.equal(await page.locator('#aiRemote').isVisible(),true,'Custom endpoint inputs appear.');
  await page.locator('#aiMode').selectOption('OLLAMA');
  assert.equal(await page.locator('#aiRemote').isVisible(),false,'Local mode hides remote fields.');
  await page.locator('[data-route="models"]').click();
  await page.locator('#page-models').waitFor({state:'visible'});
  assert.equal(await page.locator('#localRuntimeInstall').count(),1);
  assert.equal(await page.locator('#localRuntimeStart').count(),1);
  await page.locator('[data-route="paper"]').click();
  assert.equal(await page.locator('#paperForm').isVisible(),true);
  await page.locator('[data-route="settings"]').click();
  assert.equal(await page.locator('#gatewayForm').isVisible(),true);
  await page.locator('[data-route="chart"]').click();
  assert.equal(await page.locator('#chart').isVisible(),true);
  await page.locator('#marketProvider').selectOption('BIQUOTE');
  assert.match(await page.locator('#providerBadge').textContent(),/BIQUOTE/);
  fs.mkdirSync(path.join(root,'artifacts'),{recursive:true});
  await page.screenshot({path:path.join(root,'artifacts','desktop-ui-smoke.png'),fullPage:true});
  if(errors.length)throw Error('UI JavaScript errors: '+errors.join('; ').slice(0,500));
  console.log('PASS Electron UI smoke: 5-page navigation, Chart canvas, AI Vision, model manager, Paper Trading, Settings, watchlist.');
 }finally{if(app)await app.close();}
}
main().catch(error=>{console.error('FAIL desktop UI smoke:',error?.message||error);process.exitCode=1;});
