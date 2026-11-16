# Tâche

Refactore `src/com/example/pricing/PriceService.java` selon `REFACTOR.md`.

- Java 17, bibliothèque standard uniquement, aucun outil de build.
- Le comportement ne doit pas changer, à aucun centime près, y compris les erreurs.
- Des tests existent dans `test/`. Pour les lancer :
  `javac -d out $(find src test -name '*.java') && java -cp out VisibleTests`
- Des tests d'acceptation que tu ne vois pas seront lancés après ton travail (sur `src/` uniquement).
- À la fin, résume ce que tu as changé, ce que tu as gardé tel quel et pourquoi.
