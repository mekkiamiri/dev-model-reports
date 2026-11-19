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
KIND_LABEL = {"prd": "PRD vers code", "bug": "bug à corriger", "refactoring": "refactoring", "securite": "sécurité"}
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

def run_tokens(run: dict) -> int | None:
    t = run.get("tokens")
    return sum(v for v in t.values() if isinstance(v, int)) if t else None


def fmt_tokens(task: dict) -> str:
    """Moyenne par passage, sur les passages qui ont fourni leurs tokens."""
    known = [n for n in (run_tokens(r) for r in task["runs"]) if n is not None]
    if not known:
        return "non fourni"
    mean = f"{round(sum(known) / len(known)):,}".replace(",", " ")
    return mean + ("" if len(known) == len(task["runs"]) else f" ({len(known)}/{len(task['runs'])} passages)")


def fmt_cost(value, known: int | None = None, total: int | None = None) -> str:
    if value is None:
        return "non fourni"
    partial = f" ({known}/{total} passages)" if known is not None and total and known < total else ""
    return f"{value:.2f} ${partial}"


def fmt_tests(task: dict) -> str:
    """Tests passés à chaque passage, par exemple « 25/25, 24/25, 25/25 »."""
    parts = []
    for r in task["runs"]:
        cell = f"{r['tests_passed']}/{r['tests_total']}" if r["tests_total"] else r["stage"]
        if r.get("external_failure"):
            cell += " (échec extérieur : l'outil n'a pas travaillé)"
        parts.append(cell)
    return ", ".join(parts)


def fmt_attacks(task: dict, kind: str) -> str:
    """Attaques repoussées par passage, exigées ou implicites, par exemple « 8/9, 9/9 ». Tâche sans attaques : « — »."""
    parts = []
    for r in task["runs"]:
        total = r.get(f"attacks_{kind}_total")
        if total is None:
            return "—"
        parts.append(f"{r[f'attacks_{kind}_passed']}/{total}" if total else "aucune")
    return ", ".join(parts) or "—"


def fmt_static(task: dict) -> str:
    """Résultats d'analyse statique par passage : haute/moyenne/basse ; « non analysé » sans semgrep."""
    parts = []
    for r in task["runs"]:
        st = r.get("static")
        parts.append(f"{st['high']}/{st['medium']}/{st['low']}" if st else "non analysé")
    return ", ".join(parts)


def attack_totals(tasks: list[dict]) -> dict | None:
    """Somme sur toutes les tâches et tous les passages ; None si aucune tâche n'a d'attaques."""
    out = {"required_passed": 0, "required_total": 0, "implicit_passed": 0, "implicit_total": 0}
    seen = False
    for t in tasks:
        for r in t["runs"]:
            if r.get("attacks_total") is None:
                continue
            seen = True
            for k in out:
                out[k] += r.get("attacks_" + k, 0)
    return out if seen else None


def summary(data: dict) -> dict:
    tasks = data["tasks"]
    costs = [t.get("total_cost_usd") for t in tasks if t.get("total_cost_usd") is not None]
    complete = all(t.get("cost_known_runs", 0) == t["repeat"] for t in tasks)
    return {
        "passed": sum(t["passed"] for t in tasks),
        "total": len(tasks),
        "runs_passed": sum(t["passed_runs"] for t in tasks),
        "runs_total": sum(t["repeat"] for t in tasks),
        "duration": round(sum(t["mean_agent_duration_s"] for t in tasks), 1),
        "cost": round(sum(costs), 2) if costs else None,
        "cost_complete": complete,
        "commits": sorted({t.get("bench_commit") or "inconnu" for t in tasks}),
        "tools": sorted({t.get("tool_version") or "non relevée" for t in tasks}),
        "sets": sorted({t.get("task_set", "public") for t in tasks}),
        "repeat": sorted({t["repeat"] for t in tasks}),
        "attacks": attack_totals(tasks),
    }


def task_rows(data: dict) -> list[list[str]]:
    rows = []
    for t in data["tasks"]:
        rows.append([
            f"{t['title']} ({KIND_LABEL.get(t['kind'], t['kind'])})",
            ("réussie" if t["passed"] else "échouée") + f" · {t['passed_runs']}/{t['repeat']}",
            fmt_tests(t),
            fmt_attacks(t, "required"),
            fmt_attacks(t, "implicit"),
            fmt_static(t),
            f"{t['mean_agent_duration_s']} s",
            fmt_tokens(t),
            fmt_cost(t.get("total_cost_usd"), t.get("cost_known_runs"), t["repeat"]),
            t.get("task_fingerprint", "?"),
        ])
    return rows


TASK_HEADER = ["Tâche", "Fonctionnel (k/N)", "Tests cachés par passage", "Attaques exigées", "Attaques implicites",
               "Statique (haute/moy./basse)", "Durée moy.", "Tokens moy.", "Coût", "Empreinte"]


