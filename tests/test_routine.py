"""Tests de la routine 48 h : ai-radar, calendrier, machine à états, notification, launchd.
Aucun modèle appelé, aucune notification envoyée, rien d'installé dans launchd."""

import datetime as dt
import io
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bench"))

import notify  # noqa: E402
import radar  # noqa: E402
import report  # noqa: E402
import routine  # noqa: E402
import run  # noqa: E402

HAS_JAVA = shutil.which("javac") is not None and shutil.which("java") is not None
needs_java = unittest.skipUnless(HAS_JAVA, "javac et java nécessaires")
WATCH = radar.load_watch()
T0 = dt.datetime(2026, 11, 17, 18, 0, tzinfo=dt.timezone.utc)  # H : sortie du modèle
HOUR = dt.timedelta(hours=1)


def quiet(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()) as out:
        result = fn(*args, **kwargs)
    return result, out.getvalue()


def make_db(path: Path, items: list[tuple[str, str, dt.datetime]], last_run: dt.datetime | None) -> Path:
    """Faux radar.db : mêmes colonnes utiles que la vraie base d'ai-radar."""
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE items (id TEXT PRIMARY KEY, category TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL,"
                " source TEXT NOT NULL, description TEXT DEFAULT '', published_at TEXT, first_seen TEXT NOT NULL,"
                " last_seen TEXT NOT NULL, signals TEXT DEFAULT '{}', score REAL DEFAULT 0, status TEXT DEFAULT 'new')")
    con.execute("CREATE TABLE runs (id INTEGER PRIMARY KEY AUTOINCREMENT, started TEXT NOT NULL, finished TEXT, report TEXT DEFAULT '{}')")
    for item_id, source, published in items:
        con.execute("INSERT INTO items (id, category, title, url, source, published_at, first_seen, last_seen) VALUES (?,?,?,?,?,?,?,?)",
                    (item_id, "model", item_id.split("/")[-1], "https://exemple.invalid", source,
                     published.isoformat(), (published + HOUR).isoformat(), (published + HOUR).isoformat()))
    if last_run:
        con.execute("INSERT INTO runs (started, finished) VALUES (?, ?)", ((last_run - HOUR).isoformat(), last_run.isoformat()))
    con.commit()
    con.close()
    return path


SAMPLE_ITEMS = [
    ("model:openai/gpt-6.2-astra", "OpenRouter", T0),
    ("model:openai/gpt-6.2-astra-pro", "OpenRouter", T0),
    ("model:anthropic/claude-fable-5.2", "OpenRouter", T0),
    ("model:anthropic/claude-opus-5.5", "OpenRouter", T0 - 2 * HOUR),
    ("model:google/gemini-4-carbon", "OpenRouter", T0 - 7 * 24 * HOUR),
    ("model:qwen/qwen4-max", "OpenRouter", T0),
    ("model-hf:openai/gpt-6.2-astra", "Hugging Face (trending)", T0),
]


class RadarTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dmr-radar-"))
        self.db = make_db(self.tmp / "radar.db", SAMPLE_ITEMS, T0)

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_candidates_in_window_without_excluded_or_duplicates(self):
        items = radar.fetch_models(self.db)
        self.assertNotIn("model-hf:openai/gpt-6.2-astra", [i["id"] for i in items])
        names = [c["name"] for c in radar.candidates(items, WATCH, T0 + 3 * HOUR)]
        self.assertEqual(["claude-opus-5-5", "gpt-6-2-astra"], sorted(names))
        later = [c["name"] for c in radar.candidates(items, WATCH, T0 + 49 * HOUR)]
        self.assertEqual([], later)

    def test_fable_is_always_excluded(self):
        self.assertIn("non autorisé", radar.excluded("anthropic/claude-fable-5.2", WATCH))
        self.assertIn("non autorisé", radar.excluded("Claude-FABLE-6", {}))  # même sans configuration
        self.assertIsNotNone(radar.excluded("gpt-6.2-astra-pro", WATCH))
        self.assertIsNone(radar.excluded("gpt-6.2-astra", WATCH))
        self.assertIsNotNone(radar.excluded("x-preview", {"exclude": {"patterns": [r"-preview$"]}}))

    def test_translation_per_publisher(self):
        cases = {"model:anthropic/claude-opus-5.5": ("claude-opus-5-5", "claude", "claude-opus-5-5", "claude-json"),
                 "model:openai/gpt-6.2-astra": ("gpt-6-2-astra", "codex", "gpt-6.2-astra", "codex-jsonl"),
                 "model:google/gemini-4-carbon": ("gemini-4-carbon", "gemini", "gemini-4-carbon", "gemini-json")}
        for item_id, (name, tool, model_arg, usage) in cases.items():
            got_name, agent = radar.to_agent({"id": item_id, "title": "x", "published_at": T0.isoformat()}, WATCH)
            self.assertEqual(name, got_name)
            self.assertEqual(tool, agent["cmd"][0])
            self.assertIn(model_arg, agent["cmd"])
            self.assertIn("{prompt}", agent["cmd"])
            self.assertEqual(usage, agent["usage"])
            self.assertTrue(agent["auto_from_radar"])
            self.assertEqual(item_id, agent["radar_id"])
            self.assertEqual(T0.isoformat(), agent["release_date"])
        self.assertIsNone(radar.to_agent({"id": "model:qwen/qwen4-max"}, WATCH))

    def test_database_is_opened_read_only(self):
        con = radar.connect(self.db)
        try:
            with self.assertRaises(sqlite3.OperationalError):
                con.execute("DELETE FROM items")
        finally:
            con.close()

    def test_health(self):
        self.assertTrue(radar.health(self.db, T0 + 29 * HOUR)["ok"])
        h = radar.health(self.db, T0 + 31 * HOUR)
        self.assertFalse(h["ok"])
        self.assertIn("ai-radar muet", h["message"])
        self.assertFalse(radar.health(self.tmp / "absente.db", T0)["ok"])

    def test_write_agent_keeps_manual_entries(self):
        path = self.tmp / "agents.json"
        name, agent = radar.to_agent({"id": "model:anthropic/claude-opus-5.5", "title": "Opus"}, WATCH)
        self.assertTrue(radar.write_agent(name, agent, path))
        agents = json.loads(path.read_text(encoding="utf-8"))
        self.assertIn("fake-reference", agents)  # créé depuis agents.example.json
        self.assertTrue(agents["claude-opus-5-5"]["auto_from_radar"])
        agents["claude-opus-5-5"] = {"cmd": ["à la main"]}
        path.write_text(json.dumps(agents), encoding="utf-8")
        self.assertFalse(radar.write_agent(name, agent, path))
        self.assertEqual(["à la main"], json.loads(path.read_text(encoding="utf-8"))["claude-opus-5-5"]["cmd"])

    def test_cli_list(self):
        code, out = quiet(radar.main, ["--list", "--all", "--radar-db", str(self.db)])
        self.assertEqual(0, code)
        self.assertIn("non autorisé", out)
        self.assertIn("éditeur non suivi", out)


class AppendRunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dmr-append-"))
        self.results = self.tmp / "results"

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def config(self, agents):
        path = self.tmp / "agents.json"
        path.write_text(json.dumps(agents), encoding="utf-8")
        return path

    def launch(self, name, agents, *extra):
        return quiet(run.main, ["--agent", name, "--config", str(self.config(agents)), "--results", str(self.results), *extra])

    @needs_java
    def test_append_run_adds_numbered_passages(self):
        agents = {"ref": {"label": "faux", "example": True, "cmd": ["{python}", "{bench}/fake_agent.py", "copy-reference", "{reference}"]}}
        self.launch("ref", agents, "--tasks", "bug-pagination", "--append-run", "--passage", "1")
        self.launch("ref", agents, "--tasks", "bug-pagination", "--append-run", "--passage", "2")
        rec = json.loads((self.results / "ref" / "bug-pagination.json").read_text(encoding="utf-8"))
        self.assertEqual([1, 2], [r["run"] for r in rec["runs"]])
        self.assertEqual([1, 2], [r["passage"] for r in rec["runs"]])
        self.assertEqual((2, 2, True), (rec["repeat"], rec["passed_runs"], rec["passed"]))
        for r in rec["runs"]:
            for key in ("date", "tool_version", "bench_commit", "models_used"):
                self.assertIn(key, r)
        self.assertTrue((self.results / "ref" / "artifacts" / "bug-pagination" / "run-2").is_dir())
        self.assertTrue((self.results / "ref" / "transcripts" / "bug-pagination.run2.txt").exists())
        # sans --append-run : le résultat est écrasé, comme avant
        self.launch("ref", agents, "--tasks", "bug-pagination")
        rec = json.loads((self.results / "ref" / "bug-pagination.json").read_text(encoding="utf-8"))
        self.assertEqual(1, len(rec["runs"]))

    def test_fable_and_excluded_models_are_refused(self):
        for name, cmd in (("claude-fable-5-2", ["claude", "-p", "{prompt}"]),
                          ("mon-agent", ["claude", "-p", "{prompt}", "--model", "claude-fable-5-2"]),
                          ("astra", ["codex", "exec", "{prompt}", "--model", "gpt-6.2-astra-pro"])):
            with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()) as err:
                self.launch(name, {name: {"cmd": cmd}}, "--tasks", "bug-pagination")
            self.assertIn("refusé", err.getvalue())
            self.assertFalse((self.results / name).exists())

    def test_aggregate_ignores_external_failures(self):
        ok = {"passed": True, "agent_duration_s": 10, "cost_usd": 1.0}
        ext = {"passed": False, "agent_duration_s": 1, "cost_usd": 0, "external_failure": True}
        a = run.aggregate([ok, ext, dict(ok, passed=False)])
        self.assertEqual((2, 1, False, 1), (a["repeat"], a["passed_runs"], a["passed"], a["external_failures"]))
        self.assertEqual(10, a["mean_agent_duration_s"])
        self.assertFalse(run.aggregate([ext])["passed"])

    def test_models_used_from_claude_json(self):
        out = json.dumps({"type": "result", "num_turns": 2, "usage": {"input_tokens": 1, "output_tokens": 2},
                          "modelUsage": {"claude-opus-5-5": {}, "claude-haiku-5": {}}})
        self.assertEqual(["claude-haiku-5", "claude-opus-5-5"], run.parse_usage("claude-json", out)["models_used"])

    # Exemple PLAUSIBLE (format documenté de gemini-cli), pas une sortie réelle : à confirmer au premier passage.
    GEMINI_SAMPLE = json.dumps({
        "response": "Fait. Non vérifié : fuseaux horaires.",
        "stats": {"models": {"gemini-4-carbon": {"api": {"totalRequests": 6, "totalErrors": 0},
                                                 "tokens": {"prompt": 9000, "candidates": 1200, "total": 10500,
                                                            "cached": 4000, "thoughts": 300, "tool": 0}}},
                  "tools": {"totalCalls": 9}}})

    def test_gemini_json(self):
        u = run.parse_usage("gemini-json", "Loaded cached credentials.\n" + self.GEMINI_SAMPLE)
        self.assertEqual({"input": 9000, "output": 1500, "cache_read": 4000}, u["tokens"])
        self.assertEqual(6, u["turns"])
        self.assertEqual(["gemini-4-carbon"], u["models_used"])
        self.assertIsNone(u["cost_usd"])
        self.assertEqual("Fait. Non vérifié : fuseaux horaires.", u["final_message"])
        err = run.parse_usage("gemini-json", json.dumps({"error": {"type": "FatalAuthenticationError", "message": "x"}}))
        self.assertEqual("FatalAuthenticationError", err["agent_error"])
        self.assertTrue(err["external_failure"])
        self.assertIsNone(run.parse_usage("gemini-json", "pas du json")["tokens"])


