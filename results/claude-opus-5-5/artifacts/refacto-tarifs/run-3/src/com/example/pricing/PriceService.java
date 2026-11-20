package com.example.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Calcule le prix d'une commande en déléguant à la règle du type de client. */
public class PriceService {

    private final Map<String, PricingRule> rules;

    public PriceService() {
        this(Map.of(
                "STANDARD", new StandardPricing(),
                "PREMIUM", new PremiumPricing(),
                "WHOLESALE", new WholesalePricing()));
    }

    /** Permet d'ajouter ou de remplacer des types de client sans modifier cette classe. */
    public PriceService(Map<String, PricingRule> rules) {
        this.rules = Map.copyOf(rules);
    }

    public BigDecimal price(String customerType, int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        Objects.requireNonNull(customerType);
        PricingRule rule = rules.get(customerType);
        if (rule == null) {
            throw new IllegalArgumentException("type de client inconnu : " + customerType);
        }
        return rule.price(quantity, unitPrice, firstOrder);
    }
}