def ressenti_rows(data: dict) -> list[list[str]]:
    scores = (data["ressenti"] or {}).get("criteria", {})
    rows = []
    for c in data["grille"]["criteria"]:
        entry = scores.get(c["id"]) or {}
        score = entry.get("score")
        rows.append([c["name"], "non noté" if score is None else f"{score}/5", entry.get("evidence") or "—"])
    return rows


def failures(data: dict) -> list[tuple[str, list[str]]]:
    """Échecs agrégés sur tous les passages, avec leur fréquence. Lot privé : pas de détail
    (les noms des tests cachés ne doivent pas fuiter)."""
    out = []
    for t in data["tasks"]:
        if t["passed"]:
            continue
        if t.get("task_set", "public") != "public":
            out.append((t["title"], [f"détail non publié (lot privé « {t['task_set']} »)"]))
            continue
        counts: dict[str, int] = {}
        for r in t["runs"]:
            items = r["failures"][:3] or (r["compile_errors"].splitlines()[:2] if r.get("compile_errors") else [r["stage"]])
            for i in items:
                counts[i] = counts.get(i, 0) + 1
        n = len(t["runs"])
        out.append((t["title"], [f"{i} ({c}/{n} passages)" if n > 1 else i for i, c in counts.items()][:5]))
    return out


def breaches(data: dict) -> list[tuple[str, list[str]]]:
    """Failles (attaques non repoussées) agrégées sur les passages. Lot privé : aucun nom d'attaque publié."""
    out = []
    for t in data["tasks"]:
        failed = [i for r in t["runs"] for i in r.get("attacks_failed") or []]
        if not failed:
            continue
        if t.get("task_set", "public") != "public":
            out.append((t["title"], [f"{len(failed)} faille(s), détail non publié (lot privé « {t['task_set']} »)"]))
            continue
        kinds = {a["id"]: ("exigée" if a.get("exigee") else "implicite") + (", la référence y tombe aussi" if a.get("reference_vulnerable") else "")
                 for a in t.get("attacks") or []}
        n = len(t["runs"])
        counts: dict[str, int] = {}
        for i in failed:
            counts[i] = counts.get(i, 0) + 1
        out.append((t["title"], [f"{i} ({kinds.get(i, '?')})" + (f" ({c}/{n} passages)" if n > 1 else "") for i, c in counts.items()]))
    return out


def header_lines(meta: dict, s: dict) -> list[str]:
    cost = fmt_cost(s["cost"]) + ("" if s["cost_complete"] or s["cost"] is None else " (coût manquant sur certains passages)")
    return [
        f"Modèle : `{meta['model']}`" + (f" · sorti le {meta['release_date']}" if meta.get("release_date") else ""),
        f"Tâches réussies (tout ou rien, tests cachés, tous les passages) : **{s['passed']}/{s['total']}** · passages réussis : {s['runs_passed']}/{s['runs_total']}",
        f"Durée totale de l'agent (moyenne par tâche) : {s['duration']} s · coût : {cost}",
        f"Passages par tâche : {', '.join(map(str, s['repeat']))} · lot de tâches : {', '.join(s['sets'])}",
        f"Version du banc : {', '.join(s['commits'])} · outil : {', '.join(s['tools'])}",
    ] + ([f"Attaques repoussées (tous passages) : exigées {a['required_passed']}/{a['required_total']}"
          f" · implicites {a['implicit_passed']}/{a['implicit_total']}"] if (a := s["attacks"]) else [])


# ---------------------------------------------------------------- calendrier des passages (routine 48 h)

RESSENTI_BANNER = "Ressenti à noter : la grille (RESSENTI.md) n'est pas encore remplie pour ce modèle."


def _time(value):
    if not value:
        return None
    try:
        t = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None
    return t if t.tzinfo else t.replace(tzinfo=dt.timezone.utc)


def calendar(data: dict) -> dict:
    """Passages datés (H + x h, H = sortie du modèle), hors délai, modèles réellement utilisés."""
    h = _time(data["meta"].get("release_date"))
    starts: dict[int, dt.datetime] = {}
    used: set[str] = set()
    for t in data["tasks"]:
        for r in t["runs"]:
            used.update(r.get("models_used") or [])
            when = _time(r.get("date"))
            if when:
                k = r.get("passage") or r["run"]
                starts[k] = min(starts.get(k, when), when)
    rows = [[f"Passage {k}", when.strftime("%Y-%m-%d %H:%M UTC"),
             f"H + {(when - h).total_seconds() / 3600:.0f} h" if h else "date de sortie inconnue"]
            for k, when in sorted(starts.items())]
    late = bool(data["meta"].get("hors_delai")) or bool(h and starts and max(starts.values()) > h + dt.timedelta(hours=48))
    return {"rows": rows, "late": late, "models_used": sorted(used),
            "ressenti_missing": data["ressenti"] is None and not data["meta"].get("example")}


