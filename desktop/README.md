# LUMAVUN Chart Desktop (Windows Preview v0.1)

This is an initial **Windows desktop port** developed inside the Android source repository. It is **not** an APK converted into EXE, and it is **not feature-parity** with the Jetpack Compose Android app.

## Implemented

- Locally packaged Electron desktop application, not a remote website wrapper.
- Live, verified Binance Spot USDT OHLC candles and 24-hour quote, with API hostname failover and explicit errors on unavailable data.
- Candlestick chart powered by bundled TradingView Lightweight Charts (no CDN at runtime).
- Timeframes 1m / 5m / 15m / 30m / 1h / 4h / 1d.
- EMA 20 / 50, RSI 14, and fractal support/resistance summary. Indicator formulas are ported from the equivalent Android engines.
- Editable watchlist stored locally, no fake instruments or prices.
- Read-only market data. No trading or wallet credentials.

## Not implemented yet

The Android app's Exness user-configured gateway, BiQuote, AI analyst, trading simulator, alerts, Android database/settings and account integration, and full mobile UI. The first deliverable is a functional Binance charting preview, **not an Android feature-complete clone**.

## Build Windows EXE automatically

1. Open **Actions → Build LUMAVUN Windows EXE** on GitHub.
2. Trigger **Run workflow** after the workflow is available on the default branch, or use the pull-request checks.
3. Download the **LUMAVUN-Chart-Windows-EXE** artifact from the completed run.
4. Extract ZIP: Windows Setup is an installer; Portable runs without installation.

Both files are **unsigned**. Windows SmartScreen may warn; only run binaries you trust.

## Build locally

Requires Node.js 22+ and Windows for Windows packaging:

    cd desktop
    npm install
    npm test
    npm run dist:win

Run development app with:

    npm start

Uses a local copy of Lightweight Charts from npm installed at build time; runtime still requires an internet connection to obtain live Binance prices.

## Security

- Electron sandbox and context isolation enabled; Node integration disabled in the renderer.
- Preload exposes only two fixed market-data methods, not arbitrary URL requests.
- Public Binance market-data endpoints only; no API keys required or stored.
- Browser navigation/new-window requests are blocked.
