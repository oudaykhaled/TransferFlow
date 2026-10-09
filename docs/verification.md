# Verification evidence

Verified locally on 9 October 2026 with Java 17, Gradle 9.5.0, AGP 9.3.3, Kotlin 2.4.21, Android SDK 37.0 and build tools 36.0.0. Direct versions and the wrapper checksum are pinned. The compatible toolchain follows the matrix validated while repairing Katty; AGP 9.4 is deferred until the documented Kotlin compatibility range expands.

## JVM, static checks and packaging

```sh
./gradlew --no-daemon allJvmTests lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  --continue --max-workers=2 --console=plain
```

Result: **BUILD SUCCESSFUL**, 1m 31s. **83 JVM tests, zero failures, errors or skips**:

| Scope | Tests | Important behaviors |
| --- | ---: | --- |
| Domain | 34 | Exact EUR parsing/arithmetic, overflow, Dutch IBAN checks, review validation |
| Data | 36 | File-backed Room reopen, concurrency, same-key replay/conflict, cancellation, pending/terminal races, wall-clock rollback, HTTP contract |
| App | 13 | Frozen review, offline action, duplicate-action guard, state restoration, exact two-decimal locale formatting |

The data suite uses real SQLite under Robolectric (17 repository, 7 gateway and 12 MockWebServer tests); it is not an in-memory fake repository. HTTP tests exercise the optional adapter, not a deployed bank.

Android lint reports **zero errors and 10 warnings**: retained target/toolchain pins, backup-rule metadata, and unused strings. Debug and instrumentation APKs compile. The release APK is R8-minified and resource-shrunk; it is unsigned, not a production-distributed build. No coverage percentage or measured performance claim is made.

## Device journeys

```sh
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.injected.device.serial=emulator-5690 --no-parallel --max-workers=2
```

Result: **BUILD SUCCESSFUL**, 52 seconds. **10 instrumented tests, zero failures or skips** on API 35 arm64, Google Play emulator. These Compose journeys use real, individually isolated Room client and gateway stores. They cover success, validation, rejection, pending/recreation, lost-response lookup, lost-response safe retry with the original operation and exactly one debit/history entry, review editing, offline confirmation, and two 200% font size regressions (new-step heading visibility and first-error focus/visibility with the keyboard).

The emulator used a task-owned AVD, a private ADB server on port 5038, and serial emulator-5690. Local environment variables ANDROID_ADB_SERVER_PORT and ANDROID_SERIAL selected it; other running devices were left alone. CI uses its own API 35 x86_64 emulator.

## Normal application recovery walkthrough

The default debug APK (no instrumentation runtime override) executed the [reviewer walkthrough](demo-guide.md). After a lost response, force-stop removed PID 8441; a cold launch created PID 8737 and restored Unknown with the same immutable recipient and €25.00 request. **Retry safely** resolved the original operation.

A read-only SQLite snapshot before and after retry confirmed the same request key, an unchanged gateway ledger, one gateway operation, one history row in each database, and a released active slot after success. The booked/available balances moved from 1,248,055 to 1,245,555 cents once. See [the recorded summary](process-recovery.json) and actual [restored-outcome](screenshots/unknown-restored.png), [success](screenshots/success.png), and [account](screenshots/account-after-retry.png) captures. This force-stop test establishes recovery from the durable journal; activity recreation in the Compose suite is a separate lifecycle check.

The normal account screen was also inspected in dark mode and at Android system font scale 2.0. Both settings were restored after capture. These captures supplement the two automated large-text journey checks; they do not establish a full accessibility audit.

## Minified release runtime smoke

The unsigned release APK was signed into a temporary file with the standard local Android debug key for emulator installation. The R8-minified, resource-shrunk application reopened the existing Room account/history, accepted manually entered recipient/IBAN/amount, displayed the review, and completed a new synthetic transfer. Developer scenarios and sample-fill controls were absent from the release form/outcome. The debug APK was restored afterward. No signing material is committed, and this temporary install is not a production release.

## Reproducibility and boundaries

Run the commands above, followed by `./gradlew :app:connectedDebugAndroidTest` against a connected emulator/device. Generated reports are ignored by Git. CI runs the same JVM/build gates and API 35 emulator journeys; a local pass does not establish a remote GitHub Actions result.

All transfers, accounts and recipients are synthetic. Two separate local databases model client and gateway ownership, not a remote security boundary. There is no real bank, authentication server, hosted backend or real payment integration. Clearing app data removes both owners. TalkBack, a physical/OEM device matrix, platform API range testing and production security certification are not claimed.
