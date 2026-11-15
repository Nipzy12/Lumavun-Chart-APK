'use strict';
const {randomBytes}=require('node:crypto');
const PREVIEW_TTL=30000;
const UNLOCK_TTL=900000;
class MT5TradeGate{
 constructor({clock=Date.now,createToken=()=>randomBytes(24).toString('hex')}={}){
  this.clock=clock;this.createToken=createToken;this.unlocked=null;this.pending=null;
 }
 getStatus(){
  if(this.unlocked&&this.clock()>this.unlocked.expiresAt)this.lock();
  return {unlocked:!!this.unlocked,account:this.unlocked?.account||null,
   environment:this.unlocked?.mode||null,expiresAt:this.unlocked?.expiresAt||null};
 }
 lock(){this.unlocked=null;this.pending=null;}
 unlock(account,phrase,acceptReal=false){
  if(!account||!account.account||!['DEMO','REAL'].includes(account.mode))throw Error('MT5 demo/real account required.');
  if(!account.connected)throw Error('MT5 broker account is disconnected.');
  const expected='ENABLE '+account.mode+' '+account.account;
  if(phrase!==expected)throw Error('Incorrect account unlock phrase.');
  if(account.mode==='REAL'&&acceptReal!==true)throw Error('Real account risk acknowledgment required.');
  this.unlocked={account:String(account.account),mode:account.mode,expiresAt:this.clock()+UNLOCK_TTL};
  this.pending=null;
  return this.getStatus();
 }
 assertUnlocked(account){
  const state=this.getStatus();
  if(!state.unlocked)throw Error('MT5 trading is locked. Unlock the exact account first.');
  if(state.account!==String(account.account)||state.environment!==account.mode){
   this.lock();throw Error('MT5 account or environment changed. Trading locked.');
  }
 }
 createPreview(account,preview){
  this.assertUnlocked(account);
  if(!preview?.checked||String(preview.account)!==String(account.account)||
     preview.environment!==account.mode){
    throw Error('MT5 broker preview does not match connected account.');
  }
  const token=this.createToken();
  this.pending={token,preview,expiresAt:this.clock()+PREVIEW_TTL};
  return {token,expiresAt:this.pending.expiresAt,preview};
 }
 consume(account,token,phrase){
  this.assertUnlocked(account);
  const pending=this.pending;
  // Disarm before validating, so accidental double-clicks cannot duplicate orders.
  this.pending=null;
  if(!pending||token!==pending.token||this.clock()>pending.expiresAt)
    throw Error('Order preview is missing or expired. Preview again.');
  const p=pending.preview;
  if(String(p.account)!==String(account.account)||p.environment!==account.mode)
    throw Error('Account changed after preview.');
  if(phrase!=='CONFIRM '+account.mode+' '+p.side+' '+account.account)
    throw Error('Incorrect execution confirmation phrase. Preview again.');
  return {symbol:p.symbol,side:p.side,volume:p.volume,sl:p.sl,tp:p.tp,
   expected_account:String(account.account),expected_price:p.price};
 }
}
module.exports={MT5TradeGate,PREVIEW_TTL,UNLOCK_TTL};
