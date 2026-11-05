# Building Ember TV

## Local development

Use JDK 17, Android SDK platform 37, Python 3 and Gradle 9.8.2. The repository
contains wrapper version metadata but no wrapper script or JAR, so install Gradle
and use the `gradle` command below. Gradle downloads the pinned build dependencies. Android Studio can
install the SDK platform and build tools during project sync.
The app runs on Android 10+ and packages ARM64 and ARMv7 libraries only.
An NDK is needed when rebuilding the native libraries; the native directories
document the pinned toolchains and corresponding sources.

1. Clone your repository and open its root in Android Studio.
2. Copy `local.properties.example` to `local.properties`. Set your SDK path and
   any optional API configuration there; do not commit the resulting file.
3. Run validation:

   ```sh
   gradle :app:testDebugUnitTest :app:lintDebug
   ```

4. To produce a local testing APK:

   ```sh
   gradle :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Unit tests cover state, persistence, provider parsing, navigation policies and
playback recovery. They run on the JVM and are excluded from the APK. Physical TV
checks remain necessary for focus, rendering, firmware shortcuts and media output.
Lint results are written under `app/build/reports/`; inspect their error severities.
The source's lint configuration does not make every finding a Gradle task failure.

## Optional credentials

The app can be used without adding provider credentials. Configure the services you
need in **Settings → API keys**, or use the documented variables in
`local.properties.example` for a local build. Entered keys use encrypted storage.
API keys bundled in an APK can be extracted; use registrations intended for this
app and keep account credentials and signing keys out of source control.

For GitHub builds, use repository **Settings → Secrets and variables → Actions**.
Only secrets explicitly mapped by `.github/workflows/build.yml` are injected into
a build; creating an unmapped secret alone does not configure a provider.

`TEST_ADDONS` is optional local/debug configuration. Release builds do not include
those private test manifest links.

## Signing and GitHub builds

Fork or create a repository, enable GitHub Actions and push your source.
The existing workflow validates pushes to `beta` and `main` and produces build
artifacts. Signed beta builds update the beta prerelease; signed main builds
update the main build release. Version tags also produce releases.

For a stable signing identity, create and retain a keystore:

```sh
keytool -genkeypair -v -keystore ember.jks -alias ember -keyalg RSA -keysize 2048 -validity 10000
```

Keep the keystore and its passwords private and backed up. GitHub signing uses
`EMBER_KEYSTORE_BASE64`, `EMBER_KEYSTORE_PASSWORD`, `EMBER_KEY_ALIAS` and
`EMBER_KEY_PASSWORD`. Local signing uses `EMBER_KEYSTORE_FILE` and the same password/alias environment
variables. Updates can install over an existing app only when
its package and signing identity match.

Develop on `beta`, validate on a TV, then merge into `main`. APKs and validation
reports are available from the workflow run or its configured release channel.
