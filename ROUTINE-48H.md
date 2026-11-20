# Routine « 48 h après une sortie »

À copier dans une issue à chaque sortie de modèle. La page du banc (section « La routine des 48 heures ») calcule les échéances à partir de l'heure de l'annonce.

## Ce qui est automatique (`bench/routine.py`, lancé par launchd toutes les heures)

Installation : `bench/install_routine.sh` (`--dry-run` pour voir le plist, `--uninstall` pour l'enlever).

- **Détection** : un modèle OpenRouter d'un éditeur suivi (`bench/watch.json` : Anthropic → `claude`, OpenAI → `codex`, Google → `gemini`), publié il y a moins de 48 h dans ai-radar, est ajouté à `bench/agents.json` (`auto_from_radar`, `release_date`, `radar_id`). Exclus : **tout modèle « fable » (non autorisé, toujours)**, les variantes `-pro`, les motifs de `watch.json`. `python3 bench/radar.py --list` montre ce que la routine voit.
- **Notification de détection**, avec la commande pour annuler : `python3 bench/routine.py skip <modèle>` (1 h pour le faire). Outil de l'éditeur absent : notification « pas lançable », aucune mesure.
- **3 passages** du banc complet (lot public, puis `tasks-private/` s'il existe), ajoutés au même résultat (`run.py --append-run --passage k`) : passage 1 à détection + 1 h, passage 2 à H + 12 h, passage 3 à H + 24 h (H = publication). Détection tardive ou machine en veille : les passages restants se resserrent pour finir avant H + 44 h ; après H + 48 h, ils s'enchaînent et le rapport porte « hors délai ».
- **Échec extérieur** (connexion, quota : l'outil n'a pas travaillé) : relancé 2 fois au plus, au tick suivant, sur les seules tâches touchées ; il reste noté mais ne compte pas contre le modèle. Au 3e échec : notification « échec répété », reprise par `routine.py run-now <modèle>`. Un échec du modèle n'est jamais relancé.
- **Budget** : 15 $ par modèle (somme des `cost_usd` connus), ou 6 lancements pour un outil qui ne donne pas son coût ; au-delà, arrêt et notification.
- **Rapport** (`report.py`, avec calendrier des passages et bandeau « ressenti à noter ») et **page** (`build_site.py`), puis notification « rapport prêt » qui ouvre le rapport.
- **Santé** : notification « ai-radar muet » si aucune collecte n'est terminée depuis 30 h (une fois par jour).
- Commandes : `routine.py status`, `skip <modèle>`, `add <id radar>` (forcer, même hors fenêtre), `run-now <modèle>`. État : `results/_routine/state.json`, journal : `results/_routine/journal.log` (hors git).

Reste humain : budget et annonce de l'éditeur (H+0), grille de ressenti (H+8), relecture (H+24, H+36), commit et publication (H+48). Les cases ci-dessous marquées *(auto)* sont faites par la routine.

## H+0 : l'annonce
- [ ] Lire l'annonce officielle : identifiant exact du modèle, date, prix, disponibilité dans l'outil utilisé (CLI, API).
- [ ] Noter ce que l'éditeur annonce comme scores (pour la section « score annoncé vs mesuré »), avec le lien.
- [ ] *(auto)* Ajouter l'agent dans `bench/agents.json` avec `label`, `release_date` et la commande exacte ; vérifier l'entrée écrite par la routine.
- [ ] Vérifier le budget : coût estimé d'un passage complet × 3.

## H+2 : faire tourner
- [ ] `git pull` ; `git status` propre (sinon le résultat portera `-dirty`).
- [ ] `python3 bench/run.py --check` et `python3 bench/run.py --check --tasks-dir tasks-private` (les deux lots sont valides).
- [ ] `python3 -m unittest discover -s tests` (le lanceur est vert).
- [ ] Un essai sur une tâche : `python3 bench/run.py --agent <modèle> --tasks bug-pagination` ; vérifier que tokens et coût remontent.
- [ ] *(auto)* Banc complet, 3 passages espacés : `python3 bench/run.py --agent <modèle> --append-run --passage k`, puis la même chose avec `--tasks-dir tasks-private`. À la main, de préférence en conteneur sans le dépôt (PROTOCOLE.md).
- [ ] Vérifier dans les résultats : `tool_version` relevée, `bench_commit` sans `-dirty`.
- [ ] *(auto)* Si un passage casse pour une raison extérieure au modèle (réseau, quota) : relancé 2 fois, noté dans le résultat.

## H+8 : le ressenti
- [ ] `python3 bench/report.py --model <modèle> --template`.
- [ ] Lire les artefacts et les résumés **avant** de regarder les scores (sauf « honnêteté »).
- [ ] Noter les 7 critères de `RESSENTI.md`, une preuve par note.

## H+24 : le rapport
- [ ] *(auto)* `python3 bench/report.py --model <modèle>` puis `python3 bench/build_site.py` ; régénérer après la grille de ressenti.
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
