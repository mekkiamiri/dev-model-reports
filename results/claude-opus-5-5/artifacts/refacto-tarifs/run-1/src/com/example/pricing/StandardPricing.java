package com.example.pricing;

import java.math.BigDecimal;

/** Client standard : -5 % dès 100 unités, puis -10 si première commande d'au moins 50 (avant arrondi). */
public class StandardPricing implements PricingRule {
    public StandardPricing() {}

    @Override
    public BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder) {
        Amounts.requirePositive(quantity);
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(quantity));
        if (quantity >= 100) {
            total = total.multiply(new BigDecimal("0.95"));
        }
        if (firstOrder && total.compareTo(new BigDecimal("50")) >= 0) {
            total = total.subtract(new BigDecimal("10"));
        }
        return Amounts.finish(total);
    }
}
