"""Lanceur du banc : un dossier de travail isolé par passage, puis les tests cachés.

Usage :
  python3 bench/run.py --check [--tasks-dir tasks-private]
      Vérifie chaque tâche : la solution de référence passe les tests cachés,
      le point de départ (ce que voit l'agent) et chaque mutant ne les passent pas.
      Pour une tâche qui déclare des attaques : la référence résiste à toutes les attaques,
      et chaque mutant vulnérable (mutants-vuln/<famille>/) passe les tests cachés mais
      tombe sur au moins une attaque de sa famille.
  python3 bench/run.py --agent <nom> [--config bench/agents.json] [--tasks id,id] [--repeat N]
                       [--tasks-dir tasks-private] [--no-static]
      Lance l'agent configuré sur chaque tâche, applique les tests cachés, puis les attaques
      cachées (axe sécurité) et l'analyse statique (bench/security.py), et écrit
      results/<nom>/<tâche>.json.

L'agent est une commande configurable (fichier JSON, voir bench/agents.example.json).
Chaque passage reçoit un dossier temporaire neuf (nom aléatoire, hors du dépôt) qui ne
contient que `workspace/` de la tâche, et un environnement épuré (sans PWD, OLDPWD ni
chemin du dépôt). Les tests cachés ne sont copiés qu'après la fin de l'agent, dans un
autre dossier temporaire. La note est lue sur une ligne portant un jeton aléatoire que
seul le harnais connaît. Bibliothèque standard uniquement ; il faut `javac` et `java`.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench"
TASKS_DIR = ROOT / "tasks"
CHECK_JAVA = BENCH / "java" / "Check.java"
ATTACKS_JAVA = BENCH / "java" / "Attaques.java"
REF_PACKAGE = "dmrref"  # paquet de la copie de la référence, pour le fuzzing différentiel
ATTACK_JVM = ["-Xmx256m"]  # mémoire limitée : une explosion mémoire est une faille, pas un gel de la machine
SCHEMA = 2
IGNORED = {".DS_Store", "__pycache__", ".git", "out"}
# Ce que le code de l'agent n'a aucune raison d'utiliser et qui servirait à tricher avec le harnais.
SUSPICIOUS = [
    (re.compile(r"\bCheck\s*\."), "appel au harnais de test (Check)"),
    (re.compile(r"\bAcceptanceTests\b"), "référence aux tests cachés"),
    (re.compile(r"setAccessible|getDeclaredField|privateLookupIn"), "réflexion sur des membres privés"),
    (re.compile(r"System\s*\.\s*in\b"), "lecture de l'entrée standard"),
    (re.compile(r"System\s*\.\s*set(Out|Err|In)\b|addShutdownHook"), "détournement de la sortie ou de l'arrêt"),
    (re.compile(r"\bAttaques\b|\bAttackTests\b|\b" + REF_PACKAGE + r"\b"), "référence aux attaques cachées"),
]
# Variables transmises à l'agent par défaut ; le reste est retiré (dont PWD et OLDPWD).
ENV_BASE = ["PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "TERM", "TMPDIR", "JAVA_HOME",
            # réseau de la machine (proxy, autorité de certification) : sans eux, aucun outil ne joint son API
            "HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY", "https_proxy", "http_proxy", "no_proxy",
            "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE"]

PROMPT = """Tu travailles dans le dossier courant, qui contient une tâche de développement.
Lis TASK.md, puis les autres fichiers .md, et réalise la tâche sans poser de question :
s'il y a une ambiguïté, choisis l'interprétation la plus raisonnable et signale-la dans ton résumé final.
Ne cherche pas de fichiers en dehors du dossier courant.
Termine par un résumé : ce que tu as fait, ce que tu n'as pas fait ou pas vérifié, les ambiguïtés."""


# ---------------------------------------------------------------- utilitaires

