# LUMAVUN Chart Desktop: Windows Preview v0.7

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


## Windows Desktop v0.5: multipage + in-app AI installation

The desktop interface is now organized into five navigable pages: **Chart**, **AI Analyst**, **AI Models**, **Paper Trading**, and **Settings**. The watchlist remains visible in the sidebar and Exness gateway configuration lives on the Settings page. All pages use local views inside the Electron app, not separate browser tabs or internet pages.

### Install and use AI models without PowerShell

1. Open **AI Models** in LUMAVUN Chart.
2. Click **Install Ollama via WinGet** and explicitly approve installation. The app launches the Windows WinGet package ID **Ollama.Ollama** with a fixed exact-match command, without shell interpolation.
3. After installation, click **Jalankan Server** (if Ollama is not yet online), then **Refresh Status**.
4. Pick one of the curated models:
   - qwen2.5:1.5b: a lightweight text-only model
   - qwen2.5:3b: a medium text-only model
   - qwen2.5vl:3b: a Vision-capable model for chart screenshots
5. Click **Install Model** and approve the potentially multi-gigabyte download. Progress and cancellation are available inside AI Models. Model installation uses the Ollama local API **POST /api/pull**, not arbitrary shell commands.
6. Once installed, click **Use in Analyst** to switch to AI Analyst and choose the installed model, then click **Hubungkan model**. Chart data is still read-only.

The Windows EXE **does not bundle Ollama itself or any model weights**. Instead, LUMAVUN installs the official Windows runtime through WinGet and downloads user-selected model files into Ollama's local storage. This keeps the LUMAVUN EXE small relative to bundled model weights, supports updates, and allows model inference to run offline once downloaded. Installation/downloads need internet, sufficient storage and RAM; local inference speed depends on hardware.

If WinGet is missing, the app reports that Windows App Installer is required. A Windows 10 22H2 or newer environment is recommended by Ollama. If the runtime is installed in a non-standard directory and is not serving the loopback API, automatic startup through the Settings page may not locate it. The model manager never accepts arbitrary executable paths or shell arguments from the UI.

### Security and verification

The model-installer IPC exposes only fixed operations to detect the local runtime, run WinGet for Ollama.Ollama, start a standard user-local Ollama server, pull a validated model name, or cancel the active download. AI model data is fetched only from http://127.0.0.1:11434. No file/desktop access is granted to model installers.

Existing API keys remain in main-process memory, and neither AI inference nor model installation can submit broker orders.

GitHub Actions runs unit tests for the installer, Windows Electron multipage UI smoke tests, and NSIS/portable EXE packaging. Live runtime installation and model downloads must still be validated manually on an actual Windows device; mock tests never install programs during CI.


## SUPERVIEW v0.7: Professional multi-panel chart workspace

The Chart page has been rebuilt as an original **TradingView-inspired** desktop terminal, preserving LUMAVUN's name and independent code. It is not a reproduction of TradingView branding or the proprietary Supercharts platform. A 1:1 implementation of all TradingView features, broker links, licensed data feeds, and Pine execution is not available in this release.

Working controls in this initial workspace:
- Dense Supercharts-style symbol/timeframe/indicator toolbar, chart-style controls (candles, line, area), watchlist and actual market-details pane, theme, fullscreen and right-pane toggle.
- EMA20/50, SMA200, Bollinger Bands 20/2, anchored-to-loaded-data VWAP and optional volume histogram via Lightweight Charts 5 public API.
- Price crosshair OHLC strip, visible-range presets, indexed-% Binance USDT comparison overlay.
- Interactive horizontal lines, two-point trendlines, Fibonacci levels and price annotations. Undo/redo, Object Tree and chart-specific local persistence.
- Local, selected-symbol price threshold alerts checked every refresh while the desktop application is running (no OS/cloud notifications when closed).
- EMA 9/21 long/flat spot strategy test on loaded real OHLC close prices, assuming $1000, 10 bps fee per side, no spread/slippage or tick execution. These results are not a historical verified backtest across missing data or proof of profits.
- Notes per symbol/timeframe and saved chart drawings. Export PNG only through a user-approved save dialog with validated PNG bounds.

### Feature boundaries and non-parity
There is no Pine Script runtime or compatibility with published community Pine scripts, deep historical replay, integrated exchange/broker order management, proprietary screener/news/calendar data, social features, cloud watchlist syncing, or the complete TradingView indicator/drawing catalog. Neither a visual resemblance nor individual indicators imply full TradingView capability. Existing LUMAVUN AI and virtual paper trading remain on separate pages; they do not submit real orders.

This initial chart workspace uses data providers already implemented in the repository. When a market feed is inaccessible, the chart shows an error, not invented candles. Watchlist and alerts are local only. Screenshot artifacts from CI may include explicitly labelled synthetic fixtures for UI testing, not live quotes. Always independently verify market prices.


### Library attribution

LUMAVUN Superview is independent software. Charts use the open-source Lightweight Charts™ library, with required attribution to TradingView. The desktop package includes `NOTICE` and the app footer links to https://www.tradingview.com/. TradingView names and product branding do not represent an endorsement of LUMAVUN.


## v0.7 TradingView-like interface refinement

This is a visual and interaction pass targeting actual Supercharts spatial structure, using official UI anatomy references: https://www.tradingview.com/support/solutions/43000746464-getting-started-with-supercharts/. The result is original LUMAVUN UI rather than a copied TradingView product.

- Chart is now dominant. The collapsible bottom dock starts closed, taking only a 34px tab row. When opened, it can be resized by dragging its separator.
- Compact 40px top title bar with five LUMAVUN pages inside, rather than a tall separate nav row.
- Original thin-stroke SVG icon set for top and left toolbars. A 42px right utility rail provides working links to watchlist, alerts, drawings, AI, paper trading, local models, and application settings.
- Real symbol search dialog (Ctrl+K), with provider tabs, stored-watchlist/common-symbol suggestions, and verified quote lookup before changing the active symbol. Suggestions have **no fabricated quote prices**.
- Right watchlist shows Last and Chg% columns populated from **actual verified provider quotes** (up to the first 12 rows, rate-limited to a 2-minute cache), and stays blank/dash when data cannot be read. Drag its left separator to resize (235-480px).
- Chart appearance popover: grid visibility, symbol watermark, normal/log right scale, UTC/device clock label, real candle color controls, reset settings.
- Smaller transparent OHLC overlay in the top corner of the chart, more uniform spacing, true central candle area, right-side watchlist, and compact chart status bar.
- Dock tabs and right rail open real local tool panes. Controls preserve watchlist/dock widths, dock open/collapsed state, indicators, drawings, and appearance preferences in local application storage.
- Chart screenshot export, AI Vision, offline Ollama management, paper trading and Exness gateway still exist on their own pages.

Functional scope limitations remain: Lightweight Charts is a lower-level charting library; unlike TradingView's licensed Advanced Charts it does **not** ship a Pine Script interpreter, built-in advanced drawing toolbox, broker order tools, library of proprietary indicators, continuous historical replay, social features or cloud syncing. UI similarity does not imply such features exist. Model and broker connections are only validated at runtime when available, with no fake data.

The GitHub Actions Electron smoke test checks actual layout elements, modal symbol search, settings, dock, model and paper pages, and exports a screenshot with explicitly labelled synthetic chart fixtures. No broker orders are placed.
