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

## Update-friendly Android APK (permanent release signing)

Previous GitHub debug builds used transient debug certificates, so an APK from a new runner could clash with the installed one. LUMAVUN now supports **one permanent app-signing key** and **automatic increasing versionCode**.

**One-time action required:** add the repository secret `LUMAVUN_SIGNING_PASSPHRASE` using the private passphrase file supplied to the repository owner. The public repository includes only an *encrypted* keystore.

1. Visit [GitHub Repository Secrets](https://github.com/Nipzy12/Lumavun-Chart-APK/settings/secrets/actions) and add the named secret.
2. Start the [Android Actions workflow](https://github.com/Nipzy12/Lumavun-Chart-APK/actions/workflows/android-build.yml) on `main` (or push an update).
3. Download **LUMAVUN-Update-Signed-APK** from successful runs, extract it and install `app-release.apk`.
4. For future updates, install only the same permanently signed release artifact. Android will keep existing app data when the versionCode is higher.

**Important:** If an older installed APK has a different signing certificate, **the first migration still requires the original key or a backup followed by uninstall/reinstall**. No workflow can bypass Android's signature verification.

Full steps, backup cautions and recovery: [docs/android-updates.md](docs/android-updates.md).

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
