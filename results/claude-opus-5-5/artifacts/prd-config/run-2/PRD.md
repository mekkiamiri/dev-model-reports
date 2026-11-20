# PRD : configuration en couches

Une appli lit sa configuration dans plusieurs couches (valeurs par défaut, fichier de l'environnement, surcharges locales). Il faut les fusionner et remplacer les variables.

## Format d'une couche

- Une propriété par ligne : `clé = valeur`. Seul le **premier** `=` sépare la clé de la valeur (la valeur peut contenir `=`).
- Les espaces autour de la clé et de la valeur sont ignorés. Une clé vide est une erreur.
- Lignes ignorées : vides, ou dont le premier caractère non blanc est `#` ou `!`.
- Une ligne non ignorée sans `=` est une erreur.
- Dans une même couche, si une clé apparaît deux fois, la dernière valeur l'emporte.

## Fusion

- Les couches sont données de la moins prioritaire à la plus prioritaire : une couche plus prioritaire remplace les valeurs des précédentes.
- La valeur spéciale `!unset` (exactement, après suppression des espaces) retire la clé du résultat, même si elle venait d'une couche précédente. Une couche suivante peut la redéfinir.

## Variables

Après la fusion, les valeurs sont interprétées :

- `${nom}` est remplacé par la valeur **résolue** de la clé `nom` dans la configuration fusionnée (les variables peuvent s'enchaîner).
- `${nom:-défaut}` : si `nom` n'existe pas dans la configuration fusionnée, on utilise `défaut` tel quel (le défaut est du texte littéral, il n'est pas interprété ; il peut être vide).
- `$$` produit un `$` littéral (ainsi `$${x}` donne `${x}` sans remplacement).
- Un `$` qui n'est suivi ni de `{` ni de `$` est gardé tel quel.
- Erreurs : variable inconnue sans défaut, `${` sans `}` fermant, nom de variable vide, et **cycle** (une clé qui dépend d'elle-même directement ou indirectement). Le message d'erreur d'un cycle contient le mot `cycle`.

Toutes les erreurs lèvent `ConfigException`.
