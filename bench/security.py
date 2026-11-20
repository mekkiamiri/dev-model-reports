"""Analyse statique de sécurité du code produit : semgrep (règles locales) et gitleaks.

Usage :
  python3 bench/security.py <dossier d'artefacts> <fichier.sarif>

Appelé par bench/run.py après chaque passage, sur results/<modèle>/artifacts/<tâche>/run-<k>/ :
- semgrep avec les seules règles de bench/semgrep/java.yml (tirées de Project CodeGuard),
  sans registre en ligne ni métriques (--metrics=off), sur src/ ;
- gitleaks (--no-git, secrets masqués) sur src/ aussi : les tests de l'agent ont souvent des clés factices.
Les deux résultats sont réunis dans un seul fichier SARIF 2.1.0 (un « run » par outil), chemins
relatifs au dossier d'artefacts. La fonction `scan` rend le compte par gravité
{"high", "medium", "low"} ; sans semgrep, elle rend None et affiche un avertissement, sans planter.
Sans effet sur la note de réussite : c'est une information à côté des attaques.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

BENCH = Path(__file__).resolve().parent
RULES = BENCH / "semgrep" / "java.yml"
LEVEL = {"error": "high", "warning": "medium", "note": "low", "none": "low"}


def _run(cmd: list[str], cwd: Path, timeout: float = 300) -> bool:
    env = dict(os.environ, SEMGREP_SEND_METRICS="off", SEMGREP_ENABLE_VERSION_CHECK="0")
    try:
        r = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as e:
        print(f"attention : {cmd[0]} n'a pas tourné ({e})", file=sys.stderr)
        return False
    if r.returncode not in (0, 1):
        print(f"attention : {Path(cmd[0]).name} a échoué (code {r.returncode}) : {r.stderr.strip()[-300:]}", file=sys.stderr)
        return False
    return True


def _clean_run(run: dict, base: Path, default_level: str | None) -> dict:
    """Identifiants courts (semgrep préfixe le chemin du fichier de règles), chemins relatifs, pas d'invocation."""
    run.pop("invocations", None)
    short = lambda rid: re.sub(r"^.*\.(dmr-)", r"\1", rid or "")
    levels = {}
    for rule in run.get("tool", {}).get("driver", {}).get("rules", []):
        rule["id"] = short(rule.get("id"))
        rule.pop("name", None)
        levels[rule["id"]] = (rule.get("defaultConfiguration") or {}).get("level")
    prefix = base.resolve().as_posix().rstrip("/") + "/"
    for res in run.get("results", []):
        res["ruleId"] = short(res.get("ruleId"))
        res.setdefault("level", levels.get(res["ruleId"]) or default_level or "warning")
        for loc in res.get("locations", []):
            art = loc.get("physicalLocation", {}).get("artifactLocation", {})
            uri = art.get("uri", "").removeprefix("file://")
            if uri.startswith(prefix):
                uri = uri[len(prefix):]
            art["uri"] = uri
    return run


def count(sarif: dict) -> dict:
    """Compte des résultats par gravité (SARIF : error = haute, warning = moyenne, note = basse)."""
    out = {"high": 0, "medium": 0, "low": 0}
    for run in sarif.get("runs", []):
        for res in run.get("results", []):
            out[LEVEL.get(res.get("level", "warning"), "medium")] += 1
    return out


def scan(artifacts: Path, sarif_path: Path) -> dict | None:
    """Analyse un dossier d'artefacts, écrit le SARIF et rend {"high", "medium", "low", "outils"}, ou None sans semgrep."""
    semgrep, gitleaks = shutil.which("semgrep"), shutil.which("gitleaks")
    if not semgrep:
        print("attention : semgrep absent, pas d'analyse statique (static: null)", file=sys.stderr)
        return None
    runs, tools = [], []
    with tempfile.TemporaryDirectory(prefix="dmr-static-") as tmp:
        tmp = Path(tmp)
        target = artifacts / "src"
        if target.is_dir() and _run([semgrep, "--config", str(RULES), "--metrics=off", "--disable-version-check", "--quiet",
                                     "--sarif", "--output", str(tmp / "semgrep.sarif"), str(target.resolve())], artifacts):
            runs += [_clean_run(r, artifacts, None) for r in json.loads((tmp / "semgrep.sarif").read_text(encoding="utf-8")).get("runs", [])]
            tools.append("semgrep")
        elif not target.is_dir():
            tools.append("semgrep")  # rien à analyser : zéro résultat
        if not gitleaks:
            print("attention : gitleaks absent, secrets non cherchés", file=sys.stderr)
        elif not target.is_dir():
            tools.append("gitleaks")
        # src/ seulement : le code livré. Les tests écrits par l'agent ont souvent des clés factices
        # (tâche HMAC) que gitleaks prendrait pour des secrets.
        elif _run([gitleaks, "detect", "--no-git", "--source", "src", "--redact", "--no-banner", "--exit-code", "0",
                   "--report-format", "sarif", "--report-path", str(tmp / "gitleaks.sarif")], artifacts):
            runs += [_clean_run(r, artifacts, "error") for r in json.loads((tmp / "gitleaks.sarif").read_text(encoding="utf-8")).get("runs", [])]
            tools.append("gitleaks")
    if "semgrep" not in tools:
        return None
    sarif = {"$schema": "https://json.schemastore.org/sarif-2.1.0.json", "version": "2.1.0", "runs": runs}
    sarif_path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(sarif, ensure_ascii=False, indent=1)
    # aucun chemin de la machine dans le SARIF (il peut être publié avec les résultats)
    text = re.sub(r"[A-Za-z0-9_.\-]+\.(dmr-)", r"\1", text).replace(artifacts.resolve().as_posix() + "/", "")
    sarif_path.write_text(text + "\n", encoding="utf-8")
    return {**count(sarif), "outils": tools}


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print(__doc__)
        return 2
    print(json.dumps(scan(Path(args[0]), Path(args[1])), ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
