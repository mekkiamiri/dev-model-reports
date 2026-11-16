"""Tests du banc : lanceur, notation, faux agents, rapport, page. Bibliothèque standard uniquement."""

import io
import json
import re
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bench"))

import build_site  # noqa: E402
import report  # noqa: E402
import run  # noqa: E402

HAS_JAVA = shutil.which("javac") is not None and shutil.which("java") is not None
needs_java = unittest.skipUnless(HAS_JAVA, "javac et java nécessaires")


def quiet(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()) as out:
        result = fn(*args, **kwargs)
    return result, out.getvalue()


class TasksTest(unittest.TestCase):
    def test_structure_of_each_task(self):
        tasks = run.load_tasks()
        self.assertEqual(5, len(tasks))
        kinds = sorted(t["kind"] for t in tasks)
        self.assertEqual(["bug", "prd", "prd", "prd", "refactoring"], kinds)
        for t in tasks:
            d = t["dir"]
            for key in ("id", "kind", "title", "summary"):
                self.assertIn(key, t)
            self.assertEqual(d.name, t["id"])
            self.assertTrue((d / "workspace" / "TASK.md").exists(), t["id"])
            self.assertTrue((d / "hidden" / "AcceptanceTests.java").exists(), t["id"])
            self.assertTrue(any((d / "reference").rglob("*.java")), t["id"])
            self.assertTrue(any((d / "mutants").iterdir()), t["id"] + " : au moins un mutant")
            if t["kind"] == "prd":
                self.assertTrue((d / "workspace" / "PRD.md").exists())
                self.assertTrue((d / "workspace" / "CONTRACT.md").exists())

    def test_workspace_never_contains_hidden_tests(self):
        for t in run.load_tasks():
            for p in (t["dir"] / "workspace").rglob("*"):
                if p.is_file():
                    self.assertNotIn("AcceptanceTests", p.read_text(encoding="utf-8"), p)

    def test_unknown_task_rejected(self):
        with self.assertRaises(SystemExit):
            run.load_tasks(["nexiste-pas"])

    def test_fingerprint_is_stable(self):
        t = run.load_tasks(["prd-facture"])[0]
        self.assertEqual(run.fingerprint(t), run.fingerprint(t))
        self.assertRegex(run.fingerprint(t), r"^[0-9a-f]{12}$")

    @needs_java
    def test_check_reference_passes_start_and_mutants_fail(self):
        ok, out = quiet(run.check_tasks, run.load_tasks())
        self.assertTrue(ok, out)
        self.assertEqual(5, out.count("OK  "))


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dmr-test-"))
        self.results = self.tmp / "results"

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def config(self, agents: dict) -> Path:
        path = self.tmp / "agents.json"
        path.write_text(json.dumps(agents), encoding="utf-8")
        return path

    def fake(self, *args, **extra):
        return {"label": "faux", "example": True, "cmd": ["{python}", "{bench}/fake_agent.py", *args], **extra}

    def run_agent(self, name, agents, tasks=None, repeat=1):
        argv = ["--agent", name, "--config", str(self.config(agents)), "--results", str(self.results), "--repeat", str(repeat)]
        if tasks:
            argv += ["--tasks", tasks]
        code, out = quiet(run.main, argv)
        self.assertEqual(0, code, out)
        return out

    def result(self, name, task):
        return json.loads((self.results / name / f"{task}.json").read_text(encoding="utf-8"))

    @needs_java
    def test_fake_reference_passes_every_task(self):
        self.run_agent("ref", {"ref": self.fake("copy-reference", "{reference}")})
        for t in run.load_tasks():
            r = self.result("ref", t["id"])
            self.assertTrue(r["passed"], r)
            self.assertEqual("ok", r["runs"][0]["stage"])
            self.assertEqual(r["runs"][0]["tests_passed"], r["runs"][0]["tests_total"])
            self.assertIsNone(r["runs"][0]["tokens"])
            self.assertIsNone(r["total_cost_usd"])
            self.assertTrue(r["example"])
            self.assertTrue((self.results / "ref" / "artifacts" / t["id"] / "run-1" / "src").is_dir())
            self.assertTrue((self.results / "ref" / "transcripts" / f"{t['id']}.run1.txt").exists())
        meta = json.loads((self.results / "ref" / "meta.json").read_text(encoding="utf-8"))
        self.assertTrue(meta["example"])

    @needs_java
    def test_fake_noop_fails_every_task(self):
        self.run_agent("noop", {"noop": self.fake("noop")})
        for t in run.load_tasks():
            r = self.result("noop", t["id"])
            self.assertFalse(r["passed"], t["id"])
            self.assertIn(r["runs"][0]["stage"], ("compile", "tests"))

    @needs_java
    def test_repeat_counts_runs(self):
        self.run_agent("ref", {"ref": self.fake("copy-reference", "{reference}")}, tasks="bug-pagination", repeat=2)
        r = self.result("ref", "bug-pagination")
        self.assertEqual(2, r["repeat"])
        self.assertEqual(2, r["passed_runs"])
        self.assertEqual([1, 2], [x["run"] for x in r["runs"]])

    @needs_java
    def test_agent_sees_only_the_workspace(self):
        self.run_agent("spy", {"spy": self.fake("list")})
        for t in run.load_tasks():
            visible = (self.results / "spy" / "artifacts" / t["id"] / "run-1" / "VISIBLE.txt").read_text(encoding="utf-8").split()
            expected = sorted(p.relative_to(t["dir"] / "workspace").as_posix()
                              for p in (t["dir"] / "workspace").rglob("*") if p.is_file())
            self.assertEqual(expected, visible)

    @needs_java
    def test_agent_timeout_is_recorded(self):
        self.run_agent("slow", {"slow": self.fake("sleep", "5", timeout_s=1)}, tasks="prd-facture")
        r = self.result("slow", "prd-facture")
        self.assertFalse(r["passed"])
        self.assertTrue(r["runs"][0]["agent_timed_out"])
        self.assertEqual("agent_timeout", r["runs"][0]["stage"])

    @needs_java
    def test_missing_command_is_a_failure_not_a_crash(self):
        self.run_agent("ghost", {"ghost": {"cmd": ["commande-qui-nexiste-pas-dmr"]}}, tasks="prd-facture")
        r = self.result("ghost", "prd-facture")
        self.assertFalse(r["passed"])
        self.assertEqual(127, r["runs"][0]["agent_exit_code"])

    def test_unknown_agent_rejected(self):
        with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
            quiet(run.main, ["--agent", "inconnu", "--config", str(self.config({})), "--results", str(self.results)])

    def test_placeholders_expanded(self):
        cmd = run.expand(["x", "{prompt}", "--model", "{model}"], {"prompt": "P", "model": "m"})
        self.assertEqual(["x", "P", "--model", "m"], cmd)

    def test_example_config_is_valid(self):
        agents = run.load_agents(ROOT / "bench" / "agents.example.json")
        self.assertTrue(agents["fake-reference"]["example"])
        for name, agent in agents.items():
            self.assertIn("cmd", agent, name)
            if not agent.get("example"):
                self.assertIn("{prompt}", agent["cmd"], name)


