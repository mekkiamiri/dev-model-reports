package com.example.invoice;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Calcule les totaux d'une facture à partir d'un export CSV. */
public final class InvoiceCalculator {

    private static final String[] HEADER = {"sku", "quantity", "unit_price", "vat_rate"};

    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)");

    private static final List<BigDecimal> ALLOWED_RATES = List.of(
            new BigDecimal("0"), new BigDecimal("5.5"), new BigDecimal("10"), new BigDecimal("20"));

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DISCOUNT_FACTOR = new BigDecimal("0.95");
    private static final BigInteger DISCOUNT_THRESHOLD = BigInteger.TEN;

    private InvoiceCalculator() {
    }

    public static Invoice compute(String csv) {
        if (csv == null) {
            throw new InvoiceFormatException(1, "missing header");
        }
        String[] lines = csv.split("\n", -1);

        checkHeader(lines[0]);

        // Somme HT par taux (clé normalisée pour que 5.5 et 5.50 soient regroupés).
        Map<BigDecimal, BigDecimal> exclTaxByRate = new TreeMap<>();
        int lineCount = 0;

        for (int i = 1; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = stripCr(lines[i]);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(";", -1);
            if (fields.length != 4) {
                throw new InvoiceFormatException(lineNumber,
                        "expected 4 fields but found " + fields.length);
            }
            BigInteger quantity = parseQuantity(fields[1].strip(), lineNumber);
            BigDecimal unitPrice = parsePrice(fields[2].strip(), lineNumber);
            BigDecimal rate = parseRate(fields[3].strip(), lineNumber);

            BigDecimal amount = round(new BigDecimal(quantity).multiply(unitPrice));
            if (quantity.compareTo(DISCOUNT_THRESHOLD) >= 0) {
                amount = round(amount.multiply(DISCOUNT_FACTOR));
            }
            exclTaxByRate.merge(rate, amount, BigDecimal::add);
            lineCount++;
        }

        BigDecimal totalExclTax = round(BigDecimal.ZERO);
        BigDecimal totalVat = round(BigDecimal.ZERO);
        for (Map.Entry<BigDecimal, BigDecimal> e : exclTaxByRate.entrySet()) {
            BigDecimal base = e.getValue();
            totalExclTax = totalExclTax.add(base);
            totalVat = totalVat.add(round(base.multiply(e.getKey()).divide(HUNDRED)));
        }
        totalExclTax = round(totalExclTax);
        totalVat = round(totalVat);
        BigDecimal totalInclTax = round(totalExclTax.add(totalVat));
        return new Invoice(totalExclTax, totalVat, totalInclTax, lineCount);
    }

    private static void checkHeader(String rawLine) {
        String line = stripCr(rawLine);
        if (line.startsWith("﻿")) {
            line = line.substring(1);
        }
        if (line.isBlank()) {
            throw new InvoiceFormatException(1, "missing header");
        }
        String[] fields = line.split(";", -1);
        boolean ok = fields.length == HEADER.length;
        for (int i = 0; ok && i < HEADER.length; i++) {
            ok = fields[i].strip().equals(HEADER[i]);
        }
        if (!ok) {
            throw new InvoiceFormatException(1,
                    "invalid header, expected 'sku;quantity;unit_price;vat_rate'");
        }
    }

    private static BigInteger parseQuantity(String s, int lineNumber) {
        if (!INTEGER.matcher(s).matches()) {
            throw new InvoiceFormatException(lineNumber, "unreadable quantity '" + s + "'");
        }
        BigInteger q = new BigInteger(s.startsWith("+") ? s.substring(1) : s);
        if (q.signum() <= 0) {
            throw new InvoiceFormatException(lineNumber, "quantity must be strictly positive: " + s);
        }
        return q;
    }

    private static BigDecimal parsePrice(String s, int lineNumber) {
        BigDecimal p = parseDecimal(s, lineNumber, "unit price");
        if (p.signum() < 0) {
            throw new InvoiceFormatException(lineNumber, "unit price must not be negative: " + s);
        }
        return p;
    }

    private static BigDecimal parseRate(String s, int lineNumber) {
        BigDecimal r = parseDecimal(s, lineNumber, "VAT rate");
        for (BigDecimal allowed : ALLOWED_RATES) {
            if (allowed.compareTo(r) == 0) {
                return allowed;
            }
        }
        throw new InvoiceFormatException(lineNumber, "unsupported VAT rate: " + s);
    }

    private static BigDecimal parseDecimal(String s, int lineNumber, String what) {
        if (!DECIMAL.matcher(s).matches()) {
            throw new InvoiceFormatException(lineNumber, "unreadable " + what + " '" + s + "'");
        }
        String normalized = s.replace(',', '.');
        if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException e) {
            throw new InvoiceFormatException(lineNumber, "unreadable " + what + " '" + s + "'");
        }
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
