"""Routine 48 h : détecte les nouveaux modèles dans ai-radar, lance les 3 passages, le rapport, notifie.

Usage (launchd l'appelle toutes les heures, voir bench/install_routine.sh) :
  python3 bench/routine.py [tick]          une étape : santé d'ai-radar, détection, au plus UN passage
  python3 bench/routine.py status          état de chaque modèle et prochaine échéance
  python3 bench/routine.py skip <modèle>   annuler (pendant l'heure qui suit la détection, ou plus tard)
  python3 bench/routine.py add <id radar>  forcer un modèle, même hors fenêtre (rapport « hors délai »)
  python3 bench/routine.py run-now <modèle>  lancer tout de suite l'étape suivante (reprend aussi après un échec)

États : detecte -> passage1 -> passage2 -> passage3 -> rapport -> notifie ; fins anticipées :
annule, budget_depasse, non_lancable (outil absent), echec_exterieur (3 essais ratés pour une raison
extérieure au modèle : connexion, quota). H = publication du modèle. Passage 1 à détection + 1 h,
passage 2 à H + 12 h au plus tôt, passage 3 à H + 24 h ; une détection tardive resserre les passages
pour finir avant H + 44 h ; après H + 48 h ils s'enchaînent et le rapport porte « hors délai ».

État : results/_routine/state.json ; journal : results/_routine/journal.log ; un verrou empêche
deux ticks simultanés. Horloge, exécuteur de commandes et notifieur sont injectables (tests).
"""

from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

BENCH = Path(__file__).resolve().parent
ROOT = BENCH.parent
sys.path.insert(0, str(BENCH))

import notify as notify_mod  # noqa: E402
import radar  # noqa: E402

PASSES = 3
TERMINAL = {"notifie", "annule", "budget_depasse", "non_lancable", "echec_exterieur"}
H = dt.timedelta(hours=1)


def utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


def iso(t: dt.datetime | None) -> str | None:
    return t.astimezone(dt.timezone.utc).isoformat(timespec="seconds") if t else None


def due_time(k: int, published: dt.datetime, detected: dt.datetime, prev_done: dt.datetime | None, watch: dict) -> dt.datetime:
    """Échéance du passage k (1..3). Fonction pure.
    - passage 1 : détection + délai d'annulation ;
    - passage k : H + décalage nominal (12 h, 24 h) s'il n'est pas encore passé ;
    - sinon (détection tardive, machine en veille) : les passages restants se répartissent entre la fin
      du précédent et H + finish_by_h (44 h) ; au-delà, ils s'enchaînent."""
    if k == 1:
        return detected + watch.get("cancel_window_h", 1) * H
    offsets = watch.get("pass_offsets_h", [0, 12, 24])
    nominal = published + offsets[k - 1] * H
    if prev_done is None or nominal > prev_done:
        return nominal
    finish = published + watch.get("finish_by_h", 44) * H
    remaining = PASSES - k + 1
    gap = min((offsets[k - 1] - offsets[k - 2]) * H, (finish - prev_done) / (remaining + 1))
    return prev_done + max(gap, dt.timedelta(0))


def default_runner(argv: list[str], cwd: Path, log) -> int:
    try:
        r = subprocess.run(argv, cwd=cwd, capture_output=True, text=True)
    except OSError as e:
        log(f"commande impossible : {e}")
        return 127
    tail = "\n".join((r.stdout + r.stderr).strip().splitlines()[-15:])
    log(f"code {r.returncode} : {' '.join(argv[1:4])}…" + ("\n" + tail if tail else ""))
    return r.returncode


