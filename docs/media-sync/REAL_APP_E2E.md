# Real local application convergence

Refs platform #47, desktop #17, Player #40; parent DriveDeck #244.

A real encrypted loopback service, an isolated real DD FreeTube Electron app, and
an owned AOSP guest running the real DD PipePipe app completed the two-way campaign
on 2026-10-28. The production sources are integrated main baselines; these branches
add test drivers and evidence only. The import drafts platform46/desktop16/Player39
are separate candidates, not silently substituted into this campaign.

## Results

| Action | Result | Whole action elapsed ms |
|---|---|---:|
| FreeTube -> PipePipe subscription/category | PASS | 15631 |
| PipePipe -> FreeTube subscription/category | PASS | 18087 |
| category rename and independent membership | PASS | 18949 |
| global unsubscribe | PASS | 14845 |
| category deletion preserves subscriptions | PASS | 14635 |
| repeated manual sync is idempotent | PASS | 15169 |
| Android offline queue survives app restart | PASS | 30249 |
| service restart preserves generation and state | PASS | 15049 |
| Electron restart preserves OS vault and converged profile | PASS | 5712 |
| explicit concurrent rename conflict | PASS | 31304 |
| expanded library opt-in and initial Watch Later conflict | PASS | 15206 |
| FreeTube history and millisecond resume to Android | PASS | 15287 |
| Android deliberate rewind and newer history to FreeTube | PASS | 15466 |
| appearance settings stay local | PASS | 14860 |

These are manual production Sync commands, not background propagation. Timings
include app startup, instrumentation and UI observation; they are not pure network
latency. Actual peer storage assertions are independent of service acceptance.
Native Subscriptions/History and desktop Profile Manager screens are captured;
this does not imply that every metadata field was visually inspected. The initial
milestone desktop screenshot caught a fade; the later same-profile app restart
waited for full paint and supplies readable UI proof.

The concurrent rename conflict was surfaced and explicitly resolved with **Use
remote**. Expanded library opt-in also exposed an initial empty Watch Later conflict,
which was explicitly resolved and retained in the receipt. There is no blanket
auto-resolution. History preserved an unrelated desktop title, converted 12.345s to
12345ms, and propagated a deliberate Android rewind of 5000ms back as 5 seconds.

There is **no general portable settings kind** and **no automatic sync**. Desktop
Light and Android Dark remained independent. Provider credentials, OS preferences,
active profile, output/volume, file paths and provider metadata remain local.
The protocol supports subscription/resume/playlist/watch-later/history/profile/
profile-member, but this campaign does not qualify nonempty playlist or Watch Later
item roundtrips, provider playback/audio, real personal libraries, LAN HTTPS, or NAS.

Machine-readable expected/observed data, artifact/source pins and hashes of local
raw logs/screenshots are in [real-app-campaign-20261028.json](real-app-campaign-20261028.json).
The full campaign was executed as an initial milestone followed by a continuation
of the same synthetic dataset; a fresh all-in-one invocation is supported by the
runner but has not been executed as one uninterrupted campaign.

All scenarios passed. The original final command returned failure because its
PowerShell empty-listener query exited nonzero after the resources had stopped.
The original result is retained. An independent read-only check verified the exact
owned PIDs gone and ports5594/5595/5067/8872 free, reconciled the slot to READY, and
consumed no extra device invocation. The reporter fix has six focused tests,
including a real Windows empty-port inventory. Whole campaign:24/30 method attempts,
including3 prior Room tests and conservatively counted setup failures. No personal
device was enumerated or selected. The separately requested synthetic portal8861
is intentionally retained; it is not the test database.

## Reproduce

Windows x64, Node24 (observed24.22.1), frozen pnpm10.34.7 dependencies, JDK25,
Gradle9.5.1 and Android SDK37 are prerequisites. Keep checkouts and outputs in
D:/tmp/dd. Use the three issue branches together. In the desktop checkout:

