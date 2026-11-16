# dev-model-reports

**Chaque nouveau modèle passe le même banc de tâches de développeur Java dans les 48 heures qui suivent sa sortie, et le rapport dit ce que les benchmarks ne disent pas : réussite sur des tests cachés, coût, temps, et ressenti au clavier.**

Support du talk « Ce que les benchmarks ne disent pas : N modèles testés » (conférence, 45 min, en préparation).

- Page : https://mekkiamiri.github.io/dev-model-reports/
- Slides : https://mekkiamiri.github.io/dev-model-reports/slides/

> **État : banc v0, aucun modèle réel mesuré.** Le premier rapport sera publié à la prochaine sortie de modèle. Le seul rapport présent (`reports/fake-reference-*.md`) est un **exemple généré avec un faux agent**, marqué comme tel.

## Ce qui tourne

- **5 tâches Java** neuves, chacune avec tests d'acceptation cachés, solution de référence et mutants :
  - 3 « PRD vers code » : calcul de facture (`prd-facture`), horaires d'ouverture (`prd-horaires`), configuration en couches (`prd-config`) ;
  - 1 bug à corriger (`bug-pagination`) ;
  - 1 refactoring (`refacto-tarifs`).
- **Lanceur** `bench/run.py` : dossier de travail isolé par tâche et par modèle, agent appelé par une commande configurable (ex. `claude -p … --model …`), tests cachés appliqués ensuite, résultat dans `results/<modèle>/<tâche>.json`.
- **Grille de ressenti** fixe à 7 critères : `RESSENTI.md`.
- **Rapport** `bench/report.py` : `reports/<modèle>-<date>.md` et `.html`.
- **Protocole** `PROTOCOLE.md` et **routine** `ROUTINE-48H.md`.
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

## État

| Élément | État |
|---|---|
| 5 tâches (référence passe, départ et mutants échouent) | fait, vérifié en CI |
| Lanceur, notation tout ou rien, tokens et coût via `claude -p --output-format json` | fait ; la lecture des tokens est testée sur un exemple de sortie, **à vérifier au premier vrai passage** |
| Grille de ressenti v1 | faite, jamais utilisée sur un vrai modèle |
| Rapport Markdown et HTML | fait ; exemple avec faux agent |
| Premier rapport sur un vrai modèle | **à mesurer** (prochaine sortie) |
| Isolation forte (conteneur sans le dépôt) | à faire |
| Tâche « comprendre une appli legacy » | à faire (piste : réutiliser `legacy-context-bench`) |
| 3 rapports publiés avant de soumettre le talk | à faire |

## Structure

```
tasks/<tâche>/      task.json, workspace/ (vu par l'agent), hidden/ (tests cachés), reference/, mutants/
bench/run.py        lanceur et notation
bench/report.py     rapport Markdown + HTML
bench/build_site.py données de la page (docs/data.js) et copie des rapports
bench/grille.json   grille de ressenti (source pour les scripts)
results/<modèle>/   résultats, artefacts, résumés des agents, ressenti.json
reports/            rapports générés
docs/               page GitHub Pages et slides
```

## Licence

Apache-2.0, voir `LICENSE`.
