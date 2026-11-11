#!/usr/bin/env python3
"""Merge the newest upstream release into this fork and open a draft pull request.

Driven by tools/upstream-sync/upstream.json (the pin plus the fork's merge policy).
Run from the repository root with the fork's base branch checked out and full history
fetched. It never merges into the base branch: it pushes `upstream-sync/<target>` and
opens a draft PR, or opens an issue when conflicts need a person.

  sync.py                    latest upstream release (the scheduled behaviour)
  sync.py --target v1.2.3    a specific upstream tag, branch or commit
  sync.py --dry-run          do everything except push / open a PR or issue
"""
import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys
import tempfile

CONFIG = "tools/upstream-sync/upstream.json"
REMOTE = "upstream-src"


def run(*cmd, check=True, cwd=None, env=None):
    p = subprocess.run(cmd, cwd=cwd, env=env, text=True, capture_output=True)
    if check and p.returncode != 0:
        sys.exit(f"command failed ({p.returncode}): {' '.join(cmd)}\n{p.stdout}{p.stderr}")
    return p.stdout.strip()


def git(*args, **kw):
    return run("git", *args, **kw)


def summary(text):
    print(text)
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")


def version_key(tag, pattern):
    m = re.match(pattern, tag)
    if not m:
        return None
    nums = tuple(int(x) for x in m.groups()[:3])
    prerelease = 1 if m.group(0) != "v%d.%d.%d" % nums else 0  # final release sorts above its beta
    return nums + (1 - prerelease,)


def latest_release(url, pattern):
    out = git("ls-remote", "--tags", url)
    best = None
    for line in out.splitlines():
        sha, ref = line.split("\t")
        if ref.endswith("^{}"):
            continue
        tag = ref[len("refs/tags/"):]
        key = version_key(tag, pattern)
        if key and (best is None or key > best[0]):
            best = (key, tag)
    if not best:
        sys.exit(f"no upstream tag in {url} matches {pattern}")
    return best[1]


def commit_for(remote, tag, subject_template, branch):
    """Commit of an upstream release: the tag itself, or the release commit by subject."""
    if subject_template:
        subject = subject_template.replace("{tag}", tag)
        sha = git("log", "-1", "--format=%H", f"--grep=^{re.escape(subject)}$", f"{remote}/{branch}")
        if not sha:
            sys.exit(f"no commit with subject '{subject}' on {remote}/{branch}")
        return sha
    return git("rev-parse", f"refs/tags/{tag}^{{commit}}")


def matches(path, patterns):
    """`dir/**` matches anything below dir; other patterns use fnmatch (`*` also crosses `/`)."""
    for p in patterns:
        if p.endswith("/**"):
            if path.startswith(p[:-2]):
                return True
        elif fnmatch.fnmatch(path, p):
            return True
    return False