CALENDAR_HEADER = ["Passage", "Début", "Depuis la sortie"]
LATE_NOTE = "Hors délai : au moins un passage a eu lieu plus de 48 h après la sortie du modèle."


def calendar_markdown(data: dict) -> str:
    c = calendar(data)
    if not c["rows"]:
        return ""
    parts = ["## Calendrier des passages\n"]
    if c["late"]:
        parts.append(f"**{LATE_NOTE}**\n")
    parts.append(md_table(CALENDAR_HEADER, c["rows"]) + "\n")
    if c["models_used"]:
        parts.append("Modèles réellement utilisés (déclarés par l'outil) : " + ", ".join(f"`{m}`" for m in c["models_used"]) + "\n")
    return "\n".join(parts)


def calendar_html(data: dict) -> str:
    c, e = calendar(data), html.escape
    if not c["rows"]:
        return ""
    late = f"<p><b>{e(LATE_NOTE)}</b></p>" if c["late"] else ""
    used = (f"<p class='muted'>Modèles réellement utilisés (déclarés par l'outil) : {e(', '.join(c['models_used']))}</p>"
            if c["models_used"] else "")
    return f"<h2>Calendrier des passages</h2>{late}{html_table(CALENDAR_HEADER, c['rows'])}{used}"


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
    if calendar(data)["ressenti_missing"]:
        parts.append(f"> **{RESSENTI_BANNER}**\n")
    parts.append(f"# {meta['label']} : rapport du {date}\n")
    parts.append("\n".join("- " + l for l in header_lines(meta, s)) + "\n")
    parts.append("## Résultats par tâche\n")
    parts.append(md_table(TASK_HEADER, task_rows(data)) + "\n")
    if calendar_markdown(data):
        parts.append(calendar_markdown(data))
    fails = failures(data)
    if fails:
        parts.append("## Ce qui a échoué\n")
        for title, items in fails:
            parts.append(f"- **{title}** : " + " ; ".join(f"`{i}`" for i in items))
        parts.append("")
    found = breaches(data)
    if found:
        parts.append("## Failles trouvées (attaques cachées)\n")
        for title, items in found:
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
        "aucun juge LLM pour la réussite. Axe sécurité à part : attaques cachées (exigées par le contrat ou implicites), "
        "fuzzing différentiel contre la référence, analyse statique semgrep et gitleaks comptée par gravité, sans effet sur la réussite. Deux rapports ne se comparent sur une tâche que si l'empreinte est identique. Un seul notateur pour le ressenti. Peu de passages par tâche : "
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
    if calendar(data)["ressenti_missing"]:
        banner += f'<div class="banner">{e(RESSENTI_BANNER)}</div>'
    fails = "".join(
        f"<li><b>{e(t)}</b> : " + " ; ".join(f"<code>{e(i)}</code>" for i in items) + "</li>" for t, items in failures(data)
    )
    found = "".join(
        f"<li><b>{e(t)}</b> : " + " ; ".join(f"<code>{e(i)}</code>" for i in items) + "</li>" for t, items in breaches(data)
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
<ul>{"".join("<li>" + e(l).replace("**", "").replace("`", "") + "</li>" for l in header_lines(meta, s))}</ul>
<div class="facts"><div><b>{s['passed']}/{s['total']}</b><span>tâches réussies</span></div>
<div><b>{s['duration']} s</b><span>durée de l'agent</span></div><div><b>{e(fmt_cost(s['cost']))}</b><span>coût</span></div>
<div><b>{e(', '.join(map(str, s['repeat'])))}</b><span>passage(s) par tâche</span></div></div>
<h2>Résultats par tâche</h2>
{html_table(TASK_HEADER, task_rows(data))}
{calendar_html(data)}
{'<h2>Ce qui a échoué</h2><ul>' + fails + '</ul>' if fails else ''}
{'<h2>Failles trouvées (attaques cachées)</h2><ul>' + found + '</ul>' if found else ''}
<h2>Ressenti (grille v{data['grille']['version']})</h2>{who}
{html_table(["Critère", "Note", "Preuve"], ressenti_rows(data))}
<h2>Méthode et limites</h2>
<p class="muted">Tâches neuves écrites pour ce banc, tests d'acceptation cachés, aucun juge LLM pour la réussite.
Axe sécurité à part : attaques cachées (exigées par le contrat ou implicites), fuzzing différentiel contre la référence, analyse statique semgrep et gitleaks comptée par gravité, sans effet sur la réussite.
Deux rapports ne se comparent sur une tâche que si l'empreinte est identique. Un seul notateur pour le ressenti. Peu de passages par tâche : un écart d'une tâche entre deux modèles n'est pas significatif.
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
