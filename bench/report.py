"""Générateur de rapport : results/<modèle>/ + grille de ressenti -> reports/<modèle>-<date>.md et .html

Usage :
  python3 bench/report.py --model <nom> [--date AAAA-MM-JJ] [--results results] [--out reports]
  python3 bench/report.py --model <nom> --template   # crée results/<nom>/ressenti.json à remplir

Un modèle marqué « example » (faux agent) produit un rapport barré d'un bandeau
« EXEMPLE, pas un vrai modèle ». Aucune note de ressenti n'est inventée : un critère
non rempli s'affiche « non noté ».
"""

from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRILLE = ROOT / "bench" / "grille.json"
KIND_LABEL = {"prd": "PRD vers code", "bug": "bug à corriger", "refactoring": "refactoring"}
EXAMPLE_BANNER = "EXEMPLE, pas un vrai modèle : rapport généré avec un faux agent, sans appel à un modèle. Il montre le format, pas une mesure."


def load(model: str, results: Path) -> dict:
    mdir = results / model
    if not (mdir / "meta.json").exists():
        raise SystemExit(f"pas de résultats pour {model} dans {results} (lancer bench/run.py --agent {model})")
    meta = json.loads((mdir / "meta.json").read_text(encoding="utf-8"))
    tasks = []
    for f in sorted(mdir.glob("*.json")):
        if f.name in ("meta.json", "ressenti.json"):
            continue
        tasks.append(json.loads(f.read_text(encoding="utf-8")))
    ressenti_file = mdir / "ressenti.json"
    ressenti = json.loads(ressenti_file.read_text(encoding="utf-8")) if ressenti_file.exists() else None
    grille = json.loads(GRILLE.read_text(encoding="utf-8"))
    return {"meta": meta, "tasks": tasks, "ressenti": ressenti, "grille": grille}


