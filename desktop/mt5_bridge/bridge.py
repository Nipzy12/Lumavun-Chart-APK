"""LUMAVUN MT5 stdio bridge and separate read-only MCP server.

Electron --jsonl: requests over private subprocess pipes. Its execute_order method
is only reachable by Electron main process after an explicit UI confirmation.
MCP --mcp: exposes read-only queries and broker order_check, never order_send.
"""
import json
import sys
from mt5_core import Terminal

TERMINAL = Terminal()
JSONL_METHODS = {"account", "symbols", "ticker", "candles", "positions",
                 "preview_order", "execute_order"}

def jsonl():
    try:
        for raw in sys.stdin:
            packet = None
            try:
                if len(raw) > 65536:
                    raise ValueError("MT5 request too large.")
                packet = json.loads(raw)
                if not isinstance(packet,dict):
                    raise ValueError("Invalid request object.")
                method = packet.get("method")
                if method not in JSONL_METHODS:
                    raise ValueError("Unsupported MT5 method.")
                args = packet.get("params",{})
                if not isinstance(args,dict):
                    raise ValueError("MT5 request params must be an object.")
                result = getattr(TERMINAL,method)(**args)
                answer = {"id": packet.get("id"),"ok":True,"result":result}
            except Exception as exc:
                answer = {"id":packet.get("id") if isinstance(packet,dict) else None,
                          "ok":False,"error":str(exc)[:350]}
            sys.stdout.write(json.dumps(answer,separators=(",",":"),allow_nan=False)+"\n")
            sys.stdout.flush()
    finally:
        TERMINAL.close()

def mcp():
    try:
        from mcp.server import MCPServer
    except ImportError as exc:
        raise SystemExit("MCP SDK missing. Run: py -3 -m pip install \"mcp>=2,<3\"") from exc
    server=MCPServer("LUMAVUN-MT5-READ-ONLY")
    @server.tool()
    def account() -> dict:
        """Read current MT5 account type, balance, equity and permissions."""
        return TERMINAL.account()
    @server.tool()
    def symbols(query: str = "") -> list[dict]:
        """Search symbols available on the linked broker MT5 terminal."""
        return TERMINAL.symbols(query)
    @server.tool()
    def ticker(symbol: str) -> dict:
        """Read live MT5 bid, ask, spread and freshness of one broker symbol."""
        return TERMINAL.ticker(symbol)
    @server.tool()
    def candles(symbol: str, interval: str = "15m", limit: int = 300) -> list[dict]:
        """Read actual MT5 OHLC bars for a broker symbol and timeframe."""
        return TERMINAL.candles(symbol,interval,limit)
    @server.tool()
    def positions() -> list[dict]:
        """Read open MT5 positions without changing them."""
        return TERMINAL.positions()
    @server.tool()
    def preview_order(symbol: str, side: str, volume: float, sl: float, tp: float) -> dict:
        """Broker order_check and margin estimate only. Never sends an order."""
        return TERMINAL.preview_order(symbol,side,volume,sl,tp)
    try:
        server.run(transport="stdio")
    finally:
        TERMINAL.close()

if __name__=="__main__":
    if len(sys.argv)!=2 or sys.argv[1] not in ("--jsonl","--mcp"):
        raise SystemExit("Usage: py -3 bridge.py --jsonl | --mcp")
    if sys.argv[1]=="--jsonl":
        jsonl()
    else:
        mcp()
