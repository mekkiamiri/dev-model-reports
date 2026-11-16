package com.example.pricing;

import java.math.BigDecimal;

/** Politique de prix d'un type de client, pour une quantité strictement positive. */
public interface PricingRule {
    BigDecimal price(int quantity, BigDecimal unitPrice, boolean firstOrder);
}
