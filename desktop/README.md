# LUMAVUN Chart Desktop: Windows Preview v0.2

This is a standalone Electron app **ported from the LUMAVUN Chart Android codebase** (Kotlin + Jetpack Compose). It is not a binary APK-to-EXE converter, and **not yet at feature parity** with Android.

## Functional scope

- Windows packaging as unsigned NSIS Setup EXE or Portable EXE via GitHub Actions.
- Price chart: Binance USDT Spot, BiQuote public endpoints, or a user-supplied **Exness HTTPS gateway**. If data is missing, an error appears instead of invented quotes.
- 1m, 5m, 15m, 30m, 1h, 4h, 1d timeframes; candlesticks, EMA20/50, RSI14, support/resistance.
- Watchlist saved locally, scoped to each provider; symbols are validated with a live quote before being added.
- Technical Insight: **deterministic local technical-analysis engine**, using EMA/RSI/support-resistance, **NOT an LLM**. Descriptive only, not a guaranteed setup.
- Manual paper trading: **Binance USDT only**, synthetic LONG/SHORT trades, up to 3 positions, risk sizing, user-set SL/TP, modeled fee 10 bps per side and slippage 2 bps per side. Account and history saved locally.
- Paper position SL/TP is checked **only for the selected Binance symbol on refresh (45 seconds)**. Other symbols are not automatically tracked until viewed; price gaps or intraperiod touches may be missed. This is **not** tick-level simulation, verified backtest, brokerage execution, or proof of profitability.

## Market adapter contracts

- Binance: public GET /api/v3/klines and GET /api/v3/ticker/24hr. Binance Spot USDT only in this preview.
- BiQuote (Android parity attempt): GET https://biquote.io/api/{SYMBOL} and GET /api/{SYMBOL}/ohlc?interval=15m&limit=300. Runtime availability must be tested; if blocked it shows errors.
- Exness: **no claimed universal direct Exness market API**. Configure an owned HTTPS gateway implementing:
  - GET /api/symbols returning {"symbols":[{"symbol":"XAUUSD","status":"TRADING"}]}
  - GET /api/candles?symbol=XAUUSD&interval=15m&limit=300 returning {"candles":[{"openTime":1730000000000,"open":2700,"high":2710,"low":2690,"close":2705,"volume":100}]}
  - GET /api/quote?symbol=XAUUSD returning {"symbol":"XAUUSD","bid":2704.9,"ask":2705.1,"last":2705,"priceChangePercent":0.1}
  - Optional Authorization: Bearer SESSION_TOKEN header on gateway requests only.
  - The gateway must return **real authorized account inventory and actual quotes**, not test/fallback catalog. Missing or non-trading symbols are blocked. Token remains in main-process memory, not disk; reconfigure after restart. HTTPS public DNS names only; HTTP redirects denied.
  - Example numeric prices above are **JSON contract examples only**, not executable fixtures or live market quotes.

## Still missing from Android

Full AI model orchestration and Vision, multi-provider unified symbol discovery UI, BiQuote SignalR stream, Exness WS stream, paper auto-bot/event-risk gates, platform notifications, Android database migration, and exact Android screen fidelity. Real broker order submission is intentionally unavailable.

## Build and download

Open **GitHub Actions → Build LUMAVUN Windows EXE**. Trigger on merge/push or manually. Download **LUMAVUN-Chart-Windows-EXE** from the successful run, extract, then use either Setup or Portable. A Windows SmartScreen prompt is possible because binaries are **unsigned**.

Local build (Node.js 22 and Windows):

    cd desktop
    npm install
    npm run check:syntax
    npm test
    npm run dist:win

## Security

Electron sandbox, context isolation and disabled Node integration. Renderer uses fixed IPC methods, not arbitrary HTTP requests. External requests originate in main process using fixed public provider hosts or validated user-supplied gateway. No brokerage order execution.
