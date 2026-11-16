# Protocole

Objectif : évaluer chaque nouveau modèle sur des tâches de développeur Java, de la même façon à chaque fois, et publier un rapport dans les 48 heures qui suivent sa sortie (voir `ROUTINE-48H.md`).

## Méthodes de référence

- **Zero2Repo** (arXiv 2609.38269, v3 du 18 octobre 2026) : l'agent reçoit un PRD, un contrat d'interface et un dossier vide ; des tests d'acceptation cachés jusqu'à la soumission décident de la réussite, en tout ou rien, sans juge LLM. Une solution de référence prouve que chaque tâche est faisable, et des contrôles vérifient que les tests rejettent des implémentations incorrectes. L'article note que les soumissions ratées passent quand même 90 à 99 % des tests cachés, et que la plupart des échecs viennent d'une règle rare ou oubliée du PRD. C'est pour ça que le banc enregistre aussi le nombre de tests passés, et que les PRD contiennent volontairement des règles rares.
- **Vibe Code Bench** (vals.ai, version 1.1) : construire des applis web complètes à partir d'une spécification, notées par un agent évaluateur qui clique dans l'appli ; publie précision, coût, tokens et durée par test. On reprend l'idée de publier le coût et la durée à côté de la réussite. On n'utilise pas d'agent évaluateur.
- **MathArena Euler** : 24 problèmes de Project Euler (943 à 966). Les auteurs ne publient pas les solutions des modèles « pour respecter les règles de Project Euler ». **Project Euler n'est pas utilisé ici** : son contenu hors énoncés principaux ne peut pas être republié, et le site a pris des mesures contre l'usage de l'IA générative (annonce du 3 juillet 2026). Un rapport public qui montre le code des modèles serait en conflit avec ces règles.

## Les tâches (v0)

| Tâche | Type | Ce qui la rend difficile |
|---|---|---|
| `prd-facture` | PRD vers code | TVA par taux et non par ligne, virgule décimale, numéro de ligne exact dans les erreurs |
| `prd-horaires` | PRD vers code | plages de nuit, « la dernière règle l'emporte », débordement sur un jour d'exception |
| `prd-config` | PRD vers code | `!unset`, défaut littéral, `$$`, détection de cycle sans faux positif |
| `bug-pagination` | bug à corriger | la correction évidente casse un cas documenté (liste vide) |
| `refacto-tarifs` | refactoring | ne pas changer un centime, y compris les erreurs ; structure imposée |

**Pourquoi Java** : le talk vise des développeurs Java, et les bancs publics les plus cités sont surtout en Python. Pour garder le banc sans dépendance, les tâches sont en Java 17 pur : pas de Maven, pas de JUnit ; un mini-harnais (`bench/java/Check.java`) suffit, avec `javac --release 17`. La CI utilise un JDK 21 (compile en `--release 17`). Le lanceur et le rapport sont en Python, bibliothèque standard.

Chaque tâche a :
- `workspace/` : tout ce que l'agent voit (TASK.md, PRD ou ticket, contrat, code de départ, tests visibles éventuels) ;
- `hidden/` : les tests d'acceptation cachés ;
- `reference/` : une solution de référence ;
- `mutants/` : la référence avec une erreur plausible, qui doit échouer.

`python3 bench/run.py --check` vérifie pour chaque tâche que la référence passe, que le point de départ échoue et que chaque mutant échoue (lancé en CI).

## Déroulé d'un passage

1. Le lanceur copie `workspace/` dans un dossier temporaire neuf, hors du dépôt.
2. Il lance la commande de l'agent (fichier `bench/agents.json`, modèle dans `bench/agents.example.json`) dans ce dossier, avec la même consigne pour tous (`PROMPT` dans `bench/run.py`). Le banc est non interactif : l'agent doit écrire ses hypothèses dans son résumé au lieu de poser des questions.
3. À la fin (ou au délai maximal), il copie `src/` produit par l'agent dans un **autre** dossier temporaire, y ajoute les tests cachés, compile et exécute.
4. Réussite = compilation + tous les tests cachés passent (tout ou rien). On enregistre aussi les tests passés, la durée, les tokens, le coût et le nombre de tours si la commande les fournit (`claude -p --output-format json` les fournit ; leur format est à revérifier au premier vrai passage).
5. Le code produit et le résumé final sont gardés (`results/<modèle>/artifacts/`, `results/<modèle>/transcripts/`) pour la grille de ressenti.

## Règles de publication

- **3 passages par tâche** (`--repeat 3`) pour un rapport publié ; le rapport affiche « réussie k/3 ». Un seul passage ne sert qu'aux essais.
- Même consigne, même version du banc (commit et empreinte de chaque tâche dans les résultats) pour tous les modèles comparés. Si une tâche change, son empreinte change, et les rapports ne sont plus comparés sur cette tâche.
- La commande exacte (outil, version, options, effort de raisonnement) est publiée dans le rapport (`meta.json`).
- La grille de ressenti suit `RESSENTI.md` : une preuve par note, notateur nommé.
- Aucun chiffre sans fichier de résultat. Un rapport généré avec un faux agent porte le bandeau « EXEMPLE, pas un vrai modèle ».

## Limites connues (dites dans chaque rapport)

- **Peu de tâches** (5) : un écart d'une tâche entre deux modèles n'est pas significatif.
- **Isolation partielle** : l'agent tourne sur la machine, dans un dossier temporaire, sans les tests cachés ; mais un agent qui a accès à tout le disque pourrait chercher le dépôt. À faire : lancer chaque passage dans un conteneur sans le dépôt.
- **Contamination à terme** : les tâches et les tests sont publics dans ce dépôt, donc pourront finir dans des données d'entraînement. Parade prévue : ajouter des tâches neuves régulièrement et comparer les modèles sur les tâches créées après leur date de coupure.
- **Le harnais compte** : un modèle mesuré via Claude Code n'est pas mesuré « nu ». Le rapport dit quel outil a été utilisé.
- **Un seul notateur** pour le ressenti.