def template(model: str, results: Path) -> Path:
    grille = json.loads(GRILLE.read_text(encoding="utf-8"))
    path = results / model / "ressenti.json"
    if path.exists():
        raise SystemExit(f"{path} existe déjà")
    data = {
        "grille_version": grille["version"],
        "evaluator": "Mekki Amiri",
        "date": None,
        "criteria": {c["id"]: {"score": None, "evidence": ""} for c in grille["criteria"]},
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


# ---------------------------------------------------------------- mise en forme commune

def fmt_tokens(run: dict) -> str:
    t = run.get("tokens")
    if not t:
        return "non fourni"
    total = sum(v for v in t.values() if isinstance(v, int))
    return f"{total:,}".replace(",", " ")


def fmt_cost(value) -> str:
    return "non fourni" if value is None else f"{value:.2f} $"


def summary(data: dict) -> dict:
    tasks = data["tasks"]
    costs = [t.get("total_cost_usd") for t in tasks]
    return {
        "passed": sum(t["passed"] for t in tasks),
        "total": len(tasks),
        "duration": round(sum(t["mean_agent_duration_s"] for t in tasks), 1),
        "cost": round(sum(costs), 2) if costs and all(c is not None for c in costs) else None,
        "commits": sorted({t.get("bench_commit") or "inconnu" for t in tasks}),
        "repeat": sorted({t["repeat"] for t in tasks}),
    }


def task_rows(data: dict) -> list[list[str]]:
    rows = []
    for t in data["tasks"]:
        last = t["runs"][-1]
        tests = f"{last['tests_passed']}/{last['tests_total']}" if last["tests_total"] else last["stage"]
        rows.append([
            f"{t['title']} ({KIND_LABEL.get(t['kind'], t['kind'])})",
            ("réussie" if t["passed"] else "échouée") + f" · {t['passed_runs']}/{t['repeat']}",
            tests,
            f"{t['mean_agent_duration_s']} s",
            fmt_tokens(last),
            fmt_cost(t.get("total_cost_usd")),
        ])
    return rows


def ressenti_rows(data: dict) -> list[list[str]]:
    scores = (data["ressenti"] or {}).get("criteria", {})
    rows = []
    for c in data["grille"]["criteria"]:
        entry = scores.get(c["id"]) or {}
        score = entry.get("score")
        rows.append([c["name"], "non noté" if score is None else f"{score}/5", entry.get("evidence") or "—"])
    return rows


def failures(data: dict) -> list[tuple[str, list[str]]]:
    out = []
    for t in data["tasks"]:
        last = t["runs"][-1]
        items = last["failures"][:3] or (last["compile_errors"].splitlines()[:2] if last.get("compile_errors") else [])
        if not t["passed"] and items:
            out.append((t["title"], items))
    return out


# ---------------------------------------------------------------- Markdown

def md_table(header: list[str], rows: list[list[str]]) -> str:
    esc = lambda s: str(s).replace("|", "\\|")
    lines = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    lines += ["| " + " | ".join(esc(c) for c in r) + " |" for r in rows]
    return "\n".join(lines)


def to_markdown(data: dict, date: str) -> str:
    meta, s = data["meta"], summary(data)
    parts = []
    if meta.get("example"):
        parts.append(f"> **{EXAMPLE_BANNER}**\n")
    parts.append(f"# {meta['label']} : rapport du {date}\n")
    parts.append(
        f"- Modèle : `{meta['model']}`" + (f" · sorti le {meta['release_date']}" if meta.get("release_date") else "")
        + f"\n- Tâches réussies (tout ou rien, tests cachés) : **{s['passed']}/{s['total']}**"
        + f"\n- Durée totale de l'agent : {s['duration']} s · coût : {fmt_cost(s['cost'])}"
        + f"\n- Passages par tâche : {', '.join(map(str, s['repeat']))} · version du banc : {', '.join(s['commits'])}\n"
    )
    parts.append("## Résultats par tâche\n")
    parts.append(md_table(["Tâche", "Résultat", "Tests cachés", "Durée", "Tokens", "Coût"], task_rows(data)) + "\n")
    fails = failures(data)
    if fails:
        parts.append("## Ce qui a échoué\n")
        for title, items in fails:
            parts.append(f"- **{title}** : " + " ; ".join(f"`{i}`" for i in items))
        parts.append("")
    parts.append(f"## Ressenti (grille v{data['grille']['version']}, voir RESSENTI.md)\n")
    r = data["ressenti"]
    if r:
        parts.append(f"Notateur : {r.get('evaluator', 'non indiqué')} · {r.get('date') or 'date non indiquée'}\n")
    parts.append(md_table(["Critère", "Note", "Preuve"], ressenti_rows(data)) + "\n")
    parts.append(
        "## Méthode et limites\n\n"
        "Protocole complet : `PROTOCOLE.md`. Tâches neuves écrites pour ce banc, tests d'acceptation cachés, "
        "aucun juge LLM pour la réussite. Un seul notateur pour le ressenti. Peu de passages par tâche : "
        "un écart d'une tâche entre deux modèles n'est pas significatif.\n"
    )
    return "\n".join(parts)


# ---------------------------------------------------------------- HTML

CSS = """
:root{--bg:#121416;--fg:#e9e4d8;--muted:#a9a39a;--dim:#5b5f63;--amber:#ffb000;--line:#2a2e31}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.6 'Martian Mono',ui-monospace,monospace}
main{max-width:980px;margin:0 auto;padding:48px 20px 80px}
h1,h2{font-family:'Instrument Serif',serif;font-weight:400;letter-spacing:-.01em}
h1{font-size:2.6rem;line-height:1.1;margin:0 0 16px}h2{font-size:1.8rem;margin:48px 0 12px;color:var(--amber)}
.banner{border:2px dashed var(--amber);color:var(--amber);padding:14px 18px;margin:0 0 28px}
.facts{display:flex;flex-wrap:wrap;gap:12px;margin:20px 0}.facts div{border:1px solid var(--dim);padding:10px 16px}
.facts b{display:block;font-size:1.6rem;color:var(--amber)}.facts span{color:var(--muted);font-size:.8rem;text-transform:uppercase;letter-spacing:.1em}
.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-size:.9rem}
th{color:var(--muted);text-align:left;font-weight:400;text-transform:uppercase;letter-spacing:.08em;font-size:.75rem;border-bottom:2px solid var(--dim);padding:8px}
td{border-bottom:1px solid var(--line);padding:8px;vertical-align:top}code{color:var(--amber)}p.muted,li{color:var(--muted)}
"""
FONTS = "https://fonts.googleapis.com/css2?family=Instrument+Serif:ital@0;1&family=Martian+Mono:wght@400;500&display=swap"


def html_table(header: list[str], rows: list[list[str]]) -> str:
    e = html.escape
    head = "".join(f"<th>{e(h)}</th>" for h in header)
    body = "".join("<tr>" + "".join(f"<td>{e(str(c))}</td>" for c in r) + "</tr>" for r in rows)
    return f'<div class="scroll"><table><tr>{head}</tr>{body}</table></div>'


def to_html(data: dict, date: str) -> str:
    e = html.escape
    meta, s = data["meta"], summary(data)
    banner = f'<div class="banner">{e(EXAMPLE_BANNER)}</div>' if meta.get("example") else ""
    fails = "".join(
        f"<li><b>{e(t)}</b> : " + " ; ".join(f"<code>{e(i)}</code>" for i in items) + "</li>" for t, items in failures(data)
    )
    r = data["ressenti"]
    who = f"<p class='muted'>Notateur : {e(r.get('evaluator', 'non indiqué'))} · {e(r.get('date') or 'date non indiquée')}</p>" if r else ""
    title = f"{meta['label']} : rapport du {date}"
    return f"""<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{e(title)}</title><link rel="preconnect" href="https://fonts.googleapis.com"><link href="{FONTS}" rel="stylesheet">
<!-- Généré par bench/report.py : ne pas modifier à la main. -->
<style>{CSS}</style></head><body><main>
{banner}<h1>{e(title)}</h1>
<p class="muted">Modèle <code>{e(meta['model'])}</code>{' · sorti le ' + e(meta['release_date']) if meta.get('release_date') else ''} · version du banc {e(', '.join(s['commits']))}</p>
<div class="facts"><div><b>{s['passed']}/{s['total']}</b><span>tâches réussies</span></div>
<div><b>{s['duration']} s</b><span>durée de l'agent</span></div><div><b>{e(fmt_cost(s['cost']))}</b><span>coût</span></div>
<div><b>{e(', '.join(map(str, s['repeat'])))}</b><span>passage(s) par tâche</span></div></div>
<h2>Résultats par tâche</h2>
{html_table(["Tâche", "Résultat", "Tests cachés", "Durée", "Tokens", "Coût"], task_rows(data))}
{'<h2>Ce qui a échoué</h2><ul>' + fails + '</ul>' if fails else ''}
<h2>Ressenti (grille v{data['grille']['version']})</h2>{who}
{html_table(["Critère", "Note", "Preuve"], ressenti_rows(data))}
<h2>Méthode et limites</h2>
<p class="muted">Tâches neuves écrites pour ce banc, tests d'acceptation cachés, aucun juge LLM pour la réussite.
Un seul notateur pour le ressenti. Peu de passages par tâche : un écart d'une tâche entre deux modèles n'est pas significatif.
Protocole complet : PROTOCOLE.md du dépôt.</p>
</main></body></html>
"""


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--model", required=True)
    p.add_argument("--date", default=dt.date.today().isoformat())
    p.add_argument("--results", type=Path, default=ROOT / "results")
    p.add_argument("--out", type=Path, default=ROOT / "reports")
    p.add_argument("--template", action="store_true", help="créer results/<modèle>/ressenti.json à remplir")
    args = p.parse_args(argv)
    if args.template:
        print(f"écrit {template(args.model, args.results)}")
        return 0
    data = load(args.model, args.results)
    args.out.mkdir(parents=True, exist_ok=True)
    stem = f"{args.model}-{args.date}"
    (args.out / f"{stem}.md").write_text(to_markdown(data, args.date), encoding="utf-8")
    (args.out / f"{stem}.html").write_text(to_html(data, args.date), encoding="utf-8")
    print(f"écrit {args.out / (stem + '.md')} et .html")
    return 0


if __name__ == "__main__":
    sys.exit(main())
