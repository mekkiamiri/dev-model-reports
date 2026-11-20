package com.example.pricing;

import java.math.BigDecimal;

/** Grossiste : minimum 10 unités, -20 %, -3 % dès 500 unités, -25 si première commande. */
public class WholesalePricing implements PricingRule {
    public WholesalePricing() {}

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        if (quantity < 10) {
            throw new IllegalArgumentException("minimum 10 unités pour un grossiste");
        }
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(new BigDecimal("0.80"));
        if (quantity >= 500) {
            total = total.multiply(new BigDecimal("0.97"));
        }
        if (firstOrder) {
            total = total.subtract(new BigDecimal("25"));
        }
        return Amounts.finish(total);
    }
}
