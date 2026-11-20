package com.example.invoice;

import java.math.BigDecimal;

/** Totaux d'une facture. Les montants ont exactement 2 décimales. */
public record Invoice(BigDecimal totalExclTax, BigDecimal totalVat, BigDecimal totalInclTax, int lineCount) {}
