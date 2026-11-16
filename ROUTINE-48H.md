# Routine « 48 h après une sortie »

À copier dans une issue à chaque sortie de modèle. La page du banc (section « La routine des 48 heures ») calcule les échéances à partir de l'heure de l'annonce.

## H+0 : l'annonce
- [ ] Lire l'annonce officielle : identifiant exact du modèle, date, prix, disponibilité dans l'outil utilisé (CLI, API).
- [ ] Noter ce que l'éditeur annonce comme scores (pour la section « score annoncé vs mesuré »), avec le lien.
- [ ] Ajouter l'agent dans `bench/agents.json` (copie d'une entrée de `bench/agents.example.json`) avec `label`, `release_date` et la commande exacte.
- [ ] Vérifier le budget : coût estimé d'un passage complet × 3.

## H+2 : faire tourner
- [ ] `git pull` ; `git status` propre (sinon le résultat portera `-dirty`).
- [ ] `python3 bench/run.py --check` et `python3 bench/run.py --check --tasks-dir tasks-private` (les deux lots sont valides).
- [ ] `python3 -m unittest discover -s tests` (le lanceur est vert).
- [ ] Un essai sur une tâche : `python3 bench/run.py --agent <modèle> --tasks bug-pagination` ; vérifier que tokens et coût remontent.
- [ ] Banc complet, de préférence en conteneur sans le dépôt (PROTOCOLE.md) : `python3 bench/run.py --agent <modèle> --repeat 3`, puis la même chose avec `--tasks-dir tasks-private`.
- [ ] Vérifier dans les résultats : `tool_version` relevée, `bench_commit` sans `-dirty`.
- [ ] Si un passage casse pour une raison extérieure au modèle (réseau, quota) : le relancer et le noter dans le rapport.

## H+8 : le ressenti
- [ ] `python3 bench/report.py --model <modèle> --template`.
- [ ] Lire les artefacts et les résumés **avant** de regarder les scores (sauf « honnêteté »).
- [ ] Noter les 7 critères de `RESSENTI.md`, une preuve par note.

## H+24 : le rapport
- [ ] `python3 bench/report.py --model <modèle>` puis `python3 bench/build_site.py`.
- [ ] Relire chaque chiffre du rapport contre les fichiers `results/<modèle>/*.json`.
- [ ] Écrire 3 phrases au plus : ce qui surprend, comparé au score annoncé et aux rapports précédents.

## H+36 : relecture à froid
- [ ] Aucune phrase plus forte que les données (5 tâches, 3 passages, un notateur).
- [ ] Les limites du protocole sont dans le rapport.
- [ ] Aucun secret, aucun chemin personnel dans les fichiers publiés.

## H+48 : publier
- [ ] Commit (`results/<modèle>/`, `reports/`, `docs/`), CI verte, push.
- [ ] Lien vers le rapport sur la page et sur les réseaux.
- [ ] Ouvrir une issue pour ce qui a gêné dans la routine.
