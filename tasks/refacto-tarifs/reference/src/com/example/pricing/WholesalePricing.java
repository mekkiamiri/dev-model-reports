package com.example.pricing;

import java.math.BigDecimal;

public class WholesalePricing implements PricingRule {
    private static final BigDecimal BASE_FACTOR = new BigDecimal("0.80");
    private static final BigDecimal VOLUME_FACTOR = new BigDecimal("0.97");
    private static final BigDecimal WELCOME_DISCOUNT = new BigDecimal("25");

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        if (quantity < 10) {
            throw new IllegalArgumentException("minimum 10 unités pour un grossiste");
        }
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(BASE_FACTOR);
        if (quantity >= 500) {
            total = total.multiply(VOLUME_FACTOR);
        }
        if (firstOrder) {
            total = total.subtract(WELCOME_DISCOUNT);
        }
        return Money.finish(total);
    }
}
