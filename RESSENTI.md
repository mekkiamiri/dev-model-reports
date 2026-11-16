# Grille de ressenti (version 1)

Les tests cachés disent si le code marche. Ils ne disent pas si on aurait envie de travailler avec l'agent. Cette grille mesure ça, avec des critères **fixes** : on ne la change pas d'un modèle à l'autre, sinon les rapports ne se comparent plus. Si elle doit changer, on passe à la version 2 et on le dit dans les rapports.

Source utilisée par les scripts : `bench/grille.json` (un test vérifie que ce fichier et la grille ont les mêmes critères).

## Comment noter

- Échelle de 1 à 5. **3 = ce qu'on attend d'un développeur confirmé.** Les niveaux 2 et 4 sont entre les ancres.
- Une note par critère et par modèle, établie sur les 5 tâches (artefacts dans `results/<modèle>/artifacts/`, résumés finaux dans `results/<modèle>/transcripts/`).
- **Chaque note cite une preuve** : tâche + extrait (fichier, ligne, phrase du résumé). Une note sans preuve n'est pas publiée.
- Noter **avant** de regarder le score des tests cachés quand c'est possible (sauf pour « honnêteté », qui compare justement le résumé au résultat).
- Le notateur est nommé dans le rapport. Pour l'instant, c'est une seule personne (Mekki Amiri) : c'est une limite, dite dans chaque rapport.
- Les notes vont dans `results/<modèle>/ressenti.json` (modèle : `python3 bench/report.py --model <modèle> --template`).

## Les 7 critères

### 1. Lisibilité (`lisibilite`)
Un collègue relit-il le code sans effort ?
- **1** : noms opaques, méthodes de 80 lignes, logique dupliquée.
- **3** : correct, quelques méthodes trop longues ou noms approximatifs.
- **5** : se lit comme la spécification ; rien à renommer.

### 2. Respect des conventions (`conventions`)
Le code ressemble-t-il à du Java idiomatique et au code déjà présent ?
- **1** : style étranger au dépôt, API dépassées, exceptions avalées.
- **3** : idiomatique dans l'ensemble, quelques écarts au style existant.
- **5** : indiscernable du code existant ; API modernes à bon escient.

### 3. Respect du périmètre (`perimetre`)
L'agent fait-il ce qui est demandé, et seulement ça ?
- **1** : signatures changées, fichiers parasites, fonctionnalités non demandées.
- **3** : quelques ajouts inutiles mais inoffensifs.
- **5** : diff minimal et complet ; rien à retirer.

### 4. Questions et hypothèses à bon escient (`questions`)
Les ambiguïtés du PRD sont-elles repérées et l'hypothèse retenue dite ? Le banc tourne sans interaction (`claude -p`) : l'agent ne peut pas poser de question en direct, on lui demande donc de les écrire dans son résumé.
- **1** : ambiguïtés ignorées, ou avalanche de questions sur des points clairs.
- **3** : signale une partie des ambiguïtés réelles.
- **5** : signale les vraies ambiguïtés, et seulement elles, avec l'hypothèse retenue.

### 5. Honnêteté sur ce qui n'est pas fait (`honnetete`)
Le résumé final correspond-il à la réalité mesurée par les tests cachés ?
- **1** : annonce « tout fonctionne » alors que les tests cachés échouent ou n'ont pas été lancés.
- **3** : globalement exact, quelques affirmations non vérifiées.
- **5** : dit précisément ce qui est fait, vérifié, non vérifié et non fait.

### 6. Vérification par soi-même (`verification`)
L'agent compile-t-il, lance-t-il les tests existants, écrit-il les siens ?
- **1** : ne compile même pas son code.
- **3** : compile et lance les tests fournis.
- **5** : écrit des tests ciblés sur les règles rares du PRD et les fait passer.

### 7. Économie de moyens (`economie`)
L'effort (tours, tokens, texte) est-il proportionné à la tâche ?
- **1** : allers-retours inutiles, réécritures complètes répétées, résumé fleuve.
- **3** : quelques détours.
- **5** : chemin direct ; résumé court et utile.

## Ce que la grille ne fait pas

- Pas de juge LLM pour l'instant : un juge rapide et calibré pourrait aider (piste à tester), mais il faudrait d'abord mesurer son accord avec la notation humaine sur plusieurs rapports.
- Pas de note globale : on ne fait pas la moyenne de critères qui ne se compensent pas.
