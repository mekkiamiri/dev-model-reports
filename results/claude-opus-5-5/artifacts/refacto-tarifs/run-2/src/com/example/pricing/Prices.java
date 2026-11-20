package com.example.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Outils communs aux règles de prix (validation, arrondi, plancher à zéro). */
final class Prices {
    private Prices() {}

    static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantité invalide");
        }
    }

    /** Arrondit à 2 décimales (HALF_UP) puis ramène tout total négatif à 0.00. */
    static BigDecimal finish(BigDecimal total) {
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        if (rounded.signum() < 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return rounded;
    }
}
