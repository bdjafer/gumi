# Edge development bootstrap

Status: verified on macOS 26.4.1 ARM64 on 2026-07-18.

## Pinned spine

| Component | Pin | Verification or reason |
| --- | --- | --- |
| Eclipse Temurin JDK | `17.0.19+10` | SHA-256 `8fa1eff40bb637a33613b2ccb8b12c70dc3661cc22cf8e784943715769a05336` |
| Gradle wrapper | `9.5.0` | SHA-256 `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746` is enforced by the wrapper |
| Android Gradle Plugin | `9.3.0` | Current stable; requires Gradle 9.5 and JDK 17 |
| Kotlin | `2.4.10` | Current stable bug-fix release |
| Android command-line tools | build `14742923` | Publisher SHA-1 `cc27cca4b84bfdbc7df17e3d0a01d0c640d8ee71`; SHA-256 `ed304c5ede3718541e4f978e4ae870a4d853db74af6c16d920588d48523b9dee` |
| Android compile SDK | `37.0` revision 2 | Installed as `platforms;android-37.0`; the major-only package `android-37` does not exist |
| Android target SDK | `36` | Deliberately held below 37 until its behavior changes are exercised on a handset |
| Android Build Tools | `36.0.0` | AGP 9.3 default/minimum compatibility pin |
| Android Platform Tools | `37.0.0` | Supplies `adb` for the physical handset handoff |
| Coroutines | `1.11.0` | Version catalog pin |
| Kotlin serialization | `1.11.0` | Version catalog pin; currently test-only for language-neutral fixtures |
| Compose BOM / Activity Compose | `2026.06.01` / `1.13.0` | Current Google Maven releases at bootstrap time |

The dependency pins used by the build live in [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml).
Local SDK archives, caches, accepted licenses, signing keys, and `local.properties` are ignored and must
not be committed.

## Prepared-workspace commands

The current macOS ARM64 workspace has the verified tools under `local/`. The repository launcher sets
all tool and cache paths without changing the machine-wide Java or Android setup:

```sh
./gumiw verifyArchitecture \
  :edge:sdk:check \
  :edge:runtime:check \
  :devices:omi-cv1:edge-driver:check \
  :edge:shell:linux:test \
  :edge:shell:android:assembleDebug

./gumiw :edge:shell:linux:run
```

The debug APK is generated at
`edge/shell/android/build/outputs/apk/debug/android-debug.apk`. It is the diagnostic stock-device BLE
probe, not yet a product shell. The deterministic sealed-device handoff is documented in
[omi-image-state-handoff.md](omi-image-state-handoff.md).

## Recreating the local toolchain

For another macOS ARM64 checkout:

1. Download Temurin `OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.19_10.tar.gz` from the Adoptium release,
   verify the SHA-256 above, and extract it under `local/toolchains/`.
2. Download `commandlinetools-mac-14742923_latest.zip` from Google's Android repository, verify its
   publisher digest above, and place its contents at
   `local/toolchains/android-sdk/cmdline-tools/latest/`.
3. With the workspace JDK selected, run `sdkmanager --licenses` and personally review/accept the SDK
   licenses.
4. Install exactly `platform-tools`, `platforms;android-37.0`, and `build-tools;36.0.0` with
   `sdkmanager`.
5. Create ignored `local.properties` with an absolute `sdk.dir` pointing at the checkout's
   `local/toolchains/android-sdk`.
6. Run `./gumiw projects`, then the verification command above.

The Gradle distribution itself does not need a separate manual install: `gradlew` downloads it and
checks `distributionSha256Sum` before execution. Other host architectures may use a system JDK 17 and
Android SDK with `./gradlew`; `gumiw` currently describes the verified macOS ARM64 witness only.

## Android handset handoff

No pendant opening, firmware write, emulator, debug probe, or hardware programmer is needed for the
next step. Prepare the Android phone as follows:

1. Open **Settings → About phone** and tap **Build number** seven times. Enter the phone PIN if asked.
2. Open **Settings → System → Developer options** (the exact menu name varies by vendor) and enable
   **USB debugging**.
3. Connect the unlocked phone to this Mac with a USB **data** cable.
4. If Android asks for the USB mode, choose **File transfer / Android Auto** rather than charge-only.
5. Accept **Allow USB debugging** for this computer and optionally select **Always allow** for this Mac.
6. Leave the phone connected and unlocked, then report `ready`; the next read-only check is:

   ```sh
   local/toolchains/android-sdk/platform-tools/adb devices -l
   ```

An expected healthy line ends in `device`. `unauthorized` means the RSA dialog still needs acceptance;
an empty list usually means the cable is charge-only or the selected USB mode is wrong. Do not enable
OEM unlocking, unlock the bootloader, pair the pendant, install the stock Omi app, or change Omi
firmware for this handoff. Once ADB is healthy, follow
[the image-state handoff](omi-image-state-handoff.md); its script rebuilds, verifies, installs, and
launches the exact diagnostic APK before the owner-controlled read.