STATE = {"started": False, "base": None}  # set once this run has created its sync branch


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--target", default="")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    with open(CONFIG, encoding="utf-8") as f:
        cfg = json.load(f)
    up = cfg["upstream"]
    base = cfg.get("baseBranch", "main")
    pin = cfg["pin"]
    sub = cfg.get("submodule")

    if git("status", "--porcelain"):
        sys.exit("working tree is not clean")
    # git merge/commit need an identity; the runner has none.
    for k, v in (("NAME", "upstream-sync"), ("EMAIL", "upstream-sync@users.noreply.github.com")):
        os.environ.setdefault(f"GIT_AUTHOR_{k}", v)
        os.environ.setdefault(f"GIT_COMMITTER_{k}", v)

    # Fetch upstream with tags. The fork shares history with upstream, so this is a real merge.
    git("remote", "remove", REMOTE, check=False)
    git("remote", "add", REMOTE, up["url"])
    git("fetch", "--quiet", "--tags", REMOTE, up["branch"])

    release_url = up.get("releaseTagsUrl", up["url"])
    if args.target:
        target = args.target
        if re.match(r"^[0-9a-f]{7,40}$", target):
            commit = git("rev-parse", target)
            label = commit[:8]
        else:
            label = target
            commit = git("rev-parse", "--verify", "--quiet", f"{REMOTE}/{target}^{{commit}}", check=False) \
                or commit_for(REMOTE, target, up.get("commitSubject"), up["branch"])
    else:
        label = latest_release(release_url, up["tagPattern"])
        commit = commit_for(REMOTE, label, up.get("commitSubject"), up["branch"])

    is_release = not args.target or bool(re.match(up["tagPattern"], args.target))
    if not is_release and not re.match(r"^[0-9a-f]{7,40}$", args.target):
        label = f"{args.target}-{commit[:8]}"  # a moving branch: name the branch after the exact commit
    ahead_dev = git("rev-list", "--count", f"{pin['commit']}..{REMOTE}/{up['branch']}")
    lines = [
        f"### Upstream sync ({up['name']})",
        f"- Fork pin: `{pin['tag'] or pin['commit'][:8]}` (`{pin['commit'][:8]}`)",
        f"- Newest target: `{label}` (`{commit[:8]}`)",
        f"- Upstream `{up['branch']}` is {ahead_dev} commits ahead of the pin",
    ]
    if subprocess.run(["git", "merge-base", "--is-ancestor", commit, "HEAD"]).returncode == 0:
        summary("\n".join(lines + ["- Up to date. Nothing to do."]))
        return

    branch = f"upstream-sync/{label}"
    if not args.dry_run and git("ls-remote", "--heads", "origin", branch):
        # A previous run pushed the branch. If its PR was never opened (for example Actions was not yet
        # allowed to create pull requests), open it now instead of waiting forever.
        if run("gh", "pr", "list", "--head", branch, "--state", "all", "--json", "number", "--jq", "length") != "0":
            summary("\n".join(lines + [f"- Branch `{branch}` already has a pull request. Waiting for it to be merged or closed."]))
            return
        git("fetch", "--quiet", "origin", branch)
        sub_note = ""
        if sub and is_release:
            pinned = json.loads(git("show", f"FETCH_HEAD:{CONFIG}")).get("submodulePin", {}).get("commit")
            if pinned:
                sub_note = f"\n- Extractor submodule `{sub['path']}` -> `{pinned[:8]}` (the upstream release commit; review the extractor diff too)"
        changelog = git("log", "--no-merges", "--format=- %h %s", f"{pin['commit']}..{commit}").splitlines()
        summary("\n".join(lines + [f"- Branch `{branch}` exists without a pull request. Opening it."]))
        open_pr(branch, base, label, commit, pin, changelog, sub_note, up["name"])
        return

    have_origin = subprocess.run(["git", "rev-parse", "--verify", "-q", f"origin/{base}"], capture_output=True).returncode == 0
    git("checkout", "-q", "-B", branch, f"origin/{base}" if have_origin else base)
    merge = subprocess.run(["git", "merge", "--no-ff", "--no-commit", commit], text=True, capture_output=True)

    STATE.update(started=True, base=base)
    conflicts = git("diff", "--name-only", "--diff-filter=U").splitlines()
    if merge.returncode != 0 and not conflicts:
        sys.exit(f"git merge of {commit} failed without conflicts:\n{merge.stdout}{merge.stderr}")
    lock = cfg.get("lockfile")
    unresolved = []
    for path in conflicts:
        if lock and path == lock["path"]:
            git("checkout", "--ours", "--", path, check=False)
            git("add", "--", path)
        elif matches(path, cfg.get("keepOurs", [])):
            if subprocess.run(["git", "cat-file", "-e", f"HEAD:{path}"], capture_output=True).returncode == 0:
                git("checkout", "--ours", "--", path)
                git("add", "--", path)
            else:
                git("rm", "-q", "-f", "--", path)
        else:
            unresolved.append(path)

    changelog = git("log", "--no-merges", "--format=- %h %s", f"{pin['commit']}..{commit}").splitlines()
    summary_txt = "\n".join(lines)

    if unresolved:
        body = (
            f"Merging upstream `{label}` into `{base}` leaves {len(unresolved)} conflict(s) that need a person:\n\n"
            + "\n".join(f"- `{p}`" for p in unresolved)
            + f"\n\nAlready resolved by policy: {len(conflicts) - len(unresolved)} file(s) (`keepOurs`, lockfile).\n"
            f"Upstream changes since the pin: {len(changelog)} commits.\n\n"
            f"To reproduce: `git switch -c upstream-sync/{label} {base} && git fetch {up['url']} {commit} && git merge {commit}`\n"
        )
        git("merge", "--abort", check=False)
        git("checkout", "-q", base, check=False)
        summary(summary_txt + "\n- Conflicts need a person:\n" + "\n".join(f"  - `{p}`" for p in unresolved))
        if not args.dry_run:
            title = f"Upstream sync {label}: merge conflicts need a person"
            existing = run("gh", "issue", "list", "--label", "upstream-sync", "--state", "open", "--search", title, "--json", "number", "--jq", "length")
            if existing == "0":
                run("gh", "label", "create", "upstream-sync", "--color", "5319e7", "--description", "Upstream merge automation", check=False)
                run("gh", "issue", "create", "--title", title, "--body", body, "--label", "upstream-sync")
        return

    if lock:
        subprocess.run(lock["command"], shell=True, check=True)
        git("add", "--", lock["path"])

    pin_new = {"tag": label if is_release else None, "commit": commit}
    cfg["pin"] = pin_new
    sub_note = ""
    if sub and not is_release:
        sub_note = f"\n- Extractor submodule `{sub['path']}` was **not** bumped (only release targets move it). Bump it separately if this merge needs a newer extractor."
    elif sub:
        sub_ref = commit_for_sub(sub, label, args.target)
        git("update-index", "--add", "--cacheinfo", f"160000,{sub_ref},{sub['path']}")
        cfg["submodulePin"] = {"commit": sub_ref}
        sub_note = f"\n- Extractor submodule `{sub['path']}` -> `{sub_ref[:8]}` (the upstream release commit; review the extractor diff too)"
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
        f.write("\n")
    git("add", "--", CONFIG)

    msg = f"Merge upstream {up['name']} {label}"
    git("-c", "user.name=upstream-sync", "-c", "user.email=upstream-sync@users.noreply.github.com", "commit", "-q", "-m", msg)
    summary(summary_txt + f"\n- Clean merge on `{branch}`." + sub_note)
    if args.dry_run:
        print(f"dry run: merged cleanly, would push {branch}")
        return

    git("push", "origin", branch)
    open_pr(branch, base, label, commit, pin, changelog, sub_note, up["name"])


