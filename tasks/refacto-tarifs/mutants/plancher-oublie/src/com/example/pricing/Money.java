package com.example.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Arrondi au centime et plancher à zéro, communs à toutes les règles. */
final class Money {
    private Money() {}

    static BigDecimal finish(BigDecimal total) {
        BigDecimal rounded = total.setScale(2, RoundingMode.HALF_UP);
        return rounded;
    }
}
