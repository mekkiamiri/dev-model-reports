package com.example.pricing;

import java.math.BigDecimal;

public class PremiumPricing implements PricingRule {
    private static final BigDecimal BASE_FACTOR = new BigDecimal("0.90");
    private static final BigDecimal VOLUME_FACTOR = new BigDecimal("0.95");

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(BASE_FACTOR);
        if (quantity >= 50) {
            total = total.multiply(VOLUME_FACTOR);
        }
        return Money.finish(total);
    }
}
