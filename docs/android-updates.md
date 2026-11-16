# LUMAVUN APK: permanent signing and safe updates

## What is fixed

- Android application ID remains **com.aistudio.nexsyncailite.qxvrtk**. Do not change it for future upgrades.
- GitHub Actions injects **LUMAVUN_VERSION_CODE = 100000 + GITHUB_RUN_NUMBER**, which increases on new workflow runs.
- Every installable APK is built as `:app:assembleRelease` and signed using the **same permanent signing certificate**.
- The public repository stores only an **AES-256/PBKDF2 encrypted PKCS12 signing keystore**, never an unencrypted private key.
- CI verifies the output APK signature, certificate fingerprint, and package ID before uploading an installable artifact.
- The raw debug APK is compiled for tests only, **not uploaded as an installable update**.

## One-time setup (GitHub mobile browser)

1. Obtain the private signing passphrase from the securely supplied `lumavun-signing-secret.txt` file. **Never post it publicly or commit it to Git.**
2. Open https://github.com/Nipzy12/Lumavun-Chart-APK/settings/secrets/actions (sign in as repository owner).
3. Tap **New repository secret**.
4. Name: `LUMAVUN_SIGNING_PASSPHRASE`.
5. Value: paste the entire passphrase **without extra spaces or newlines**. Save.
6. Open https://github.com/Nipzy12/Lumavun-Chart-APK/actions/workflows/android-build.yml and tap **Run workflow**, branch `main`.
7. After a successful run, download artifact **LUMAVUN-Update-Signed-APK** (contains `app-release.apk`). Extract the ZIP before installation.

Once the secret is set, every new build uses the **identical signing key** and a higher versionCode. GitHub **does not show saved secret values again**, so back up the passphrase privately.

### Existing older APKs: first migration only

Android accepts an in-place update only if the **package ID and app signing certificate** match (and versionCode is not lower).

Older `app-debug.apk` files were signed by Android Studio/Google AI Studio or an ephemeral GitHub Actions debug key. Those old signing keys may differ. **A brand-new permanent key cannot override that Android security rule.**

If the installed APK was signed with a different certificate and the old signing key is unavailable, back up/export all important app data first (if available), then uninstall the old version and install the first permanent-signed `app-release.apk`. This one-time migration can erase local preferences, watchlists, journal entries and other app data; never uninstall before ensuring data is safely backed up.

After migrating to the permanent-signed APK, later permanent-signed releases can install **over the existing app** without deleting its local data.

### Security / recovery

- The repository contains `.github/signing/lumavun-release.jks.enc.b64`: **encrypted private key bytes**. This alone is not enough to sign.
- The repository secret `LUMAVUN_SIGNING_PASSPHRASE` decrypts that key and unlocks the PKCS12 signing file.
- The fingerprint is pinned at `.github/signing/cert-sha256.txt`, allowing CI to catch accidental key rotation.
- Store a private copy of the passphrase; losing it may make future in-place updates impossible.
- Do **not** share the passphrase, private keystore or any unencrypted signing file in issues, pull requests, commits or screenshots.
- Avoid installing older/unofficial `app-debug.apk` files after the migration; those are not compatible update packages.
