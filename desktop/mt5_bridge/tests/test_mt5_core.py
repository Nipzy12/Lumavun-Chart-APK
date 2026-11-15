"""Unit tests use a simulated MT5 Python API; they NEVER place real orders."""
import sys
import time
import types
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from mt5_core import Terminal

class FakeMT5:
    ACCOUNT_TRADE_MODE_DEMO=0
    ACCOUNT_TRADE_MODE_REAL=2
    TIMEFRAME_M15=15
    POSITION_TYPE_BUY=0
    TRADE_ACTION_DEAL=1
    ORDER_TYPE_BUY=0
    ORDER_TYPE_SELL=1
    ORDER_TIME_GTC=0
    ORDER_FILLING_FOK=0
    ORDER_FILLING_IOC=1
    ORDER_FILLING_RETURN=2
    SYMBOL_FILLING_FOK=1
    SYMBOL_FILLING_IOC=2
    SYMBOL_TRADE_EXECUTION_MARKET=2
    TRADE_RETCODE_DONE=10009
    def __init__(self):
        self.sent=[]
        self.last=None
        self.timestamp=1_700_000_000
        self.mode=0
        self.trade_allowed=True
        self.quote_bid=100.00
        self.quote_ask=100.02
    def initialize(self):return True
    def shutdown(self):return None
    def account_info(self):
        return dict(login=12345,server="Broker-Demo",currency="USD",trade_mode=self.mode,
                    balance=1000.0,equity=1000.0,margin_free=1000.0,trade_allowed=self.trade_allowed)
    def terminal_info(self):return dict(connected=True,trade_allowed=self.trade_allowed)
    def symbol_info(self,name):
        if name!="XAUUSDm":return None
        return dict(name=name,visible=True,point=.01,volume_step=.01,volume_min=.01,volume_max=100,
                    trade_stops_level=5,filling_mode=3,trade_exemode=2)
    def symbol_info_tick(self,name):return dict(bid=self.quote_bid,ask=self.quote_ask,time=self.timestamp)
    def symbols_get(self):return [dict(name="XAUUSDm",visible=True,description="Gold CFD")]
    def copy_rates_from_pos(self,symbol,tf,offset,count):
        return [dict(time=1_700_000_000+i*900,open=100+i*.01,high=101+i*.01,
                     low=99+i*.01,close=100+i*.01,tick_volume=30) for i in range(count)]
    def positions_get(self):
        return [dict(ticket=144,symbol="XAUUSDm",type=0,volume=.01,
                     price_open=100,price_current=101,sl=99,tp=102,profit=1)]
    def order_check(self,req):
        self.last=req
        return dict(retcode=0)
    def order_calc_margin(self,side,symbol,volume,price):return 20
    def order_send(self,req):
        self.sent.append(req)
        return dict(retcode=10009,order=44,deal=45,price=req["price"],volume=req["volume"],comment="done")

class MT5AdapterTests(unittest.TestCase):
    def setUp(self):
        self.api=FakeMT5()
        self.adapter=Terminal(self.api,clock=lambda:self.api.timestamp+1)
    def test_account_demo_and_real(self):
        self.assertEqual(self.adapter.account()["mode"],"DEMO")
        self.api.mode=2
        self.assertEqual(self.adapter.account()["mode"],"REAL")
    def test_broker_symbol_suffix_is_preserved(self):
        self.assertEqual(self.adapter.symbols("xau")[0]["symbol"],"XAUUSDm")
        self.assertEqual(self.adapter.ticker("XAUUSDm")["ask"],100.02)
        with self.assertRaises(ValueError):self.adapter.ticker("XAUUSD")
    def test_live_data_returns_actual_stub_rates_and_positions(self):
        self.assertEqual(len(self.adapter.candles("XAUUSDm","15m",100)),100)
        self.assertEqual(self.adapter.positions()[0]["ticket"],"144")
    def test_preview_checks_broker_but_never_sends_order(self):
        result=self.adapter.preview_order("XAUUSDm","BUY",.01,99,102)
        self.assertEqual(result["account"],"12345")
        self.assertEqual(result["environment"],"DEMO")
        self.assertEqual(len(self.api.sent),0)
    def test_guard_lot_and_stop_loss_geometry(self):
        with self.assertRaises(ValueError):self.adapter.preview_order("XAUUSDm","BUY",.11,99,102)
        with self.assertRaises(ValueError):self.adapter.preview_order("XAUUSDm","BUY",.01,101,102)
        with self.assertRaises(ValueError):self.adapter.preview_order("XAUUSDm","SELL",.01,101,102)
    def test_guard_stale_quote_and_disabled_trading(self):
        self.adapter.clock=lambda:self.api.timestamp+35
        with self.assertRaises(RuntimeError):self.adapter.preview_order("XAUUSDm","BUY",.01,99,102)
        self.adapter.clock=lambda:self.api.timestamp
        self.api.trade_allowed=False
        with self.assertRaises(RuntimeError):self.adapter.preview_order("XAUUSDm","BUY",.01,99,102)
    def test_manual_buy_sell_send_with_account_and_price_confirmation(self):
        result=self.adapter.execute_order("XAUUSDm","BUY",.01,99,102,"12345",100.02)
        self.assertTrue(result["filled"])
        self.assertEqual(self.api.sent[-1]["type"],0)
        result2=self.adapter.execute_order("XAUUSDm","SELL",.01,101.5,98,"12345",100)
        self.assertTrue(result2["filled"])
        self.assertEqual(self.api.sent[-1]["type"],1)
    def test_account_changed_or_quote_moves_no_order(self):
        with self.assertRaises(RuntimeError):self.adapter.execute_order("XAUUSDm","BUY",.01,99,102,"99999",100.02)
        with self.assertRaises(RuntimeError):self.adapter.execute_order("XAUUSDm","BUY",.01,99,102,"12345",95)
        self.assertEqual(len(self.api.sent),0)

if __name__=="__main__":unittest.main()
