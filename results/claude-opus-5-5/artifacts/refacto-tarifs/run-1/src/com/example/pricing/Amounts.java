package com.example.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Étapes communes à toutes les règles de prix. */
final class Amounts {
    private Amounts() {}

    static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantité invalide");
        }
    }

    /** Arrondi à 2 décimales (HALF_UP) puis plancher à zéro. */
    static BigDecimal finish(BigDecimal total) {
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        if (rounded.signum() < 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return rounded;
    }
}
