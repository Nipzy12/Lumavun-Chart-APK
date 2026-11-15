"""MT5 local terminal integration. No broker login/passwords are accepted."""
import importlib
import math
import re
import time

TIMES={"1m":"TIMEFRAME_M1","5m":"TIMEFRAME_M5","15m":"TIMEFRAME_M15",
       "30m":"TIMEFRAME_M30","1h":"TIMEFRAME_H1","4h":"TIMEFRAME_H4","1d":"TIMEFRAME_D1"}
SYMBOL=re.compile(r"^[A-Za-z0-9._-]{1,32}$")
MAX_LOTS=.10

def as_dict(value):
    if value is None: return {}
    if hasattr(value,"_asdict"): return value._asdict()
    if isinstance(value,dict): return value.copy()
    return {}

def name(value):
    if not isinstance(value,str) or not SYMBOL.fullmatch(value):
        raise ValueError("Invalid MT5 broker symbol.")
    return value

def positive(value,label):
    try: n=float(value)
    except (TypeError,ValueError): raise ValueError(label+" must be positive.")
    if not math.isfinite(n) or n<=0: raise ValueError(label+" must be positive.")
    return n

class Terminal:
    def __init__(self,api=None,clock=time.time):
        self.api=api
        self.clock=clock
        self.ready=False
    def connect(self):
        if self.api is None:
            try: self.api=importlib.import_module("MetaTrader5")
            except ImportError as exc:
                raise RuntimeError("MetaTrader5 Python package missing. Install MetaTrader5 for this Python environment.") from exc
        if not self.ready:
            if not self.api.initialize():
                raise RuntimeError("Could not initialize local MT5 terminal; open MT5 and log into your broker.")
            self.ready=True
        return self.api
    def account(self):
        api=self.connect()
        a=as_dict(api.account_info());t=as_dict(api.terminal_info())
        if not a or not t: raise RuntimeError("No connected MT5 account/terminal.")
        mode=a.get("trade_mode")
        env="REAL" if mode==getattr(api,"ACCOUNT_TRADE_MODE_REAL",-1) else "DEMO" if mode==getattr(api,"ACCOUNT_TRADE_MODE_DEMO",-2) else "OTHER"
        return dict(account=str(a.get("login","")),server=str(a.get("server",""))[:120],
                    currency=str(a.get("currency",""))[:15],mode=env,
                    balance=float(a.get("balance",0)),equity=float(a.get("equity",0)),
                    freeMargin=float(a.get("margin_free",0)),
                    connected=bool(t.get("connected")),
                    terminalTradeAllowed=bool(t.get("trade_allowed")),
                    accountTradeAllowed=bool(a.get("trade_allowed")))
    def _symbol(self,symbol):
        api=self.connect();symbol=name(symbol)
        info=as_dict(api.symbol_info(symbol))
        if not info: raise ValueError("Broker symbol unavailable: "+symbol)
        if not info.get("visible"):
            if not api.symbol_select(symbol,True):
                raise RuntimeError("Cannot activate symbol in MT5 Market Watch.")
            info=as_dict(api.symbol_info(symbol))
        return info
    def symbols(self,query=""):
        api=self.connect();query=str(query or "")
        if len(query)>32 or (query and not SYMBOL.fullmatch(query)):
            raise ValueError("Invalid MT5 search text.")
        result=api.symbols_get()
        if result is None: raise RuntimeError("Cannot obtain MT5 symbol catalog.")
        out=[]
        for item in result:
            x=as_dict(item);symbol=x.get("name","")
            if not isinstance(symbol,str) or not SYMBOL.fullmatch(symbol): continue
            if query and query.lower() not in symbol.lower():continue
            out.append(dict(symbol=symbol,name=str(x.get("description",symbol))[:110],
                            status="ACTIVE" if x.get("visible") else "AVAILABLE",category="MT5"))
            if len(out)>=150:break
        return out
    def ticker(self,symbol):
        api=self.connect();info=self._symbol(symbol)
        tick=as_dict(api.symbol_info_tick(symbol))
        bid=float(tick.get("bid",0));ask=float(tick.get("ask",0))
        if not(math.isfinite(bid) and math.isfinite(ask) and bid>0 and ask>=bid):
            raise RuntimeError("No valid MT5 bid/ask for symbol.")
        stamp=int(tick.get("time",0));age=max(0,self.clock()-stamp)
        point=float(info.get("point",0) or 0)
        return dict(symbol=symbol,provider="MT5",bid=bid,ask=ask,lastPrice=(bid+ask)/2,
                    spreadPoints=(ask-bid)/point if point>0 else None,
                    tickTime=stamp,ageSeconds=age,verifiedBidAsk=True,
                    marketOpen=age<120,priceChangePercent=None,
                    highPrice=None,lowPrice=None,volume=None)
    def candles(self,symbol,interval="15m",limit=300):
        api=self.connect();self._symbol(symbol)
        if interval not in TIMES:raise ValueError("Invalid timeframe.")
        if not isinstance(limit,int) or isinstance(limit,bool) or limit<30 or limit>500:
            raise ValueError("Limit must be 30..500.")
        rates=api.copy_rates_from_pos(symbol,getattr(api,TIMES[interval]),0,limit)
        if rates is None:raise RuntimeError("No MT5 candle history.")
        out=[]
        for row in rates:
            x=as_dict(row)
            if not x:
                try:x={k:row[k] for k in ("time","open","high","low","close","tick_volume")}
                except (KeyError,TypeError,ValueError):continue
            try:
                values={k:float(x[k]) for k in ("open","high","low","close")}
                volume=float(x.get("tick_volume",0))
                stamp=int(x["time"])
            except (KeyError,ValueError,TypeError):continue
            if (stamp>0 and volume>=0 and all(math.isfinite(v) and v>0 for v in values.values())
                and values["high"]>=max(values.values()) and values["low"]<=min(values.values())):
                out.append({"time":stamp,**values,"volume":volume})
        return sorted(out,key=lambda b:b["time"])
    def positions(self):
        api=self.connect();rows=api.positions_get()
        if rows is None:raise RuntimeError("MT5 positions unavailable.")
        out=[]
        for item in list(rows)[:100]:
            x=as_dict(item)
            out.append(dict(ticket=str(x.get("ticket","")),symbol=str(x.get("symbol","")),
                            side="BUY" if x.get("type")==getattr(api,"POSITION_TYPE_BUY",0) else "SELL",
                            volume=float(x.get("volume",0)),entry=float(x.get("price_open",0)),
                            current=float(x.get("price_current",0)),sl=float(x.get("sl",0)),
                            tp=float(x.get("tp",0)),profit=float(x.get("profit",0))))
        return out
    def _request(self,symbol,side,volume,sl,tp):
        api=self.connect()
        if side not in ("BUY","SELL"):raise ValueError("Side must be BUY or SELL.")
        info=self._symbol(symbol);account=self.account()
        if (not account["connected"] or not account["terminalTradeAllowed"]
            or not account["accountTradeAllowed"]):
            raise RuntimeError("MT5 or broker account trading disabled.")
        volume=positive(volume,"Volume");sl=positive(sl,"Stop Loss");tp=positive(tp,"Take Profit")
        step=float(info.get("volume_step",0) or 0)
        lower=float(info.get("volume_min",0) or 0)
        upper=float(info.get("volume_max",0) or 0)
        if (volume>MAX_LOTS+1e-8 or volume<lower-1e-8 or
            (upper>0 and volume>upper+1e-8) or step<=0 or
            abs(volume/step-round(volume/step))>1e-6):
            raise ValueError("Lot exceeds broker limits, 0.10 cap, or lot step.")
        q=self.ticker(symbol)
        if q["ageSeconds"]>30:raise RuntimeError("MT5 quote is stale; do not trade.")
        price=q["ask"] if side=="BUY" else q["bid"]
        if (side=="BUY" and not(sl<price<tp)) or (side=="SELL" and not(tp<price<sl)):
            raise ValueError("SL/TP must bracket current entry in proper direction.")
        point=float(info.get("point",0) or 0)
        gap=max(0,int(info.get("trade_stops_level",0) or 0))*point
        # Broker evaluates SL/TP against current opposite-side closing price.
        check_price=q["bid"] if side=="BUY" else q["ask"]
        if min(abs(check_price-sl),abs(check_price-tp))<=gap+point*.1:
            raise ValueError("SL/TP violates broker stop-level distance.")
        filling=int(info.get("filling_mode",0))
        execution=int(info.get("trade_exemode",0))
        if filling & getattr(api,"SYMBOL_FILLING_FOK",1):
            mode=api.ORDER_FILLING_FOK
        elif filling & getattr(api,"SYMBOL_FILLING_IOC",2):
            mode=api.ORDER_FILLING_IOC
        elif execution!=getattr(api,"SYMBOL_TRADE_EXECUTION_MARKET",2):
            mode=api.ORDER_FILLING_RETURN
        else:raise RuntimeError("Unsupported MT5 symbol filling mode.")
        request=dict(action=api.TRADE_ACTION_DEAL,symbol=symbol,volume=volume,
                     type=api.ORDER_TYPE_BUY if side=="BUY" else api.ORDER_TYPE_SELL,
                     price=price,sl=sl,tp=tp,deviation=20,magic=700800,
                     comment="LUMAVUN manual",type_time=api.ORDER_TIME_GTC,type_filling=mode)
        return request,account,q
    def preview_order(self,symbol,side,volume,sl,tp):
        api=self.connect();req,account,q=self._request(symbol,side,volume,sl,tp)
        checked=as_dict(api.order_check(req))
        if not checked:raise RuntimeError("MT5 order_check returned no result.")
        if int(checked.get("retcode",-1))!=0:
            raise RuntimeError("Broker order_check rejected request (retcode "+str(checked.get("retcode"))+").")
        margin=api.order_calc_margin(req["type"],symbol,req["volume"],req["price"])
        if margin is None:raise RuntimeError("Broker margin calculation unavailable.")
        margin=positive(margin,"Required margin")
        if margin>account["freeMargin"]:
            raise RuntimeError("Insufficient free margin.")
        return dict(symbol=symbol,side=side,volume=req["volume"],sl=req["sl"],tp=req["tp"],
                    price=req["price"],spreadPoints=q["spreadPoints"],margin=margin,
                    account=account["account"],environment=account["mode"],
                    currency=account["currency"],tickTime=q["tickTime"],checked=True)
    def execute_order(self,symbol,side,volume,sl,tp,expected_account,expected_price):
        """Private bridge operation; not a callable MCP tool. Never automatically retry."""
        api=self.connect();req,account,q=self._request(symbol,side,volume,sl,tp)
        if account["account"]!=str(expected_account):
            raise RuntimeError("MT5 account switched since preview.")
        price=positive(expected_price,"Preview price")
        point=float(self._symbol(symbol).get("point",0) or 0)
        if point<=0 or abs(req["price"]-price)>point*20:
            raise RuntimeError("Quote moved since preview. Preview again.")
        # Final server-side preflight immediately before send.
        checked=as_dict(api.order_check(req))
        if not checked or int(checked.get("retcode",-1))!=0:
            raise RuntimeError("MT5 rejected final order_check; no order sent.")
        result=as_dict(api.order_send(req))
        if not result:raise RuntimeError("order_send returned nothing. Check MT5 trade history before retry.")
        code=int(result.get("retcode",-1))
        return dict(sent=True,filled=code==api.TRADE_RETCODE_DONE,retcode=code,
                    order=str(result.get("order",0)),deal=str(result.get("deal",0)),
                    price=float(result.get("price",0)),volume=float(result.get("volume",0)),
                    comment=str(result.get("comment",""))[:180],symbol=symbol,side=side)
    def close(self):
        if self.ready:
            self.api.shutdown()
        self.ready=False
