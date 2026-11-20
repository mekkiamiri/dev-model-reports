package com.example.pricing;

import java.math.BigDecimal;

/** Client premium : -10 % toujours, puis -5 % dès 50 unités. */
public class PremiumPricing implements PricingRule {
    private static final BigDecimal BASE_RATE = new BigDecimal("0.90");
    private static final BigDecimal VOLUME_RATE = new BigDecimal("0.95");

    public PremiumPricing() {}

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        BigDecimal total = Amounts.gross(quantity, unitPrice).multiply(BASE_RATE);
        if (quantity >= 50) {
            total = total.multiply(VOLUME_RATE);
        }
        return Amounts.finish(total);
    }
}
