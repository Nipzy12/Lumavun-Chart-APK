'use strict';
/* Manual terminal integration. AI/MCP tools have NO access to these handlers. */
(function(){
 const el=id=>document.getElementById(id);
 let currentAccount=null,currentPreview=null,pendingAction=false,previewTimer=null;
 function notice(message){
  el('mt5TradeMessage').textContent=String(message||'').slice(0,700);
 }
 function currency(value){
  if(!currentAccount||!Number.isFinite(Number(value)))return '—';
  return new Intl.NumberFormat('en-US',{style:'currency',currency:currentAccount.currency||'USD',
   maximumFractionDigits:2}).format(Number(value));
 }
 function dropPreview(){
  currentPreview=null;
  clearTimeout(previewTimer);
  el('mt5OrderPreview').hidden=true;
  el('mt5ConfirmationPhrase').value='';
  el('mt5Execute').disabled=true;
 }
 function renderLock(gate){
  const unlocked=!!gate?.unlocked;
  const badge=el('mt5LockBadge');
  badge.textContent=unlocked?'UNLOCKED · '+gate.environment:'LOCKED';
  badge.classList.toggle('unlocked',unlocked);
  el('mt5Preview').disabled=!currentAccount||!unlocked||pendingAction;
  el('mt5Unlock').disabled=!currentAccount||pendingAction;
  el('mt5Lock').disabled=!unlocked||pendingAction;
  el('mt5UnlockHint').textContent=currentAccount?
   'ENABLE '+currentAccount.mode+' '+currentAccount.account:'ENABLE DEMO [account]';
 }
 function renderAccount(a){
  const old=currentAccount;
  if(old&&(old.account!==a.account||old.mode!==a.mode)){
   dropPreview();el('mt5UnlockPhrase').value='';el('mt5AcceptReal').checked=false;
  }
  currentAccount=a;
  el('mt5Account').textContent=a.account;
  el('mt5Environment').textContent=a.mode;
  el('mt5Environment').className=a.mode==='REAL'?'real':'demo';
  el('mt5Server').textContent=a.server;
  el('mt5Balance').textContent=currency(a.balance);
  el('mt5Equity').textContent=currency(a.equity);
  el('mt5Margin').textContent=currency(a.freeMargin);
  el('mt5ConnectionTitle').textContent='MT5 '+a.mode+' · '+a.account;
  el('mt5ConnectionInfo').textContent=a.connected?
   'Broker '+a.server+' · '+(a.accountTradeAllowed&&a.terminalTradeAllowed?'Order permission detected':'Trading disabled in terminal or broker'):
   'Terminal connected to Python, but broker session is offline.';
 }
 function setBusy(busy){
  pendingAction=busy;
  for(const id of ['mt5Connect','mt5Refresh','mt5RefreshPositions','mt5OpenChart'])
   el(id).disabled=busy;
  el('mt5Execute').disabled=busy||!currentPreview;
  renderLock(window._mt5GateStatus||{unlocked:false});
 }
 async function refreshPositions(){
  const list=el('mt5Positions');list.textContent='Loading MT5 positions…';
  try{
   const positions=await window.lumavun.mt5Positions();
   list.replaceChildren();
   if(!positions.length){list.textContent='No open MT5 positions.';return;}
   for(const p of positions){
    const row=document.createElement('article');row.className='mt5Position';
    const title=document.createElement('div'),label=document.createElement('strong'),detail=document.createElement('small');
    label.textContent=p.symbol+' '+p.side+' '+p.volume+' lot';
    detail.textContent='Ticket '+p.ticket+' · Open '+p.entry+' · SL '+p.sl+' · TP '+p.tp;
    title.append(label,detail);
    const pnl=document.createElement('strong');pnl.textContent=currency(p.profit);
    pnl.style.color=Number(p.profit)>=0?'#72d4b0':'#f38d83';
    row.append(title,pnl);list.append(row);
   }
  }catch(error){list.textContent='Cannot read MT5 positions: '+String(error.message||error);}
 }
 async function refreshAccount(){
  const a=await window.lumavun.mt5Account();
  if(!a?.account||!['DEMO','REAL'].includes(a.mode))
   throw Error('A valid MT5 demo/real trading account is required.');
  renderAccount(a);
  const gate=await window.lumavun.mt5GateStatus();
  window._mt5GateStatus=gate;
  renderLock(gate);
  return a;
 }
 async function connect(){
  if(pendingAction)return;
  setBusy(true);
  try{
   await refreshAccount();
   await refreshPositions();
   notice('Connected to MT5. Trading stays locked until explicitly enabled for this account.');
  }catch(error){
   notice('MT5 connection unavailable: '+String(error.message||error));
   el('mt5ConnectionTitle').textContent='MT5 connection failed';
   currentAccount=null;dropPreview();renderLock({unlocked:false});
  }finally{setBusy(false);}
 }
 el('mt5Connect').addEventListener('click',connect);
 el('mt5Refresh').addEventListener('click',connect);
 el('mt5RefreshPositions').addEventListener('click',async()=>{
  if(pendingAction)return;
  setBusy(true);try{await refreshPositions();}finally{setBusy(false);}
 });
 el('mt5Unlock').addEventListener('click',async()=>{
  if(pendingAction||!currentAccount)return;
  setBusy(true);dropPreview();
  try{
   const a=await refreshAccount();
   const gate=await window.lumavun.mt5Unlock(el('mt5UnlockPhrase').value,el('mt5AcceptReal').checked);
   window._mt5GateStatus=gate;renderLock(gate);
   el('mt5UnlockPhrase').value='';
   notice('Trading unlocked temporarily for '+a.mode+' account '+a.account+'. Every order still needs preview and confirmation.');
  }catch(error){notice('Trading remains locked: '+String(error.message||error));}
  finally{setBusy(false);}
 });
 el('mt5Lock').addEventListener('click',async()=>{
  dropPreview();window._mt5GateStatus=await window.lumavun.mt5Lock();
  renderLock(window._mt5GateStatus);
  notice('Trading locked. No new BUY/SELL may be submitted.');
 });
 el('mt5TradeForm').addEventListener('submit',async event=>{
  event.preventDefault();
  if(pendingAction)return;
  dropPreview();setBusy(true);
  try{
   await refreshAccount();
   const args={
    symbol:el('mt5Symbol').value.trim(),side:el('mt5Side').value,
    volume:Number(el('mt5Volume').value),sl:Number(el('mt5SL').value),tp:Number(el('mt5TP').value)
   };
   const envelope=await window.lumavun.mt5Preview(args);
   currentPreview=envelope;
   const p=envelope.preview;
   el('mt5PreviewTitle').textContent='BROKER CHECKED · '+p.environment+' '+p.side+' '+p.symbol;
   el('mt5PreviewText').textContent=
    'Account: '+p.account+' ('+p.environment+')\n'+
    'Order: '+p.side+' '+p.volume+' lot · Estimated entry '+p.price+'\n'+
    'Stop Loss: '+p.sl+' · Take Profit: '+p.tp+'\n'+
    'Estimated margin: '+p.margin+' '+p.currency+' · Spread: '+p.spreadPoints+' points\n'+
    'This is NOT an execution guarantee. Market prices can change.';
   el('mt5ConfirmationPhrase').placeholder='CONFIRM '+p.environment+' '+p.side+' '+p.account;
   el('mt5OrderPreview').hidden=false;
   el('mt5Execute').disabled=false;
   const ttl=Math.max(0,envelope.expiresAt-Date.now());
   previewTimer=setTimeout(()=>{dropPreview();notice('MT5 order preview expired. Run broker check again.');},ttl);
   notice('Broker order_check accepted. Review details and type the exact confirmation phrase before sending.');
  }catch(error){notice('Order preview rejected: '+String(error.message||error));}
  finally{setBusy(false);}
 });
 el('mt5Execute').addEventListener('click',async()=>{
  const envelope=currentPreview;if(pendingAction||!envelope)return;
  const p=envelope.preview,phrase=el('mt5ConfirmationPhrase').value;
  const expected='CONFIRM '+p.environment+' '+p.side+' '+p.account;
  if(phrase!==expected){
   dropPreview();notice('Incorrect execution phrase. Preview again.');return;
  }
  if(!window.confirm('SEND '+p.side+' '+p.volume+' LOT '+p.symbol+' to '+p.environment+
   ' account '+p.account+'?\nThis submits a REAL broker order on REAL accounts.')){
   dropPreview();notice('Order canceled before submission.');return;
  }
  const token=envelope.token;dropPreview();setBusy(true);
  try{
   const result=await window.lumavun.mt5Execute(token,phrase);
   if(result.filled){
    notice('MT5 ORDER FILLED: '+result.side+' '+result.symbol+' · ticket '+result.order+
     ' · deal '+result.deal+' · price '+result.price+'. Verify position in MT5.');
   }else{
    notice('MT5 order_send returned retcode '+result.retcode+
     '. Broker did not confirm full fill. Check MT5 orders/trade history before retry.');
   }
   await refreshPositions();
  }catch(error){
   notice('MT5 execution not confirmed: '+String(error.message||error)+
    '. Check MT5 history before placing another order. NO automatic retry.');
  }finally{setBusy(false);}
 });
 for(const id of ['mt5Symbol','mt5Side','mt5Volume','mt5SL','mt5TP']){
  el(id).addEventListener('input',()=>{if(currentPreview)dropPreview();});
  el(id).addEventListener('change',()=>{if(currentPreview)dropPreview();});
 }
 el('mt5OpenChart').addEventListener('click',async()=>{
  if(pendingAction)return;
  const symbol=el('mt5Symbol').value.trim();
  if(!/^[A-Za-z0-9._-]{1,32}$/.test(symbol))return notice('Enter exact MT5 broker symbol first.');
  setBusy(true);
  try{
   const q=await window.lumavun.ticker('MT5',symbol);
   if(!(q.bid>0&&q.ask>=q.bid))throw Error('No valid MT5 broker quote.');
   const item=normalizeWatchItem({provider:'MT5',symbol});
   if(!item)throw Error('Invalid MT5 broker symbol.');
   if(!state.watchlist.some(i=>i.provider==='MT5'&&i.symbol===symbol)){
    if(state.watchlist.length>=40)throw Error('Watchlist is full.');
    state.watchlist.push(item);
   }
   state.provider='MT5';state.symbol=symbol;
   save();renderWatchlist();showPage('chart');refresh(true);
  }catch(error){notice('MT5 chart unavailable: '+String(error.message||error));}
  finally{setBusy(false);}
 });
 async function showMcpConfig(){
  try{
   const command=await window.lumavun.mt5McpConfig();
   el('mt5McpCommand').value=command.command+' '+command.args.map(v=>
    v.includes(' ')?'"'+v.replace(/"/g,'')+'"':v).join(' ');
  }catch(error){el('mt5McpCommand').value='MCP configuration unavailable: '+String(error.message||error);}
 }
 showMcpConfig();
 renderLock({unlocked:false});
})();
