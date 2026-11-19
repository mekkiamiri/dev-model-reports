package com.example.pricing;

import java.math.BigDecimal;

/** Calcule le prix d'une commande selon le type de client, en déléguant à la règle adaptée. */
public class PriceService {

    public BigDecimal price(String customerType, int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        return ruleFor(customerType).price(quantity, unitPrice, firstOrder);
    }

    /** Un type de client = une règle. Un type null lève NullPointerException, comme avant. */
    private static PricingRule ruleFor(String customerType) {
        return switch (customerType) {
            case "STANDARD" -> new StandardPricing();
            case "PREMIUM" -> new PremiumPricing();
            case "WHOLESALE" -> new WholesalePricing();
            default -> throw new IllegalArgumentException("type de client inconnu : " + customerType);
        };
    }
}
