"""Construit les données de la page (docs/data.js) et copie les rapports HTML dans docs/rapports/.

Usage :
  python3 bench/build_site.py          # écrit docs/data.js et docs/rapports/
  python3 bench/build_site.py --check  # échoue si la page n'est pas à jour (CI)

Les rapports d'exemple (faux agent) sont listés à part et marqués comme tels :
la zone « Rapports » de la page ne contient que des rapports de vrais modèles.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
REPORT_NAME = re.compile(r"^(?P<model>.+)-(?P<date>\d{4}-\d{2}-\d{2})\.html$")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def tasks() -> list[dict]:
    out = []
    for task_json in sorted((ROOT / "tasks").glob("*/task.json")):
        t = json.loads(task_json.read_text(encoding="utf-8"))
        ws = task_json.parent / "workspace"
        brief = next((read(ws / n) for n in ("PRD.md", "ISSUE.md", "REFACTOR.md") if (ws / n).exists()), "")
        out.append({
            "id": t["id"],
            "kind": t["kind"],
            "title": t["title"],
            "summary": t["summary"],
            "brief": brief,
            "contract": read(ws / "CONTRACT.md"),
            "hidden_tests": t["hidden_tests"],
            "mutants": len([p for p in (task_json.parent / "mutants").glob("*") if p.is_dir()]),
        })
    return out


def reports() -> tuple[list[dict], list[dict], dict[str, str]]:
    """(rapports réels, rapports d'exemple, fichiers à copier)."""
    real, examples, files = [], [], {}
    for path in sorted((ROOT / "reports").glob("*.html")):
        m = REPORT_NAME.match(path.name)
        if not m:
            continue
        meta_file = ROOT / "results" / m["model"] / "meta.json"
        meta = json.loads(read(meta_file)) if meta_file.exists() else {"label": m["model"], "example": False}
        entry = {"model": m["model"], "label": meta.get("label", m["model"]), "date": m["date"], "href": f"rapports/{path.name}"}
        (examples if meta.get("example") else real).append(entry)
        files[f"rapports/{path.name}"] = read(path)
    real.sort(key=lambda r: r["date"], reverse=True)
    return real, examples, files


def build() -> dict[str, str]:
    real, examples, files = reports()
    data = {
        "tasks": tasks(),
        "grille": json.loads(read(ROOT / "bench" / "grille.json")),
        "reports": real,
        "examples": examples,
    }
    files["data.js"] = (
        "// Généré par bench/build_site.py : ne pas modifier à la main.\n"
        "window.BENCH = " + json.dumps(data, ensure_ascii=False, indent=1) + ";\n"
    )
    return files


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--check", action="store_true")
    args = p.parse_args(argv)
    files = build()
    stale = [name for name, content in files.items() if read(DOCS / name) != content]
    if args.check:
        if stale:
            print("page pas à jour (lancer python3 bench/build_site.py) : " + ", ".join(stale))
            return 1
        print("page à jour")
        return 0
    for name, content in files.items():
        (DOCS / name).parent.mkdir(parents=True, exist_ok=True)
        (DOCS / name).write_text(content, encoding="utf-8")
    print(f"écrit {len(files)} fichier(s) dans docs/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
