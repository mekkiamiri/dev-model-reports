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

1. Le lanceur crée un dossier temporaire neuf, au nom aléatoire, hors du dépôt, et n'y copie que `workspace/`. Aucun dossier voisin ne vient du banc : ni autre tâche, ni autre passage, ni `results/`.
2. Il lance la commande de l'agent (fichier `bench/agents.json`, modèle dans `bench/agents.example.json`) dans ce dossier, avec la même consigne pour tous (`PROMPT` dans `bench/run.py`) et un **environnement épuré** : liste blanche (`PATH`, `HOME`, `LANG`…, plus les clés d'API demandées par `env_passthrough`), sans `OLDPWD` ni chemin du dépôt, `PWD` = dossier de travail. La commande tourne dans son propre groupe de processus, tué en entier au délai maximal.
3. Le banc est non interactif : l'agent doit écrire ses hypothèses dans son résumé au lieu de poser des questions.
4. À la fin, le lanceur refuse d'abord le code « suspect » (appel au harnais `Check`, référence aux tests cachés, réflexion sur des membres privés, lecture de l'entrée standard). Puis il copie `src/` dans un **autre** dossier temporaire, y ajoute les tests cachés, compile et exécute.
5. **Note infalsifiable** : le lanceur tire un jeton aléatoire par passage et le donne au harnais sur l'entrée standard, lue avant tout code de l'agent. Réussite seulement si la **dernière** ligne de sortie est `RESULT <jeton> passed=N total=N`, avec N égal au nombre de tests déclaré dans `task.json`, et si le processus sort avec le code 0. Un code qui imprime une fausse ligne `RESULT` puis s'arrête est noté en échec (test `test_cheating_agent_is_graded_as_failure`, faux agent `fake-cheat`).
6. On enregistre aussi les tests passés à chaque passage, la durée, les tokens, le coût, le nombre de tours et l'erreur éventuelle de l'agent si la commande les fournit (`claude -p --output-format json` : `total_cost_usd`, `usage`, `num_turns`, `is_error`, `subtype` ; `codex exec --json` : tokens des événements `turn.completed`, pas de coût). Le format réel est à revérifier au premier vrai passage.
7. Le code produit et le résumé final sont gardés (`results/<modèle>/artifacts/`, `results/<modèle>/transcripts/`) pour la grille de ressenti.

## Axe sécurité (tests d'intrusion)

Le code produit est aussi attaqué. Même principe que les tests cachés : une suite cachée, une note sans juge LLM, et la preuve que la suite attrape de vraies failles.