def run_cmd(cmd: list[str], cwd: Path, timeout: float, env: dict | None = None, stdin_text: str | None = None) -> dict:
    """Lance une commande dans son propre groupe de processus ; au délai, tue tout le groupe."""
    start = time.monotonic()
    try:
        proc = subprocess.Popen(
            cmd, cwd=cwd, env=env, text=True, start_new_session=True,
            stdin=subprocess.PIPE if stdin_text is not None else subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    except (FileNotFoundError, PermissionError) as e:
        return {"code": 127, "stdout": "", "stderr": str(e), "timed_out": False, "duration": 0.0}
    try:
        out, err = proc.communicate(stdin_text, timeout=timeout)
        timed_out = False
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        out, err = proc.communicate()
        timed_out = True
    return {"code": None if timed_out else proc.returncode, "stdout": out or "", "stderr": err or "",
            "timed_out": timed_out, "duration": round(time.monotonic() - start, 2)}


def _tail(text: str, lines: int = 20) -> str:
    return "\n".join(text.strip().splitlines()[-lines:])


# ---------------------------------------------------------------- tâches

def load_tasks(ids: list[str] | None = None, tasks_dir: Path = TASKS_DIR) -> list[dict]:
    tasks = []
    for task_json in sorted(tasks_dir.glob("*/task.json")):
        task = json.loads(task_json.read_text(encoding="utf-8"))
        task["dir"] = task_json.parent
        task["set"] = "public" if tasks_dir.resolve() == TASKS_DIR.resolve() else tasks_dir.name
        tasks.append(task)
    if ids:
        known = {t["id"] for t in tasks}
        unknown = [i for i in ids if i not in known]
        if unknown:
            raise SystemExit(f"tâche(s) inconnue(s) : {', '.join(unknown)}")
        tasks = [t for t in tasks if t["id"] in ids]
    return tasks


def _files(base: Path) -> list[Path]:
    return sorted(p for p in base.rglob("*") if p.is_file() and not (IGNORED & set(p.relative_to(base).parts)))


def fingerprint(task: dict) -> str:
    """Empreinte de ce qui fait la mesure : workspace, tests cachés, référence et harnais.
    Deux rapports ne se comparent sur une tâche que si l'empreinte est identique."""
    h = hashlib.sha256()
    for part in ("workspace", "hidden", "reference"):
        for path in _files(task["dir"] / part):
            h.update(path.relative_to(task["dir"]).as_posix().encode())
            h.update(path.read_bytes())
    h.update(CHECK_JAVA.read_bytes())
    return h.hexdigest()[:12]


def prepare_workdir(task: dict) -> Path:
    """Dossier temporaire neuf au nom aléatoire, avec seulement `workspace/` :
    pas de dossier voisin (autres tâches, autres passages), ni hidden/, ni reference/."""
    workdir = Path(tempfile.mkdtemp(prefix="dmr-work-"))
    shutil.copytree(task["dir"] / "workspace", workdir, dirs_exist_ok=True)
    return workdir


# ---------------------------------------------------------------- notation

def suspicious(src: Path) -> list[str]:
    found = []
    for path in sorted(src.rglob("*.java")) if src.is_dir() else []:
        text = path.read_text(encoding="utf-8", errors="replace")
        for pattern, why in SUSPICIOUS:
            if pattern.search(text):
                found.append(f"{path.relative_to(src).as_posix()} : {why}")
    return found


def grade(task: dict, workdir: Path) -> dict:
    """Compile `src/` de l'agent avec les tests cachés, exécute, tout ou rien.
    Réussite seulement si : pas de code suspect, compilation, sortie 0, et la DERNIÈRE ligne
    RESULT porte le jeton de ce passage avec passed == total == hidden_tests de la tâche."""
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("javac et java sont nécessaires pour noter les tâches (JDK 17 ou plus)")
    start = time.monotonic()
    timeout = task.get("timeout_tests_s", 60)
    expected = task.get("hidden_tests")
    result = {"stage": "compile", "tests_passed": 0, "tests_total": None, "failures": [], "compile_errors": None}
    flags = suspicious(workdir / "src")
    if flags:
        result.update(stage="suspect", failures=flags[:10])
    else:
        with tempfile.TemporaryDirectory(prefix="dmr-grade-") as tmp:
            grading = Path(tmp)
            if (workdir / "src").is_dir():
                shutil.copytree(workdir / "src", grading / "src")
            tests = grading / "hidden-tests"
            shutil.copytree(task["dir"] / "hidden", tests)
            shutil.copy(CHECK_JAVA, tests / "Check.java")
            sources = sorted(str(p) for p in grading.rglob("*.java"))
            comp = run_cmd([javac, "--release", "17", "-encoding", "UTF-8", "-nowarn", "-d", str(grading / "out"), *sources],
                           grading, timeout)
            if comp["timed_out"]:
                result["stage"] = "compile_timeout"
            elif comp["code"] != 0:
                text = (comp["stdout"] + comp["stderr"]).replace(str(grading) + "/", "")
                errors = [l for l in text.splitlines() if ": error:" in l]
                result["compile_errors"] = "\n".join([f"compilation : {len(errors)} erreur(s)", *errors[:10]]) if errors else _tail(text)
            else:
                token = secrets.token_hex(16)
                runp = run_cmd([java, "-cp", str(grading / "out"), "AcceptanceTests"], grading, timeout, stdin_text=token + "\n")
                if runp["timed_out"]:
                    result["stage"] = "tests_timeout"
                else:
                    result.update(_read_result(runp, token, expected))
    result["passed"] = result["stage"] == "ok"
    result["grading_duration_s"] = round(time.monotonic() - start, 2)
    return result


def _read_result(runp: dict, token: str, expected: int | None) -> dict:
    lines = runp["stdout"].splitlines()
    line_re = re.compile(r"^RESULT " + re.escape(token) + r" passed=(\d+) total=(\d+)\s*$")
    out = {"stage": "tests", "tests_passed": 0, "tests_total": None,
           "failures": [l[6:] for l in lines if l.startswith("ECHEC ")][:10]}
    last = lines[-1] if lines else ""
    m = line_re.match(last)
    if not m:
        out["failures"].append("pas de ligne RESULT valide en dernière ligne (jeton absent ou sortie interrompue)")
        if runp["stderr"]:
            out["failures"].append(_tail(runp["stderr"], 5))
        return out
    passed, total = int(m.group(1)), int(m.group(2))
    out["tests_passed"], out["tests_total"] = passed, total
    if expected is not None and total != expected:
        out["failures"].append(f"{total} tests exécutés, {expected} attendus")
    elif runp["code"] == 0 and passed == total:
        out["stage"] = "ok"
    return out


# ---------------------------------------------------------------- attaques (axe sécurité)

def attacks_fingerprint(task: dict) -> str | None:
    """Empreinte de l'axe sécurité (attaques, leur déclaration, référence, harnais).
    Séparée de `fingerprint` : ajouter des attaques ne change pas l'empreinte fonctionnelle publiée."""
    if not task.get("attacks"):
        return None
    h = hashlib.sha256(json.dumps(task["attacks"], sort_keys=True).encode())
    for part in ("hidden-attacks", "reference"):
        for path in _files(task["dir"] / part):
            h.update(path.relative_to(task["dir"]).as_posix().encode())
            h.update(path.read_bytes())
    h.update(CHECK_JAVA.read_bytes())
    h.update(ATTACKS_JAVA.read_bytes())
    return h.hexdigest()[:12]


def declared_attack_ids(task: dict) -> list[str]:
    """Identifiants trouvés dans le code des attaques (`a.attaque("id", …)`)."""
    ids = []
    for path in sorted((task["dir"] / "hidden-attacks").rglob("*.java")):
        ids += re.findall(r'\.attaque\(\s*"([^"]+)"', path.read_text(encoding="utf-8"))
    return ids


def _copy_reference(task: dict, dest: Path) -> None:
    """Référence copiée sous un autre paquet (com.example.x -> dmrref.x) : le fuzzing la compare au code de l'agent."""
    base = task["dir"] / "reference" / "src"
    for path in _files(base):
        if path.suffix == ".java":
            target = dest / REF_PACKAGE / path.relative_to(base)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(re.sub(r"\bcom\.example\.", REF_PACKAGE + ".", path.read_text(encoding="utf-8")), encoding="utf-8")


def _run_attack(java: str, out: Path, cwd: Path, attack_id: str, delay_s: float) -> str | None:
    """Une JVM par attaque, jeton propre. Rend None si l'attaque est repoussée, sinon la raison de la faille."""
    token = secrets.token_hex(16)
    runp = run_cmd([java, *ATTACK_JVM, "-cp", str(out), "AttackTests", attack_id, str(int(delay_s * 1000))],
                   cwd, delay_s + 30, stdin_text=token + "\n")
    if runp["timed_out"]:
        return "délai global dépassé (JVM tuée)"
    read = _read_result(runp, token, 1)
    if read["stage"] == "ok":
        return None
    return (read["failures"] or ["échec"])[0].removeprefix(attack_id + " : ").removeprefix("java.lang.AssertionError: ")[:300]


def grade_attacks(task: dict, workdir: Path) -> dict | None:
    """Attaques cachées sur `src/` de l'agent, dans un troisième dossier temporaire.
    Le code est attaqué même si les tests fonctionnels échouent, pourvu qu'il compile.
    Chaque attaque tourne dans sa propre JVM (mémoire limitée, délai) et doit rendre
    `RESULT <jeton> passed=1 total=1` ; tout le reste est une faille. Sans `attacks` : None."""
    attacks = task.get("attacks") or []
    if not attacks:
        return None
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("javac et java sont nécessaires pour noter les tâches (JDK 17 ou plus)")
    start = time.monotonic()
    delay = float(task.get("timeout_attack_s", 10))
    reasons = {a["id"]: "non exécutée" for a in attacks}
    stage = "compile"
    flags = suspicious(workdir / "src")
    if flags:
        stage = "suspect"
        reasons = {a["id"]: "code suspect" for a in attacks}
    else:
        with tempfile.TemporaryDirectory(prefix="dmr-attack-") as tmp:
            grading = Path(tmp)
            if (workdir / "src").is_dir():
                shutil.copytree(workdir / "src", grading / "src")
            shutil.copytree(task["dir"] / "hidden-attacks", grading / "hidden-attacks")
            shutil.copy(CHECK_JAVA, grading / "hidden-attacks" / "Check.java")
            shutil.copy(ATTACKS_JAVA, grading / "hidden-attacks" / "Attaques.java")
            _copy_reference(task, grading / "ref-src")
            sources = sorted(str(p) for p in grading.rglob("*.java"))
            comp = run_cmd([javac, "--release", "17", "-encoding", "UTF-8", "-nowarn", "-d", str(grading / "out"), *sources],
                           grading, task.get("timeout_tests_s", 60))
            if comp["timed_out"]:
                stage = "compile_timeout"
            elif comp["code"] == 0:
                stage = "ok"
                with ThreadPoolExecutor(max_workers=max(1, min(len(attacks), os.cpu_count() or 2, 8))) as pool:
                    found = pool.map(lambda a: _run_attack(java, grading / "out", grading, a["id"], delay), attacks)
                    reasons = dict(zip((a["id"] for a in attacks), found))
    failed = [a for a in attacks if reasons[a["id"]] is not None]
    required = [a for a in attacks if a.get("exigee")]
    implicit = [a for a in attacks if not a.get("exigee")]
    return {
        "attacks_stage": stage,
        "attacks_passed": len(attacks) - len(failed), "attacks_total": len(attacks),
        "attacks_failed": [a["id"] for a in failed],
        "attacks_failures": [f"{a['id']} : {reasons[a['id']]}" for a in failed][:20],
        "attacks_required_passed": sum(a not in failed for a in required), "attacks_required_total": len(required),
        "attacks_implicit_passed": sum(a not in failed for a in implicit), "attacks_implicit_total": len(implicit),
        "attacks_duration_s": round(time.monotonic() - start, 2),
    }


# ---------------------------------------------------------------- agent

def load_agents(config: Path) -> dict:
    return json.loads(config.read_text(encoding="utf-8"))


def expand(cmd: list[str], values: dict[str, str]) -> list[str]:
    out = []
    for part in cmd:
        for key, val in values.items():
            part = part.replace("{" + key + "}", val)
        out.append(part)
    return out


def agent_env(agent: dict, workdir: Path) -> dict:
    """Environnement épuré : liste blanche + variables demandées par la configuration."""
    keep = ENV_BASE + list(agent.get("env_passthrough", []))
    env = {k: os.environ[k] for k in keep if k in os.environ}
    env.update(agent.get("env", {}))
    env["PWD"] = str(workdir)
    return env


def parse_usage(kind: str, stdout: str) -> dict:
    """Tokens, coût, tours, message final et erreur éventuelle, si la commande les fournit."""
    usage = {"tokens": None, "cost_usd": None, "turns": None, "final_message": None, "agent_error": None,
             "external_failure": False}
    text = stdout.strip()
    if kind == "claude-json":
        data = None
        for candidate in (text, (text.splitlines() or [""])[-1]):
            try:
                data = json.loads(candidate)
                break
            except json.JSONDecodeError:
                continue
        if not isinstance(data, dict):
            return usage
        u = data.get("usage") or {}
        if u:
            usage["tokens"] = {"input": u.get("input_tokens"), "output": u.get("output_tokens"),
                               "cache_read": u.get("cache_read_input_tokens"), "cache_write": u.get("cache_creation_input_tokens")}
        usage["cost_usd"] = data.get("total_cost_usd")
        usage["turns"] = data.get("num_turns")
        usage["final_message"] = data.get("result")
        subtype = data.get("subtype")
        if data.get("is_error"):
            # « subtype: success » avec is_error vrai : l'outil n'a pas pu travailler (connexion, quota…)
            usage["agent_error"] = subtype if subtype not in (None, "success") else "is_error"
            spent = sum(v or 0 for v in (usage["tokens"] or {}).values())
            usage["external_failure"] = spent == 0
        elif subtype not in (None, "success"):
            usage["agent_error"] = subtype
    elif kind == "codex-jsonl":
        totals, turns = {"input": 0, "output": 0, "cache_read": 0}, 0
        for line in text.splitlines():
            try:
                ev = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not isinstance(ev, dict):
                continue
            if ev.get("type") == "turn.completed":
                turns += 1
                u = ev.get("usage") or {}
                totals["input"] += u.get("input_tokens") or 0
                totals["output"] += (u.get("output_tokens") or 0) + (u.get("reasoning_output_tokens") or 0)
                totals["cache_read"] += u.get("cached_input_tokens") or 0
            elif ev.get("type") in ("turn.failed", "error"):
                usage["agent_error"] = ev.get("type")
        if turns:
            usage["tokens"], usage["turns"] = totals, turns
    return usage


def run_agent(name: str, agent: dict, task: dict, workdir: Path) -> dict:
    values = {"prompt": PROMPT, "workdir": str(workdir), "task_id": task["id"],
              "reference": str(task["dir"] / "reference"), "bench": str(BENCH), "python": sys.executable, "model": name}
    timeout = agent.get("timeout_s", task.get("timeout_agent_s", 1800))
    return run_cmd(expand(agent["cmd"], values), workdir, timeout, env=agent_env(agent, workdir))


def save_artifacts(workdir: Path, dest: Path) -> None:
    """Garde ce que l'agent a produit (sans binaires) pour la grille de ressenti."""
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(workdir, dest, ignore=shutil.ignore_patterns("out", "*.class", ".git"))


def bench_commit() -> str | None:
    try:
        out = subprocess.run(["git", "describe", "--always", "--dirty", "--abbrev=7"], cwd=ROOT, capture_output=True, text=True)
        return out.stdout.strip() or None
    except OSError:
        return None


def tool_version(agent: dict) -> str | None:
    cmd = agent.get("version_cmd")
    if not cmd:
        return None
    r = run_cmd(cmd, ROOT, 30)
    return (r["stdout"].strip() or r["stderr"].strip() or None) if r["code"] == 0 else None


def run_task(name: str, agent: dict, task: dict, repeat: int, results: Path, keep: bool, context: dict) -> dict:
    runs = []
    model_dir = results / name
    for i in range(1, repeat + 1):
        workdir = prepare_workdir(task)
        try:
            agent_out = run_agent(name, agent, task, workdir)
            usage = parse_usage(agent.get("usage", "none"), agent_out["stdout"])
            graded = grade(task, workdir)
            if agent_out["timed_out"] and not graded["passed"]:
                graded["stage"] = "agent_timeout"
            attacked = grade_attacks(task, workdir)
            transcript = model_dir / "transcripts" / f"{task['id']}.run{i}.txt"
            transcript.parent.mkdir(parents=True, exist_ok=True)
            transcript.write_text(
                "# Message final\n" + (usage["final_message"] or "(non fourni par la commande)")
                + "\n\n# stdout\n" + agent_out["stdout"] + "\n\n# stderr\n" + agent_out["stderr"], encoding="utf-8")
            artifacts = model_dir / "artifacts" / task["id"] / f"run-{i}"
            save_artifacts(workdir, artifacts)
            static = None
            if context.get("static", True):
                import security  # bench/security.py ; semgrep ou gitleaks absent : None et un avertissement
                static = security.scan(artifacts, model_dir / "security" / f"{task['id']}.run{i}.sarif")
        finally:
            if keep:
                print(f"dossier de travail gardé : {workdir}")
            else:
                shutil.rmtree(workdir, ignore_errors=True)
        runs.append({
            "run": i, "passed": graded["passed"], "stage": graded["stage"],
            "tests_passed": graded["tests_passed"], "tests_total": graded["tests_total"],
            "failures": graded["failures"], "compile_errors": graded["compile_errors"],
            "agent_exit_code": agent_out["code"], "agent_timed_out": agent_out["timed_out"],
            "agent_error": usage["agent_error"], "external_failure": usage["external_failure"], "agent_duration_s": agent_out["duration"],
            "grading_duration_s": graded["grading_duration_s"],
            "tokens": usage["tokens"], "cost_usd": usage["cost_usd"], "turns": usage["turns"],
            **(attacked or {}), "static": static,
        })
    costs = [r["cost_usd"] for r in runs if r["cost_usd"] is not None]
    record = {
        "schema": SCHEMA, "model": name, "label": agent.get("label", name), "example": bool(agent.get("example", False)),
        "task": task["id"], "task_set": task["set"], "kind": task["kind"], "title": task["title"],
        "task_fingerprint": fingerprint(task), "hidden_tests": task.get("hidden_tests"),
        "attacks_fingerprint": attacks_fingerprint(task),
        "attacks": [{k: a[k] for k in ("id", "famille", "exigee") if k in a} for a in task.get("attacks") or []],
        "bench_commit": context["bench_commit"], "tool_version": context["tool_version"],
        "date": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "repeat": repeat, "passed_runs": sum(r["passed"] for r in runs), "passed": all(r["passed"] for r in runs),
        "mean_agent_duration_s": round(sum(r["agent_duration_s"] for r in runs) / len(runs), 2),
        "total_cost_usd": round(sum(costs), 4) if costs else None,
        "cost_known_runs": len(costs),
        "runs": runs,
    }
    model_dir.mkdir(parents=True, exist_ok=True)
    (model_dir / f"{task['id']}.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return record


def write_meta(name: str, agent: dict, results: Path, context: dict) -> None:
    meta = {"model": name, "label": agent.get("label", name), "example": bool(agent.get("example", False)),
            "release_date": agent.get("release_date"), "cmd": agent["cmd"],
            "tool_version": context["tool_version"], "bench_commit": context["bench_commit"],
            "updated_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")}
    (results / name).mkdir(parents=True, exist_ok=True)
    (results / name / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- vérification des tâches

def _overlay(task: dict, *layers: Path) -> Path:
    workdir = prepare_workdir(task)
    for layer in layers:
        shutil.copytree(layer, workdir, dirs_exist_ok=True)
    return workdir


def check_tasks(tasks: list[dict]) -> bool:
    """Une tâche ne mesure quelque chose que si :
    - la solution de référence passe les tests cachés (la tâche est faisable) ;
    - le point de départ, tel que l'agent le reçoit, échoue ;
    - chaque mutant (référence avec une erreur plausible, dossier mutants/) échoue.
    Axe sécurité (tâche avec `attacks`) : voir `check_attacks`."""
    ok = True
    for task in tasks:
        dirs = []
        try:
            dirs.append(_overlay(task)); start = grade(task, dirs[-1])
            dirs.append(_overlay(task, task["dir"] / "reference")); ref = grade(task, dirs[-1])
            mutants = {}
            for mutant in sorted(p for p in (task["dir"] / "mutants").glob("*") if p.is_dir()):
                dirs.append(_overlay(task, task["dir"] / "reference", mutant))
                mutants[mutant.name] = grade(task, dirs[-1])
            attacks_ok, attacks_lines = check_attacks(task, dirs[1]) if task.get("attacks") else (True, [])
        finally:
            for d in dirs:
                shutil.rmtree(d, ignore_errors=True)
        good = ref["passed"] and not start["passed"] and bool(mutants) and not any(m["passed"] for m in mutants.values())
        good &= attacks_ok
        ok &= good
        caught = sum(not m["passed"] for m in mutants.values())
        print(f"{'OK  ' if good else 'KO  '} {task['id']:<16} référence {ref['tests_passed']}/{ref['tests_total']}"
              f"  départ : {start['stage']} {start['tests_passed']}/{start['tests_total']}"
              f"  mutants rejetés : {caught}/{len(mutants)}")
        if not ref["passed"]:
            print("     " + "\n     ".join(ref["failures"] or [ref["compile_errors"] or ref["stage"]]))
        for name, m in mutants.items():
            if m["passed"]:
                print(f"     mutant non détecté : {name}")
        for line in attacks_lines:
            print("     " + line)
    return ok


def check_attacks(task: dict, ref_dir: Path) -> tuple[bool, list[str]]:
    """Axe sécurité d'une tâche :
    - les identifiants du code des attaques sont exactement ceux de task.json (comme hidden_tests) ;
    - la référence résiste à toutes les attaques, sauf celles marquées `reference_vulnerable`
      (cas implicite que la référence publiée ne couvre pas : rapporté, jamais bloquant) ;
    - chaque mutant vulnérable mutants-vuln/<famille>/ passe les tests cachés (sinon c'est un bug,
      pas une faille) et tombe sur au moins une attaque de sa famille."""
    attacks = task["attacks"]
    lines, ok = [], True
    ids = [a["id"] for a in attacks]
    in_code = declared_attack_ids(task)
    if sorted(in_code) != sorted(ids) or len(set(ids)) != len(ids):
        ok = False
        lines.append(f"attaques déclarées {sorted(ids)} ≠ attaques du code {sorted(in_code)}")
    familles = {a["famille"] for a in attacks}
    vulnerable = {a["id"] for a in attacks if a.get("reference_vulnerable")}
    ref = grade_attacks(task, ref_dir)
    unexpected = [f for f in ref["attacks_failures"] if f.split(" : ")[0] not in vulnerable]
    if ref["attacks_stage"] != "ok" or unexpected:
        ok = False
        lines.append(f"la référence ne résiste pas : {ref['attacks_stage']} " + " ; ".join(unexpected))
    for v in sorted(vulnerable - set(ref["attacks_failed"])):
        lines.append(f"note : {v} est marquée reference_vulnerable mais la référence y résiste")
    vulns = sorted(p for p in (task["dir"] / "mutants-vuln").glob("*") if p.is_dir())
    if not vulns:
        ok = False
        lines.append("aucun mutant vulnérable (mutants-vuln/<famille>/) : les attaques ne sont pas contrôlées")
    caught = 0
    for mutant in vulns:
        work = _overlay(task, task["dir"] / "reference", mutant)
        try:
            functional, attacked = grade(task, work), grade_attacks(task, work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        fam_failed = [i for i in attacked["attacks_failed"] if next(a for a in attacks if a["id"] == i)["famille"] == mutant.name]
        if mutant.name not in familles:
            ok = False
            lines.append(f"mutant vulnérable {mutant.name} : famille inconnue (familles : {', '.join(sorted(familles))})")
        elif not functional["passed"]:
            ok = False
            lines.append(f"mutant vulnérable {mutant.name} : échoue aux tests cachés (bug fonctionnel, pas une faille)")
        elif not fam_failed:
            ok = False
            lines.append(f"mutant vulnérable non détecté : {mutant.name}")
        else:
            caught += 1
    required = sum(bool(a.get("exigee")) for a in attacks)
    lines.insert(0, f"attaques : référence {ref['attacks_passed']}/{ref['attacks_total']}"
                    f" ({required} exigées, {len(attacks) - required} implicites"
                    + (f", {len(vulnerable)} où la référence est vulnérable" if vulnerable else "")
                    + f")  mutants vulnérables détectés : {caught}/{len(vulns)}")
    return ok, lines


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--check", action="store_true", help="vérifier les tâches (référence passe, départ et mutants échouent)")
    p.add_argument("--agent", help="nom de l'agent dans le fichier de configuration")
    p.add_argument("--config", type=Path, help="fichier JSON des agents (défaut : bench/agents.json, sinon bench/agents.example.json)")
    p.add_argument("--tasks", help="identifiants de tâches séparés par des virgules (défaut : toutes)")
    p.add_argument("--tasks-dir", type=Path, default=TASKS_DIR, help="lot de tâches (défaut : tasks/ ; lot privé : tasks-private/)")
    p.add_argument("--repeat", type=int, default=1, help="nombre de passages par tâche (défaut : 1)")
    p.add_argument("--results", type=Path, default=ROOT / "results", help="dossier des résultats")
    p.add_argument("--keep", action="store_true", help="garder les dossiers de travail temporaires")
    p.add_argument("--no-static", action="store_true", help="sans analyse statique (semgrep, gitleaks), par exemple en CI")
    args = p.parse_args(argv)
    tasks = load_tasks(args.tasks.split(",") if args.tasks else None, args.tasks_dir)
    if not tasks:
        p.error(f"aucune tâche dans {args.tasks_dir}")
    if args.check:
        return 0 if check_tasks(tasks) else 1
    if not args.agent:
        p.error("--agent ou --check est obligatoire")
    config = args.config or (BENCH / "agents.json" if (BENCH / "agents.json").exists() else BENCH / "agents.example.json")
    agents = load_agents(config)
    if args.agent not in agents:
        p.error(f"agent inconnu dans {config} : {args.agent} (connus : {', '.join(agents)})")
    agent = agents[args.agent]
    context = {"bench_commit": bench_commit(), "tool_version": tool_version(agent), "static": not args.no_static}
    if context["bench_commit"] and context["bench_commit"].endswith("-dirty"):
        print("attention : le dépôt a des modifications non commitées (bench_commit « -dirty »)")
    write_meta(args.agent, agent, args.results, context)
    passed = 0
    for task in tasks:
        rec = run_task(args.agent, agent, task, args.repeat, args.results, args.keep, context)
        passed += rec["passed"]
        last = rec["runs"][-1]
        attacks = f"  attaques {last['attacks_passed']}/{last['attacks_total']}" if "attacks_total" in last else ""
        print(f"{'réussi ' if rec['passed'] else 'échoué '} {task['id']:<16} {rec['passed_runs']}/{rec['repeat']} passages"
              f"  tests {last['tests_passed']}/{last['tests_total']}  {last['stage']}{attacks}  {rec['mean_agent_duration_s']} s")
    print(f"{passed}/{len(tasks)} tâches réussies · résultats dans {args.results / args.agent}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
