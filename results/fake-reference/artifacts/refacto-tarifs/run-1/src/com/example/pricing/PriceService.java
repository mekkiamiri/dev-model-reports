package com.example.pricing;

import java.math.BigDecimal;
import java.util.Map;

/** Calcule le prix d'une commande selon le type de client. */
public class PriceService {
    private static final Map<String, PricingRule> RULES = Map.of(
            "STANDARD", new StandardPricing(),
            "PREMIUM", new PremiumPricing(),
            "WHOLESALE", new WholesalePricing());

    public BigDecimal price(String customerType, int quantity, BigDecimal unitPrice, boolean firstOrder) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantité invalide");
        }
        PricingRule rule = RULES.get(customerType);
        if (rule == null) {
            throw new IllegalArgumentException("type de client inconnu : " + customerType);
        }
        return rule.price(quantity, unitPrice, firstOrder);
    }
}
