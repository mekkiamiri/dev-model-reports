"""Détection des nouveaux modèles dans ai-radar et traduction en agents du banc.

Usage :
  python3 bench/radar.py --list [--all] [--radar-db chemin]
      Modèles OpenRouter des éditeurs suivis (bench/watch.json) publiés dans la fenêtre
      (48 h), avec la raison d'une exclusion, et l'état de la source.

La base d'ai-radar (~/.ai-radar/radar.db, ou --radar-db, ou DMR_RADAR_DB) est ouverte en
lecture seule. Les doublons Hugging Face (`model-hf:…`) sont ignorés. Tout modèle contenant
« fable » est exclu, quelle que soit la configuration. Bibliothèque standard uniquement.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sqlite3
import sys
from contextlib import closing
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BENCH = ROOT / "bench"
WATCH = BENCH / "watch.json"
AGENTS = BENCH / "agents.json"
# Règle absolue, hors configuration : Claude Fable n'est pas autorisé.
ALWAYS_EXCLUDED = ("fable",)


def default_db() -> Path:
    return Path(os.environ.get("DMR_RADAR_DB") or Path.home() / ".ai-radar" / "radar.db")


def load_watch(path: Path = WATCH) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_time(value: str | None) -> dt.datetime | None:
    if not value:
        return None
    try:
        t = dt.datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return t if t.tzinfo else t.replace(tzinfo=dt.timezone.utc)


# ---------------------------------------------------------------- règles (fonctions pures)

def excluded(text: str, watch: dict | None = None) -> str | None:
    """Raison de l'exclusion d'un identifiant de modèle (ou d'un morceau de commande), sinon None."""
    low = text.lower()
    rules = (watch or {}).get("exclude", {})
    for word in ALWAYS_EXCLUDED + tuple(w.lower() for w in rules.get("contains", [])):
        if word in low:
            return f"contient « {word} »" + (" (non autorisé)" if word in ALWAYS_EXCLUDED else "")
    for suffix in rules.get("suffixes", []):
        if low.endswith(suffix.lower()):
            return f"suffixe « {suffix} »"
    for pattern in rules.get("patterns", []):
        if re.search(pattern, text, re.I):
            return f"motif « {pattern} »"
    return None


def excluded_command(name: str, cmd: list[str], watch: dict | None = None) -> str | None:
    """Le lanceur refuse un agent dont le nom ou un morceau de commande désigne un modèle exclu."""
    for part in [name, *cmd]:
        why = excluded(part, watch)
        if why:
            return f"{part!r} : {why}"
    return None


def split_id(item_id: str) -> tuple[str, str] | None:
    """`model:anthropic/claude-opus-5.5` -> (anthropic, claude-opus-5.5) ; doublons `model-hf:` ignorés."""
    m = re.fullmatch(r"model:([\w.-]+)/([\w.:-]+)", item_id or "")
    return (m.group(1).lower(), m.group(2)) if m else None


def agent_name(item_id: str) -> str | None:
    parts = split_id(item_id)
    return parts[1].replace(".", "-").replace(":", "-") if parts else None


def to_agent(item: dict, watch: dict) -> tuple[str, dict] | None:
    """Entrée de bench/agents.json pour un modèle du radar, d'après le gabarit de son éditeur."""
    parts = split_id(item["id"])
    if not parts or parts[0] not in watch["publishers"]:
        return None
    publisher, slug = parts
    rule = watch["publishers"][publisher]
    tpl = watch["templates"][rule["template"]]
    model_arg = rule["model_arg"].format(slug=slug, slug_dashed=slug.replace(".", "-"))
    agent = {k: v for k, v in tpl.items() if k != "cmd"}
    agent.update({
        "label": f"{item.get('title') or slug} via {tpl['tool']}",
        "cmd": [part.replace("{model_arg}", model_arg) for part in tpl["cmd"]],
        "model_arg": model_arg, "template": rule["template"],
        "auto_from_radar": True, "radar_id": item["id"], "release_date": item.get("published_at"),
    })
    return agent_name(item["id"]), agent


def classify(items: list[dict], watch: dict, now: dt.datetime, window_h: float | None = None) -> list[dict]:
    """Chaque modèle avec `name`, `in_window`, `excluded` (raison ou None) et `followed`."""
    window = dt.timedelta(hours=window_h if window_h is not None else watch.get("detect_window_h", 48))
    out = []
    for item in items:
        parts = split_id(item["id"])
        if not parts:
            continue
        published = parse_time(item.get("published_at")) or parse_time(item.get("first_seen"))
        out.append(dict(item, name=agent_name(item["id"]), publisher=parts[0],
                        followed=parts[0] in watch["publishers"], excluded=excluded(parts[1], watch),
                        in_window=published is not None and now - window <= published <= now + dt.timedelta(hours=1)))
    return out


def candidates(items: list[dict], watch: dict, now: dt.datetime) -> list[dict]:
    """Modèles à faire passer : éditeur suivi, non exclu, publiés dans la fenêtre."""
    return [c for c in classify(items, watch, now) if c["followed"] and not c["excluded"] and c["in_window"]]


# ---------------------------------------------------------------- base ai-radar (lecture seule)

def connect(db: Path) -> sqlite3.Connection:
    if not db.exists():
        raise FileNotFoundError(f"base ai-radar introuvable : {db}")
    return sqlite3.connect(f"file:{db}?mode=ro", uri=True)


def fetch_models(db: Path) -> list[dict]:
    with closing(connect(db)) as con:
        con.row_factory = sqlite3.Row
        rows = con.execute("SELECT id, title, url, published_at, first_seen FROM items "
                           "WHERE category = 'model' AND source = 'OpenRouter' AND id LIKE 'model:%' "
                           "ORDER BY published_at DESC").fetchall()
    return [dict(r) for r in rows]


def fetch_item(db: Path, item_id: str) -> dict | None:
    with closing(connect(db)) as con:
        con.row_factory = sqlite3.Row
        row = con.execute("SELECT id, title, url, published_at, first_seen FROM items WHERE id = ?", (item_id,)).fetchone()
    return dict(row) if row else None


def health(db: Path, now: dt.datetime, max_silence_h: float = 30) -> dict:
    """ai-radar est « muet » si aucune collecte n'est terminée depuis plus de `max_silence_h` heures."""
    try:
        with closing(connect(db)) as con:
            last = con.execute("SELECT MAX(finished) FROM runs WHERE finished IS NOT NULL AND finished != ''").fetchone()[0]
    except (FileNotFoundError, sqlite3.Error) as e:
        return {"ok": False, "last_finished": None, "message": f"ai-radar illisible : {e}"}
    t = parse_time(last)
    if t is None:
        return {"ok": False, "last_finished": None, "message": "ai-radar muet : aucune collecte terminée"}
    hours = (now - t).total_seconds() / 3600
    ok = hours <= max_silence_h
    return {"ok": ok, "last_finished": last,
            "message": f"dernière collecte terminée il y a {hours:.0f} h" if ok
            else f"ai-radar muet : aucune collecte terminée depuis {hours:.0f} h"}


# ---------------------------------------------------------------- bench/agents.json

def write_agent(name: str, agent: dict, path: Path = AGENTS) -> bool:
    """Ajoute l'agent à bench/agents.json (créé depuis agents.example.json s'il manque).
    Une entrée modifiée à la main (sans `auto_from_radar`, différente de l'exemple) n'est jamais remplacée."""
    example_file = BENCH / "agents.example.json"
    example = json.loads(example_file.read_text(encoding="utf-8")) if example_file.exists() else {}
    agents = json.loads(path.read_text(encoding="utf-8")) if path.exists() else dict(example)
    old = agents.get(name)
    if old and not old.get("auto_from_radar") and old != example.get(name):
        return False
    if old and not old.get("auto_from_radar"):
        old = None  # entrée d'exemple : remplacée par le gabarit
    agents[name] = dict(old or {}, **agent)
    path.write_text(json.dumps(agents, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--list", action="store_true", help="modèles des éditeurs suivis dans la fenêtre")
    p.add_argument("--all", action="store_true", help="avec --list : aussi hors fenêtre et éditeurs non suivis")
    p.add_argument("--radar-db", type=Path, default=None)
    p.add_argument("--watch", type=Path, default=WATCH)
    args = p.parse_args(argv)
    db, watch = args.radar_db or default_db(), load_watch(args.watch)
    now = dt.datetime.now(dt.timezone.utc)
    h = health(db, now)
    print(("source : " if h["ok"] else "ATTENTION : ") + h["message"])
    if not h["last_finished"] and not db.exists():
        return 1
    rows = classify(fetch_models(db), watch, now)
    if not args.all:
        rows = [r for r in rows if r["followed"] and r["in_window"]]
    for r in rows:
        status = f"exclu ({r['excluded']})" if r["excluded"] else ("à mesurer" if r["followed"] and r["in_window"] else
                                                                  "hors fenêtre" if r["followed"] else "éditeur non suivi")
        print(f"{r['published_at'] or '?':<26} {r['id']:<44} {status}")
    if not rows:
        print("aucun modèle dans la fenêtre" if not args.all else "aucun modèle")
    return 0


if __name__ == "__main__":
    sys.exit(main())
