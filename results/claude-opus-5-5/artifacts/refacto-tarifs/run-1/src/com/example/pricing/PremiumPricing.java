package com.example.pricing;

import java.math.BigDecimal;

/** Client premium : -10 %, puis -5 % supplémentaires dès 50 unités. Pas d'effet première commande. */
public class PremiumPricing implements PricingRule {
    public PremiumPricing() {}

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(new BigDecimal("0.90"));
        if (quantity >= 50) {
            total = total.multiply(new BigDecimal("0.95"));
        }
        return Amounts.finish(total);
    }
}
