"""Faux agent pour tester le lanceur sans appeler de modèle (utilisé par la CI).

Usage (lancé par bench/run.py, dossier courant = dossier de travail de la tâche) :
  fake_agent.py copy-reference <dossier>   copie la solution de référence : doit tout réussir
  fake_agent.py noop                       ne fait rien : doit tout rater
  fake_agent.py sleep <secondes>           dort : sert à tester le délai maximal
  fake_agent.py list                       écrit la liste des fichiers visibles dans VISIBLE.txt

Ce n'est pas un modèle : un rapport produit avec ce faux agent est un exemple de format.
"""

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
    elif mode == "noop":
        print("Faux agent : rien fait.")
    elif mode == "sleep":
        time.sleep(float(argv[1]))
    elif mode == "list":
        files = sorted(p.relative_to(here).as_posix() for p in here.rglob("*") if p.is_file())
        (here / "VISIBLE.txt").write_text("\n".join(files) + "\n", encoding="utf-8")
    else:
        sys.exit(f"mode inconnu : {mode}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
