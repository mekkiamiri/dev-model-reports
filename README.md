# dev-model-reports

**Chaque nouveau modèle passe le même banc de tâches de développeur Java dans les 48 heures qui suivent sa sortie, et le rapport dit ce que les benchmarks ne disent pas : réussite sur des tests cachés, coût, temps, et ressenti au clavier.**

Support du talk « Ce que les benchmarks ne disent pas : N modèles testés » (conférence, 45 min, en préparation).

- Page : https://mekkiamiri.github.io/dev-model-reports/
- Slides : https://mekkiamiri.github.io/dev-model-reports/slides/

> **État : banc v0, aucun modèle réel mesuré.** Le premier rapport sera publié à la prochaine sortie de modèle. Le seul rapport présent (`reports/fake-reference-*.md`) est un **exemple généré avec un faux agent**, marqué comme tel.

## Ce qui tourne

- **5 tâches Java** neuves (lot public), chacune avec tests d'acceptation cachés, solution de référence et mutants :
  - 3 « PRD vers code » : calcul de facture (`prd-facture`), horaires d'ouverture (`prd-horaires`), configuration en couches (`prd-config`) ;
  - 1 bug à corriger (`bug-pagination`) ;
  - 1 refactoring (`refacto-tarifs`).
- **Lanceur** `bench/run.py` : dossier de travail neuf par passage (hors du dépôt, environnement épuré), agent appelé par une commande configurable (`claude -p …`, `codex exec …`, réglages de la machine ignorés), tests cachés appliqués ensuite avec une note infalsifiable (jeton aléatoire par passage, nombre de tests attendu), résultat dans `results/<modèle>/<tâche>.json` avec version de l'outil, commit du banc et empreinte de la tâche.
- **Lot de tâches privé** `tasks-private/` (ignoré par git, même format) pour les rapports publics.
- **Grille de ressenti** fixe à 7 critères : `RESSENTI.md`.
- **Rapport** `bench/report.py` : `reports/<modèle>-<date>.md` et `.html`.
- **Protocole** `PROTOCOLE.md` et **routine** `ROUTINE-48H.md`.
- **Routine automatique 48 h** `bench/routine.py` (launchd, toutes les heures) : détection des nouveaux modèles dans ai-radar (`bench/radar.py`, règles dans `bench/watch.json`, Claude Fable toujours exclu), 3 passages espacés ajoutés au même résultat (`run.py --append-run`), rapport, page et notification macOS (`bench/notify.py`).
- **CI** : vérification des tâches, tests du lanceur avec de faux agents (aucun modèle n'est appelé en CI), page à jour.

## Lancer

Prérequis : Python 3.10+ et un JDK 17+ (`javac`, `java`). Aucune dépendance.

```bash
python3 bench/run.py --check                 # les 5 tâches sont valides
python3 -m unittest discover -s tests        # tests du banc (faux agents)
```

Évaluer un modèle :

```bash
cp bench/agents.example.json bench/agents.json   # adapter la commande
python3 bench/run.py --agent claude-opus-5-5 --repeat 3
python3 bench/report.py --model claude-opus-5-5 --template   # puis remplir la grille
python3 bench/report.py --model claude-opus-5-5
python3 bench/build_site.py
```

## Routine automatique

```bash
python3 bench/radar.py --list                  # modèles que la routine voit dans ai-radar
bench/install_routine.sh --dry-run             # plist launchd (toutes les heures), sans l'installer
bench/install_routine.sh                       # installer ; --uninstall pour l'enlever
python3 bench/routine.py status                # état de chaque modèle, prochaine échéance
python3 bench/routine.py skip <modèle>         # annuler (1 h après la notification de détection)
python3 bench/routine.py add <id ai-radar>     # forcer un modèle, même hors fenêtre (« hors délai »)
python3 bench/routine.py run-now <modèle>      # étape suivante tout de suite (reprise après échec)
```

La grille de ressenti, la relecture et la publication restent humaines (`ROUTINE-48H.md`).

## État

| Élément | État |
|---|---|
| 5 tâches (référence passe, départ et mutants échouent) | fait, vérifié en CI |
| Lanceur, notation tout ou rien infalsifiable (faux agent tricheur noté en échec) | fait, testé |
| Tokens et coût via `claude -p --output-format json` / `codex exec --json` | lecture testée sur des exemples de sortie, **à vérifier au premier vrai passage** |
| Options d'isolation de `claude` (`--safe-mode`, `--restricted`…) | vérifiées dans `claude --help`, **jamais lancées sur une tâche** |
| Lot de tâches privé | format et lanceur prêts ; **tâches privées à écrire** |
| Grille de ressenti v1 | faite, jamais utilisée sur un vrai modèle |
| Rapport Markdown et HTML | fait ; exemple avec faux agent |
| Premier rapport sur un vrai modèle | **à mesurer** (prochaine sortie) |
| Isolation forte (conteneur sans le dépôt) | documentée dans PROTOCOLE.md, intégration au lanceur à faire |
| Routine 48 h (détection ai-radar, 3 passages, budget, relances, rapport, notification) | faite, testée avec horloge injectée et faux exécuteur ; **launchd pas encore installé, jamais lancée sur un vrai modèle** |
| Gabarits `codex` et `gemini` (`watch.json`), lecture `gemini-json` | options vérifiées dans `--help` ; format JSON de `gemini` **à confirmer au premier passage** |
| Tâche « comprendre une appli legacy » | à faire (piste : réutiliser `legacy-context-bench`) |
| 3 rapports publiés avant de soumettre le talk | à faire |

## Structure

```
tasks/<tâche>/      task.json, workspace/ (vu par l'agent), hidden/ (tests cachés), reference/, mutants/
bench/run.py        lanceur et notation
bench/report.py     rapport Markdown + HTML
bench/build_site.py données de la page (docs/data.js) et copie des rapports
bench/radar.py      détection des nouveaux modèles dans ai-radar (lecture seule)
bench/routine.py    routine 48 h (état dans results/_routine/, hors git)
bench/watch.json    éditeurs suivis, gabarits d'agent, exclusions, budget, calendrier
bench/notify.py     notification macOS (terminal-notifier, sinon osascript), ntfy optionnel
bench/grille.json   grille de ressenti (source pour les scripts)
results/<modèle>/   résultats, artefacts, résumés des agents, ressenti.json
reports/            rapports générés
docs/               page GitHub Pages et slides
```

## Licence

Apache-2.0, voir `LICENSE`.
