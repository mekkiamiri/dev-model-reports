"""Lanceur du banc : un dossier de travail isolé par tâche et par modèle, puis les tests cachés.

Usage :
  python3 bench/run.py --check
      Vérifie chaque tâche : la solution de référence passe les tests cachés,
      le point de départ (ce que voit l'agent) et chaque mutant ne les passent pas.
  python3 bench/run.py --agent <nom> [--config bench/agents.json] [--tasks id,id] [--repeat N]
      Lance l'agent configuré sur chaque tâche, applique les tests cachés et écrit
      results/<nom>/<tâche>.json.

L'agent est une commande configurable (fichier JSON, voir bench/agents.example.json).
Il ne voit que le dossier `workspace/` de la tâche, copié dans un dossier temporaire
hors du dépôt. Les tests cachés ne sont copiés qu'après la fin de l'agent, dans un
autre dossier temporaire. Bibliothèque standard uniquement ; il faut `javac` et `java`.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench"
TASKS_DIR = ROOT / "tasks"
CHECK_JAVA = BENCH / "java" / "Check.java"
RESULT_LINE = re.compile(r"^RESULT passed=(\d+) total=(\d+)\s*$", re.M)
SCHEMA = 1

PROMPT = """Tu travailles dans le dossier courant, qui contient une tâche de développement.
Lis TASK.md, puis les autres fichiers .md, et réalise la tâche sans poser de question :
s'il y a une ambiguïté, choisis l'interprétation la plus raisonnable et signale-la dans ton résumé final.
Ne cherche pas de fichiers en dehors du dossier courant.
Termine par un résumé : ce que tu as fait, ce que tu n'as pas fait ou pas vérifié, les ambiguïtés."""


# ---------------------------------------------------------------- tâches

def load_tasks(ids: list[str] | None = None) -> list[dict]:
    tasks = []
    for task_json in sorted(TASKS_DIR.glob("*/task.json")):
        task = json.loads(task_json.read_text(encoding="utf-8"))
        task["dir"] = task_json.parent
        tasks.append(task)
    if ids:
        known = {t["id"] for t in tasks}
        unknown = [i for i in ids if i not in known]
        if unknown:
            raise SystemExit(f"tâche(s) inconnue(s) : {', '.join(unknown)}")
        tasks = [t for t in tasks if t["id"] in ids]
    return tasks


def fingerprint(task: dict) -> str:
    """Empreinte des fichiers de la tâche : deux rapports ne se comparent que si elle est identique."""
    h = hashlib.sha256()
    for path in sorted(p for p in task["dir"].rglob("*") if p.is_file()):
        h.update(path.relative_to(task["dir"]).as_posix().encode())
        h.update(path.read_bytes())
    h.update(CHECK_JAVA.read_bytes())
    return h.hexdigest()[:12]


def prepare_workdir(task: dict, base: Path) -> Path:
    """Copie uniquement `workspace/` : ni `hidden/` ni `reference/` ne sont visibles de l'agent."""
    workdir = base / task["id"]
    shutil.copytree(task["dir"] / "workspace", workdir)
    return workdir


# ---------------------------------------------------------------- notation

def _tail(text: str, lines: int = 20) -> str:
    return "\n".join(text.strip().splitlines()[-lines:])


