# LUMAVUN Chart Android

Android Kotlin + Jetpack Compose trading chart, AI Insight, Watchlist and simulation bot.

## UI / market improvements

- Lightweight gradient glass-style cards for AI Insight and Settings, responsive two-column AI tabs.
- Simulation/Performance metrics laid out on two columns for narrow Android displays.
- Performance advanced filters and Journal expansion to reduce clutter.
- Provider filter in Market Search: Binance USDT, BiQuote, Exness.
- Binance USDT and BiQuote symbols are fetched from their supported market-data endpoints. Seed examples are **unverified** until validated.
- Exness symbol availability is shown **only after the configured, enabled gateway returns verified tradable symbols**. No fallback to illustrative catalog on gateway failure.
- Live trading is **not implemented**. Bot execution remains simulation-only.

## Build APK via GitHub Actions

1. Push the **extracted** project files and `.github/workflows/android-build.yml` to `main`.
2. Open GitHub **Actions → Build LUMAVUN Android APK → Run workflow** (or simply push to trigger).
3. When the workflow succeeds, open the run and download **LUMAVUN-Professional-UI-debug** under **Artifacts**.
4. Unzip the artifact, tap `app-debug.apk` on Android and allow installation from that source if prompted.

If Android warns about incompatible update signatures, back up app data first. An APK signed with the CI runner's debug key may not install over an existing debug APK signed elsewhere.

## Exness gateway contract (market data only)

The Exness provider in this project uses a **user-configured gateway**, not an asserted universal Exness endpoint. Instrument discovery expects a response to `GET {gatewayUrl}/api/symbols` in the shape:

```json
{ "symbols": [
  { "symbol": "EURUSD", "baseAsset": "EUR", "quoteAsset": "USD", "category": "FOREX", "displayName": "Euro / USD", "status": "TRADING" }
] }
```

A gateway must return the **actual tradable inventory** for the authorized account. No account/gateway means no verified Exness instruments. Example catalog entries are not proof that an instrument is available. Credentials must be managed securely; no real-account execution or API secrets in APK/source.

## Build locally

Open the project in Android Studio with a compatible Android SDK and Gradle JDK. This project lacks a committed Gradle Wrapper; the GitHub workflow installs Gradle explicitly. Keep `.env`, signing keys, and other secrets outside version control.

**Verification note:** source-level changes are supplied for review; compilation and runtime UI screenshots must be validated by the Android workflow/device. No APK is included in this source package.
