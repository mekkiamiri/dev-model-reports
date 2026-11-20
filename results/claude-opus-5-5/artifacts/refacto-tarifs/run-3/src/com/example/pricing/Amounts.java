package com.example.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Outils communs aux règles de prix (validation, arrondi, plancher à zéro). */
final class Amounts {
    private Amounts() {}

    static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantité invalide");
        }
    }

    static BigDecimal gross(int quantity, BigDecimal unitPrice) {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    /** Arrondi à 2 décimales (HALF_UP), puis plancher à zéro — dans cet ordre. */
    static BigDecimal finish(BigDecimal total) {
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        return rounded.signum() < 0 ? BigDecimal.ZERO.setScale(2) : rounded;
    }
}