def grade(task: dict, workdir: Path) -> dict:
    """Compile `src/` de l'agent avec les tests cachés, exécute, tout ou rien."""
    javac, java = shutil.which("javac"), shutil.which("java")
    if not javac or not java:
        raise SystemExit("javac et java sont nécessaires pour noter les tâches (JDK 17 ou plus)")
    start = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="dmr-grade-") as tmp:
        grading = Path(tmp)
        if (workdir / "src").is_dir():
            shutil.copytree(workdir / "src", grading / "src")
        tests = grading / "hidden-tests"
        shutil.copytree(task["dir"] / "hidden", tests)
        shutil.copy(CHECK_JAVA, tests / "Check.java")
        sources = sorted(str(p) for p in grading.rglob("*.java"))
        result = {"stage": "compile", "tests_passed": 0, "tests_total": None, "failures": [], "compile_errors": None}
        comp = subprocess.run(
            [javac, "--release", "17", "-encoding", "UTF-8", "-nowarn", "-d", str(grading / "out"), *sources],
            cwd=grading, capture_output=True, text=True, timeout=task.get("timeout_tests_s", 60),
        )
        if comp.returncode != 0:
            text = (comp.stdout + comp.stderr).replace(str(grading) + "/", "")
            errors = [l for l in text.splitlines() if ": error:" in l]
            result["compile_errors"] = "\n".join([f"compilation : {len(errors)} erreur(s)", *errors[:10]]) if errors else _tail(text)
        else:
            try:
                run = subprocess.run(
                    [java, "-cp", str(grading / "out"), "AcceptanceTests"],
                    cwd=grading, capture_output=True, text=True, timeout=task.get("timeout_tests_s", 60),
                )
                out = run.stdout
                m = RESULT_LINE.search(out)
                result["stage"] = "tests"
                if m:
                    result["tests_passed"], result["tests_total"] = int(m.group(1)), int(m.group(2))
                result["failures"] = [l[6:] for l in out.splitlines() if l.startswith("ECHEC ")][:10]
                if not m and run.stderr:
                    result["failures"].append(_tail(run.stderr, 5))
                if run.returncode == 0 and m and m.group(1) == m.group(2):
                    result["stage"] = "ok"
            except subprocess.TimeoutExpired:
                result["stage"] = "tests_timeout"
    result["passed"] = result["stage"] == "ok"
    result["grading_duration_s"] = round(time.monotonic() - start, 2)
    return result


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


def parse_usage(kind: str, stdout: str) -> dict:
    """Tokens, coût, tours et message final, si la commande les fournit. Sinon : None."""
    usage = {"tokens": None, "cost_usd": None, "turns": None, "final_message": None}
    if kind != "claude-json":
        return usage
    data = None
    for candidate in (stdout.strip(), (stdout.strip().splitlines() or [""])[-1]):
        try:
            data = json.loads(candidate)
            break
        except json.JSONDecodeError:
            continue
    if not isinstance(data, dict):
        return usage
    u = data.get("usage") or {}
    if u:
        usage["tokens"] = {
            "input": u.get("input_tokens"),
            "output": u.get("output_tokens"),
            "cache_read": u.get("cache_read_input_tokens"),
            "cache_write": u.get("cache_creation_input_tokens"),
        }
    usage["cost_usd"] = data.get("total_cost_usd")
    usage["turns"] = data.get("num_turns")
    usage["final_message"] = data.get("result")
    return usage


def run_agent(name: str, agent: dict, task: dict, workdir: Path) -> dict:
    values = {
        "prompt": PROMPT,
        "workdir": str(workdir),
        "task_id": task["id"],
        "reference": str(task["dir"] / "reference"),
        "bench": str(BENCH),
        "python": sys.executable,
        "model": name,
    }
    cmd = expand(agent["cmd"], values)
    timeout = agent.get("timeout_s", task.get("timeout_agent_s", 1800))
    start = time.monotonic()
    try:
        proc = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True, timeout=timeout, stdin=subprocess.DEVNULL)
        exit_code, stdout, stderr, timed_out = proc.returncode, proc.stdout, proc.stderr, False
    except subprocess.TimeoutExpired as e:
        exit_code, timed_out = None, True
        stdout = e.stdout.decode(errors="replace") if isinstance(e.stdout, bytes) else (e.stdout or "")
        stderr = e.stderr.decode(errors="replace") if isinstance(e.stderr, bytes) else (e.stderr or "")
    except FileNotFoundError as e:
        exit_code, stdout, stderr, timed_out = 127, "", str(e), False
    return {
        "agent_exit_code": exit_code,
        "agent_timed_out": timed_out,
        "agent_duration_s": round(time.monotonic() - start, 2),
        "stdout": stdout,
        "stderr": stderr,
    }