def open_pr(branch, base, label, commit, pin, changelog, sub_note, name):
    msg = f"Merge upstream {name} {label}"
    body = "\n".join([
        f"Merges upstream **{name} `{label}`** (`{commit[:8]}`) into `{base}`. Previous pin: `{pin['tag'] or pin['commit'][:8]}`.",
        "",
        "Generated by `.github/workflows/upstream-sync.yml`. A merge needs explicit maintainer authority, so this is a draft: it is never merged automatically.",
        sub_note.strip(),
        "",
        "## Before merging",
        "- [ ] CI is green on this PR.",
        "- [ ] Provider playback was checked on a device (CI cannot prove playback works).",
        "- [ ] Anything the fork turns off (updates, analytics, announcements) is still off.",
        "",
        f"## Upstream changes ({len(changelog)} commits)",
        *changelog[:150],
        *([f"- ...and {len(changelog) - 150} more"] if len(changelog) > 150 else []),
    ])
    with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False) as f:
        f.write(body)
    run("gh", "label", "create", "upstream-sync", "--color", "5319e7", "--description", "Upstream merge automation", check=False)
    out = run("gh", "pr", "create", "--draft", "--base", base, "--head", branch, "--title", msg, "--body-file", f.name, "--label", "upstream-sync")
    summary(f"- Opened {out}")


def commit_for_sub(sub, label, target):
    remote = REMOTE + "-sub"
    git("remote", "remove", remote, check=False)
    git("remote", "add", remote, sub["url"])
    git("fetch", "--quiet", "--tags", remote, sub["branch"])
    return commit_for(remote, label, sub.get("commitSubject"), sub["branch"])


if __name__ == "__main__":
    try:
        main()
    except BaseException:
        # Only if this run created its sync branch: drop a half-finished merge and go back to the base
        # branch. Argument errors and the clean-tree guard exit before that and touch nothing.
        if STATE["started"]:
            subprocess.run(["git", "merge", "--abort"], capture_output=True)
            subprocess.run(["git", "checkout", "-q", "-f", STATE["base"]], capture_output=True)
        raise
