"""
Sets the release version everywhere it lives: the root and module pom.xml files, frontend/package.json
and the top of frontend/package-lock.json. Every change ships as a new version (see CHANGELOG.md):

    python scripts/bump-version.py 1.3.1

Then add the CHANGELOG.md entry, commit, and tag:  git tag -a v1.3.1 -m "..."
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main(new: str) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+", new):
        sys.exit("version must look like 1.2.3")
    old = json.loads((ROOT / "frontend" / "package.json").read_text(encoding="utf-8"))["version"]
    if old == new:
        sys.exit(f"already {new}")
    poms = [p for p in ROOT.glob("**/pom.xml") if "node_modules" not in p.parts and "target" not in p.parts]
    changed = 0
    for pom in poms:
        text = pom.read_text(encoding="utf-8")
        updated = text.replace(f"<version>{old}</version>", f"<version>{new}</version>")
        if updated != text:
            pom.write_text(updated, encoding="utf-8")
            changed += 1
    pkg = ROOT / "frontend" / "package.json"
    pkg.write_text(pkg.read_text(encoding="utf-8").replace(f'"version": "{old}"', f'"version": "{new}"', 1), encoding="utf-8")
    lock = ROOT / "frontend" / "package-lock.json"
    lines = lock.read_text(encoding="utf-8").split("\n")
    for i in range(min(12, len(lines))):
        if lines[i].strip() == f'"version": "{old}",':
            lines[i] = lines[i].replace(old, new)
    lock.write_text("\n".join(lines), encoding="utf-8")
    print(f"{old} -> {new}: {changed} pom files, package.json, package-lock.json")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "")
