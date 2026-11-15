# Keeping up with upstream PipePipe

`main` is the integrated fork line and upstream is a reference, so nothing here merges by itself.
The `Upstream sync` workflow (`.github/workflows/upstream-sync.yml`) checks every Monday 06:17 UTC,
and on demand from the Actions tab, for a newer upstream **release** than the pin in
`tools/upstream-sync/upstream.json`.

When there is one it:

1. merges the upstream client release commit into `upstream-sync/<tag>`, resolving only the
   paths listed in `keepOurs` by keeping the fork's version;
2. moves the `vendor/PipePipeExtractor` submodule to the extractor's release commit of the same
   version and records both pins in `upstream.json`;
3. opens a **draft** PR with the upstream change list and a merge checklist. CI runs on it.

If any other file conflicts it opens an `upstream-sync` issue listing them instead. A person (or a
Claude thread) resolves those on the branch from the reproduce command in the issue.

Run it by hand from Actions with an optional target: a tag, an upstream `dev` commit for an urgent
fix, or empty for the newest release. Only release targets move the extractor submodule.

Merging the PR still needs maintainer authority and a device playback check: CI cannot prove that
YouTube plays. Preserve the pin file in the merge so the next run knows where it stands.

One-time setup: add a fine-grained token as the repository secret `UPSTREAM_SYNC_TOKEN` (this repository;
Contents, Pull requests, Issues and Workflows, all read/write; Workflows is needed because a merge can
bring in upstream workflow files and GitHub rejects such a push without it), enable Actions to create pull
requests (Settings -> Actions -> General), and if the repository is a GitHub fork enable scheduled workflows
in the Actions tab.
