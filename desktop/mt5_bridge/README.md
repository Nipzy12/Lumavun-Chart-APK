# LUMAVUN Chart Windows: MT5 Terminal Bridge (v0.8)

This is a local integration with a running MetaTrader 5 desktop terminal,
using the official MetaTrader5 Python package.

## Installation

1. Install the MetaTrader 5 Windows terminal from your broker. Log in to a
   dedicated DEMO account. Ensure its server connection is active.
2. Install Python 3.11+ x64 with the Windows Python launcher (py).
3. Install the Python bridge dependencies:
   py -3 -m pip install MetaTrader5
   py -3 -m pip install "mcp>=2,<3"  (optional for external MCP clients)
4. Launch LUMAVUN Chart Windows, open MT5 Trading, and click Connect MT5.
   Data will be retrieved through a private local Python subprocess.
5. Select provider MT5 for direct OHLC and bid/ask market data in charts.

LUMAVUN does not ask for MT5 username/password. The bridge attaches to the
already logged-in local terminal. No cloud server receives account credentials.

## MCP server (read only)

The EXE includes mt5_bridge/bridge.py as an unpacked extra resource. The
MT5 Trading page shows the exact path and command for its installation.

Example MCP stdio configuration (replace path with installed file location):

{
  "mcpServers": {
    "lumavun-mt5": {
      "command": "py",
      "args": ["-3", "C:\\Path\\To\\LUMAVUN\\resources\\mt5_bridge\\bridge.py", "--mcp"]
    }
  }
}

The MCP server exposes ONLY these tools:
- account: account status, currency, equity/balance
- symbols: broker symbol catalog
- ticker: broker bid/ask, spread, tick freshness
- candles: OHLC up to 500 candles
- positions: open positions, floating P/L
- preview_order: broker order_check with margin estimate (NO order_send)

MCP has NO execute_order tool. The separate Electron private bridge uses
stdin/stdout JSONL requests to implement manual order execution.

## Trading controls

LUMAVUN trading is DISABLED by default. Every time you want to submit orders:
- Open MT5 Trading and connect the exact terminal account.
- Type ENABLE DEMO <account> or ENABLE REAL <account> to unlock for 15 minutes.
- For REAL you must also check the explicit live-money warning.
- Enter an exact broker symbol, BUY or SELL, lot size, absolute Stop Loss and
  Take Profit. Lots are capped at 0.10 in this first release.
- Preview must pass broker order_check, stop distance, trade permissions,
  bid/ask freshness, volume step and free margin.
- Review the one-time 30-second preview and type
  CONFIRM DEMO BUY <account> (or the exact REAL/SELL variation).
- Click Execute Order. LUMAVUN never invokes order_send automatically,
  never retries timed-out orders, and never lets an AI/MCP tool bypass the UI.

SL and TP are absolute price levels. Closing positions is not available in v0.8.
Broker may reject the request, prices can move, partial fills may occur,
and profit is never guaranteed.

## Known limitations

- The EXE includes the bridge code but NOT Python or the official MetaTrader5
  package. These must be installed explicitly on the target Windows computer.
- A local logged-in MT5 terminal and broker connection are required.
- The MT5 terminal may have trading disabled or a market closed. Live
  execution depends on broker trading permissions and specifications.
- GitHub Actions runs Python adapter tests with a FAKE simulated MT5 API.
  It cannot verify connectivity to a real terminal or execute broker orders.
- Neither Python SDK v2 MCP interoperability nor broker-specific symbols,
  filling modes or stop levels are verified on the user's computer in CI.
- Do not use on a live account before testing the entire flow on DEMO.