def save_artifacts(workdir: Path, dest: Path) -> None:
    """Garde ce que l'agent a produit (sans binaires) pour la grille de ressenti."""
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(workdir, dest, ignore=shutil.ignore_patterns("out", "*.class", ".git"))


def bench_commit() -> str | None:
    try:
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True)
        return out.stdout.strip() or None
    except OSError:
        return None


def run_task(name: str, agent: dict, task: dict, repeat: int, results: Path, work_base: Path, keep: bool) -> dict:
    runs = []
    model_dir = results / name
    for i in range(1, repeat + 1):
        base = work_base / name / f"run-{i}"
        base.mkdir(parents=True, exist_ok=True)
        workdir = prepare_workdir(task, base)
        agent_out = run_agent(name, agent, task, workdir)
        usage = parse_usage(agent.get("usage", "none"), agent_out["stdout"])
        graded = grade(task, workdir)
        if agent_out["agent_timed_out"]:
            graded["stage"] = "agent_timeout" if not graded["passed"] else graded["stage"]
        transcript = model_dir / "transcripts" / f"{task['id']}.run{i}.txt"
        transcript.parent.mkdir(parents=True, exist_ok=True)
        transcript.write_text(
            "# Message final\n" + (usage["final_message"] or "(non fourni par la commande)")
            + "\n\n# stdout\n" + agent_out["stdout"] + "\n\n# stderr\n" + agent_out["stderr"],
            encoding="utf-8",
        )
        save_artifacts(workdir, model_dir / "artifacts" / task["id"] / f"run-{i}")
        if not keep:
            shutil.rmtree(base, ignore_errors=True)
        runs.append({
            "run": i,
            "passed": graded["passed"],
            "stage": graded["stage"],
            "tests_passed": graded["tests_passed"],
            "tests_total": graded["tests_total"],
            "failures": graded["failures"],
            "compile_errors": graded["compile_errors"],
            "agent_exit_code": agent_out["agent_exit_code"],
            "agent_timed_out": agent_out["agent_timed_out"],
            "agent_duration_s": agent_out["agent_duration_s"],
            "grading_duration_s": graded["grading_duration_s"],
            "tokens": usage["tokens"],
            "cost_usd": usage["cost_usd"],
            "turns": usage["turns"],
        })
    costs = [r["cost_usd"] for r in runs]
    record = {
        "schema": SCHEMA,
        "model": name,
        "label": agent.get("label", name),
        "example": bool(agent.get("example", False)),
        "task": task["id"],
        "kind": task["kind"],
        "title": task["title"],
        "task_fingerprint": fingerprint(task),
        "bench_commit": bench_commit(),
        "date": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "repeat": repeat,
        "passed_runs": sum(r["passed"] for r in runs),
        "passed": all(r["passed"] for r in runs),
        "mean_agent_duration_s": round(sum(r["agent_duration_s"] for r in runs) / len(runs), 2),
        "total_cost_usd": round(sum(costs), 4) if all(c is not None for c in costs) else None,
        "runs": runs,
    }
    model_dir.mkdir(parents=True, exist_ok=True)
    (model_dir / f"{task['id']}.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return record


def write_meta(name: str, agent: dict, results: Path) -> None:
    meta = {
        "model": name,
        "label": agent.get("label", name),
        "example": bool(agent.get("example", False)),
        "release_date": agent.get("release_date"),
        "cmd": agent["cmd"],
        "updated_at": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
    }
    (results / name).mkdir(parents=True, exist_ok=True)
    (results / name / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------- vérification des tâches

def check_tasks(tasks: list[dict]) -> bool:
    """Une tâche ne mesure quelque chose que si :
    - la solution de référence passe les tests cachés (la tâche est faisable) ;
    - le point de départ, tel que l'agent le reçoit, échoue ;
    - chaque mutant (référence avec une erreur plausible, dossier mutants/) échoue."""
    ok = True
    for task in tasks:
        with tempfile.TemporaryDirectory(prefix="dmr-check-") as tmp:
            start = grade(task, prepare_workdir(task, Path(tmp) / "depart"))
            ref_dir = prepare_workdir(task, Path(tmp) / "reference")
            shutil.copytree(task["dir"] / "reference", ref_dir, dirs_exist_ok=True)
            ref = grade(task, ref_dir)
            mutants = {}
            for mutant in sorted(p for p in (task["dir"] / "mutants").glob("*") if p.is_dir()):
                mdir = prepare_workdir(task, Path(tmp) / "mutants" / mutant.name)
                shutil.copytree(task["dir"] / "reference", mdir, dirs_exist_ok=True)
                shutil.copytree(mutant, mdir, dirs_exist_ok=True)
                mutants[mutant.name] = grade(task, mdir)
        good = ref["passed"] and not start["passed"] and not any(m["passed"] for m in mutants.values())
        ok &= good
        caught = sum(not m["passed"] for m in mutants.values())
        print(f"{'OK  ' if good else 'KO  '} {task['id']:<16} référence {ref['tests_passed']}/{ref['tests_total']}"
              f"  départ : {start['stage']} {start['tests_passed']}/{start['tests_total']}"
              f"  mutants rejetés : {caught}/{len(mutants)}")
        if not ref["passed"]:
            print("     " + "\n     ".join(ref["failures"] or [ref["compile_errors"] or ""]))
        for name, m in mutants.items():
            if m["passed"]:
                print(f"     mutant non détecté : {name}")
    return ok


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--check", action="store_true", help="vérifier les tâches (référence passe, départ et mutants échouent)")
    p.add_argument("--agent", help="nom de l'agent dans le fichier de configuration")
    p.add_argument("--config", type=Path, help="fichier JSON des agents (défaut : bench/agents.json, sinon bench/agents.example.json)")
    p.add_argument("--tasks", help="identifiants de tâches séparés par des virgules (défaut : toutes)")
    p.add_argument("--repeat", type=int, default=1, help="nombre de passages par tâche (défaut : 1)")
    p.add_argument("--results", type=Path, default=ROOT / "results", help="dossier des résultats")
    p.add_argument("--keep", action="store_true", help="garder les dossiers de travail temporaires")
    args = p.parse_args(argv)
    tasks = load_tasks(args.tasks.split(",") if args.tasks else None)
    if args.check:
        return 0 if check_tasks(tasks) else 1
    if not args.agent:
        p.error("--agent ou --check est obligatoire")
    config = args.config or (BENCH / "agents.json" if (BENCH / "agents.json").exists() else BENCH / "agents.example.json")
    agents = load_agents(config)
    if args.agent not in agents:
        p.error(f"agent inconnu dans {config} : {args.agent} (connus : {', '.join(agents)})")
    agent = agents[args.agent]
    write_meta(args.agent, agent, args.results)
    work_base = Path(tempfile.mkdtemp(prefix="dmr-work-"))
    passed = 0
    for task in tasks:
        rec = run_task(args.agent, agent, task, args.repeat, args.results, work_base, args.keep)
        passed += rec["passed"]
        last = rec["runs"][-1]
        print(f"{'réussi ' if rec['passed'] else 'échoué '} {task['id']:<16} {rec['passed_runs']}/{rec['repeat']} passages"
              f"  tests {last['tests_passed']}/{last['tests_total']}  {rec['mean_agent_duration_s']} s")
    if not args.keep:
        shutil.rmtree(work_base, ignore_errors=True)
    else:
        print(f"dossiers de travail gardés : {work_base}")
    print(f"{passed}/{len(tasks)} tâches réussies · résultats dans {args.results / args.agent}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
