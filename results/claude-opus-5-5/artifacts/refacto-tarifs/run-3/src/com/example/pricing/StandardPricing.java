package com.example.pricing;

import java.math.BigDecimal;

/** Client standard : -5 % dès 100 unités, -10 sur une première commande d'au moins 50. */
public class StandardPricing implements PricingRule {
    private static final BigDecimal VOLUME_RATE = new BigDecimal("0.95");
    private static final BigDecimal FIRST_ORDER_THRESHOLD = new BigDecimal("50");
    private static final BigDecimal FIRST_ORDER_DISCOUNT = new BigDecimal("10");

    public StandardPricing() {}

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        BigDecimal total = Amounts.gross(quantity, unitPrice);
        if (quantity >= 100) {
            total = total.multiply(VOLUME_RATE);
        }
        if (firstOrder && total.compareTo(FIRST_ORDER_THRESHOLD) >= 0) {
            total = total.subtract(FIRST_ORDER_DISCOUNT);
        }
        return Amounts.finish(total);
    }
}