- **Attaques cachées** (`hidden-attacks/`) : chaque attaque tourne dans sa propre JVM (`-Xmx256m`, délai par attaque), avec son propre jeton. Blocage, `OutOfMemoryError`, `StackOverflowError` ou sortie interrompue = faille. Le code est attaqué même si les tests fonctionnels échouent, pourvu qu'il compile.
- **Exigée ou implicite** : une attaque `exigee` correspond à une règle écrite dans le PRD ou le contrat ; une attaque implicite teste le bon sens de développeur (attente : une exception documentée ou un résultat borné, jamais un blocage ni un crash de la JVM). Le rapport donne les deux scores séparément.
- **Mutants vulnérables** (`mutants-vuln/<famille>/`) : la référence avec une faille plausible. `--check` exige que la référence résiste, que chaque mutant vulnérable tombe sur une attaque de sa famille, et qu'il passe quand même les tests fonctionnels (sinon c'est un bug, pas une faille).
- **Référence vulnérable** : trois attaques implicites font tomber la référence elle-même (`reference_vulnerable` dans `task.json`). Elles restent dans le banc, non bloquantes pour `--check`, et la référence n'est pas modifiée (son empreinte publiée ne change pas).
- **Fuzzing différentiel** : entrées pseudo-aléatoires à graine fixe, comparées à la référence compilée sous un autre paquet. C'est une faille seulement si l'agent **plante** (exception standard de Java non annoncée : `NullPointerException`, dépassement d'index…), bloque ou fait tomber la JVM. Une saisie plus tolérante ou une valeur différente est un écart de conformité (ligne `ECART` sur la sortie d'erreur), pas une faille : les tests fonctionnels s'en chargent. Exception : `refacto-tarifs`, où tout écart avec l'original est un défaut (`memeIssueStricte`).
- **Rejouer** : `python3 bench/regrade.py --model <m>` rejoue attaques et analyse statique sur le code gardé, sans rappeler le modèle (refusé si l'empreinte de la tâche a changé).
- **Analyse statique** (`bench/security.py`) : semgrep avec les seules règles locales `bench/semgrep/java.yml` (tirées de Project CodeGuard, `--metrics=off`, sans registre en ligne) et gitleaks. SARIF dans `results/<modèle>/security/`, compte par gravité, sans effet sur la note.

| Tâche | Attaques (* = implicite, † = la référence y est vulnérable) |
|---|---|
| `bug-pagination` | taille nulle*, taille négative extrême, taille max, page hors bornes, fuzz |
| `prd-config` | expansion exponentielle, expansion géante*†, cycle long, chaîne longue sans cycle, clé géante, valeur géante, fuzz |
| `prd-facture` | montants géants, exposant géant*†, lignes nombreuses, caractères de contrôle, Unicode, message injecté*†, fuzz |
| `prd-horaires` | règles nombreuses, heures hors bornes, syntaxe mal formée, entrée géante, instant extrême*, fuzz |
| `refacto-tarifs` | quantité max, prix géant, entrées nulles, fuzz |
| `secu-fichiers` | traversée `../`, chemin absolu, encodage, lien symbolique, lien vers dossier, nom réservé, nom long, dossier*, fuzz |
| `secu-jeton` | algorithme « none », signature tronquée, expiration, format d'expiration, sujet injecté, comparaison constante, clé courte, jeton géant, durée géante*, fuzz |

Le test d'intrusion d'une appli qui tourne (ZAP contre un serveur HTTP) n'est pas fait : les tâches sont des bibliothèques.

## Reproductibilité

- **Sans les réglages de la machine.** Pour Claude Code, la configuration d'exemple utilise des options vérifiées dans `claude --help` (version 2.1.325) : `--safe-mode` (CLAUDE.md, skills, plugins installés, hooks, serveurs MCP, commandes et agents personnalisés désactivés), `--strict-mcp-config` (aucun serveur MCP hors `--mcp-config`), `--disable-slash-commands`, `--no-session-persistence`, et `--restricted` avec `--tools` (outils de fichiers confinés au dossier de travail). Commandes autorisées : `javac -d out …` et `java -cp out …` seulement (ni `find`, ni `java` libre). Pour Codex CLI : `--ignore-user-config`, `--ignore-rules`, `--ephemeral`, `--sandbox workspace-write`.
- Chaque résultat note la **version de l'outil** (`version_cmd`, par exemple `claude --version`) et le **commit du banc** (`git describe --dirty` : un suffixe `-dirty` signale des modifications non commitées ; un rapport publié ne doit pas en avoir).
- **Empreinte** de chaque tâche : SHA-256 de `workspace/`, `hidden/`, `reference/` et du harnais `Check.java` (sans `task.json` ni fichiers parasites), affichée dans le rapport.

## Exécution recommandée pour un rapport public

L'isolation du lanceur réduit les portes, elle ne remplace pas un vrai bac à sable : l'agent tourne avec les droits de l'utilisateur, et un `java -cp out …` exécute le code qu'il a écrit. Pour un rapport public :

- lancer chaque passage dans un **conteneur** (ou sous un **utilisateur dédié**) qui ne voit pas le dépôt : seul le dossier de travail est monté, les tests cachés restent sur l'hôte et ne servent qu'à la notation ;
- par exemple : `docker run --rm -v "$WORK:/work" -w /work -e ANTHROPIC_API_KEY <image avec JDK et CLI> claude -p …`, la notation restant faite par `bench/run.py` sur l'hôte (intégration à écrire : v1) ;
- utiliser le **lot de tâches privé**.

## Lot de tâches privé

Les tâches de `tasks/` sont publiques : leurs tests cachés seront connus, et pourront finir dans des données d'entraînement. Les rapports publics s'appuient donc aussi sur un lot privé :

- dossier `tasks-private/`, **ignoré par git**, même format que `tasks/` (task.json, workspace, hidden, reference, mutants) ;
- `python3 bench/run.py --check --tasks-dir tasks-private`, puis `--agent <modèle> --tasks-dir tasks-private` ;
- le rapport indique le lot utilisé et **ne publie pas le détail des échecs** d'un lot privé (les noms des tests cachés ne doivent pas fuiter) ;
- le lot public sert à montrer la méthode et à vérifier le lanceur ; le lot privé sert à mesurer.

## Règles de publication

- **3 passages par tâche** (`--repeat 3`) pour un rapport publié ; le rapport affiche « réussie k/3 ». Un seul passage ne sert qu'aux essais.
- Même consigne, même version du banc (commit et empreinte de chaque tâche dans les résultats) pour tous les modèles comparés. Si une tâche change, son empreinte change, et les rapports ne sont plus comparés sur cette tâche.
- La commande exacte (outil, version, options, effort de raisonnement) est publiée dans le rapport (`meta.json`).
- La grille de ressenti suit `RESSENTI.md` : une preuve par note, notateur nommé.
- Aucun chiffre sans fichier de résultat. Un rapport généré avec un faux agent porte le bandeau « EXEMPLE, pas un vrai modèle ».

## Limites connues (dites dans chaque rapport)

- **Peu de tâches** (5) : un écart d'une tâche entre deux modèles n'est pas significatif.
- **Isolation partielle sans conteneur** : voir « Exécution recommandée ». L'intégration conteneur du lanceur est à faire.
- **Contamination à terme** du lot public : parade, le lot privé, et des tâches neuves ajoutées régulièrement.
- **Détection de triche par motifs** : la liste de motifs suspects attrape les tricheries évidentes, pas toutes ; le jeton et le nombre de tests attendu ferment la tricherie la plus simple (fausse ligne RESULT).
- **Le harnais compte** : un modèle mesuré via Claude Code n'est pas mesuré « nu ». Le rapport dit quel outil a été utilisé.
- **Un seul notateur** pour le ressenti.
- **Les attaques ne couvrent que ce qu'on a pensé à attaquer** : une note de sécurité parfaite ne prouve pas l'absence de faille. Les règles semgrep sont peu nombreuses (9) et reconnaissent surtout des noms parlants.