```powershell
pnpm install --frozen-lockfile
pnpm run pack
$env:CSC_IDENTITY_AUTO_DISCOVERY='false'
# Clear any CSC_LINK/CSC_KEY_PASSWORD/WIN_CSC_LINK/WIN_CSC_KEY_PASSWORD variables
# from this test shell before packaging. Never supply release signing credentials.
pnpm exec electron-builder --config _scripts/ebuilder.config.mjs --win --x64 --dir --publish never
```

In the Player checkout, initialize the pinned extractor submodule and build only
(the build never discovers devices):

```powershell
git submodule update --init --recursive
./gradlew.bat --no-daemon --max-workers=2 assembleDebug assembleDebugAndroidTest
```

A separately reserved OWN fresh API36 default AOSP x86_64 guest is mandatory.
The current bounded recipe deliberately requires emulator5594 (ports5594/5595),
private ADB5067 with `--one-device DD_SYNC_NO_USB`,2 cores/2560MiB/headless/no
accounts/no snapshots/no audio. Store its exact launcher/QEMU/privateADB PID,
CreationDate and CommandLine receipt as `owned-processes-before-install.json`
beside the slot. The slot JSON records guest identity, `invocationCap:30` and the
current `invocations`. The checked evidence records its shape. Never reuse this
completed READY slot or an arbitrary running emulator: reserve a fresh set with a
new receipt. The script does not bootstrap or claim an existing AVD automatically.

The coordinator currently uses the documented local tool locations
`D:/tmp/dd/youtube-player-sdk/{platform-tools/adb.exe,build-tools/36.0.0/aapt2.exe}`;
provision those before running. It verifies source cleanliness and all artifact,
manifest/package/ABI/runner identities before installing ONLY the owned Test apps.
No source edits while a build/campaign executes. From the platform checkout the
single campaign command is:

```powershell
node tools/media-sync/real-app-e2e.mjs --desktop D:/tmp/dd/actual-sync-e2e-20261028/desktop --player D:/tmp/dd/actual-sync-e2e-20261028/player --slot D:/tmp/dd/sync-validation-NEW/android-test-set.json --output D:/tmp/dd/actual-sync-e2e-NEW
```

The default `--suite full` runs both initial directions and all expanded scenarios;
`--suite milestone` runs only the first two directions and explicitly retains the
reserved guest for bounded follow-up. A successful full run closes the Electron
app, HTTP listener/database, owned emulator and private ADB, verifies released ports,
and records READY. A failed data phase intentionally preserves the reserved guest
for diagnosis: it must be explicitly reconciled/cleaned before handback. A process
identity mismatch fails closed. Never kill an unverified replacement PID.

`--continue <milestone-directory>` reuses only that exact protected synthetic
owner/service/profile, without overwriting expected peer data. This is the mode
actually used for the expanded evidence. `--resume <failed-full-directory>` with
`--continue` can copy previously passed named receipts with their evidence origin;
that optimization is implemented but not runtime-qualified. Do not call it a new
fresh run. Outputs include results.json, RESULTS.md, pins-before-install.json,
raw instrumentation/driver logs and actual UI PNGs. Protected enrollment files,
vaults/databases and synthetic credentials stay out of Git.

## Test boundaries

Desktop mutations use production preload IPC and actual NeDB handlers; Android
mutations use production subscription/feed/history/state repositories and the
real NewPipeDatabase/Keystore/MediaSyncClient. No mocked sync engine or injected
expected peer database is used. The driver disables background provider fetching
and update checks only in its fresh synthetic profile, restricts test network
requests to loopback, and suppresses protocol registration. The normal Electron
sandbox and application assets remain. Literal127.0.0.1 HTTP uses the existing
production validation via explicitly targeted adb reverse; no CA/TLS/firewall or
host binding is weakened. Test-only notification permission is granted only to the
disposable guest app so its unrelated first-run explanation cannot cover proof UI.

Review/merge, distribution, signing, personal-device enrollment and NAS deployment
remain separate gates. Related field-validation issues remain open.


Post-run harness hardening also rejects reusing a released READY slot and attempts
HTTP/database closure even if Electron shutdown fails. These error paths have
focused unit coverage; the passing full data campaign was not repeated for these
reporting/cleanup-only changes.
