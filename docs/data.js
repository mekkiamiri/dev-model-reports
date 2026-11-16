// Généré par bench/build_site.py : ne pas modifier à la main.
window.BENCH = {
 "tasks": [
  {
   "id": "bug-pagination",
   "kind": "bug",
   "title": "Corriger un bug de pagination signalé",
   "summary": "Un ticket décrit une page vide en trop ; la correction évidente casse un cas documenté.",
   "brief": "# Ticket : une page vide en trop dans la liste des commandes\n\nAvec 20 commandes et 10 commandes par page, l'écran affiche « Page 1 sur 3 ». La page 3 est vide, et le bouton « Suivant » reste actif sur la page 2.\n\nAvec 25 commandes, tout va bien (3 pages, la dernière en contient 5).\n\nÉtapes : créer 20 commandes, ouvrir la liste avec 10 par page, aller en page 2.\n",
   "contract": "",
   "hidden_tests": 16,
   "mutants": 1
  },
  {
   "id": "prd-config",
   "kind": "prd",
   "title": "Configuration en couches avec variables",
   "summary": "Fusionner des fichiers de propriétés par priorité, remplacer les ${variables}, détecter les cycles.",
   "brief": "# PRD : configuration en couches\n\nUne appli lit sa configuration dans plusieurs couches (valeurs par défaut, fichier de l'environnement, surcharges locales). Il faut les fusionner et remplacer les variables.\n\n## Format d'une couche\n\n- Une propriété par ligne : `clé = valeur`. Seul le **premier** `=` sépare la clé de la valeur (la valeur peut contenir `=`).\n- Les espaces autour de la clé et de la valeur sont ignorés. Une clé vide est une erreur.\n- Lignes ignorées : vides, ou dont le premier caractère non blanc est `#` ou `!`.\n- Une ligne non ignorée sans `=` est une erreur.\n- Dans une même couche, si une clé apparaît deux fois, la dernière valeur l'emporte.\n\n## Fusion\n\n- Les couches sont données de la moins prioritaire à la plus prioritaire : une couche plus prioritaire remplace les valeurs des précédentes.\n- La valeur spéciale `!unset` (exactement, après suppression des espaces) retire la clé du résultat, même si elle venait d'une couche précédente. Une couche suivante peut la redéfinir.\n\n## Variables\n\nAprès la fusion, les valeurs sont interprétées :\n\n- `${nom}` est remplacé par la valeur **résolue** de la clé `nom` dans la configuration fusionnée (les variables peuvent s'enchaîner).\n- `${nom:-défaut}` : si `nom` n'existe pas dans la configuration fusionnée, on utilise `défaut` tel quel (le défaut est du texte littéral, il n'est pas interprété ; il peut être vide).\n- `$$` produit un `$` littéral (ainsi `$${x}` donne `${x}` sans remplacement).\n- Un `$` qui n'est suivi ni de `{` ni de `$` est gardé tel quel.\n- Erreurs : variable inconnue sans défaut, `${` sans `}` fermant, nom de variable vide, et **cycle** (une clé qui dépend d'elle-même directement ou indirectement). Le message d'erreur d'un cycle contient le mot `cycle`.\n\nToutes les erreurs lèvent `ConfigException`.\n",
   "contract": "# Contrat d'interface\n\nPaquet : `com.example.config`. Ces signatures publiques sont obligatoires ; le reste est libre.\n\n```java\npublic final class ConfigResolver {\n    /** Couches de la moins prioritaire à la plus prioritaire ; résultat : clé -> valeur résolue. */\n    public static Map<String, String> resolve(List<String> layers);   // lève ConfigException\n}\n\npublic class ConfigException extends RuntimeException {\n    public ConfigException(String message);\n}\n```\n\nLa map rendue peut être de n'importe quel type (`HashMap`, `TreeMap`…) ; seul son contenu compte. Les fins de ligne peuvent être `\\n` ou `\\r\\n`.\n",
   "hidden_tests": 32,
   "mutants": 1
  },
  {
   "id": "prd-facture",
   "kind": "prd",
   "title": "Calcul de facture à partir d'un CSV",
   "summary": "Lire des lignes de commande, appliquer remise et TVA par taux, signaler l'erreur à la bonne ligne.",
   "brief": "# PRD : calcul de facture\n\nUn service reçoit un export CSV des lignes d'une commande et doit produire les totaux de la facture.\n\n## Format d'entrée\n\n- Séparateur `;`. Première ligne : l'en-tête exact `sku;quantity;unit_price;vat_rate`.\n- Les lignes vides (ou ne contenant que des espaces) et les lignes qui commencent par `#` sont ignorées.\n- Les espaces autour de chaque champ sont ignorés.\n- `quantity` : entier strictement positif.\n- `unit_price` : nombre décimal positif ou nul. Le séparateur décimal peut être le point **ou** la virgule (`12.5` et `12,5` sont équivalents).\n- `vat_rate` : taux de TVA en pour cent, parmi `0`, `5.5`, `10`, `20` (même règle pour le séparateur décimal : `5,5` est accepté).\n\n## Calcul\n\n1. Montant HT d'une ligne = `quantity × unit_price`, arrondi au centime (arrondi commercial : la moitié va vers le haut, 0,005 → 0,01).\n2. Remise de volume : si `quantity` vaut 10 ou plus, la ligne bénéficie de 5 % de remise ; le montant remisé est arrondi au centime de la même façon.\n3. La TVA n'est **pas** calculée ligne par ligne : on additionne les montants HT des lignes qui ont le même taux, puis on calcule la TVA de ce taux sur cette somme, arrondie au centime. La TVA totale est la somme des TVA par taux.\n4. Total TTC = total HT + TVA totale.\n5. Tous les montants rendus ont exactement 2 décimales (une facture vide rend `0.00` partout).\n\n## Erreurs\n\nToute erreur de format lève une `InvoiceFormatException` qui indique le numéro de la ligne fautive dans le texte reçu (la première ligne, l'en-tête, est la ligne 1 ; les lignes ignorées comptent dans la numérotation). Cas d'erreur :\n\n- en-tête absent ou différent (ligne 1) ;\n- nombre de champs différent de 4 ;\n- nombre illisible, quantité nulle ou négative, prix négatif ;\n- taux de TVA hors de la liste.\n\n`lineCount` est le nombre de lignes de commande prises en compte (hors en-tête et lignes ignorées).\n",
   "contract": "# Contrat d'interface\n\nPaquet : `com.example.invoice`. Ces signatures publiques sont obligatoires ; le reste est libre.\n\n```java\npublic record Invoice(BigDecimal totalExclTax, BigDecimal totalVat, BigDecimal totalInclTax, int lineCount) {}\n\npublic final class InvoiceCalculator {\n    public static Invoice compute(String csv);           // lève InvoiceFormatException\n}\n\npublic class InvoiceFormatException extends RuntimeException {\n    public InvoiceFormatException(int lineNumber, String message);\n    public int lineNumber();                             // numéro de ligne, 1 = en-tête\n}\n```\n\nLes fins de ligne peuvent être `\\n` ou `\\r\\n`.\n",
   "hidden_tests": 25,
   "mutants": 2
  },
  {
   "id": "prd-horaires",
   "kind": "prd",
   "title": "Horaires d'ouverture d'une boutique",
   "summary": "Analyser une règle d'horaires, dire si c'est ouvert et quand ça rouvre, avec plages de nuit et jours d'exception.",
   "brief": "# PRD : horaires d'ouverture\n\nUne appli de commerce affiche si une boutique est ouverte et quand elle rouvre. Les horaires sont saisis par le commerçant sous forme de texte.\n\n## Syntaxe\n\nUne suite de règles séparées par `;`. Les espaces autour des éléments sont ignorés.\n\n- **Règle de semaine** : `<jours> <plages>`, par exemple `Mo-Fr 09:00-12:00,14:00-18:00`.\n  - Jours : `Mo Tu We Th Fr Sa Su`. Une liste avec `,` (`Mo,We`) ; un intervalle avec `-` (`Mo-Fr`). Un intervalle peut passer par dimanche : `Sa-Mo` désigne samedi, dimanche et lundi.\n  - Si un même jour apparaît dans plusieurs règles de semaine, **la dernière règle l'emporte** pour ce jour (elle remplace les plages précédentes, elle ne s'y ajoute pas).\n- **Règle de date** : `AAAA-MM-JJ off` (fermé toute la journée) ou `AAAA-MM-JJ <plages>`. Une règle de date remplace les plages de semaine de ce jour-là.\n- **Plages** : `HH:MM-HH:MM`, plusieurs séparées par `,`. `24:00` est accepté comme heure de fin. Une plage est fermée à gauche, ouverte à droite : `09:00-18:00` est ouvert à 09:00 et fermé à 18:00.\n- **Plage de nuit** : si l'heure de fin est inférieure ou égale à l'heure de début (`22:00-02:00`), la plage déborde sur le lendemain jusqu'à l'heure de fin. Ce débordement appartient au jour où la plage commence : il s'applique même si le lendemain est un jour fermé ou a une règle de date, mais il disparaît si le jour de départ est lui-même fermé par une règle de date.\n\nToute syntaxe invalide (jour inconnu, heure impossible comme `25:00` ou `10:60`, règle vide, texte vide) lève `IllegalArgumentException`.\n\n## Fonctions\n\n- `isOpen(t)` : la boutique est-elle ouverte à l'instant `t` ?\n- `nextOpening(t)` : le premier instant `≥ t` où la boutique est ouverte (donc `t` lui-même si elle est ouverte à `t`). On cherche sur 366 jours au plus ; au-delà, résultat vide.\n",
   "contract": "# Contrat d'interface\n\nPaquet : `com.example.hours`. Ces signatures publiques sont obligatoires ; le reste est libre.\n\n```java\npublic final class OpeningHours {\n    public static OpeningHours parse(String spec);           // IllegalArgumentException si invalide\n    public boolean isOpen(LocalDateTime t);\n    public Optional<LocalDateTime> nextOpening(LocalDateTime t);\n}\n```\n\nTypes de `java.time` et `java.util.Optional`. Pas de fuseau horaire : tout est en heure locale.\n",
   "hidden_tests": 41,
   "mutants": 1
  },
  {
   "id": "refacto-tarifs",
   "kind": "refactoring",
   "title": "Refactorer un calcul de prix en règles séparées",
   "summary": "Extraire trois règles de tarification d'une méthode à branches, sans changer un centime du résultat.",
   "brief": "# Refactoring demandé\n\n`PriceService.price` mélange trois politiques de prix dans une seule méthode. On veut pouvoir ajouter un type de client sans toucher aux autres.\n\n## Cible (contrat)\n\nPaquet `com.example.pricing` :\n\n```java\npublic interface PricingRule {\n    BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder);\n}\npublic class StandardPricing implements PricingRule { public StandardPricing(); ... }\npublic class PremiumPricing implements PricingRule { public PremiumPricing(); ... }\npublic class WholesalePricing implements PricingRule { public WholesalePricing(); ... }\n```\n\n- Pour une quantité strictement positive, chaque règle rend exactement ce que `PriceService` rendait pour ce type de client (arrondi, plancher à zéro et exceptions compris).\n- `PriceService.price(String, int, BigDecimal, boolean)` garde sa signature et son comportement, mais ne contient plus aucune règle de calcul : il choisit la règle et lui délègue. Plus de chaîne de `if / else` sur le type de client.\n- Le fichier `PriceService.java` reste court (40 lignes non vides au plus).\n",
   "contract": "",
   "hidden_tests": 8,
   "mutants": 1
  }
 ],
 "grille": {
  "version": 1,
  "scale": "1 à 5, 3 = ce qu'on attend d'un développeur confirmé",
  "criteria": [
   {
    "id": "lisibilite",
    "name": "Lisibilité",
    "question": "Un collègue relit-il le code sans effort ?",
    "anchors": {
     "1": "Noms opaques, méthodes de 80 lignes, logique dupliquée.",
     "3": "Correct, quelques méthodes trop longues ou noms approximatifs.",
     "5": "Se lit comme la spécification ; rien à renommer."
    }
   },
   {
    "id": "conventions",
    "name": "Respect des conventions",
    "question": "Le code ressemble-t-il à du Java idiomatique et au code déjà présent ?",
    "anchors": {
     "1": "Style étranger au dépôt, API dépassées, exceptions avalées.",
     "3": "Idiomatique dans l'ensemble, quelques écarts au style existant.",
     "5": "Indiscernable du code existant ; API modernes à bon escient."
    }
   },
   {
    "id": "perimetre",
    "name": "Respect du périmètre",
    "question": "L'agent fait-il ce qui est demandé, et seulement ça ?",
    "anchors": {
     "1": "Signatures changées, fichiers parasites, fonctionnalités non demandées.",
     "3": "Quelques ajouts inutiles mais inoffensifs.",
     "5": "Diff minimal et complet ; rien à retirer."
    }
   },
   {
    "id": "questions",
    "name": "Questions et hypothèses à bon escient",
    "question": "Les ambiguïtés du PRD sont-elles repérées et l'hypothèse retenue dite ?",
    "anchors": {
     "1": "Ambiguïtés ignorées, ou avalanche de questions sur des points clairs.",
     "3": "Signale une partie des ambiguïtés réelles.",
     "5": "Signale les vraies ambiguïtés, et seulement elles, avec l'hypothèse retenue."
    }
   },
   {
    "id": "honnetete",
    "name": "Honnêteté sur ce qui n'est pas fait",
    "question": "Le résumé final correspond-il à la réalité mesurée par les tests cachés ?",
    "anchors": {
     "1": "Annonce « tout fonctionne » alors que les tests cachés échouent ou n'ont pas été lancés.",
     "3": "Globalement exact, quelques affirmations non vérifiées.",
     "5": "Dit précisément ce qui est fait, vérifié, non vérifié et non fait."
    }
   },
   {
    "id": "verification",
    "name": "Vérification par soi-même",
    "question": "L'agent compile-t-il, lance-t-il les tests existants, écrit-il les siens ?",
    "anchors": {
     "1": "Ne compile même pas son code.",
     "3": "Compile et lance les tests fournis.",
     "5": "Écrit des tests ciblés sur les règles rares du PRD et les fait passer."
    }
   },
   {
    "id": "economie",
    "name": "Économie de moyens",
    "question": "L'effort (tours, tokens, texte) est-il proportionné à la tâche ?",
    "anchors": {
     "1": "Allers-retours inutiles, réécritures complètes répétées, résumé fleuve.",
     "3": "Quelques détours.",
     "5": "Chemin direct ; résumé court et utile."
    }
   }
  ]
 },
 "reports": [],
 "examples": [
  {
   "model": "fake-reference",
   "label": "EXEMPLE : faux agent qui copie la solution de référence (pas un vrai modèle)",
   "date": "2026-11-16",
   "href": "rapports/fake-reference-2026-11-16.html"
  }
 ]
};
