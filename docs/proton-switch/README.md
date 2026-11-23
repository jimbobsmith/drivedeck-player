# Switch the Proton VPN server when YouTube blocks the video

When YouTube blocks the address DD PipePipe plays from, DD PipePipe can ask the Proton API client on the same device (the Fire TV app from `jimbobsmith/protonvpn-linux-ui`, `firetv/`) for a Quick switch, wait until the new tunnel is up, and continue the video at the same position. The user sees each step on the player.

DD PipePipe never chooses a server, holds Proton credentials, or changes Proton settings. The Proton app chooses the server: its oldest last-connected favorite that is not the current one.

## Setting

**Settings → Video and audio → When YouTube blocks the video**

| Choice | What happens |
| --- | --- |
| Switch automatically (default) | The player shows "Proton VPN is switching…", waits for the new tunnel, then continues the video. At most 2 automatic switches per video and 4 in 15 minutes; after that it says switching is paused and shows the usual error. |
| Ask me first | The player shows "YouTube blocked this video. Switch to another favorite Proton VPN server and continue the video?" with **Switch and continue** (focused for the D-pad) and **Not now**. |
| Do nothing | The player's own error handling runs as before. |

Below it, **Proton VPN app** says whether the Proton app was found, and **Switch server now** appears only when it can be asked.

A block is YouTube's bot check (the extractor's `AntiBotException` or "Sign in to confirm"), or HTTP 429 or 403 on a stream. Other failures do not count, because a new address would not fix them. On the video page (before playback), an IP block adds **Switch server and retry** to the error panel, and starts by itself in automatic mode.

## The request

| | |
| --- | --- |
| Request | Explicit broadcast `com.jimbobsmith.protonapi.firetv.QUICK_SWITCH` to the Proton package |
| Permission | `com.jimbobsmith.protonapi.firetv.permission.QUICK_SWITCH` (normal, defined by the Proton app) |
| Result | Broadcast `com.jimbobsmith.protonapi.firetv.QUICK_SWITCH_RESULT`, extras `ok` (boolean) and `message` (string), sent only to holders of the permission |
| Wait | Up to 10 minutes. No answer is reported as "did not answer", not as a failure. |

`ok` is true only when the new tunnel is up. A second block while a switch runs waits for the same answer, so two players never stack switches. After a switch, cached stream URLs are dropped (they belong to the old address) and the play queue is reloaded at the saved position.

Code: `drivedeck/ProtonBlockPolicy.kt` (rules, JVM-tested), `drivedeck/ProtonQuickSwitch.kt` (the request), `player/PlayerProtonSwitch.kt` (player status and resume), `error/ErrorPanelHelper.kt` (video page).

### Install order

Android grants a `normal` custom permission only to apps installed or updated after the app that defines it. If DD PipePipe was installed before the Proton app, the setting says so and asks the user to reinstall or update DD PipePipe once. The Proton project has been asked to replace the permission with a reply target and a caller allowlist.

## Testing without a VPN

`tools/proton-mock` is a stand-in APK (`com.drivedeck.protonmock`) that answers the same broadcast. Only debug builds of DD PipePipe look for it. It defines the same permission, so install it only on a device or emulator without the real Proton app, and install it **before** DD PipePipe.

```sh
./gradlew :proton-mock:assembleDebug :app:assembleDebug
adb install tools/proton-mock/build/outputs/apk/debug/proton-mock-debug.apk
adb install app/build/outputs/apk/debug/DriveDeckPlayer_*-x86_64-debug.apk

# Choose how the stand-in answers: ok | fail | no-favorites | never (delay in ms, at most 8000)
adb shell am broadcast -n com.drivedeck.protonmock/.ControlReceiver \
  -a com.drivedeck.protonmock.SET --es scenario ok --ei delay 3000

adb logcat -s ProtonMock ProtonQuickSwitch
```

Then open **Settings → Video and audio** and choose **Switch server now**. The stand-in logs the request, and the summary shows the answer ("Switched. CA#202", the failure message, or nothing yet for `never`).

## What is not proven yet

- A real Fire TV with the real Proton app and a real YouTube block.
- That the reloaded stream plays from the new address on the first try. The emulator run proves the request, the answer and the UI, not the network.
