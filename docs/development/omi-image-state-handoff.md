# Omi CV1 image-state handoff

This is the next physical step for the owned, sealed Omi CV1. The phone may remain disconnected until
the session. No pendant opening, pairing, firmware upload, bootloader unlock, reset, erase, or stock-app
installation is required.

## What is already prepared

- The Gumi Android shell performs the observed read-only GATT inventory itself.
- A separate two-step MCU Manager screen discloses every transient BLE write before it can run.
- The firmware port exposes image-state inspection only. Upload, test, confirm, reset, erase, file,
  settings, and shell operations are absent and rejected by the architecture check.
- The official v3.0.12 artifact matching the pendant has been decoded and cryptographically verified.
- Offline tests cover Nordic response mapping, cancellation/release behavior, and the v3.0.12 oracle.

The semantic image-state read does require temporary on-air writes: enabling SMP notifications and
sending MCU Manager READ requests. It requests ATT MTU 23 and disconnects immediately after the
response. No persistent device mutation is expected.

## Morning procedure

1. Connect the unlocked Android phone with its USB data cable.
2. Accept the USB-debugging prompt if Android shows it. Leave Bluetooth enabled.
3. Hold the Omi close to the phone and run from the repository root:

   ```sh
   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh prepare
   ```

4. In Gumi, find the Omi card and tap **Review MCU image-state read**.
5. Review the operations shown by the app, then tap **Run disclosed image-state read**.
6. Leave the successful result visible and run:

   ```sh
   devices/omi-cv1/tests/hardware-in-loop/android-image-state-probe.sh capture
   ```

The script never synthesizes a screen tap. The second, disclosed action always requires the owner's
physical tap. Its capture mode stores only Gumi's firmware-probe log tag and a screenshot under the
ignored `local/hardware-in-loop/` directory.

## Expected installed-release result

| Image | Expected active slot | MCUboot version | Expected MCUboot hash |
| --- | --- | --- | --- |
| Application `0` | `0` | `0.0.0+0` | `0eed1a42063975be5f8aee0e0df710122de445f7473681ba780bdcdad2fe7b36` |
| Network `1` | `0` | `0.0.0+0` | `267b11e2d413acebb874c76204be9f5f3872b9d033bd228ba5bcd46a27903089` |

`MATCHES_PUBLISHED_V3012` means both active, bootable images match the official release and no slot is
pending. It does not assert that `confirmed` or `permanent` must have a particular representation.

Stop without attempting OTA if the result is `MISMATCH` or `TRANSITIONAL`. `INCOMPLETE` means the
response did not expose both active images and also needs investigation before any firmware action.
For `ENDPOINT_EXPIRED`, scan and review the freshly observed endpoint again. For one transport failure,
move the Omi closer and retry once; preserve a second failure rather than looping requests.

## Immediate continuation after a match

The image-state match closes only the installed-image identity gate. The next non-destructive work is
the stock-behavior matrix: single/double/hold gestures, audio notification framing, disconnect/reconnect,
offline storage semantics, phone lock/app termination, pendant reboot persistence, and power behavior.
No custom or stock image is uploaded until those observations and the application-only recovery
procedure are qualified. The already reported stock storage bytes remain quarantined: this workflow
does not subscribe to audio, read ring content, advance a cursor, or clear storage.
