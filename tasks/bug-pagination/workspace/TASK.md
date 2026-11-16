# Tâche

Un utilisateur a ouvert le ticket `ISSUE.md`. Corrige le bug dans `src/`.

- Java 17, bibliothèque standard uniquement, aucun outil de build.
- Ne change pas les signatures publiques de `Paginator`.
- Des tests existent dans `test/`. Pour les lancer :
  `javac -d out src/com/example/paging/*.java test/*.java` puis `java -cp out VisibleTests`
- Des tests d'acceptation que tu ne vois pas seront lancés après ton travail (sur `src/` uniquement) ; ne mets pas de tests dans `src/`.
- À la fin, résume la cause du bug, ta correction, et ce que tu n'as pas vérifié.