class DueTimeTest(unittest.TestCase):
    def due(self, k, detected_h, prev_done_h=None):
        prev = T0 + prev_done_h * HOUR if prev_done_h is not None else None
        return (routine.due_time(k, T0, T0 + detected_h * HOUR, prev, WATCH) - T0) / HOUR

    def test_nominal(self):
        self.assertEqual(2, self.due(1, 1))
        self.assertEqual(12, self.due(2, 1, 3))
        self.assertEqual(24, self.due(3, 1, 13))

    def test_tightening_when_detected_late(self):
        p2 = self.due(2, 30, 32)
        self.assertTrue(32 < p2 < 44, p2)
        p3 = self.due(3, 30, p2 + 1)
        self.assertTrue(p2 + 1 < p3 < 44, p3)

    def test_chained_after_deadline(self):
        self.assertEqual(50, self.due(2, 49, 50))
        self.assertEqual(51, self.due(3, 49, 51))


class Clock:
    def __init__(self, t):
        self.t = t

    def __call__(self):
        return self.t


class FakeRunner:
    """Simule run.py, report.py et build_site.py : écrit des résultats plausibles, sans modèle."""

    def __init__(self, tmp: Path):
        self.calls, self.external, self.cost, self.tmp = [], [], None, tmp

    def __call__(self, argv, cwd, log):
        script = Path(argv[1]).name
        self.calls.append((script, argv[2:]))
        if script == "run.py":
            arg = lambda k: argv[argv.index(k) + 1] if k in argv else None
            name, results, passage = arg("--agent"), Path(arg("--results")), int(arg("--passage"))
            ids = arg("--tasks").split(",") if arg("--tasks") else sorted(p.parent.name for p in Path(arg("--tasks-dir")).glob("*/task.json"))
            external = self.external.pop(0) if self.external else False
            for tid in ids:
                f = results / name / f"{tid}.json"
                rec = json.loads(f.read_text(encoding="utf-8")) if f.exists() else {"task": tid, "runs": []}
                rec["runs"].append({"run": len(rec["runs"]) + 1, "passage": passage, "passed": not external,
                                    "external_failure": external, "cost_usd": self.cost, "agent_duration_s": 1})
                rec.update(run.aggregate(rec["runs"]))
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(json.dumps(rec), encoding="utf-8")
        elif script == "report.py":
            out = Path(argv[argv.index("--out") + 1])
            out.mkdir(parents=True, exist_ok=True)
            (out / f"{argv[argv.index('--model') + 1]}-{argv[argv.index('--date') + 1]}.html").write_text("rapport")
        return 0

    def passages(self):
        return [int(a[a.index("--passage") + 1]) for s, a in self.calls if s == "run.py"]


class RoutineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dmr-routine-"))
        self.tasks = self.tmp / "tasks"
        for tid in ("t1", "t2"):
            (self.tasks / tid).mkdir(parents=True)
            (self.tasks / tid / "task.json").write_text("{}")
        self.clock = Clock(T0)
        self.runner = FakeRunner(self.tmp)
        self.sent = []
        self.missing = set()
        self.watch = self.tmp / "watch.json"
        self.watch.write_text(json.dumps(WATCH), encoding="utf-8")
        self.items = [("model:openai/gpt-6.2-astra", "OpenRouter", T0), ("model:anthropic/claude-fable-5.2", "OpenRouter", T0),
                      ("model:google/gemini-4-carbon", "OpenRouter", T0 - 9 * 24 * HOUR)]

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def routine(self, last_run_h=None, **watch_changes):
        if watch_changes:
            self.watch.write_text(json.dumps(dict(WATCH, **watch_changes)), encoding="utf-8")
        db = self.tmp / "radar.db"
        db.unlink(missing_ok=True)
        make_db(db, self.items, self.clock() if last_run_h is None else T0 + last_run_h * HOUR)
        return routine.Routine(root=ROOT, results=self.tmp / "results", watch=self.watch, radar_db=db,
                               agents=self.tmp / "agents.json", reports=self.tmp / "reports", tasks_dirs=[self.tasks],
                               clock=self.clock, runner=self.runner, which=lambda t: None if t in self.missing else "/bin/" + t,
                               notifier=lambda title, msg, path=None: self.sent.append((title, msg, path)))

    def tick(self, at_h, **kw):
        self.clock.t = T0 + at_h * HOUR
        quiet(self.routine(**kw).tick)
        return json.loads((self.tmp / "results" / "_routine" / "state.json").read_text(encoding="utf-8"))["models"]

    def test_full_cycle(self):
        models = self.tick(1)
        self.assertEqual(["gpt-6-2-astra"], list(models))  # Fable jamais retenu, Gemini hors fenêtre
        self.assertEqual("detecte", models["gpt-6-2-astra"]["state"])
        self.assertIn("routine.py skip gpt-6-2-astra", self.sent[-1][1])
        agent = json.loads((self.tmp / "agents.json").read_text(encoding="utf-8"))["gpt-6-2-astra"]
        self.assertTrue(agent["auto_from_radar"])
        self.assertIn("gpt-6.2-astra", agent["cmd"])
        self.assertEqual("detecte", self.tick(1.5)["gpt-6-2-astra"]["state"])  # fenêtre d'annulation
        self.assertEqual("passage1", self.tick(2)["gpt-6-2-astra"]["state"])
        self.assertEqual("passage1", self.tick(11)["gpt-6-2-astra"]["state"])
        self.assertEqual("passage2", self.tick(12)["gpt-6-2-astra"]["state"])
        self.assertEqual("notifie", self.tick(24)["gpt-6-2-astra"]["state"])
        self.assertEqual([1, 2, 3], self.runner.passages())
        run_args = [a for s, a in self.runner.calls if s == "run.py"][0]
        self.assertIn("--append-run", run_args)
        self.assertIn(["report.py", "build_site.py"], [[s for s, _ in self.runner.calls[-2:]]])
        title, msg, path = self.sent[-1]
        self.assertIn("Rapport prêt", title)
        self.assertIn("2/2 tâches", msg)
        self.assertIn("Ressenti à noter", msg)
        self.assertTrue(str(path).endswith("gpt-6-2-astra-2026-11-18.html"))
        self.tick(30)
        self.assertEqual(3, len(self.runner.passages()))  # rien de plus après la notification

    def test_catch_up_after_sleep(self):
        self.tick(1)
        self.tick(2)
        models = self.tick(30)  # la machine dormait de H+3 à H+30
        self.assertEqual("passage2", models["gpt-6-2-astra"]["state"])
        self.assertEqual("passage2", self.tick(31)["gpt-6-2-astra"]["state"])  # passage 3 resserré, pas enchaîné
        m = self.routine().state["models"]["gpt-6-2-astra"]
        step, due = self.routine().next_step(m)
        self.assertEqual("passage 3", step)
        self.assertTrue(T0 + 31 * HOUR < due < T0 + 44 * HOUR, due)
        self.assertEqual("notifie", self.tick((due - T0) / HOUR)["gpt-6-2-astra"]["state"])

    def test_tightening_when_detected_late(self):
        self.items = [("model:openai/gpt-6.2-astra", "OpenRouter", T0)]
        self.tick(30)
        self.tick(31)
        self.assertEqual([1], self.runner.passages())
        hours = 31
        while self.routine().state["models"]["gpt-6-2-astra"]["state"] != "notifie" and hours < 44:
            hours += 0.5
            self.tick(hours)
        self.assertLess(hours, 44)
        self.assertEqual([1, 2, 3], self.runner.passages())
        self.assertFalse(self.routine().state["models"]["gpt-6-2-astra"]["hors_delai"])

    def test_forced_model_out_of_window_is_late(self):
        self.clock.t = T0
        code, _ = quiet(self.routine().add, "model:google/gemini-4-carbon")
        self.assertEqual(0, code)
        self.assertIn("hors délai", self.sent[-1][1])
        agent = json.loads((self.tmp / "agents.json").read_text(encoding="utf-8"))["gemini-4-carbon"]
        self.assertTrue(agent["hors_delai"])
        self.assertEqual("gemini", agent["cmd"][0])
        for h in (1, 2, 3):
            self.tick(h)
        self.assertEqual("notifie", self.routine().state["models"]["gemini-4-carbon"]["state"])
        self.assertIn("Hors délai", self.sent[-1][1])

    def test_fable_cannot_be_forced(self):
        with self.assertRaises(SystemExit):
            quiet(self.routine().add, "model:anthropic/claude-fable-5.2")

    def test_skip(self):
        self.tick(1)
        code, _ = quiet(self.routine().skip, "gpt-6-2-astra")
        self.assertEqual(0, code)
        self.assertEqual("annule", self.tick(2)["gpt-6-2-astra"]["state"])
        self.tick(30)
        self.assertEqual([], self.runner.passages())

    def test_skip_during_a_running_tick_is_applied_later(self):
        self.tick(1)
        r = self.routine()
        lock = r.locked()
        try:
            quiet(r.skip, "gpt-6-2-astra")
            self.assertEqual("detecte", r.load()["models"]["gpt-6-2-astra"]["state"])
        finally:
            lock.close()
        self.assertEqual("annule", self.tick(2)["gpt-6-2-astra"]["state"])
        self.assertEqual([], self.runner.passages())

    def test_budget_exceeded(self):
        self.runner.cost = 8.0  # 2 tâches : 16 $ dès le passage 1, plafond 15 $
        self.tick(1)
        self.assertEqual("budget_depasse", self.tick(2)["gpt-6-2-astra"]["state"])
        self.assertIn("Budget dépassé", self.sent[-1][0])
        self.tick(12)
        self.assertEqual([1], self.runner.passages())

    def test_budget_in_passages_without_cost(self):
        budget = dict(WATCH["budget"], passages_without_cost=2)
        for h in (1, 2, 12):
            self.tick(h, budget=budget)
        self.assertEqual("budget_depasse", self.tick(24, budget=budget)["gpt-6-2-astra"]["state"])
        self.assertEqual([1, 2], self.runner.passages())

    def test_external_failure_retried_twice_then_abandoned(self):
        self.runner.external = [True, True, True, False]
        self.tick(1)
        self.assertEqual("detecte", self.tick(2)["gpt-6-2-astra"]["state"])
        self.assertEqual("detecte", self.tick(3)["gpt-6-2-astra"]["state"])
        self.assertEqual("echec_exterieur", self.tick(4)["gpt-6-2-astra"]["state"])
        self.assertIn("Échec répété", self.sent[-1][0])
        self.assertIn("run-now gpt-6-2-astra", self.sent[-1][1])
        self.tick(5)
        self.assertEqual([1, 1, 1], self.runner.passages())  # jamais un 4e essai tout seul
        retry_args = [a for s, a in self.runner.calls if s == "run.py"][1]
        self.assertIn("--tasks", retry_args)
        self.clock.t = T0 + 6 * HOUR
        quiet(self.routine().run_now, "gpt-6-2-astra")
        self.assertEqual("passage1", self.routine().state["models"]["gpt-6-2-astra"]["state"])

    def test_external_failure_then_success_and_model_failure_not_retried(self):
        self.runner.external = [True, False]
        self.tick(1)
        self.tick(2)
        self.assertEqual("passage1", self.tick(3)["gpt-6-2-astra"]["state"])
        rec = json.loads((self.tmp / "results" / "gpt-6-2-astra" / "t1.json").read_text(encoding="utf-8"))
        self.assertEqual((1, 1), (rec["repeat"], rec["external_failures"]))

    def test_tool_missing_is_not_launchable(self):
        self.missing = {"codex"}
        self.assertEqual("non_lancable", self.tick(1)["gpt-6-2-astra"]["state"])
        self.assertIn("pas lançable", self.sent[-1][0])
        self.assertFalse((self.tmp / "agents.json").exists())
        self.tick(5)
        self.assertEqual([], self.runner.passages())

    def test_radar_silent_is_notified_once_a_day(self):
        self.tick(40, last_run_h=0)
        self.tick(41, last_run_h=0)
        self.assertEqual(1, sum("ai-radar muet" in t for t, _, _ in self.sent))
        self.tick(65, last_run_h=0)
        self.assertEqual(2, sum("ai-radar muet" in t for t, _, _ in self.sent))

    def test_lock_prevents_overlap(self):
        r = self.routine()
        lock = r.locked()
        try:
            self.clock.t = T0 + HOUR
            code, out = quiet(self.routine().tick)
        finally:
            lock.close()
        self.assertEqual(0, code)
        self.assertIn("déjà en cours", out)
        self.assertFalse((self.tmp / "results" / "_routine" / "state.json").exists())

    def test_routine_state_is_ignored_by_git(self):
        self.assertIn("results/_routine/", (ROOT / ".gitignore").read_text(encoding="utf-8").split())

    def test_status(self):
        self.tick(1)
        code, out = quiet(self.routine().status)
        self.assertIn("gpt-6-2-astra", out)
        self.assertIn("passage 1", out)

    @needs_java
    def test_real_runner_with_fake_agent(self):
        """Bout en bout avec le vrai run.py (faux agent, une tâche) : les arguments de la routine sont compris."""
        tasks = self.tmp / "vraies-taches"
        shutil.copytree(ROOT / "tasks" / "bug-pagination", tasks / "bug-pagination")
        (self.tmp / "agents.json").write_text(json.dumps({"fake-ref": {"example": True, "label": "faux",
            "cmd": ["{python}", "{bench}/fake_agent.py", "copy-reference", "{reference}"]}}), encoding="utf-8")
        r = routine.Routine(root=ROOT, results=self.tmp / "results", watch=self.watch, radar_db=self.tmp / "absente.db",
                            agents=self.tmp / "agents.json", reports=self.tmp / "reports", tasks_dirs=[tasks],
                            clock=self.clock, runner=lambda a, c, log: subprocess.run(a, cwd=c, capture_output=True).returncode,
                            notifier=lambda *a: None)
        r.state["models"]["fake-ref"] = {"state": "detecte", "published_at": routine.iso(T0), "detected_at": routine.iso(T0),
                                         "passes": [], "launches": 0}
        r.save()
        quiet(r.run_now, "fake-ref")
        rec = json.loads((self.tmp / "results" / "fake-ref" / "bug-pagination.json").read_text(encoding="utf-8"))
        self.assertTrue(rec["passed"])
        self.assertEqual(1, rec["runs"][0]["passage"])
        self.assertEqual("passage1", r.state["models"]["fake-ref"]["state"])


