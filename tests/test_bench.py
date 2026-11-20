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
import security  # noqa: E402

HAS_JAVA = shutil.which("javac") is not None and shutil.which("java") is not None
needs_java = unittest.skipUnless(HAS_JAVA, "javac et java nécessaires")


def quiet(fn, *args, **kwargs):
    with redirect_stdout(io.StringIO()) as out:
        result = fn(*args, **kwargs)
    return result, out.getvalue()


class TasksTest(unittest.TestCase):
    def test_structure_of_each_task(self):
        tasks = run.load_tasks()
        self.assertEqual(7, len(tasks))
        kinds = sorted(t["kind"] for t in tasks)
        self.assertEqual(["bug", "prd", "prd", "prd", "refactoring", "securite", "securite"], kinds)
        for t in tasks:
            d = t["dir"]
            for key in ("id", "kind", "title", "summary", "hidden_tests"):
                self.assertIn(key, t)
            self.assertEqual(d.name, t["id"])
            self.assertTrue((d / "workspace" / "TASK.md").exists(), t["id"])
            self.assertTrue((d / "hidden" / "AcceptanceTests.java").exists(), t["id"])
            self.assertTrue(any((d / "reference").rglob("*.java")), t["id"])
            self.assertTrue(any((d / "mutants").iterdir()), t["id"] + " : au moins un mutant")
            if t["kind"] == "prd":
                self.assertTrue((d / "workspace" / "PRD.md").exists())
            if t["kind"] in ("prd", "securite"):
                self.assertTrue((d / "workspace" / "CONTRACT.md").exists())

    def test_attacks_are_declared_like_hidden_tests(self):
        for t in run.load_tasks():
            d = t["dir"]
            self.assertTrue(t.get("attacks"), t["id"] + " : axe sécurité attendu sur les 7 tâches")
            self.assertTrue((d / "hidden-attacks" / "AttackTests.java").exists(), t["id"])
            ids = [a["id"] for a in t["attacks"]]
            self.assertEqual(sorted(ids), sorted(run.declared_attack_ids(t)), t["id"])
            self.assertEqual(len(ids), len(set(ids)), t["id"])
            for a in t["attacks"]:
                self.assertEqual({"id", "famille", "exigee"}, set(a) - {"reference_vulnerable"}, a)
                self.assertIsInstance(a["exigee"], bool)
                if a.get("reference_vulnerable"):
                    self.assertFalse(a["exigee"], "seule une attaque implicite peut être marquée reference_vulnerable")
            self.assertIn("fuzz", {a["famille"] for a in t["attacks"]}, t["id"])
            familles = {a["famille"] for a in t["attacks"]}
            vulns = [p.name for p in (d / "mutants-vuln").iterdir() if p.is_dir()]
            self.assertTrue(vulns, t["id"])
            self.assertLessEqual(set(vulns), familles, t["id"])
            self.assertRegex(run.attacks_fingerprint(t), r"^[0-9a-f]{12}$")
        self.assertIsNone(run.attacks_fingerprint({"attacks": []}))

    def test_security_tasks_have_mostly_required_attacks(self):
        for t in run.load_tasks():
            if t["kind"] == "securite":
                implicit = [a for a in t["attacks"] if not a["exigee"]]
                self.assertTrue(1 <= len(implicit) <= 2, t["id"])

    def test_workspace_never_contains_hidden_tests(self):
        for t in run.load_tasks():
            for p in (t["dir"] / "workspace").rglob("*"):
                if p.is_file():
                    text = p.read_text(encoding="utf-8")
                    for secret in ("AcceptanceTests", "AttackTests", "Attaques", run.REF_PACKAGE):
                        self.assertNotIn(secret, text, p)

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
        self.assertEqual(7, out.count("OK  "))
        self.assertEqual(7, out.count("mutants vulnérables détectés"))
        self.assertNotIn("non détecté", out)


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

    def run_agent(self, name, agents, tasks=None, repeat=1, static=False):
        argv = ["--agent", name, "--config", str(self.config(agents)), "--results", str(self.results), "--repeat", str(repeat)]
        if not static:
            argv.append("--no-static")
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
            self.assertEqual(t["hidden_tests"], r["runs"][0]["tests_total"], "hidden_tests de task.json à jour")
            self.assertIsNone(r["runs"][0]["tokens"])
            self.assertIsNone(r["total_cost_usd"])
            self.assertTrue(r["example"])
            self.assertTrue((self.results / "ref" / "artifacts" / t["id"] / "run-1" / "src").is_dir())
            self.assertTrue((self.results / "ref" / "transcripts" / f"{t['id']}.run1.txt").exists())
            # axe sécurité : la référence résiste à toutes les attaques, sauf celles marquées reference_vulnerable
            run0 = r["runs"][0]
            self.assertEqual("ok", run0["attacks_stage"])
            self.assertEqual(len(t["attacks"]), run0["attacks_total"])
            vulnerable = sorted(a["id"] for a in t["attacks"] if a.get("reference_vulnerable"))
            self.assertEqual(vulnerable, sorted(run0["attacks_failed"]), t["id"])
            self.assertEqual(run0["attacks_required_total"], run0["attacks_required_passed"], t["id"])
            self.assertEqual(run0["attacks_total"], run0["attacks_required_total"] + run0["attacks_implicit_total"])
            self.assertIsNone(run0["static"])  # --no-static
            self.assertEqual(run.attacks_fingerprint(t), r["attacks_fingerprint"])
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
            seen = json.loads((self.results / "spy" / "artifacts" / t["id"] / "run-1" / "VISIBLE.txt").read_text(encoding="utf-8"))
            expected = sorted(p.relative_to(t["dir"] / "workspace").as_posix()
                              for p in (t["dir"] / "workspace").rglob("*") if p.is_file())
            self.assertEqual(expected, seen["files"])
            # dossier de travail hors du dépôt, sans voisin venant du banc
            self.assertFalse(Path(seen["cwd"]).resolve().is_relative_to(ROOT.resolve()), seen["cwd"])
            for name in seen["parent"]:
                self.assertNotIn(name, {"hidden", "reference", "mutants", "results", "artifacts"} | {x["id"] for x in run.load_tasks()})
            # environnement épuré : ni OLDPWD, ni chemin du dépôt, PWD = dossier de travail
            env = seen["env"]
            self.assertNotIn("OLDPWD", env)
            self.assertEqual(Path(seen["cwd"]).resolve(), Path(env.get("PWD", "")).resolve())
            for key, value in env.items():
                self.assertNotIn(str(ROOT), value, key)
            self.assertLessEqual(set(env) - {"PWD", "__CF_USER_TEXT_ENCODING", "PYTHONIOENCODING", "LC_CTYPE"},
                                 set(run.ENV_BASE) | {"__PYVENV_LAUNCHER__"})

    @needs_java
    def test_cheating_agent_is_graded_as_failure(self):
        self.run_agent("cheat", {"cheat": self.fake("cheat", "{reference}")})
        for t in run.load_tasks():
            r = self.result("cheat", t["id"])
            self.assertFalse(r["passed"], t["id"])
            # refusé dès le contrôle du code (halt) ; sinon, le jeton aurait fait échouer la fausse ligne
            self.assertRegex(" ".join(r["runs"][0]["failures"]), "pas de ligne RESULT valide|arrêt de la JVM", t["id"])
            # La fausse ligne ne trompe pas les attaques : toute attaque qui charge la classe piégée échoue,
            # et le tricheur ne repousse jamais plus d'attaques que la référence (celles qui ne chargent
            # pas la classe piégée exécutent vraiment le code de la référence).
            run_ = r["runs"][0]
            self.assertLess(run_["attacks_passed"], run_["attacks_total"], t["id"])
            vulnerable = sum(1 for a in t["attacks"] if a.get("reference_vulnerable"))
            self.assertLessEqual(run_["attacks_passed"], run_["attacks_total"] - vulnerable, t["id"])

    @needs_java
    def test_fake_vuln_passes_functional_and_fails_attacks(self):
        agents = run.load_agents(ROOT / "bench" / "agents.example.json")
        self.run_agent("fake-vuln", {"fake-vuln": agents["fake-vuln"]})
        for t in run.load_tasks():
            r = self.result("fake-vuln", t["id"])
            run0 = r["runs"][0]
            self.assertTrue(r["passed"], t["id"])
            first = sorted(p.name for p in (t["dir"] / "mutants-vuln").iterdir() if p.is_dir())[0]
            families = {a["id"]: a["famille"] for a in t["attacks"]}
            self.assertIn(first, {families[i] for i in run0["attacks_failed"]}, t["id"])
            self.assertLess(run0["attacks_passed"], run0["attacks_total"])

    @needs_java
    def test_attacks_graded_even_if_functional_fails(self):
        t = run.load_tasks(["secu-jeton"])[0]
        work = run.prepare_workdir(t)
        try:
            shutil.copytree(t["dir"] / "mutants" / "base64-standard", work, dirs_exist_ok=True)
            shutil.copytree(t["dir"] / "reference" / "src" / "com" / "example" / "token", work / "src" / "com" / "example" / "token",
                            dirs_exist_ok=True, ignore=shutil.ignore_patterns("TokenService.java"))
            self.assertFalse(run.grade(t, work)["passed"])
            g = run.grade_attacks(t, work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        self.assertEqual("ok", g["attacks_stage"])
        self.assertGreater(g["attacks_passed"], 0)

    @needs_java
    def test_suspicious_code_is_not_attacked(self):
        t = run.load_tasks(["bug-pagination"])[0]
        work = run.prepare_workdir(t)
        try:
            shutil.copytree(t["dir"] / "reference", work, dirs_exist_ok=True)
            (work / "src" / "com" / "example" / "paging" / "Hack.java").write_text(
                "package com.example.paging;\nclass Hack { static { System.setOut(null); } }\n", encoding="utf-8")
            g = run.grade_attacks(t, work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        self.assertEqual("suspect", g["attacks_stage"])
        self.assertEqual(0, g["attacks_passed"])
        self.assertEqual(g["attacks_total"], len(g["attacks_failed"]))

    def test_task_without_attacks_has_no_security_axis(self):
        t = dict(run.load_tasks(["bug-pagination"])[0], attacks=[])
        self.assertIsNone(run.grade_attacks(t, Path(tempfile.gettempdir())))

    @needs_java
    def test_runaway_attack_is_a_breach_within_the_delay(self):
        task_dir = self.tmp / "tasks" / "boucle"
        shutil.copytree(ROOT / "tasks" / "bug-pagination", task_dir)
        (task_dir / "hidden-attacks" / "AttackTests.java").write_text("""
public class AttackTests {
    static long pile(long n) { return pile(n + 1) + 1; }
    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("boucle", () -> { while (true) { Thread.onSpinWait(); } });
        a.attaque("pile", () -> pile(0));
        a.attaque("memoire", () -> { java.util.List<long[]> l = new java.util.ArrayList<>(); while (true) { l.add(new long[1 << 20]); } });
        a.attaque("ok", () -> Check.eq(1, 1));
        a.fin();
    }
}
""", encoding="utf-8")
        t = dict(run.load_tasks(["bug-pagination"])[0], dir=task_dir, timeout_attack_s=2, attacks=[
            {"id": i, "famille": "test", "exigee": True} for i in ("boucle", "pile", "memoire", "ok")])
        work = run._overlay(t, task_dir / "reference")
        try:
            g = run.grade_attacks(t, work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        self.assertEqual(["boucle", "pile", "memoire"], g["attacks_failed"])
        reasons = " ".join(g["attacks_failures"])
        self.assertIn("délai dépassé", reasons)
        self.assertIn("StackOverflowError", reasons)
        self.assertIn("OutOfMemoryError", reasons)
        self.assertEqual((1, 4), (g["attacks_passed"], g["attacks_total"]))
        self.assertLess(g["attacks_duration_s"], 30)

    @needs_java
    def test_suspicious_code_is_rejected_before_running(self):
        t = run.load_tasks(["prd-facture"])[0]
        work = run.prepare_workdir(t)
        try:
            shutil.copytree(t["dir"] / "reference", work, dirs_exist_ok=True)
            (work / "src" / "com" / "example" / "invoice" / "Hack.java").write_text(
                "package com.example.invoice;\nclass Hack { static { try { Class.forName(\"Check\").getDeclaredField(\"TOKEN\").setAccessible(true); } catch (Exception e) {} } }\n",
                encoding="utf-8")
            g = run.grade(t, work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        self.assertFalse(g["passed"])
        self.assertEqual("suspect", g["stage"])

    @needs_java
    def test_test_count_must_match_task(self):
        t = dict(run.load_tasks(["bug-pagination"])[0], hidden_tests=99)
        work = run.prepare_workdir(t)
        try:
            shutil.copytree(t["dir"] / "reference", work, dirs_exist_ok=True)
            g = run.grade(t, work)
        finally:
            shutil.rmtree(work, ignore_errors=True)
        self.assertFalse(g["passed"])
        self.assertIn("99 attendus", " ".join(g["failures"]))

    @needs_java
    def test_private_task_set(self):
        private = self.tmp / "tasks-private"
        shutil.copytree(ROOT / "tasks" / "bug-pagination", private / "bug-pagination")
        argv = ["--agent", "noop", "--config", str(self.config({"noop": self.fake("noop")})),
                "--results", str(self.results), "--tasks-dir", str(private)]
        code, _ = quiet(run.main, argv)
        self.assertEqual(0, code)
        r = self.result("noop", "bug-pagination")
        self.assertEqual("tasks-private", r["task_set"])
        quiet(report.main, ["--model", "noop", "--date", "2026-11-16", "--results", str(self.results), "--out", str(self.tmp / "out")])
        md = (self.tmp / "out" / "noop-2026-11-16.md").read_text(encoding="utf-8")
        self.assertIn("détail non publié", md)
        self.assertNotIn("cas du ticket", md)

    def test_timeout_kills_the_whole_process_group(self):
        r = run.run_cmd(["sh", "-c", "sleep 30 & sleep 30"], Path(tempfile.gettempdir()), 1)
        self.assertTrue(r["timed_out"])
        self.assertLess(r["duration"], 10)

    def test_private_tasks_are_ignored_by_git(self):
        self.assertIn("tasks-private/", (ROOT / ".gitignore").read_text(encoding="utf-8").split())

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


class GradingIntegrityTest(unittest.TestCase):
    """Tricheries trouvées en revue : aucune ne doit rapporter de point."""

    HIJACK = """package com.example.paging;
public final class Hack {
    static {
        try {
            Object out = new java.io.PrintStream(new java.io.ByteArrayOutputStream());
            System.class.getMethod("set" + "Out", java.io.PrintStream.class).invoke(null, out);
        } catch (Exception e) { }
    }
}
"""

    def write(self, root: Path, name: str, text: str) -> Path:
        f = root / "src" / "com" / "example" / "paging" / name
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")
        return root

    def test_reflection_hijack_of_stdout_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            flags = run.suspicious(self.write(Path(tmp), "Hack.java", self.HIJACK) / "src")
        self.assertTrue(any("réflexion" in f for f in flags), flags)

    def test_unicode_escapes_do_not_hide_forbidden_calls(self):
        hidden = "class X { void f() { Runtime.getRuntime().\\u0068alt(0); } }".replace("\\\\", "\\")
        with tempfile.TemporaryDirectory() as tmp:
            flags = run.suspicious(self.write(Path(tmp), "X.java", hidden) / "src")
        self.assertTrue(flags, hidden)

    def test_calling_the_reference_by_name_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            flags = run.suspicious(self.write(Path(tmp), "D.java", "class D { Object o = new dmrref.paging.Paginator<>(null, 1); }") / "src")
        self.assertTrue(flags)

    def test_detached_process_does_not_survive(self):
        with tempfile.TemporaryDirectory() as tmp:
            marker = Path(tmp) / "vivant"
            script = f"import subprocess,sys; subprocess.Popen([sys.executable,'-c','import time,pathlib; time.sleep(2); pathlib.Path({str(marker)!r}).write_text(\"x\")'])"
            run.run_cmd([sys.executable, "-c", script], Path(tmp), 30)
            import time
            time.sleep(3)
            self.assertFalse(marker.exists(), "le processus détaché a survécu à l'agent")

    def test_fake_result_line_without_token_fails(self):
        runp = {"code": 0, "stdout": "RESULT passed=16 total=16\n", "stderr": "", "timed_out": False}
        self.assertNotEqual("ok", run._read_result(runp, "jeton-secret", 16)["stage"])
        runp["stdout"] = "RESULT jeton-secret passed=16 total=16\n"
        self.assertEqual("ok", run._read_result(runp, "jeton-secret", 16)["stage"])

    def test_excluded_model_through_env_is_refused(self):
        agent = {"cmd": ["claude", "-p", "{prompt}"], "env": {"ANTHROPIC_MODEL": "claude-FABLE-5-2"}}
        self.assertTrue(run.refused("anodin", agent, ROOT / "bench" / "watch.json"))


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

    def test_claude_error_is_recorded(self):
        data = json.loads(self.SAMPLE)
        data.update(is_error=True, subtype="error_max_turns")
        u = run.parse_usage("claude-json", json.dumps(data))
        self.assertEqual("error_max_turns", u["agent_error"])
        self.assertEqual(0.4213, u["cost_usd"])
        self.assertIsNone(run.parse_usage("claude-json", self.SAMPLE)["agent_error"])

    # Sortie réelle du premier passage (19/11/2026) : connexion absente, aucun token dépensé
    NOT_LOGGED_IN = ('{"total_cost_usd":0,"usage":{"input_tokens":0,"cache_creation_input_tokens":0,'
                     '"cache_read_input_tokens":0,"output_tokens":0},"modelUsage":{},"terminal_reason":"api_error",'
                     '"is_error":true,"num_turns":1,"subtype":"success","result":"Not logged in · Please run /login",'
                     '"type":"result"}')

    def test_not_logged_in_is_external_failure(self):
        u = run.parse_usage("claude-json", self.NOT_LOGGED_IN)
        self.assertEqual("is_error", u["agent_error"])
        self.assertTrue(u["external_failure"])

    def test_model_error_with_tokens_is_not_external(self):
        data = json.loads(self.SAMPLE)
        data.update(is_error=True, subtype="error_max_turns")
        self.assertFalse(run.parse_usage("claude-json", json.dumps(data))["external_failure"])

    def test_network_env_is_kept(self):
        import os
        os.environ["HTTPS_PROXY"] = "http://proxy.exemple:3128"
        try:
            self.assertEqual("http://proxy.exemple:3128", run.agent_env({}, Path("/tmp"))["HTTPS_PROXY"])
        finally:
            del os.environ["HTTPS_PROXY"]

    def test_codex_jsonl(self):
        out = "\n".join([
            '{"type":"thread.started"}',
            '{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":40,"output_tokens":10,"reasoning_output_tokens":5}}',
            '{"type":"turn.completed","usage":{"input_tokens":50,"cached_input_tokens":0,"output_tokens":20,"reasoning_output_tokens":0}}',
        ])
        u = run.parse_usage("codex-jsonl", out)
        self.assertEqual({"input": 150, "output": 35, "cache_read": 40}, u["tokens"])
        self.assertEqual(2, u["turns"])
        self.assertIsNone(u["cost_usd"])

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
               "total_cost_usd": 0.5, "cost_known_runs": 1, "runs": [r]}
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

    def test_repeat_is_aggregated_and_missing_cost_kept(self):
        fake_results(self.tmp / "results", "rep", False)
        f = self.tmp / "results" / "rep" / "t2.json"
        rec = json.loads(f.read_text(encoding="utf-8"))
        second = dict(rec["runs"][0], run=2, tests_passed=1, cost_usd=None, tokens=None,
                      failures=["autre règle : attendu <a> obtenu <b>"])
        rec.update(runs=[rec["runs"][0], second], repeat=2, passed_runs=0, cost_known_runs=1)
        f.write_text(json.dumps(rec), encoding="utf-8")
        quiet(report.main, ["--model", "rep", "--date", "2026-11-16", "--results", str(self.tmp / "results"), "--out", str(self.tmp / "out")])
        md = (self.tmp / "out" / "rep-2026-11-16.md").read_text(encoding="utf-8")
        self.assertIn("2/3, 1/3", md)
        self.assertIn("0.50 $ (1/2 passages)", md)
        self.assertIn("autre règle", md)
        self.assertIn("(1/2 passages)", md)
        self.assertIn("abc", md)  # empreinte affichée

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
