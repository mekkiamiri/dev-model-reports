# PRD : horaires d'ouverture

Une appli de commerce affiche si une boutique est ouverte et quand elle rouvre. Les horaires sont saisis par le commerçant sous forme de texte.

## Syntaxe

Une suite de règles séparées par `;`. Les espaces autour des éléments sont ignorés.

- **Règle de semaine** : `<jours> <plages>`, par exemple `Mo-Fr 09:00-12:00,14:00-18:00`.
  - Jours : `Mo Tu We Th Fr Sa Su`. Une liste avec `,` (`Mo,We`) ; un intervalle avec `-` (`Mo-Fr`). Un intervalle peut passer par dimanche : `Sa-Mo` désigne samedi, dimanche et lundi.
  - Si un même jour apparaît dans plusieurs règles de semaine, **la dernière règle l'emporte** pour ce jour (elle remplace les plages précédentes, elle ne s'y ajoute pas).
- **Règle de date** : `AAAA-MM-JJ off` (fermé toute la journée) ou `AAAA-MM-JJ <plages>`. Une règle de date remplace les plages de semaine de ce jour-là.
- **Plages** : `HH:MM-HH:MM`, plusieurs séparées par `,`. `24:00` est accepté comme heure de fin. Une plage est fermée à gauche, ouverte à droite : `09:00-18:00` est ouvert à 09:00 et fermé à 18:00.
- **Plage de nuit** : si l'heure de fin est inférieure ou égale à l'heure de début (`22:00-02:00`), la plage déborde sur le lendemain jusqu'à l'heure de fin. Ce débordement appartient au jour où la plage commence : il s'applique même si le lendemain est un jour fermé ou a une règle de date, mais il disparaît si le jour de départ est lui-même fermé par une règle de date.

Toute syntaxe invalide (jour inconnu, heure impossible comme `25:00` ou `10:60`, règle vide, texte vide) lève `IllegalArgumentException`.

## Fonctions

- `isOpen(t)` : la boutique est-elle ouverte à l'instant `t` ?
- `nextOpening(t)` : le premier instant `≥ t` où la boutique est ouverte (donc `t` lui-même si elle est ouverte à `t`). On cherche sur 366 jours au plus ; au-delà, résultat vide.