class NotifyTest(unittest.TestCase):
    def test_record_mode(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "notes.jsonl"
            notify.notify("Titre", "Texte", Path("reports/x.html"), env={"DMR_NOTIFY": f"record:{out}"})
            notify.notify("Titre 2", "Texte 2", env={"DMR_NOTIFY": f"record:{out}"})
            lines = [json.loads(l) for l in out.read_text(encoding="utf-8").splitlines()]
        self.assertEqual(["Titre", "Titre 2"], [l["title"] for l in lines])
        self.assertEqual("reports/x.html", lines[0]["open"])

    def test_terminal_notifier_opens_the_report(self):
        cmds = notify.commands("T", "M", Path("/tmp/r.html"), which=lambda t: "/opt/homebrew/bin/terminal-notifier")
        self.assertEqual(1, len(cmds))
        self.assertIn("-open", cmds[0])
        self.assertTrue(cmds[0][-1].startswith("file://") and cmds[0][-1].endswith("r.html"))

    def test_osascript_text_is_an_argument_never_code(self):
        evil = 'x" & (do shell script "touch /tmp/pwned") & "'
        cmds = notify.commands("Titre", evil, Path("/tmp/r.html"), which=lambda t: None)
        self.assertEqual(["osascript", "-e", notify.APPLESCRIPT, "Titre", evil], cmds[0])
        self.assertNotIn("pwned", notify.APPLESCRIPT)
        self.assertEqual(["open", "-a", "Google Chrome"], cmds[1][:3])

    def test_ntfy_only_when_topic_is_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            conf = Path(tmp) / "config.toml"
            conf.write_text('ntfy_topic = ""\n', encoding="utf-8")
            self.assertIsNone(notify.ntfy_settings(conf))
            conf.write_text('ntfy_topic = "sujet-test"\nntfy_server = "https://ntfy.exemple"\n', encoding="utf-8")
            self.assertEqual(("https://ntfy.exemple", "sujet-test"), notify.ntfy_settings(conf))
            self.assertIsNone(notify.ntfy_settings(Path(tmp) / "absent.toml"))


class InstallTest(unittest.TestCase):
    def test_dry_run_prints_the_plist_and_writes_nothing(self):
        with tempfile.TemporaryDirectory() as home:
            env = {"HOME": home, "PATH": os.environ["PATH"]}
            r = subprocess.run(["bash", str(ROOT / "bench" / "install_routine.sh"), "--dry-run"], env=env,
                               capture_output=True, text=True)
            self.assertEqual(0, r.returncode, r.stderr)
            self.assertFalse((Path(home) / "Library").exists())
        plist = r.stdout
        for needle in ("local.dev-model-reports.routine", "<integer>3600</integer>", "<key>RunAtLoad</key><true/>",
                       f"{ROOT}/bench/routine.py", "/opt/homebrew/bin", f"{home}/.local/bin",
                       f"{home}/.sdkman/candidates/java/current/bin", f"{home}/Library/Logs/dev-model-reports.log"):
            self.assertIn(needle, plist)
        if shutil.which("plutil"):
            with tempfile.NamedTemporaryFile("w", suffix=".plist", delete=False) as f:
                f.write(plist)
            try:
                self.assertEqual(0, subprocess.run(["plutil", "-lint", f.name], capture_output=True).returncode)
            finally:
                os.unlink(f.name)


class ReportCalendarTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dmr-cal-"))
        d = self.tmp / "results" / "m"
        d.mkdir(parents=True)
        (d / "meta.json").write_text(json.dumps({"model": "m", "label": "Modèle m", "example": False, "cmd": [],
                                                 "release_date": T0.isoformat()}), encoding="utf-8")
        base = {"passed": True, "stage": "ok", "tests_passed": 3, "tests_total": 3, "failures": [], "compile_errors": None,
                "agent_duration_s": 10.0, "tokens": None, "cost_usd": None, "models_used": ["gpt-6.2-astra"]}
        runs = [dict(base, run=k, passage=k, date=(T0 + h * HOUR).isoformat()) for k, h in ((1, 2), (2, 12), (3, 50))]
        rec = {"model": "m", "task": "t1", "kind": "prd", "title": "Tâche t1", "task_fingerprint": "abc",
               "bench_commit": "1234567", **run.aggregate(runs), "runs": runs}
        (d / "t1.json").write_text(json.dumps(rec), encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def build(self):
        quiet(report.main, ["--model", "m", "--date", "2026-11-19", "--results", str(self.tmp / "results"), "--out", str(self.tmp / "out")])
        return ((self.tmp / "out" / "m-2026-11-19.md").read_text(encoding="utf-8"),
                (self.tmp / "out" / "m-2026-11-19.html").read_text(encoding="utf-8"))

    def test_calendar_late_and_ressenti_banner(self):
        md, page = self.build()
        self.assertIn("## Calendrier des passages", md)
        for h in ("H + 2 h", "H + 12 h", "H + 50 h"):
            self.assertIn(h, md)
        self.assertIn("Hors délai", md)
        self.assertIn("Ressenti à noter", md)
        self.assertIn("`gpt-6.2-astra`", md)
        self.assertIn("Calendrier des passages", page)
        self.assertIn("Ressenti à noter", page)
        quiet(report.main, ["--model", "m", "--template", "--results", str(self.tmp / "results")])
        md, _ = self.build()
        self.assertNotIn("Ressenti à noter", md)


if __name__ == "__main__":
    unittest.main()
