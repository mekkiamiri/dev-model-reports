package com.example.pricing;

import java.math.BigDecimal;

/** Grossiste : minimum 10 unités, -20 %, puis -3 % dès 500 unités, -25 sur une première commande. */
public class WholesalePricing implements PricingRule {
    private static final BigDecimal BASE_RATE = new BigDecimal("0.80");
    private static final BigDecimal VOLUME_RATE = new BigDecimal("0.97");
    private static final BigDecimal FIRST_ORDER_DISCOUNT = new BigDecimal("25");

    public WholesalePricing() {}

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        if (quantity < 10) {
            throw new IllegalArgumentException("minimum 10 unités pour un grossiste");
        }
        BigDecimal total = Amounts.gross(quantity, unitPrice).multiply(BASE_RATE);
        if (quantity >= 500) {
            total = total.multiply(VOLUME_RATE);
        }
        if (firstOrder) {
            total = total.subtract(FIRST_ORDER_DISCOUNT);
        }
        return Amounts.finish(total);
    }
}
