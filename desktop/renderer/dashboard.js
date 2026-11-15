'use strict';
/* LUMAVUN virtual office and verified trading performance dashboard.
 * Local, user-triggered research only. No broker order actions are referenced here.
 */
(function(){
const $=id=>document.getElementById(id);
const jobKey='lumavun-office-jobs-v1';
const desks={SCOUT:'Market Scout',RISK:'Risk Officer',RESEARCH:'Research Lab',BRIEFING:'Briefing Desk'};
let jobs=[],currentRun=null,mt5Trades=null,mt5Error=null,perfBusy=false;
try{
 const raw=JSON.parse(localStorage.getItem(jobKey)||'[]');
 if(Array.isArray(raw))jobs=raw.filter(j=>j&&typeof j.summary==='string'&&desks[j.desk]).slice(0,18);
}catch{}
function money(n){
 return Number.isFinite(n)?new Intl.NumberFormat('en-US',{style:'currency',currency:'USD',
  maximumFractionDigits:2}).format(n):'—';
}
function dateTime(t){return t?new Date(t).toLocaleString('id-ID',{dateStyle:'medium',timeStyle:'short'}):'—';}
function safeText(element,text){element.textContent=String(text??'');}
function officeUpdate(){
 safeText($('officeWorkspaceState'),currentRun?'RUNNING · '+desks[currentRun]:aiUi.configured?'READY · MANUAL':'STANDBY');
 safeText($('officeEngine'),aiUi.configured?
  byId('aiMode').value+' · '+(byId('aiModel').value||'Model belum dipilih'):'Model belum terhubung');
 safeText($('officeSymbol'),(aiUi.ready&&aiUi.loadedKey===marketAiKey())?
  state.provider+' / '+state.symbol+' · '+state.interval:'Chart belum diverifikasi');
 safeText($('officeLastCompleted'),jobs[0]?dateTime(jobs[0].at):'—');
 for(const [key] of Object.entries(desks)){
  const indicator=document.querySelector('[data-desk-status="'+key+'"]');
  if(!indicator)continue;
  const isRunning=currentRun===key;
  safeText(indicator,isRunning?'RUNNING':jobs.find(j=>j.desk===key)?.ok?'LAST RUN OK':'IDLE');
  indicator.classList.toggle('running',isRunning);
 }
 const log=$('officeActivityLog');log.replaceChildren();
 if(!jobs.length){log.textContent='Belum ada tugas yang dijalankan.';return;}
 for(const job of jobs.slice(0,8)){
  const item=document.createElement('div');item.className='officeLogItem';
  const head=document.createElement('strong');head.textContent=desks[job.desk]+' · '+(job.ok?'DONE':'FAILED');
  const dt=document.createElement('small');dt.textContent=dateTime(job.at);
  const desc=document.createElement('p');desc.textContent=job.summary.slice(0,160);
  item.append(head,dt,desc);log.append(item);
 }
}
async function runDesk(desk){
 if(!desks[desk]||currentRun)return;
 if(!aiUi.configured){showPage('ai');setAiStatus('Hubungkan model AI sebelum menjalankan Virtual AI Office.');return;}
 if(!aiUi.ready||aiUi.loadedKey!==marketAiKey()){
  safeText($('officeMessage'),'Chart belum memiliki candle terverifikasi. Buka Chart, refresh, lalu coba lagi.');
  return;
 }
 currentRun=desk;officeUpdate();
 document.querySelectorAll('[data-office-run]').forEach(b=>b.disabled=true);
 safeText($('officeMessage'),desks[desk]+' sedang menganalisis snapshot '+state.symbol+' secara read-only…');
 const snapshot={provider:state.provider,symbol:state.symbol,interval:state.interval};
 let job;
 try{
  const result=await window.lumavun.officeRun(desk,snapshot.provider,snapshot.symbol,snapshot.interval);
  if(result.provider!==snapshot.provider||result.symbol!==snapshot.symbol||result.interval!==snapshot.interval)
   throw Error('Market changed; discarded stale agent report.');
  const out=result.result;
  if(!out||typeof out.summary!=='string')throw Error('Model did not return a validated summary.');
  const panel=$('officeLatest');panel.replaceChildren();
  const label=document.createElement('span');label.className='dashMiniLabel';
  label.textContent=desks[desk]+' · '+result.model+' · '+dateTime(Date.now());
  const decision=document.createElement('h3');
  decision.textContent=out.decision+' · '+out.bias+' · '+out.confidence+'% confidence';
  const summary=document.createElement('p');summary.textContent=out.summary;
  panel.append(label,decision,summary);
  if(Array.isArray(out.reasoning)){
   const ul=document.createElement('ul');
   for(const reason of out.reasoning.slice(0,4)){
    const li=document.createElement('li');li.textContent=reason;ul.append(li);
   }panel.append(ul);
  }
  safeText($('officeMessage'),'Analisis selesai. Hasil memerlukan peninjauan manusia sebelum keputusan trading.');
  job={desk,ok:true,at:Date.now(),summary:out.summary,decision:out.decision};
 }catch(error){
  const message=String(error.message||error);
  safeText($('officeMessage'),'Tugas gagal: '+message.slice(0,350));
  job={desk,ok:false,at:Date.now(),summary:message.slice(0,240)};
 }finally{
  jobs.unshift(job);jobs=jobs.slice(0,18);
  try{localStorage.setItem(jobKey,JSON.stringify(jobs));}catch{}
  currentRun=null;document.querySelectorAll('[data-office-run]').forEach(b=>b.disabled=false);
  officeUpdate();
 }
}
document.querySelectorAll('[data-office-run]').forEach(button=>{
 button.addEventListener('click',()=>runDesk(button.dataset.officeRun));
});
$('officeGoAi').addEventListener('click',()=>showPage('ai'));
$('officeGoChart').addEventListener('click',()=>showPage('chart'));
window.addEventListener('lumavun-ai-connected',officeUpdate);

function perfRender(trades,source){
 const lib=window.LumavunPerformance;
 const paper=source==='PAPER';
 const baseline=paper?account.startingBalance:null;
 const stats=lib.stats(trades,{startingBalance:baseline});
 $('perfSourceNote').textContent=paper?
  'PAPER · simulasi lokal, closed trades saja. P&L sudah net biaya paper model.':
  'MT5 MCP · riwayat closed positions broker, 90 hari · Net P&L hanya jika biaya dilaporkan.';
 safeText($('perfNet'),stats.count?money(stats.netPnl):'—');
 $('perfNet').classList.toggle('negative',stats.netPnl<0);
 safeText($('perfTrades'),stats.count+' closed trades'+(stats.breakeven?' · '+stats.breakeven+' BE':''));
 safeText($('perfWin'),stats.winRate==null?'—':stats.winRate.toFixed(1)+'%');
 safeText($('perfFactor'),stats.profitFactor==null?'—':stats.profitFactor.toFixed(2));
 safeText($('perfExpectancy'),stats.expectancy==null?'—':money(stats.expectancy));
 safeText($('perfDd'),stats.count?money(stats.maxDrawdown)+(stats.maxDrawdownPercent!=null?
  ' ('+stats.maxDrawdownPercent.toFixed(2)+'%)':''):'—');
 safeText($('perfCurveNote'),stats.note);
 safeText($('perfCountWin'),stats.wins);safeText($('perfCountLoss'),stats.losses);
 safeText($('perfCountBE'),stats.breakeven);
 safeText($('perfGrossProfit'),stats.count?money(stats.grossProfit):'—');
 safeText($('perfGrossLoss'),stats.count?money(-stats.grossLoss):'—');
 safeText($('perfDataSource'),source);
 safeText($('perfCurveBadge'),stats.count+' CLOSED');
 const all=stats.count||1;
 $('perfSplitBar').querySelector('.perfWinFill').style.width=(stats.wins/all*100)+'%';
 $('perfSplitBar').querySelector('.perfLossFill').style.width=(stats.losses/all*100)+'%';
 safeText($('perfChartTitle'),paper?'Paper Closed-Trade Balance Path':'MT5 Cumulative Realized P&L');
 renderCurve(stats.curve,stats.count,stats.startingBalance!=null);
 const table=$('perfHistoryTable');table.replaceChildren();
 if(!trades.length){const hint=document.createElement('p');hint.className='muted';
  hint.textContent='Belum ada histori posisi tertutup yang memenuhi validasi data.';table.append(hint);return;}
 const header=document.createElement('div');header.className='perfTableHead';
 for(const txt of ['Close time','Symbol','Side','Net P&L']){const t=document.createElement('span');t.textContent=txt;header.append(t);}
 table.append(header);
 for(const trade of trades.slice().sort((a,b)=>b.at-a.at).slice(0,30)){
  const row=document.createElement('div');row.className='perfTableRow';
  const cols=[dateTime(trade.at),trade.symbol,trade.side,money(trade.net)];
  for(let i=0;i<4;i++){const span=document.createElement('span');span.textContent=cols[i];if(i===3)span.className=trade.net<0?'negative':'positive';row.append(span);}
  table.append(row);
 }
}
function renderCurve(points,tradeCount,hasCapital){
 const root=$('perfPlot');root.replaceChildren();
 if(!tradeCount){const empty=document.createElement('div');empty.className='perfPlotEmpty';
  empty.textContent='Belum ada closed trade untuk ditampilkan.';root.append(empty);return;}
 const width=760,height=250,pad=30;
 const numbers=points.map(p=>p.value),min=Math.min(...numbers),max=Math.max(...numbers);
 const range=Math.max(1,max-min);
 const coords=points.map((p,i)=>[
  pad+(i/(points.length-1||1))*(width-pad*2),
  height-pad-((p.value-min)/range)*(height-pad*2)
 ]);
 const ns='http://www.w3.org/2000/svg',svg=document.createElementNS(ns,'svg');
 svg.setAttribute('viewBox','0 0 '+width+' '+height);svg.setAttribute('role','img');
 svg.setAttribute('aria-label',hasCapital?'Closed-trade balance curve':'Cumulative closed-trade net P&L curve');
 for(let i=0;i<4;i++){
  const line=document.createElementNS(ns,'line'),y=pad+i*(height-pad*2)/3;
  for(const [key,val] of Object.entries({x1:pad,y1:y,x2:width-pad,y2:y}))line.setAttribute(key,val);
  line.setAttribute('stroke','#344057');line.setAttribute('stroke-dasharray','3 6');svg.append(line);
 }
 const polyline=document.createElementNS(ns,'polyline');polyline.setAttribute('fill','none');
 polyline.setAttribute('stroke',numbers.at(-1)<numbers[0]?'#ed778a':'#70d8be');
 polyline.setAttribute('stroke-width','3');
 polyline.setAttribute('stroke-linecap','round');polyline.setAttribute('stroke-linejoin','round');
 polyline.setAttribute('points',coords.map(p=>p.join(',')).join(' '));
 svg.append(polyline);root.append(svg);
}
function perfPaper(){
 const trades=window.LumavunPerformance.paperTrades(account);
 perfRender(trades,'PAPER');
 safeText($('perfLastSync'),'Local · '+dateTime(Date.now()));
 safeText($('perfNotice'),'Paper Trading only. No broker data is mixed into these metrics; historical drawdown excludes intratrade equity fluctuations.');
}
async function perfMt5(){
 if(perfBusy)return;
 perfBusy=true;$('perfRefresh').disabled=true;
 safeText($('perfNotice'),'Meminta riwayat closed position 90 hari melalui MCP native MT5 (read-only)…');
 try{
  const data=await window.lumavun.performanceMt5History();
  if(!data||!Array.isArray(data.trades))throw Error('Invalid MT5 history response.');
  mt5Trades=data.trades;mt5Error=null;
  if($('perfSource').value==='MT5')perfRender(mt5Trades,'MT5');
  safeText($('perfLastSync'),dateTime(Date.now())+' · MT5 MCP');
  safeText($('perfNotice'),data.trades.length?
   'Riwayat MT5 dibaca. Hanya posisi tertutup yang memiliki angka Net P&L terpercaya ditampilkan.':
   'Tidak ada closed positions dengan rincian Net P&L lengkap; statistik tetap kosong.');
 }catch(error){
  mt5Trades=null;mt5Error=String(error.message||error);
  if($('perfSource').value==='MT5')perfRender([],'MT5');
  safeText($('perfNotice'),'Data MT5 tidak tersedia: '+mt5Error+'. Tidak ada angka estimasi yang dibuat.');
 }finally{perfBusy=false;$('perfRefresh').disabled=false;}
}
function perfPage(){
 if($('perfSource').value==='PAPER'){perfPaper();return;}
 if(mt5Trades){perfRender(mt5Trades,'MT5');return;}
 perfRender([],'MT5');
 safeText($('perfNotice'),mt5Error?
  'MT5: '+mt5Error:'Klik Refresh data untuk membaca riwayat broker via native MCP MT5.');
}
$('perfSource').addEventListener('change',perfPage);
$('perfRefresh').addEventListener('click',()=>$('perfSource').value==='PAPER'?perfPaper():perfMt5());
window.LumaDash={onPage(page){
 if(page==='office')officeUpdate();
 if(page==='performance')perfPage();
}};
officeUpdate();
})();