class UsageTest(unittest.TestCase):
    SAMPLE = json.dumps({
        "type": "result", "result": "Fait. Non vérifié : CRLF.", "num_turns": 7, "total_cost_usd": 0.4213,
        "usage": {"input_tokens": 1200, "output_tokens": 3400, "cache_read_input_tokens": 50000, "cache_creation_input_tokens": 8000},
    })

    def test_claude_json(self):
        u = run.parse_usage("claude-json", self.SAMPLE)
        self.assertEqual({"input": 1200, "output": 3400, "cache_read": 50000, "cache_write": 8000}, u["tokens"])
        self.assertEqual(0.4213, u["cost_usd"])
        self.assertEqual(7, u["turns"])
        self.assertEqual("Fait. Non vérifié : CRLF.", u["final_message"])

    def test_claude_json_after_noise(self):
        u = run.parse_usage("claude-json", "avertissement\n" + self.SAMPLE)
        self.assertEqual(7, u["turns"])

    def test_garbage_or_none_gives_nothing(self):
        for kind, out in (("claude-json", "pas du json"), ("claude-json", ""), ("none", self.SAMPLE)):
            u = run.parse_usage(kind, out)
            self.assertIsNone(u["tokens"])
            self.assertIsNone(u["cost_usd"])


def fake_results(base: Path, name: str, example: bool) -> None:
    d = base / name
    d.mkdir(parents=True)
    (d / "meta.json").write_text(json.dumps({"model": name, "label": "Modèle " + name, "example": example, "cmd": []}), encoding="utf-8")
    run_ok = {"run": 1, "passed": True, "stage": "ok", "tests_passed": 3, "tests_total": 3, "failures": [], "compile_errors": None,
              "agent_exit_code": 0, "agent_timed_out": False, "agent_duration_s": 12.0, "grading_duration_s": 1.0,
              "tokens": {"input": 10, "output": 20, "cache_read": 0, "cache_write": 0}, "cost_usd": 0.5, "turns": 3}
    run_ko = dict(run_ok, passed=False, stage="tests", tests_passed=2, failures=["règle rare : attendu <1> obtenu <2>"])
    for task, r in (("t1", run_ok), ("t2", run_ko)):
        rec = {"schema": 1, "model": name, "label": "Modèle " + name, "example": example, "task": task, "kind": "prd",
               "title": "Tâche " + task, "task_fingerprint": "abc", "bench_commit": "1234567", "date": "2026-11-16T00:00:00+00:00",
               "repeat": 1, "passed_runs": int(r["passed"]), "passed": r["passed"], "mean_agent_duration_s": 12.0,
               "total_cost_usd": 0.5, "runs": [r]}
        (d / f"{task}.json").write_text(json.dumps(rec), encoding="utf-8")


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="dmr-report-"))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def build(self, name, example):
        fake_results(self.tmp / "results", name, example)
        quiet(report.main, ["--model", name, "--date", "2026-11-16", "--results", str(self.tmp / "results"), "--out", str(self.tmp / "out")])
        md = (self.tmp / "out" / f"{name}-2026-11-16.md").read_text(encoding="utf-8")
        page = (self.tmp / "out" / f"{name}-2026-11-16.html").read_text(encoding="utf-8")
        return md, page

    def test_example_has_banner_and_no_invented_scores(self):
        md, page = self.build("faux", True)
        self.assertIn("EXEMPLE, pas un vrai modèle", md)
        self.assertIn("EXEMPLE, pas un vrai modèle", page)
        self.assertEqual(7, md.count("non noté"))
        self.assertRegex(md, r"\*\*1/2\*\*")
        self.assertIn("règle rare", md)
        self.assertIn("1.00 $", md)

    def test_real_model_has_no_banner(self):
        md, page = self.build("vrai", False)
        self.assertNotIn("EXEMPLE", md)
        self.assertNotIn("EXEMPLE", page)

    def test_ressenti_scores_are_shown(self):
        fake_results(self.tmp / "results", "noté", False)
        quiet(report.main, ["--model", "noté", "--template", "--results", str(self.tmp / "results")])
        path = self.tmp / "results" / "noté" / "ressenti.json"
        data = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(7, len(data["criteria"]))
        data["criteria"]["honnetete"] = {"score": 2, "evidence": "t2 : annonce « tous les tests passent »"}
        path.write_text(json.dumps(data), encoding="utf-8")
        quiet(report.main, ["--model", "noté", "--date", "2026-11-16", "--results", str(self.tmp / "results"), "--out", str(self.tmp / "out")])
        md = (self.tmp / "out" / "noté-2026-11-16.md").read_text(encoding="utf-8")
        self.assertIn("| Honnêteté sur ce qui n'est pas fait | 2/5 |", md)
        self.assertEqual(6, md.count("non noté"))

    def test_html_escapes_content(self):
        fake_results(self.tmp / "results", "x", False)
        f = self.tmp / "results" / "x" / "t2.json"
        rec = json.loads(f.read_text(encoding="utf-8"))
        rec["runs"][0]["failures"] = ["<script>alert(1)</script>"]
        f.write_text(json.dumps(rec), encoding="utf-8")
        quiet(report.main, ["--model", "x", "--date", "2026-11-16", "--results", str(self.tmp / "results"), "--out", str(self.tmp / "out")])
        self.assertNotIn("<script>alert", (self.tmp / "out" / "x-2026-11-16.html").read_text(encoding="utf-8"))


