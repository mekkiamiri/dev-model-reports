# PRD : calcul de facture

Un service reçoit un export CSV des lignes d'une commande et doit produire les totaux de la facture.

## Format d'entrée

- Séparateur `;`. Première ligne : l'en-tête exact `sku;quantity;unit_price;vat_rate`.
- Les lignes vides (ou ne contenant que des espaces) et les lignes qui commencent par `#` sont ignorées.
- Les espaces autour de chaque champ sont ignorés.
- `quantity` : entier strictement positif.
- `unit_price` : nombre décimal positif ou nul. Le séparateur décimal peut être le point **ou** la virgule (`12.5` et `12,5` sont équivalents).
- `vat_rate` : taux de TVA en pour cent, parmi `0`, `5.5`, `10`, `20` (même règle pour le séparateur décimal : `5,5` est accepté).

## Calcul

1. Montant HT d'une ligne = `quantity × unit_price`, arrondi au centime (arrondi commercial : la moitié va vers le haut, 0,005 → 0,01).
2. Remise de volume : si `quantity` vaut 10 ou plus, la ligne bénéficie de 5 % de remise ; le montant remisé est arrondi au centime de la même façon.
3. La TVA n'est **pas** calculée ligne par ligne : on additionne les montants HT des lignes qui ont le même taux, puis on calcule la TVA de ce taux sur cette somme, arrondie au centime. La TVA totale est la somme des TVA par taux.
4. Total TTC = total HT + TVA totale.
5. Tous les montants rendus ont exactement 2 décimales (une facture vide rend `0.00` partout).

## Erreurs

Toute erreur de format lève une `InvoiceFormatException` qui indique le numéro de la ligne fautive dans le texte reçu (la première ligne, l'en-tête, est la ligne 1 ; les lignes ignorées comptent dans la numérotation). Cas d'erreur :

- en-tête absent ou différent (ligne 1) ;
- nombre de champs différent de 4 ;
- nombre illisible, quantité nulle ou négative, prix négatif ;
- taux de TVA hors de la liste.

`lineCount` est le nombre de lignes de commande prises en compte (hors en-tête et lignes ignorées).
