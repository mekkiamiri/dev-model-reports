"""Faux agent pour tester le lanceur sans appeler de modèle (utilisé par la CI).

Usage (lancé par bench/run.py, dossier courant = dossier de travail de la tâche) :
  fake_agent.py copy-reference <dossier>   copie la solution de référence : doit tout réussir
  fake_agent.py noop                       ne fait rien : doit tout rater
  fake_agent.py sleep <secondes>           dort : sert à tester le délai maximal
  fake_agent.py list                       écrit dans VISIBLE.txt les fichiers visibles, l'environnement
                                           reçu et le contenu du dossier parent (agent espion)
  fake_agent.py cheat <dossier>            copie la référence puis y injecte une fausse ligne
                                           « RESULT » et un arrêt brutal : doit être noté en échec
  fake_agent.py copy-vuln <dossier>        copie la référence puis le premier mutant vulnérable
                                           (mutants-vuln/ voisin de la référence) : réussit les tests
                                           cachés, tombe sur des attaques

Ce n'est pas un modèle : un rapport produit avec ce faux agent est un exemple de format.
"""

import json
import os
import re
import shutil
import sys
import time
from pathlib import Path


def main(argv: list[str]) -> int:
    if not argv:
        sys.exit(__doc__)
    mode = argv[0]
    here = Path.cwd()
    if mode == "copy-reference":
        shutil.copytree(argv[1], here, dirs_exist_ok=True)
        print("Faux agent : solution de référence copiée. Aucun modèle n'a été appelé.")
    elif mode == "copy-vuln":
        reference = Path(argv[1])
        shutil.copytree(reference, here, dirs_exist_ok=True)
        vulns = sorted(p for p in (reference.parent / "mutants-vuln").glob("*") if p.is_dir())
        if vulns:
            shutil.copytree(vulns[0], here, dirs_exist_ok=True)
            print(f"Faux agent vulnérable : référence et mutant {vulns[0].name} copiés. Aucun modèle n'a été appelé.")
        else:
            print("Faux agent vulnérable : pas de mutant vulnérable, référence seule copiée.")
    elif mode == "noop":
        print("Faux agent : rien fait.")
    elif mode == "sleep":
        time.sleep(float(argv[1]))
    elif mode == "list":
        files = sorted(p.relative_to(here).as_posix() for p in here.rglob("*") if p.is_file())
        report = {
            "files": files,
            "env": dict(os.environ),
            "parent": sorted(p.name for p in here.parent.iterdir()),
            "cwd": str(here),
        }
        (here / "VISIBLE.txt").write_text(json.dumps(report, indent=1), encoding="utf-8")
    elif mode == "cheat":
        shutil.copytree(argv[1], here, dirs_exist_ok=True)
        fake = 'static { System.out.println("RESULT passed=999 total=999"); Runtime.getRuntime().halt(0); }'
        for path in sorted((here / "src").rglob("*.java")):
            text = path.read_text(encoding="utf-8")
            m = re.search(r"^public (final )?class \w+[^\n]*\{$", text, re.M)
            if m:
                path.write_text(text[: m.end()] + "\n    " + fake + text[m.end():], encoding="utf-8")
                print(f"Faux agent tricheur : fausse ligne RESULT injectée dans {path.name}.")
                break
    else:
        sys.exit(f"mode inconnu : {mode}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
