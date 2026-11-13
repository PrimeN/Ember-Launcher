"""Compare this run's UI screenshots with the previous run on the same branch.

Usage: screenshots.py <shots dir> <store dir> <branch> <run label>

The store is a checkout of a private repository. For each branch it keeps the latest pictures in
<branch>/current and, for every run that changed something, the before, after and difference
pictures in <branch>/runs/<run label>. Only screen names and counts reach the public run summary.
"""
import os
import shutil
import sys

from PIL import Image, ImageChops, ImageOps

TOLERANCE = 16          # per-channel difference ignored as rendering noise
CHANGED_SHARE = 0.0001  # share of pixels that must differ for a screen to count as changed
KEEP_RUNS = 30


def differs(before, after):
    if before.size != after.size:
        return True, None
    diff = ImageChops.difference(before.convert("RGB"), after.convert("RGB"))
    mask = diff.convert("L").point(lambda v: 255 if v > TOLERANCE else 0)
    changed = mask.histogram()[255]
    return changed > before.size[0] * before.size[1] * CHANGED_SHARE, mask


def diff_picture(before, mask):
    base = ImageOps.grayscale(before.convert("RGB")).point(lambda v: v // 3).convert("RGB")
    red = Image.new("RGB", base.size, (255, 40, 40))
    return Image.composite(red, base, mask)


def main(shots, store, branch, label):
    branch = branch.replace("/", "-")
    current = os.path.join(store, branch, "current")
    runs = os.path.join(store, branch, "runs")
    run = os.path.join(runs, label)
    names = sorted(f for f in os.listdir(shots) if f.endswith(".png"))
    old = set(os.listdir(current)) if os.path.isdir(current) else set()
    first = not old
    added, changed, same = [], [], []
    for name in names:
        after = Image.open(os.path.join(shots, name))
        if name not in old:
            added.append(name)
            continue
        before = Image.open(os.path.join(current, name))
        moved, mask = differs(before, after)
        if not moved:
            same.append(name)
            continue
        changed.append(name)
        os.makedirs(run, exist_ok=True)
        stem = name[:-4]
        before.save(os.path.join(run, stem + "-before.png"))
        shutil.copy(os.path.join(shots, name), os.path.join(run, stem + "-after.png"))
        if mask is not None:
            diff_picture(before, mask).save(os.path.join(run, stem + "-diff.png"))
    removed = sorted(old - set(names))
    if (added and not first) or removed:
        os.makedirs(run, exist_ok=True)
        for name in added:
            shutil.copy(os.path.join(shots, name), os.path.join(run, name[:-4] + "-new.png"))
    if os.path.isdir(run):
        with open(os.path.join(run, "README.md"), "w") as out:
            out.write(f"# {branch} run {label}\n\n")
            for title, items in (("Changed", changed), ("New", added if not first else []), ("Removed", removed)):
                if items:
                    out.write(f"## {title}\n\n" + "".join(f"- {n[:-4]}\n" for n in items) + "\n")
    # The new pictures become the branch's current set.
    shutil.rmtree(current, ignore_errors=True)
    os.makedirs(current)
    for name in names:
        shutil.copy(os.path.join(shots, name), os.path.join(current, name))
    if os.path.isdir(runs):
        for old_run in sorted(os.listdir(runs), key=lambda r: int(r.split("-")[0]) if r.split("-")[0].isdigit() else 0)[:-KEEP_RUNS]:
            shutil.rmtree(os.path.join(runs, old_run), ignore_errors=True)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    lines = [f"### Screenshots: {len(names)} screens"]
    if first:
        lines.append("First run on this branch: these pictures are the starting point.")
    else:
        lines.append(f"{len(changed)} changed, {len(added)} new, {len(removed)} removed, {len(same)} unchanged.")
        for title, items in (("Changed", changed), ("New", added), ("Removed", removed)):
            if items:
                lines.append(f"{title}: " + ", ".join(n[:-4] for n in items))
        if os.path.isdir(run):
            lines.append(f"Pictures are in the private screenshots repository under {branch}/runs/{label}.")
    text = "\n\n".join(lines) + "\n"
    print(text)
    if summary:
        with open(summary, "a") as out:
            out.write(text)


if __name__ == "__main__":
    main(*sys.argv[1:5])
