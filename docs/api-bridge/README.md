# API bridge: maintainer notes

How the API bridge is built, tested and released. For connecting an app to Anytype, see
[integration.md](integration.md). For the contract tests against real heart, see
[scripts/api-bridge/README.md](../../scripts/api-bridge/README.md).

## Heart comes from a fork

Upstream heart's mobile library never calls `api.SetMiddlewareParams`, so its local JSON API
answers every authenticated request with an empty HTTP 500. The app therefore depends on
`io.anyproto:anytype-heart-android:<version>` (`middlewareVersion` in `gradle/libs.versions.toml`)
built from the fork [garyp/anytype-heart](https://github.com/garyp/anytype-heart), branch
`apibridge` (PR garyp/anytype-heart#2). The branch is an upstream tag plus four commits:

1. the one-line fix in `clientlibrary/service/lib.go`, the only change meant for upstream;
2. upstream's tantivy-go v1.0.7 bump (`2e54309f6`), which Go 1.26.11 needs;
3. CI changes that publish the Android library to the fork's GitHub Packages, with a `platform`
   input for manual runs;
4. a patch to `modernc.org/libc` in the Android build, so heart runs on x86_64 emulators (below).

The upstream issue draft is [heart-issue-draft.md](heart-issue-draft.md) (not filed).

### Publishing heart

Run the fork's `Build` workflow manually on `apibridge` (Actions, Build, Run workflow) with
`platform = android` and `alpha_version = vX.Y.Z-apibridge.N`. It builds only the Android library,
on Linux, in about 10 minutes, and publishes it to `https://maven.pkg.github.com/garyp/anytype-heart`.
A version can be published only once. Don't push a `v*.*.*` tag to the fork: that builds every
platform on macOS for over two hours.

One-time setup of the fork:

- Enable Actions (GitHub disables workflows on new forks).
- Repository secrets: `UNSPLASH_KEY` (the Unsplash cover picker) and `INHOUSE_KEY` (in-house
  analytics). `ANYTYPE_PAT` is needed only if the build can't fetch a private dependency.

### Resolving it in the app

`settings.gradle` has a `HeartFork` repository restricted to `io.anyproto:anytype-heart-android`,
ahead of the anyproto one. It uses the `github.properties` credentials (`gpr.usr`, `gpr.key`); the
token needs `read:packages` and access to `garyp/anytype-heart`, since GitHub Packages requires a
token even for public packages. `mavenLocal()` comes first, so a local copy of the same version
wins: delete `~/.m2/repository/io/anyproto/anytype-heart-android/<version>` to force the download.

### Bumping heart

1. Fetch the new upstream tag into the fork clone (`git fetch https://github.com/anyproto/anytype-heart
   tag vX.Y.Z --no-tags`) and rebase `apibridge` onto it. Drop the fix commit if upstream has it.
2. Push the branch, publish `vX.Y.Z-apibridge.N` as above, and tag that commit locally for the
   contract tests.
3. Set `middlewareVersion` and `HEART_TAG` in `scripts/api-bridge/run-contract-tests.sh`.
4. Run the contract tests, and regenerate `small-tools-manifest.json` (see integration.md).

### Dropping the fork

Once upstream calls `api.SetMiddlewareParams(mw)` in `clientlibrary/service`, depend on the
official artifact, remove the `HeartFork` repository from `settings.gradle`, and run the contract
tests against an upstream checkout.

## x86_64 emulators

Stock heart can't run on x86_64 Android emulators. Its pure-Go SQLite (`modernc.org/libc`) makes
the legacy x86_64 syscalls `lstat`, `access`, `unlink`, `mkdir`, `rmdir` and `rename`, which
Android's app seccomp filter blocks on x86_64, so heart dies with SIGSYS (or, with SIGSYS ignored,
fails with "unable to open database file") when it opens its database. arm64 isn't affected. The
fork's Android build patches libc to use the `*at` forms. Upstream:
https://gitlab.com/cznic/libc/-/issues/41.

## Building and testing

```bash
./gradlew :api-bridge:testDebugUnitTest :api-bridge-client:testDebugUnitTest
./gradlew :app:assembleDebug :api-bridge-testapp:assembleDebug
```

On an Android 15+ device or emulator, with a (local-only) account in Anytype:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk        # io.anytype.app.dev
adb install -r api-bridge-testapp/build/outputs/apk/debug/*.apk
./gradlew :api-bridge-testapp:connectedDebugAndroidTest           # drives the consent screen
```

The test app's buttons run the same flow by hand. It can pin the Anytype build's certificate:
`adb shell am start -n io.anytype.apibridge.testapp/.MainActivity --es pinned_cert <sha-256 hex>`.

## Accepted loopback exposure

While any app is bound to the bridge service, heart listens on `127.0.0.1:<port>`, and any local
app can reach that port. Without a key it gets only 401/403 (and the `Anytype-Version` header);
heart's keyless pairing can't complete, because Anytype never shows the approval it needs. The
listener closes when the last binding closes, so an unapproved app that stays bound can keep it
open, but learns no key. The contract tests check that keyless REST and MCP requests get 401.

## Publishing the client library

`:api-bridge-client` is published as `io.anytype.apibridge:api-bridge-client:<version>` to the
GitHub Packages registry of `garyp/anytype-kotlin`, where `<version>` is the app's version from
`app/gradle.properties`, so the library needs no version bump of its own.

Push a tag `api-bridge-client-v<version>` (for example `api-bridge-client-v0.50.4`), and
`.github/workflows/publish-api-bridge-client.yml` tests and publishes the client. The job fails if
the tag doesn't match the app's version (`./gradlew -q :api-bridge-client:printApiBridgeClientVersion`
prints it). A version can be published only once: to publish again before the app's version
changes, add `version.versionPostfix=<n>` to `app/gradle.properties`, as for an app build.
