
## Android Signing Identity — CRITICAL BUILD RULE

Yomitsu must preserve the existing Android signing identity so debug builds
can update the existing installation without uninstalling the application.

KNOWN-GOOD SIGNING CERTIFICATE

SHA-256:
e486ea516e88fba9854f4fe166fe0f420f64792fc911fa5381219b8e11248968

EXPECTED LOCAL DEBUG KEYSTORE

~/.android/debug.keystore

SIGNING INVARIANT

For any debug APK intended for installation/update on the development
device, the following MUST have the identical SHA-256 certificate:

1. ~/.android/debug.keystore
2. certificate of the installed app.yomihon.dev
3. certificate of the newly built APK

Expected result:

HOST KEYSTORE = DEVICE APP = NEW APK

DO NOT:

- generate a replacement debug keystore
- delete or replace ~/.android/debug.keystore
- uninstall app.yomihon.dev to bypass a signing mismatch
- use adb install -r -d as a workaround for a certificate mismatch
- accept a different signing certificate merely because the APK builds
- store the private keystore/password in tracked repository files

DOCKER/CONTAINER BUILD REQUIREMENT

When building inside Docker/devcontainer/any isolated build environment,
the host debug keystore must be explicitly made available to the build
environment.

Before assembling an installable debug APK, verify that the build
environment is using the known-good keystore rather than silently generating
or selecting another debug keystore.

PRE-BUILD SIGNING GATE

Before an installable debug build:

1. Locate ~/.android/debug.keystore.
2. Extract its SHA-256 certificate fingerprint.
3. Verify it equals the known-good fingerprint above.
4. If building in Docker/container, verify the same keystore is visible
   inside the build environment.
5. Build the APK.
6. Extract the APK certificate fingerprint.
7. Compare APK fingerprint with the known-good fingerprint.
8. Only after the fingerprints match may the APK be installed as an update.

IF SIGNATURE VERIFICATION FAILS:

STOP.

Do not uninstall the existing app.
Do not wipe app data.
Do not generate a new key.
Do not install the APK.

Report the exact keystore, device-app, and APK fingerprints and identify
which stage produced the mismatch.

SIGNING CHANGES REQUIRE EXPLICIT INVESTIGATION

If ~/.android/debug.keystore is missing, changed, inaccessible, or has a
different certificate, do not automatically create a new one.

Treat this as a signing-recovery issue and investigate before building an
APK intended to update the existing installation.
