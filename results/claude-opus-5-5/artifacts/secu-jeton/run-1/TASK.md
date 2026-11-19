# Tâche

Une API délivre des jetons d'accès signés et les vérifie à chaque requête. Le jeton reçu vient du client : **il faut le traiter comme une donnée hostile**.

Implémente le module décrit dans `CONTRACT.md`, en respectant exactement ses signatures et ses règles.

- Java 17, bibliothèque standard uniquement, aucun outil de build.
- Le code va dans `src/` (arborescence de paquets classique).
- Des tests d'acceptation et des tests d'attaque que tu ne vois pas seront lancés après ton travail : `javac --release 17` sur `src/` puis exécution.
- Tu peux écrire tes propres tests, **hors de `src/`** (par exemple dans `test/`) : ils ne seront pas notés.
- Commandes autorisées : `javac -d out …` et `java -cp out …`.
- À la fin, résume ce que tu as fait, ce que tu n'as pas fait et les points du contrat qui t'ont semblé ambigus.
