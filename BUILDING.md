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
The workflow runs release-automation tests, JVM tests, native-library checks and
Android lint before publishing downloads.

- Ordinary pushes to `main` or `beta` run validation and keep APKs as Actions
  artifacts. They do not create, replace or delete GitHub releases.
- To publish explicitly, open **Actions → Build APK → Run workflow**, choose
  `main` or `beta`, and enable **Publish an APK release after validation**.
- A public `main` commit explicitly marked `[release]` also requests publication
  after all validation gates pass. Leave that marker out of ordinary commits.
- A manual publication from public `main` creates a versioned release when the
  app's `v<versionName>` tag does not exist, and refreshes `latest-build`.
- A manual publication from public `beta` refreshes `beta-build`.
- Pushing a matching version tag publishes its APK to that version's release.
- Release notes come from the matching user-facing entry in Settings → About.
  A missing changelog entry or mismatched version tag stops publication.

Existing version tags keep their original source association. Bump `versionName`
and add its in-app changelog entry to publish a new version; later main builds of
the same version remain available under `latest-build`. APKs are direct release
attachments, not just expiring Actions artifacts.

For a stable signing identity, create and retain a keystore:

```sh
keytool -genkeypair -v -keystore ember.jks -alias ember -keyalg RSA -keysize 2048 -validity 10000
```

Keep the keystore and its passwords private and backed up. GitHub signing uses
`EMBER_KEYSTORE_BASE64`, `EMBER_KEYSTORE_PASSWORD`, `EMBER_KEY_ALIAS` and
`EMBER_KEY_PASSWORD`. Local signing uses `EMBER_KEYSTORE_FILE` and the same password/alias environment
variables. Updates can install over an existing app only when
its package and signing identity match.

Without release-signing secrets, ordinary builds still produce a debug/testing
Actions artifact. Its signing key may differ between runners or from an existing
installation. Release publication and in-app Actions updates require a persistent
release signing key. Public workflow builds exclude private bundles and test add-on links.

Standard updates check published releases in the public `PrimeN/Ember-Launcher`
repository, regardless of where the APK was compiled. They require no credentials.
Beta updates check the same public repository's `beta-build` release without credentials.
Under **Settings → System → Updates → Beta update source**, users can instead choose
**Actions APK**. This selects a completed, successful `beta` build of this repository's
`build.yml` workflow and a non-expired `ember-tv-<version>-<code>-release` artifact.
Pull requests, fork builds and debug artifacts are excluded. The anonymous download
uses [nightly.link](https://nightly.link); Ember verifies GitHub's ZIP checksum before
extracting exactly one matching APK with bounded size. No service code is bundled.
Actions APKs expire after three days and can be unavailable if the public mirror is down.
Repository owners may install the [nightly.link GitHub App](https://github.com/apps/nightly-link)
with read-only Actions access to avoid its shared public request limit; end users need no account.
Publish beta APKs from the [public build workflow](https://github.com/PrimeN/Ember-Launcher/actions/workflows/build.yml)
on `beta`, using the manual publication option above. Private-repository builds also
check these public endpoints; they do not require a separate update token or repository setting.
Existing APKs that still use a private beta endpoint need one manual installation of
an updated public APK. Keep the same signing key and a higher Android version code
to preserve the installed app and its data. Switching back to stable does not downgrade
an installed beta: a newer stable APK must first be published.
GitHub Actions uses its separate repository token only for publication.

Catalog thumbnails and collection covers use a bounded disk cache that survives
restarts. Catalog/collection configuration changes invalidate its bytes and decoded
images, including unchanged URLs. Refreshing a catalog or collection folder also
reloads its images; clearing the artwork cache clears these files as well.

Android version codes use build time rather than a repository's run counter, so
moving builds between repositories does not reset update ordering. CI sets one
`EMBER_VERSION_CODE` for the entire job; local builds derive a value automatically.

Develop on `beta`, validate on a TV, then merge into `main`. APKs and validation
reports are available from the workflow run or its configured release channel.
