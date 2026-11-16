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


## v0.8: Direct local MetaTrader 5 market data, read-only MCP and manual order ticket

- MT5 provider added to chart Symbol Search, watchlist and candlesticks. It reads directly from a locally running, logged-in MetaTrader 5 Windows terminal using the official MetaTrader5 Python integration. Exact broker symbol suffixes are preserved.
- New MT5 Trading page shows DEMO/REAL account, broker, balance, equity, free margin, broker open positions and a manually gated BUY/SELL order ticket.
- Initial installation requires Windows x64 MetaTrader 5, Python 3 x64 with the py launcher, and py -3 -m pip install MetaTrader5. The EXE includes Python bridge scripts as an extra resource, not the Python runtime or third-party package.
- Optional MCP SDK: py -3 -m pip install "mcp>=2,<3". The app shows a local stdio launch command. Tools: account, symbols, ticker, candles, positions and preview_order. The MCP server does NOT expose order_send / execution.
- Live order safeguards: default LOCKED, explicit environment/account unlock, real-account risk acknowledgment, max 0.10 lot, required absolute SL/TP, broker order_check and margin calculation, 30-second single-use preview, separate confirmation phrase, account binding, quote-movement guard, and zero automatic retries.
- AI Analyst uses MT5 provider candles when explicitly selected but cannot directly execute MT5 trades via MCP. Existing paper trading is separate.
- GitHub CI tests a mocked broker, not a real MT5 installation. Live MT5 connection and broker execution require a dedicated demo account on the user's Windows device. Do not test this workflow on a live-funded account first.
- Bridge installation and operational details: mt5_bridge/README.md.


## v0.9: Authenticated internal MCP HTTP server with API-key URL connection

MetaTrader 5 build 6060+ DOES expose a native MCP server and an API key in
Tools > Options > MCP. Its terminal endpoint is typically
http://127.0.0.1:22346/mcp. The MT5 MCP API key is not the broker password.
LUMAVUN also offers its separate built-in gateway, which generates its own
256-bit session key. These two servers/keys are distinct.

### Start a local MCP gateway and connect it to LUMAVUN

1. Install MT5 for Windows with Python 3 x64 and the MetaTrader5 package,
   and log into a broker demo terminal as described in v0.8.
2. Open MT5 Trading, scroll to Internal MCP HTTP Gateway.
3. Click Jalankan Server Internal. The gateway binds only to 127.0.0.1
   and selects a free port, for example http://127.0.0.1:48239/mcp.
4. Click Hubungkan Server Lokal to configure LUMAVUN to use that address.
   Click Tampilkan Key Lokal (or Salin Key) only when a user explicitly
   needs the generated key for another MCP client. It is held only in RAM
   and rotates every time the server restarts. It is never stored on disk.
5. Click Uji Data Akun MCP to test actual broker connectivity. A successful
   MCP handshake does not prove MT5 terminal connectivity.
6. Enter an exact broker symbol and click Buka Simbol di Chart MCP, or use
   MT5 via MCP Server from Symbol Search/watchlist. AI Analyst can analyze
   MCP provider candles, but cannot send trades.

### Connect a different internal server address

Enter its Streamable HTTP endpoint URL ending in /mcp and a Bearer API key,
then click Hubungkan URL + Key. LUMAVUN validates MCP initialize and tools/list
and requires ticker and candles tool names before enabling the MCP provider.
Only six read-only tool names are recognized; all requests have strict timeouts
and reject redirects. Plain HTTP is permitted only for loopback addresses.
LAN or other remote endpoints must use HTTPS with a trusted certificate.
To expose a separate MT5 server over a LAN, configure an independent secure
reverse proxy with TLS and a network allowlist; LUMAVUN will NOT expose its
own built-in server beyond the local machine.

MCP HTTP compatibility: stateless JSON-RPC POST to /mcp for initialize,
ping, tools/list, tools/call and notifications. JSON replies; not SSE/event
streaming, OAuth, or stateful MCP sessions. MCP clients that require those
capabilities may not interoperate. GET /health also needs the Bearer key.

No tool for execute_order, order_send, close_position, filesystem, or shell
commands is exposed by this gateway. BUY/SELL remains a separate, default
LOCKED manual trading workflow in the local MT5 page, requiring the account,
SL/TP, broker check, and separate exact execution confirmation. Connecting
a remote MCP server does not allow it to execute orders through LUMAVUN.

Node unit tests cover localhost-only binding, Bearer 401, Origin protection,
key rotation, tool allowlist, unauthorized execution, MCP client handshake,
HTTPS URL validation, and fake broker quotes. Electron Windows GUI smoke
checks start/connect/reveal/stop and order-lock state. This testing never
accesses an actual broker account or places a real trade.


## v0.9.1: connect directly to MetaTrader 5 built-in MCP server

**Correction to v0.9:** MetaTrader 5 desktop build 6060 (July 2026) added
native MCP. The native MT5 server exposes real read-only tools such as
get_marketwatch_symbols, get_chart_history, get_chart_ticks_history,
get_trading_account_info, and get_trading_open_positions. It also exposes
DANGEROUS tools such as trade_send_market_order, shell, and file mutation.
Those dangerous capabilities are NEVER forwarded to the LUMAVUN AI/chart
client. The existing v0.9 endpoint incorrectly demanded only "ticker" and
"candles" tools, causing native MT5 MCP connections to fail. v0.9.1 discovers
the actual schema and maps only the explicitly supported READ-ONLY tools.

