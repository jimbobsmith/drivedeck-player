# Import and campaign integration, 2026-10-29

Tracked by issues38/40. Import PR39 is merged at `bd235bd76c03b209aa6ebb46e392589e8b410017`.
The companion campaign PR contributes test drivers and historical receipts.
Product source remains the reviewed import source; changes needed to combine the
branches preserve both documentation additions. Combined-source validation is
recorded below after its frozen-input checks complete.

The source review was performed independently by DD HQ merge coordinator
`/root/merge_sync_batch` at the recorded PR heads. No blocking product finding was
reported in the inspected archive/projection/journal/Room/IPC/isolation paths.
The one reporting finding (a future runner falsely claiming import exclusion) is
fixed separately in platform48. Formal GitHub approval is not claimed. Required
checks are not bypassed; merge authority was verified from the human in DD HQ.

The original14 runtime scenario rows used their original baseline SHAs and did
not include these import candidates. Prior import UI and3 Room tests have their
own exact inputs. These receipts are reused only for unchanged relevant inputs;
they are not a fresh combined-source runtime campaign. Sync remains manual, with
no general settings kind. Nonempty playlist/Watch Later cross-app roundtrips,
Android import Activity orientation, personal libraries/provider metadata and
NAS/LAN/private-pull/reboot/restore remain field gates. No release, image
publication, NAS action or device installation is part of this integration.

Final merge IDs/authoritative branch heads are recorded on the linked issues;
implementation and test/doc ancestry are verified after GitHub merges.

Combined frozen input `6c71b27adf9d9a7251de5a92cd9f7581f468f5a8` passes app and combined instrumentation builds, validates66 successful JVM test outputs, writes the195-artifact runtime audit and passes Test APK manifest/identity audit. The audit initially failed under configuration cache; rerunning the supported command with `--no-configuration-cache` passes, with no audit/project setting change. Raw failures are retained. See `integration-evidence-20261029.json`; no guest or personal device was installed or operated. Later receipt commits change documentation only.
