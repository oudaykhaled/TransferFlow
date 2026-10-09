# Install the synthetic demo

The Android verification workflow publishes a debug application APK and its SHA-256 checksum after the build, JVM tests and lint succeed. Choose a run where **both jobs have passed**; the separate device job may still be running when the artifact first appears.

## Download

1. Sign in to GitHub and open [Android verification runs](https://github.com/oudaykhaled/TransferFlow/actions/workflows/android.yml).
2. Select a successful run for the commit you want to inspect. Confirm its branch and commit on the run summary.
3. Download **transferflow-demo-&lt;full commit SHA&gt;** from the Artifacts section and extract the ZIP.

The archive contains only `app-debug.apk` and `app-debug.apk.sha256`. The commit in the artifact name is the workflow's checked-out commit; for pull request runs this can be GitHub's generated merge commit.

Artifacts are retained for **14 days** and can expire or be deleted with the run. GitHub requires sign-in and repository read access to download them, even for this public repository. See [GitHub's artifact download instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts). A newer successful run provides a new artifact; building the source remains an option without downloading an artifact.

## Verify and install

From the extracted directory, verify the APK checksum before installation.

macOS:

```sh
shasum -a 256 -c app-debug.apk.sha256
```

Linux:

```sh
sha256sum -c app-debug.apk.sha256
```

The expected result is `app-debug.apk: OK`. The checksum confirms the downloaded file matches the adjacent checksum; the selected repository, workflow run and commit establish its source context.

Use an Android 7.0 / API 24 or newer device or emulator. Install the APK through Android's package installer, or use Android SDK platform tools:

```sh
adb install app-debug.apk
```

If Android reports a signing-certificate conflict with an existing TransferFlow installation, remove that earlier demo installation before installing. Removal also deletes its synthetic account and journal. CI uses a debug signing key generated on the runner, so different runs need not share a signing identity.

Open **TransferFlow**, then follow the [reviewer walkthrough](demo-guide.md). The default app needs no bank credentials, server or network connection.

## What this download represents

This is a **debug portfolio demo** with selectable fictional outcomes and sample recipient controls. All accounts, recipients and transfers are synthetic; no real payment occurs. The client and synthetic gateway use separate local Room databases. Clearing application data removes both.

The artifact is not a production-signed release, app-store package or security certification. The workflow also compiles an unsigned minified release to verify packaging, but only the installable debug application and checksum are distributed here. Test APKs and signing material are excluded.
