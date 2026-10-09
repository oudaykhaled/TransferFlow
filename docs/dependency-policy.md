# Dependency and toolchain policy

This project uses the toolchain exercised while repairing Katty and verified by TransferFlow's [build and device checks](verification.md). The catalog and wrapper are the source of truth; this document records the selection on 9 October 2026 rather than claiming every dependency is always the newest.

## Selected matrix

| Component | Version | Selection |
| --- | --- | --- |
| Android Gradle Plugin | 9.3.3 | Stable 9.3 patch containing D8/R8 fixes; exercised by debug and minified release builds. |
| Gradle | 9.5.0 | AGP 9.3 minimum; wrapper archive SHA-256 is pinned. |
| Java / JVM target | 17 | Aligned across Android modules, domain module and CI. |
| Kotlin / Compose compiler / serialization plugin | 2.4.21 | One compiler version across the coupled plugins. |
| KSP | 2.3.12 | KSP2 used for Room generation. |
| Compose BOM | 2026.09.00 | Compose runtime, Material 3 and UI test dependency alignment. |
| compileSdk / targetSdk / minSdk | 37 / 36 / 24 | Build against API 37 while retaining the declared target behavior and installation minimum. |
| SDK platform / build tools | 37.0 / 36.0.0 | Identical package selection in setup instructions and CI. |

The [AGP 9.3 compatibility table](https://developer.android.com/build/releases/agp-9-3-0-release-notes) specifies Gradle 9.5.0 and Java 17. The [Kotlin compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html) lists Kotlin 2.4.20–2.4.21 with Gradle 7.6.3–9.7.0 and AGP 8.5.2–9.3.1. **AGP 9.3.3 is a deliberately tested patch exception to that exact AGP upper bound**, chosen for the documented 9.3 patch fixes. Passing this repository's gates is narrower evidence than a vendor guarantee. Moving to AGP 9.4 is deferred until the supported range and project verification justify it.

Android modules use AGP's [built-in Kotlin support](https://developer.android.com/build/migrate-to-built-in-kotlin). The legacy Kotlin Android plugin is not also applied.

## Runtime and test families

| Family | Version |
| --- | --- |
| AndroidX Core / Activity | 1.19.1 / 1.13.0 |
| Lifecycle runtime and ViewModel | 2.11.0 |
| Room runtime, compiler and tests | 2.8.5 |
| Coroutines runtime and tests | 1.11.0 |
| Serialization runtime | 1.11.0 |
| OkHttp and MockWebServer | 5.5.0 |
| Robolectric / JUnit | 4.17 / 4.13.2 |
| AndroidX test core and runner / JUnit extension | 1.7.0 / 1.3.0 |

Direct versions live in [the version catalog](../gradle/libs.versions.toml). Compose artifacts use the BOM; runtime/test families share version references. Version pins and the wrapper checksum do not establish byte-for-byte reproducible APKs or an audited transitive dependency graph.

## Upgrade procedure

1. Check official release notes, published Maven metadata, the Kotlin compatibility table and Android AAR SDK requirements.
2. Update coupled compiler plugins, Room compiler/runtime/tests, Compose BOM artifacts and runtime/test libraries together where alignment requires it. Use stable releases.
3. Run the existing build gates without reducing lint severity or removing regression tests:

   ```sh
   ./gradlew --no-daemon :app:assembleDebug :app:assembleRelease allJvmTests lintDebug
   ./gradlew --no-daemon :app:connectedDebugAndroidTest
   ```

4. Exercise the minified application when an upgrade changes generated code or shrinking rules. Record the actual device/API and outcome in verification evidence.
5. Review the weekly Gradle and GitHub Actions Dependabot PRs. Grouped minor/patch updates still require the same verification; major updates need an explicit compatibility review. Keep Actions pinned to commit SHAs with readable version comments.

## Primary references

- [AGP 9.3 release notes and compatibility](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [Kotlin Gradle and AGP compatibility](https://kotlinlang.org/docs/gradle-configure-project.html)
- [KSP releases](https://github.com/google/ksp/releases)
- [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [AndroidX releases](https://developer.android.com/jetpack/androidx/versions)
- [Google Maven metadata](https://dl.google.com/dl/android/maven2/master-index.xml)
- [Maven Central](https://repo.maven.apache.org/maven2/)
- [Gradle 9.5.0 archive checksum](https://services.gradle.org/distributions/gradle-9.5.0-bin.zip.sha256)
