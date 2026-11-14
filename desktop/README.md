# LUMAVUN Chart Desktop: Windows Preview v0.4

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

## AI Analyst in Windows Preview v0.3

AI Analyst now has two model backends:
- **Ollama local**: Install Ollama separately on Windows, run PowerShell command ollama pull qwen2.5:3b, then open the EXE, choose Ollama, click Cari model Ollama, select a model, and Hubungkan model. The app connects only to http://127.0.0.1:11434. No LLM weights are bundled with the EXE.
- **Custom OpenAI-compatible API**: Configure an HTTPS base URL ending /v1, model name, and optional API key. The request uses the conventional chat/completions endpoint. Credentials are held in Electron main-process memory only, never written to disk, and are not returned to the UI. Custom API sends the selected market snapshot to your configured remote service.

Click **Analisis chart saat ini** when the provider has loaded at least 60 verified candles. The main process keeps a recent market-data cache (2-minute freshness requirement); the renderer cannot upload arbitrary candle data for AI requests. The actual prompt includes 50 OHLC candles, EMA20/50, RSI14 and nearby support/resistance. Analysis is on-demand only and never places broker orders.

The model must reply with JSON. Responses are treated as untrusted text. Incoherent entry, stop-loss and take-profit values are downgraded to WAIT and are never sent to real or virtual execution. There is no win-rate guarantee. Ollama inference itself can run offline after the model has been downloaded, but fresh market OHLC still requires the associated provider's connectivity.

Known gaps: LLM vision/screenshot analysis, AI auto-bot, fully offline historical OHLC, replay data, and full Android AI feature parity. Live Ollama and third-party API compatibility must be tested on a Windows machine after packaging. Unit tests and packaging checks cannot prove functional interactive integration.


## v0.4 Vision: screenshot-assisted on-demand AI

- Configure Ollama with a vision-capable model such as qwen2.5vl:3b (download separately using PowerShell: ollama pull qwen2.5vl:3b), or a custom HTTPS OpenAI-compatible API supporting image input.
- After verified OHLC history loads, connect the model and click Pratinjau gambar chart. The preview comes from Lightweight Charts canvas, not the whole desktop or an Electron window, and has NOT been sent yet.
- After reviewing the image, click Analisis dengan Vision. The model receives the PNG and bounded market snapshot. This is the only action that sends the image to local Ollama or remote API.
- PNG stays in memory only. The Electron main process checks fresh cache, PNG header, dimensions and a strict 4 MB binary limit.
- Ollama receives image via messages user images[] base64 field. Custom OpenAI-compatible endpoint receives user content[] with image_url PNG data URI.
- Invalid trade levels produce WAIT. No live broker order, AI auto-execution, or automatic paper trading.
- Cloud Vision transmits the screenshot and OHLC to the configured provider. Do not use it if that provider should not see your chart.
- Model capabilities vary; a text-only model may fail. Ollama model listing alone does not prove Vision support.

### Windows UI smoke test

GitHub Actions runs a Playwright Electron window test before packaging. It checks app startup, chart canvas rendering, local watchlist, AI Vision controls, and provider/model switches. It uploads a screenshot as artifact LUMAVUN-Windows-UI-Smoke. This does not validate external market data feeds or live Ollama inference. Windows EXE is unsigned.
