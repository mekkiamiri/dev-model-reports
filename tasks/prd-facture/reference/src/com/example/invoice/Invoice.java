package com.example.invoice;

import java.math.BigDecimal;

public record Invoice(BigDecimal totalExclTax, BigDecimal totalVat, BigDecimal totalInclTax, int lineCount) {}