class Routine:
    def __init__(self, root: Path = ROOT, results: Path | None = None, watch: Path | None = None,
                 radar_db: Path | None = None, agents: Path | None = None, reports: Path | None = None,
                 tasks_dirs: list[Path] | None = None, clock=utcnow, runner=default_runner,
                 notifier=notify_mod.notify, which=shutil.which, run_args: list[str] | None = None):
        self.root = root
        self.results = results or root / "results"
        self.watch = radar.load_watch(watch or root / "bench" / "watch.json")
        self.db = radar_db or radar.default_db()
        self.agents = agents or root / "bench" / "agents.json"
        self.reports = reports or root / "reports"
        if tasks_dirs is None:
            private = root / "tasks-private"
            tasks_dirs = [root / "tasks"] + ([private] if any(private.glob("*/task.json")) else [])
        self.tasks_dirs = tasks_dirs
        self.clock, self.runner, self.notifier, self.which = clock, runner, notifier, which
        self.run_args = run_args or []
        self.dir = self.results / "_routine"
        self.state_file = self.dir / "state.json"
        self.state = self.load()

    # ------------------------------------------------------------ état, journal, verrou

    def load(self) -> dict:
        if self.state_file.exists():
            return json.loads(self.state_file.read_text(encoding="utf-8"))
        return {"models": {}, "radar_alert_at": None}

    def apply_skips(self) -> None:
        """Un `skip` demandé pendant un tick laisse un fichier skip-<modèle>, appliqué ici."""
        for marker in sorted(self.dir.glob("skip-*")):
            name = marker.name[len("skip-"):]
            m = self.state["models"].setdefault(name, {"state": None, "passes": [], "detected_at": iso(self.clock())})
            if m.get("state") not in TERMINAL:
                self.set_state(name, "annule", "skip à la main")
            marker.unlink()

    def save(self) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = self.state_file.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(self.state_file)

    def log(self, msg: str) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        line = f"{iso(self.clock())} {msg}"
        with open(self.dir / "journal.log", "a", encoding="utf-8") as f:
            f.write(line + "\n")
        print(line)

    def locked(self):
        """Verrou exclusif non bloquant ; None si un autre tick tourne."""
        self.dir.mkdir(parents=True, exist_ok=True)
        f = open(self.dir / "lock", "w")
        try:
            fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            f.close()
            return None
        return f

    def set_state(self, name: str, new: str, why: str = "") -> None:
        m = self.state["models"][name]
        m.setdefault("history", []).append({"at": iso(self.clock()), "from": m.get("state"), "to": new, "why": why})
        m["state"] = new
        self.log(f"{name} : {new}" + (f" ({why})" if why else ""))
        self.save()

    def tell(self, title: str, message: str, open_path: Path | None = None) -> None:
        self.log(f"notification : {title} · {message}")
        try:
            self.notifier(title, message, open_path)
        except Exception as e:  # une notification ratée ne doit pas arrêter la routine
            self.log(f"notification impossible : {e}")

    # ------------------------------------------------------------ tick

    def tick(self) -> int:
        lock = self.locked()
        if lock is None:
            print("un tick est déjà en cours")
            return 0
        try:
            self.state = self.load()
            self.apply_skips()
            self.check_health()
            self.detect()
            self.advance()
        finally:
            lock.close()
        return 0

    def check_health(self) -> None:
        now = self.clock()
        h = radar.health(self.db, now)
        if h["ok"]:
            self.state["radar_alert_at"] = None
        else:
            last = radar.parse_time(self.state.get("radar_alert_at"))
            if last is None or now - last >= 24 * H:
                self.tell("ai-radar muet", h["message"] + ". La routine ne voit plus les nouveaux modèles.")
                self.state["radar_alert_at"] = iso(now)
        self.save()

    def detect(self) -> None:
        try:
            items = radar.fetch_models(self.db)
        except Exception as e:
            self.log(f"lecture d'ai-radar impossible : {e}")
            return
        for item in radar.candidates(items, self.watch, self.clock()):
            if item["name"] not in self.state["models"]:
                self.register(item, forced=False)

    def register(self, item: dict, forced: bool) -> str:
        now = self.clock()
        why = radar.excluded(item["id"], self.watch)
        if why:
            raise SystemExit(f"{item['id']} est exclu ({why}) : voir bench/watch.json")
        made = radar.to_agent(item, self.watch)
        if not made:
            raise SystemExit(f"{item['id']} : éditeur non suivi dans bench/watch.json")
        name, agent = made
        published = radar.parse_time(item.get("published_at")) or radar.parse_time(item.get("first_seen")) or now
        late = now > published + self.watch.get("deadline_h", 48) * H
        agent["hors_delai"] = late
        self.state["models"][name] = {
            "radar_id": item["id"], "title": item.get("title"), "published_at": iso(published), "detected_at": iso(now),
            "state": None, "forced": forced, "hors_delai": late, "tool": agent["tool"], "launches": 0, "passes": [],
        }
        if not self.which(agent["tool"]):
            self.set_state(name, "non_lancable", f"outil « {agent['tool']} » absent du PATH")
            self.tell(f"{name} détecté, pas lançable", f"L'outil « {agent['tool']} » n'est pas installé : aucune mesure.")
            return name
        radar.write_agent(name, agent, self.agents)
        self.set_state(name, "detecte", "ajouté à la main" if forced else "repéré par ai-radar")
        first = due_time(1, published, now, None, self.watch).astimezone()
        self.tell(f"Nouveau modèle : {name}",
                  f"Passage 1 à {first:%H:%M}{' (hors délai)' if late else ''}. "
                  f"Annuler : python3 bench/routine.py skip {name}")
        return name

    # ------------------------------------------------------------ avancement

    def next_step(self, m: dict) -> tuple[str, dt.datetime | None]:
        """(étape, échéance) : « passage k », « rapport », « notification » ou « fin »."""
        state = m["state"]
        if state in TERMINAL or state is None:
            return "fin", None
        if state == "passage3":
            return "rapport", None
        if state == "rapport":
            return "notification", None
        k = 1 if state == "detecte" else int(state[-1]) + 1
        if m.get("retry"):
            return f"passage {k}", radar.parse_time(m["retry"]["after"])
        done = [p for p in m["passes"] if p.get("finished") and not p.get("external")]
        prev = radar.parse_time(done[-1]["finished"]) if done else None
        return f"passage {k}", due_time(k, radar.parse_time(m["published_at"]), radar.parse_time(m["detected_at"]), prev, self.watch)

    def advance(self, force: str | None = None) -> None:
        launched = False
        for name in list(self.state["models"]):
            m = self.state["models"][name]
            while True:
                self.apply_skips()
                step, due = self.next_step(m)
                if step == "rapport":
                    if not self.make_report(name, m):
                        break
                elif step == "notification":
                    self.notify_report(name, m)
                elif step.startswith("passage") and not launched and (force == name or self.clock() >= due):
                    launched = True
                    if not self.within_budget(name, m):
                        break
                    self.run_passage(name, m, int(step.split()[1]))
                else:
                    break

    # ------------------------------------------------------------ budget

    def spent(self, name: str) -> tuple[float | None, int]:
        """(coût connu en $, ou None si aucun passage ne donne de coût ; nombre de passages lancés)."""
        costs = []
        for f in (self.results / name).glob("*.json"):
            if f.name in ("meta.json", "ressenti.json"):
                continue
            rec = json.loads(f.read_text(encoding="utf-8"))
            costs += [r["cost_usd"] for r in rec.get("runs", []) if r.get("cost_usd") is not None]
        return (round(sum(costs), 4) if costs else None), self.state["models"][name].get("launches", 0)

    def within_budget(self, name: str, m: dict) -> bool:
        cost, launches = self.spent(name)
        budget = self.watch.get("budget", {})
        usd, max_launches = m.get("budget_usd", budget.get("usd_per_model", 15)), budget.get("passages_without_cost", 6)
        if cost is not None and cost >= usd:
            why = f"{cost:.2f} $ dépensés, plafond {usd} $"
        elif cost is None and launches >= max_launches:
            why = f"{launches} passages lancés sans coût connu, plafond {max_launches}"
        else:
            return True
        self.set_state(name, "budget_depasse", why)
        self.tell(f"Budget dépassé : {name}", why + ". Routine arrêtée pour ce modèle.")
        return False

    # ------------------------------------------------------------ passage

    def task_ids(self, tasks_dir: Path) -> list[str]:
        return sorted(p.parent.name for p in tasks_dir.glob("*/task.json"))

    def run_counts(self, name: str, ids: list[str]) -> dict[str, list[dict]]:
        out = {}
        for tid in ids:
            f = self.results / name / f"{tid}.json"
            out[tid] = json.loads(f.read_text(encoding="utf-8")).get("runs", []) if f.exists() else []
        return out

    def run_passage(self, name: str, m: dict, k: int) -> None:
        now = self.clock()
        published = radar.parse_time(m["published_at"])
        if now > published + self.watch.get("deadline_h", 48) * H and not m.get("hors_delai"):
            m["hors_delai"] = True
            agents = json.loads(self.agents.read_text(encoding="utf-8")) if self.agents.exists() else {}
            if name in agents:
                radar.write_agent(name, dict(agents[name], hors_delai=True), self.agents)
        retry = m.pop("retry", None)
        attempt = retry["attempt"] + 1 if retry else 1
        targets = retry["targets"] if retry else {str(d): None for d in self.tasks_dirs}
        m["launches"] = m.get("launches", 0) + 1
        self.log(f"{name} : passage {k}, essai {attempt} (H + {(now - published) / H:.1f} h)")
        self.save()
        external: dict[str, list[str] | None] = {}
        for tasks_dir, only in targets.items():
            ids = only or self.task_ids(Path(tasks_dir))
            before = self.run_counts(name, ids)
            argv = [sys.executable, str(self.root / "bench" / "run.py"), "--agent", name, "--config", str(self.agents),
                    "--append-run", "--passage", str(k), "--results", str(self.results), "--tasks-dir", tasks_dir,
                    *(["--tasks", ",".join(only)] if only else []), *self.run_args]
            code = self.runner(argv, self.root, self.log)
            after = self.run_counts(name, ids)
            new = {tid: after[tid][len(before[tid]):] for tid in ids}
            failed = [tid for tid, runs in new.items() if not runs or any(r.get("external_failure") for r in runs)]
            if code != 0 and all(not runs for runs in new.values()):
                failed = ids  # sortie illisible ou lanceur en échec : tout le lot est à refaire
            if failed:
                external[tasks_dir] = failed
        m["passes"].append({"k": k, "attempt": attempt, "started": iso(now), "finished": iso(self.clock()),
                            "external": external or None})
        if not external:
            self.set_state(name, f"passage{k}", f"essai {attempt}")
            cost, _ = self.spent(name)
            usd = m.get("budget_usd", self.watch.get("budget", {}).get("usd_per_model", 15))
            if k < PASSES and cost is not None and cost >= usd:
                self.within_budget(name, m)
            return
        lost = sum(len(v) for v in external.values())
        if attempt <= self.watch.get("max_external_retries", 2):
            m["retry"] = {"attempt": attempt, "targets": external, "after": iso(self.clock())}
            self.log(f"{name} : passage {k}, {lost} tâche(s) en échec extérieur, relance au prochain tick")
            self.save()
            return
        self.set_state(name, "echec_exterieur", f"passage {k} : {attempt} essais en échec extérieur")
        m["resume"] = {"state": "detecte" if k == 1 else f"passage{k - 1}"}
        self.save()
        self.tell(f"Échec répété : {name}",
                  f"Passage {k} abandonné après {attempt} essais (connexion, quota ?). "
                  f"Reprendre : python3 bench/routine.py run-now {name}")

    # ------------------------------------------------------------ rapport et notification

    def make_report(self, name: str, m: dict) -> bool:
        date = self.clock().date().isoformat()
        py = sys.executable
        code = self.runner([py, str(self.root / "bench" / "report.py"), "--model", name, "--date", date,
                            "--results", str(self.results), "--out", str(self.reports)], self.root, self.log)
        if code != 0:
            self.log(f"{name} : rapport en échec (code {code}), nouvel essai au prochain tick")
            return False
        self.runner([py, str(self.root / "bench" / "build_site.py")], self.root, self.log)
        m["report"] = str(self.reports / f"{name}-{date}.html")
        self.set_state(name, "rapport", m["report"])
        return True

    def notify_report(self, name: str, m: dict) -> None:
        recs = [json.loads(f.read_text(encoding="utf-8")) for f in sorted((self.results / name).glob("*.json"))
                if f.name not in ("meta.json", "ressenti.json")]
        ok = sum(bool(r.get("passed")) for r in recs)
        runs_ok, runs = sum(r.get("passed_runs", 0) for r in recs), sum(r.get("repeat", 0) for r in recs)
        self.tell(f"Rapport prêt : {name}",
                  f"Fonctionnel : {ok}/{len(recs)} tâches réussies, {runs_ok}/{runs} passages."
                  + (" Hors délai." if m.get("hors_delai") else "") + " Ressenti à noter.",
                  Path(m["report"]) if m.get("report") else None)
        self.set_state(name, "notifie")

    # ------------------------------------------------------------ commandes

    def skip(self, name: str) -> int:
        if not name.replace("-", "").replace(".", "").replace("_", "").isalnum():
            raise SystemExit(f"nom de modèle invalide : {name!r}")
        m = self.state["models"].get(name) or {}
        if m.get("state") in TERMINAL:
            print(f"{name} est déjà terminé ({m['state']})")
            return 1
        self.dir.mkdir(parents=True, exist_ok=True)
        (self.dir / f"skip-{name}").write_text(iso(self.clock()) + "\n", encoding="utf-8")
        lock = self.locked()
        if lock is None:
            print(f"{name} : annulation notée, appliquée dès la fin du passage en cours")
            return 0
        try:
            self.state = self.load()
            self.apply_skips()
            return 0
        finally:
            lock.close()

    def add(self, item_id: str) -> int:
        lock = self.locked()
        if lock is None:
            print("un tick est en cours, réessayer plus tard")
            return 1
        try:
            self.state = self.load()
            item = radar.fetch_item(self.db, item_id)
            if not item:
                raise SystemExit(f"{item_id} absent d'ai-radar ({self.db})")
            name = radar.agent_name(item_id)
            if name in self.state["models"] and self.state["models"][name].get("state") not in ("annule",):
                print(f"{name} est déjà suivi ({self.state['models'][name]['state']})")
                return 1
            self.register(item, forced=True)
            return 0
        finally:
            lock.close()

    def run_now(self, name: str) -> int:
        lock = self.locked()
        if lock is None:
            print("un tick est en cours, réessayer plus tard")
            return 1
        try:
            self.state = self.load()
            m = self.state["models"].get(name)
            if not m:
                raise SystemExit(f"{name} n'est pas suivi (routine.py add <id radar>)")
            if m["state"] == "echec_exterieur" and m.get("resume"):
                self.set_state(name, m.pop("resume")["state"], "reprise à la main")
            elif m["state"] in TERMINAL:
                print(f"{name} est terminé ({m['state']})")
                return 1
            self.advance(force=name)
            return 0
        finally:
            lock.close()

    def status(self) -> int:
        models = self.state["models"]
        if not models:
            print("aucun modèle suivi")
        for name, m in models.items():
            step, due = self.next_step(m) if m.get("published_at") else ("fin", None)
            cost, launches = self.spent(name)
            print(f"{name:<28} {m['state'] or '?':<16} H={m.get('published_at') or '?'}"
                  f"  suite : {step}{' à ' + iso(due) if due else ''}"
                  f"  coût : {'%.2f $' % cost if cost is not None else 'inconnu'} ({launches} lancement(s))"
                  + ("  hors délai" if m.get("hors_delai") else ""))
        return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", nargs="?", default="tick", choices=["tick", "status", "skip", "add", "run-now"])
    p.add_argument("target", nargs="?", help="modèle (skip, run-now) ou identifiant ai-radar (add)")
    p.add_argument("--radar-db", type=Path)
    p.add_argument("--results", type=Path)
    args = p.parse_args(argv)
    if args.command in ("skip", "add", "run-now") and not args.target:
        p.error(f"{args.command} demande un argument")
    r = Routine(results=args.results, radar_db=args.radar_db)
    if args.command == "tick":
        return r.tick()
    if args.command == "status":
        return r.status()
    if args.command == "skip":
        return r.skip(args.target)
    if args.command == "add":
        return r.add(args.target)
    return r.run_now(args.target)


if __name__ == "__main__":
    sys.exit(main())
