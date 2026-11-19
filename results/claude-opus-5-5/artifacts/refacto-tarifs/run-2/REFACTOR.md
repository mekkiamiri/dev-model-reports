# Refactoring demandé

`PriceService.price` mélange trois politiques de prix dans une seule méthode. On veut pouvoir ajouter un type de client sans toucher aux autres.

## Cible (contrat)

Paquet `com.example.pricing` :

```java
public interface PricingRule {
    BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder);
}
public class StandardPricing implements PricingRule { public StandardPricing(); ... }
public class PremiumPricing implements PricingRule { public PremiumPricing(); ... }
public class WholesalePricing implements PricingRule { public WholesalePricing(); ... }
```

- Pour une quantité strictement positive, chaque règle rend exactement ce que `PriceService` rendait pour ce type de client (arrondi, plancher à zéro et exceptions compris).
- `PriceService.price(String, int, BigDecimal, boolean)` garde sa signature et son comportement, mais ne contient plus aucune règle de calcul : il choisit la règle et lui délègue. Plus de chaîne de `if / else` sur le type de client.
- Le fichier `PriceService.java` reste court (40 lignes non vides au plus).
