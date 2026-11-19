package com.example.pricing;

import java.math.BigDecimal;

public class StandardPricing implements PricingRule {
    private static final BigDecimal VOLUME_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal WELCOME_THRESHOLD = new BigDecimal("50");
    private static final BigDecimal WELCOME_DISCOUNT = new BigDecimal("10");

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (quantity >= 100) {
            total = total.multiply(VOLUME_FACTOR);
        }
        if (firstOrder && total.intValue() >= 50) { // faille : intValue déborde sur un gros total
            total = total.subtract(WELCOME_DISCOUNT);
        }
        return Money.finish(total);
    }
}