class RepoTest(unittest.TestCase):
    def test_grille_matches_ressenti_md(self):
        grille = json.loads((ROOT / "bench" / "grille.json").read_text(encoding="utf-8"))
        doc = (ROOT / "RESSENTI.md").read_text(encoding="utf-8")
        self.assertTrue(6 <= len(grille["criteria"]) <= 8)
        for c in grille["criteria"]:
            self.assertIn(f"{c['name']} (`{c['id']}`)", doc)
            self.assertEqual({"1", "3", "5"}, set(c["anchors"]))

    def test_every_committed_report_of_a_fake_agent_is_marked(self):
        for md in (ROOT / "reports").glob("*.md"):
            name = re.sub(r"-\d{4}-\d{2}-\d{2}$", "", md.stem)
            meta = json.loads((ROOT / "results" / name / "meta.json").read_text(encoding="utf-8"))
            if meta["example"] or name.startswith("fake-"):
                self.assertTrue(meta["example"], name)
                self.assertTrue(md.read_text(encoding="utf-8").startswith("> **EXEMPLE, pas un vrai modèle"), md)

    def test_site_is_up_to_date(self):
        code, out = quiet(build_site.main, ["--check"])
        self.assertEqual(0, code, out)

    def test_site_lists_no_example_as_real_report(self):
        data = build_site.build()["data.js"]
        payload = json.loads(data.split("window.BENCH = ", 1)[1].rstrip().rstrip(";"))
        for r in payload["reports"]:
            self.assertFalse(r["model"].startswith("fake-"), r)


if __name__ == "__main__":
    unittest.main()
