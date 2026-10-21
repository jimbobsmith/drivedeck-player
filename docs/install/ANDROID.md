# Install DD Player without ADB

A normal Android APK installer works with USB debugging disabled. This fork's current development installer is **DriveDeck Player Test** (`com.drivedeck.player.test`); it has the blue/mint DD icon. It is separate from PipePipe, DD and Spotify. This is a Test build, separate from the production distribution. The published alpha01 installer predates the [standalone updater](../updates/README.md); alpha02 prepares its one-time bootstrap.

## On each phone or tablet

1. Download the **universal-debug.apk** from the approved Test prerelease, or copy that same APK to Downloads using ordinary USB file transfer, a network share or your preferred file-transfer app. One universal APK covers ARM64, ARM32, x86 and x86_64; there is no CPU-specific download to choose.
2. Open the APK from Downloads or your file manager. If Android asks, allow that browser/file manager to install unknown apps. On Fire OS this is under Settings Ã¢†’ Security & Privacy Ã¢†’ Apps from Unknown Sources; other Android devices usually show Install unknown apps under Special app access. Follow the device's installer prompt. Developer options and USB debugging are not needed.
3. Choose **Install** (or **Update** if Player Test is already installed), then **Open**. The launcher should show the blue/mint D/play mark and the label **DriveDeck Player Test**.
4. If desired, turn off the browser/file manager's install permission afterwards. It is separate from the Player's own permissions.

For the icon field check, open Player once, return Home, find it in the app list and check that the D/play mark is complete and readable. Check portrait/landscape where your launcher supports them. On a launcher with themed icons enabled, the same mark should use the launcher's tint. There is no need to change your launcher, clear its data or enable debugging for this check.

## Existing installations and updates

Install over the existing **Player Test** app to retain its subscriptions, history and settings. The universal version code is the highest code used by this version's ABI builds (104 for 0.1.0-alpha01), so it does not request a version downgrade. The installer must also have the same signing certificate as the installed app. The prepared candidate uses the existing local Android debug signer, independently checked with apksigner; no production or upstream signing key is used.

If Android reports a package/signature conflict, keep the existing app and data and obtain a matching installer. Do not uninstall or clear data as an installation workaround. Compatibility with other machines' independently generated debug keys is not promised.

Upstream PipePipe updates remain disabled. The published alpha01 app must receive one newer approved bootstrap APK manually. Alpha02 adds the separate opt-in DD Player updater described in [Updates](../updates/README.md); after enabling it, future matching releases can be checked/downloaded in-app with Android installation confirmation. The bootstrap is not a published download until explicitly approved. Durable production signing remains a separate gate.

## Build and evidence

Issue [#22](https://github.com/jimbobsmith/drivedeck-player/issues/22). Run the ordinary foundation debug recipe. It now emits the universal APK alongside the unchanged four ABI-specific outputs:

```
app/build/outputs/apk/debug/DriveDeckPlayer_0.1.0-alpha01-universal-debug.apk
```

The APK requires Android API23+; optional personal-library sync retains its separate API26+ gate. The [manual installer evidence](manual-installer-evidence.json) records its hash, public signer fingerprint, all four native ABI directories, manifest/runtime audit and upgrade version relationship. Build and signature checks are not physical installation evidence. Phone/tablet icon acceptance remains in [#20](https://github.com/jimbobsmith/drivedeck-player/issues/20) until observed.

Publishing a Test prerelease is a separate maintainer-approved action. Draft assets are candidates and are not the public download channel. Device identifiers, screenshots of personal content, APKs and private signing material stay outside Git.
