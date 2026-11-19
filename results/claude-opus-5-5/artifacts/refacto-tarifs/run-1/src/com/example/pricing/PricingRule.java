package com.example.pricing;

import java.math.BigDecimal;

/** Politique de prix d'un type de client. */
public interface PricingRule {
    BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder);
}
