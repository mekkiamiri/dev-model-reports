"""Notification macOS de la routine 48 h (et push ntfy optionnel).

Usage :
  python3 bench/notify.py --title "Titre" --message "Texte" [--open reports/x.html]

- `terminal-notifier` s'il est installé (clic = ouvre le rapport) ;
- sinon `osascript`, texte passé en ARGUMENT (jamais inséré dans le code AppleScript),
  puis le rapport s'ouvre dans Chrome ;
- push ntfy si `ntfy_topic` est renseigné dans ~/.ai-radar/config.toml (titre et texte seulement).
- `DMR_NOTIFY=record:<fichier>` : écrit une ligne JSON par notification au lieu de notifier (tests) ;
  `DMR_NOTIFY=off` : rien.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

RADAR_CONFIG = Path.home() / ".ai-radar" / "config.toml"
# Le texte arrive par argv : aucune injection possible dans le script AppleScript.
APPLESCRIPT = 'on run argv\ndisplay notification (item 2 of argv) with title (item 1 of argv) sound name "Glass"\nend run'


def ntfy_settings(path: Path = RADAR_CONFIG) -> tuple[str, str] | None:
    """(serveur, sujet) si ntfy_topic est renseigné dans la configuration d'ai-radar."""
    if not path.exists():
        return None
    text = path.read_text(encoding="utf-8")
    try:
        import tomllib
        conf = tomllib.loads(text)
        flat = dict(conf, **{k: v for sec in conf.values() if isinstance(sec, dict) for k, v in sec.items()})
    except (ImportError, ValueError):
        flat = dict(re.findall(r'^\s*(ntfy_\w+)\s*=\s*"([^"]*)"', text, re.M))
    topic = str(flat.get("ntfy_topic") or "").strip()
    return (str(flat.get("ntfy_server") or "https://ntfy.sh").rstrip("/"), topic) if topic else None


def commands(title: str, message: str, open_path: Path | None, which=shutil.which) -> list[list[str]]:
    """Commandes à lancer, sans les lancer (testable)."""
    url = f"file://{Path(open_path).resolve()}" if open_path else None
    tn = which("terminal-notifier")
    if tn:
        return [[tn, "-title", title, "-message", message, "-group", "dev-model-reports", *(["-open", url] if url else [])]]
    cmds = [["osascript", "-e", APPLESCRIPT, title, message]]
    if open_path:
        cmds.append(["open", "-a", "Google Chrome", str(Path(open_path).resolve())])
    return cmds


def notify(title: str, message: str, open_path: Path | None = None, *, env=None, which=shutil.which,
           config: Path = RADAR_CONFIG) -> None:
    env = os.environ if env is None else env
    mode = env.get("DMR_NOTIFY", "")
    if mode == "off":
        return
    if mode.startswith("record:"):
        with open(mode[len("record:"):], "a", encoding="utf-8") as f:
            f.write(json.dumps({"title": title, "message": message, "open": str(open_path) if open_path else None},
                               ensure_ascii=False) + "\n")
        return
    for cmd in commands(title, message, open_path, which):
        try:
            subprocess.run(cmd, timeout=15, capture_output=True)
        except (OSError, subprocess.SubprocessError) as e:
            print(f"notification impossible ({cmd[0]}) : {e}", file=sys.stderr)
    ntfy = ntfy_settings(config)
    if ntfy:
        body = json.dumps({"topic": ntfy[1], "title": title, "message": message}).encode()
        try:
            urllib.request.urlopen(urllib.request.Request(ntfy[0], data=body, headers={"Content-Type": "application/json"}),
                                   timeout=10).close()
        except OSError as e:
            print(f"push ntfy impossible : {e}", file=sys.stderr)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--title", required=True)
    p.add_argument("--message", required=True)
    p.add_argument("--open", type=Path)
    args = p.parse_args(argv)
    notify(args.title, args.message, args.open)
    return 0


if __name__ == "__main__":
    sys.exit(main())
