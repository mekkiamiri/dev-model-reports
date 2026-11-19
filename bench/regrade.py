#!/usr/bin/env python3
"""Rejoue l'axe sécurité (attaques cachées + analyse statique) sur le code gardé, sans rappeler le modèle.

Usage :
  python3 bench/regrade.py --model <nom> [--tasks id,id] [--results results] [--no-static]

Pour chaque passage de results/<nom>/<tâche>.json, reprend results/<nom>/artifacts/<tâche>/run-<k>/,
l'attaque avec les attaques actuelles de la tâche et refait l'analyse statique. Les champs attacks_* et
static du passage sont remplacés, et le passage reçoit `regraded_at`. La note fonctionnelle, la durée et
le coût ne changent pas. Refusé si l'empreinte de la tâche a changé depuis le passage.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

BENCH = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCH))
import run  # noqa: E402


def regrade(model_dir: Path, task: dict, static: bool) -> dict | None:
    path = model_dir / f"{task['id']}.json"
    if not path.exists():
        return None
    record = json.loads(path.read_text(encoding="utf-8"))
    if record.get("task_fingerprint") != run.fingerprint(task):
        raise SystemExit(f"{path} : la tâche a changé depuis le passage, impossible de rejouer")
    now = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    for r in record["runs"]:
        artifacts = model_dir / "artifacts" / task["id"] / f"run-{r['run']}"
        if not artifacts.is_dir():
            continue
        r.update(run.grade_attacks(task, artifacts) or {})
        if static:
            import security
            r["static"] = security.scan(artifacts, model_dir / "security" / f"{task['id']}.run{r['run']}.sarif")
        r["regraded_at"] = now
    record["attacks_fingerprint"] = run.attacks_fingerprint(task)
    record["attacks"] = [{k: a[k] for k in ("id", "famille", "exigee", "reference_vulnerable") if k in a}
                         for a in task.get("attacks") or []]
    record.update(run.aggregate(record["runs"]))
    path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return record


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", required=True)
    p.add_argument("--tasks")
    p.add_argument("--tasks-dir", type=Path, default=run.TASKS_DIR)
    p.add_argument("--results", type=Path, default=run.ROOT / "results")
    p.add_argument("--no-static", action="store_true")
    args = p.parse_args(argv)
    for task in run.load_tasks(args.tasks.split(",") if args.tasks else None, args.tasks_dir):
        rec = regrade(args.results / args.model, task, not args.no_static)
        if rec is None:
            print(f"--       {task['id']:<16} pas de résultat")
            continue
        last = rec["runs"][-1]
        print(f"rejoué   {task['id']:<16} attaques {last.get('attacks_passed')}/{last.get('attacks_total')}"
              f"  statique {last.get('static')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