Open MT5 Windows > Tools > Options > MCP. Enable internal server, generate
a new API key, click OK to apply, and copy the address and key into LUMAVUN
MT5 Trading > MCP panel. Use the button Gunakan Alamat MCP MT5 to populate
the standard URL http://127.0.0.1:22346/mcp, or replace it with your actual
port from MT5. Click Hubungkan URL + Key, then Uji Data Akun MCP.

Native MCP server availability depends on MT5 version and configuration.
The client performs MCP initialize, optional initialized notification,
tools/list discovery and enforces a fixed set of read-only tool names. Native
input schemas may change across MT5 builds. Unsupported required parameters
are rejected with an explicit error rather than guessed. For candlestick
history, returned bars must be valid numeric OHLC with timestamps. For live
quotes, valid bid/ask from Market Watch (or supported quote/tick tool) must
exist. There are NO synthetic live candles, no order access, and no automatic
trading via this connection.

The native HTTP MCP connection does not need Python or the MetaTrader5
Python package. Those remain optional dependencies of LUMAVUN's older local
bridge used by the separate manual BUY/SELL interface. Tests use a simulated
MetaQuotes native MCP server, NOT a real terminal on the user's device.

Official documentation:
https://www.metatrader5.com/en/terminal/help/mcp_and_ai/configuration
https://www.metatrader5.com/en/terminal/help/mcp_and_ai/capabilities


## v1.0: Virtual AI Office, Trading Performance, Automatic Model Sync and Icon

### Virtual AI Office

Four specialist desks (Market Scout, Risk Officer, Research Lab, Briefing)
run actual user-triggered AI inferences against the last verified chart
snapshot with read-only role prompts. A desk stays IDLE unless running;
completed/failed tasks are listed with their real time, output and model.
Recent summaries are saved locally. There is no invisible background
execution, scheduler, broker order tool or automatic trading.

### Trading Performance

Two distinct sources: local Paper Trading closed trades, and (on explicit
Refresh) real CLOSED positions from native MT5 MCP tool
get_trading_history_positions for the last 90 days. Unsupported history
tools and incomplete records do NOT create fictional results. MT5 entries
require a real close timestamp and trustworthy net PnL, or gross PnL
with all broker-provided cost components (fee, commission, swap).

Metrics: net realized PnL, win rate on non-breakeven closed trades,
profit factor (when losses exist), expectancy as net PnL per closed trade,
and peak-to-trough realized balance drawdown. Paper trading's starting
balance supports a percentage drawdown. MT5 native history lacks an
initial capital baseline, so only absolute realized cumulative PnL
drawdown is shown. No intratrade equity drawdown is estimated.

References:
https://support.ninjatrader.com/s/article/Statistics-Definitions-Trade-Performance-in-NinjaTrader-Desktop
https://www.claritytracking.com/blog/how-we-calculate-trading-metrics
https://www.metatrader5.com/en/terminal/help/mcp_and_ai/capabilities

### Automatically synced custom AI models

Ollama obtains locally installed models from GET /api/tags. OpenAI-
compatible providers obtain available model IDs from GET /v1/models
using the user-entered HTTPS URL and optional in-session Bearer API
key. Results populate the AI model selector automatically after
changes to endpoint/key and on manual Refresh. The model selection
is validated against the provider catalog before use. The model list
must actually exist: no made-up fallback IDs are shown.

Credentials are held in Electron RAM, not local activity history or
disk. Model listing does not guarantee chat/vision inference support.
The custom endpoint must meet the existing HTTPS public-domain
validator and implement /v1/models.

References:
https://developers.openai.com/api/reference/resources/models/methods/list
https://github.com/ollama/ollama/blob/main/docs/api.md#list-local-models

### New Windows application icon

The generated LUMAVUN crescent, fog, gold star and candlestick
artwork is used for the window and application EXE. The 64px
source PNG is shipped as desktop/assets/app-icon.png.
The local scripts/build-icon.cjs creates an ICO without runtime
downloads. Windows executable editing injects this icon into the
installer/portable EXE. The full-resolution original generated
artwork is supplied as a conversation attachment.

### Verification and limitations

GitHub Actions performs Node unit tests for trading metrics and
model catalog, existing tests, Python mock MT5 tests, Electron
eight-page UI smoke and screenshot previews. Actual connection
to a personal MT5 terminal and AI provider cannot be tested in CI.


## v1.0.1: Native MT5 MCP vs optional Python gateway clarity

A common diagnostic was `MT5: MetaTrader5 Python package missing` after clicking
"Hubungkan Server Lokal" then "Uji Data Akun MCP". The local gateway is
**not the built-in MetaTrader 5 MCP server**: it is a separate Python bridge.
The recommended MT5 native MCP workflow instead connects to the real terminal
URL (usually `http://127.0.0.1:22346/mcp`) using **the API key from MT5**.
No Python package is required for native MCP reads.

The "Gunakan Alamat MCP MT5" button now explicitly **disconnects** any
existing local Python gateway session before updating the URL. A persistent
mode hint distinguishes MT5_NATIVE from LUMAVUN_BRIDGE. Account and position
errors raised by a missing Python module now state both remedies:
select native URL/key, or install MetaTrader5 with the same Python launcher
used by the optional bridge.

Manual BUY/SELL via the existing separate local MT5 trading ticket still
uses its own Python bridge; native MCP market-data access does not implicitly
convert or unlock the order execution subsystem.

Official native MT5 MCP configuration:
https://www.metatrader5.com/en/terminal/help/mcp_and_ai/configuration
