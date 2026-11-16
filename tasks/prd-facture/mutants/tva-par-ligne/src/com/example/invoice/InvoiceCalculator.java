package com.example.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class InvoiceCalculator {
    private static final String HEADER = "sku;quantity;unit_price;vat_rate";
    private static final Set<BigDecimal> RATES = Set.of(
            new BigDecimal("0"), new BigDecimal("5.5"), new BigDecimal("10"), new BigDecimal("20"));
    private static final BigDecimal VOLUME_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private InvoiceCalculator() {}

    public static Invoice compute(String csv) {
        String[] lines = csv.split("\r?\n", -1);
        if (lines.length == 0 || !lines[0].trim().equals(HEADER)) {
            throw new InvoiceFormatException(1, "en-tête attendu : " + HEADER);
        }
        Map<BigDecimal, BigDecimal> exclTaxByRate = new TreeMap<>();
        int count = 0;
        BigDecimal vatPerLine = cents(BigDecimal.ZERO);
        for (int i = 1; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = lines[i];
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(";", -1);
            if (fields.length != 4) {
                throw new InvoiceFormatException(lineNumber, "4 champs attendus");
            }
            int quantity = parseQuantity(fields[1].trim(), lineNumber);
            BigDecimal unitPrice = parseDecimal(fields[2].trim(), lineNumber);
            if (unitPrice.signum() < 0) {
                throw new InvoiceFormatException(lineNumber, "prix négatif");
            }
            BigDecimal rate = parseDecimal(fields[3].trim(), lineNumber);
            if (RATES.stream().noneMatch(r -> r.compareTo(rate) == 0)) {
                throw new InvoiceFormatException(lineNumber, "taux de TVA inconnu : " + rate);
            }
            BigDecimal amount = cents(unitPrice.multiply(BigDecimal.valueOf(quantity)));
            if (quantity >= 10) {
                amount = cents(amount.multiply(VOLUME_FACTOR));
            }
            exclTaxByRate.merge(rate.stripTrailingZeros(), amount, BigDecimal::add);
            vatPerLine = vatPerLine.add(cents(amount.multiply(rate).divide(HUNDRED)));
            count++;
        }
        BigDecimal exclTax = cents(BigDecimal.ZERO);
        BigDecimal vat = cents(BigDecimal.ZERO);
        for (Map.Entry<BigDecimal, BigDecimal> e : exclTaxByRate.entrySet()) {
            exclTax = exclTax.add(e.getValue());
            vat = vat.add(cents(e.getValue().multiply(e.getKey()).divide(HUNDRED)));
        }
        return new Invoice(exclTax, vatPerLine, exclTax.add(vatPerLine), count);
    }

    private static int parseQuantity(String text, int lineNumber) {
        try {
            int q = Integer.parseInt(text);
            if (q <= 0) {
                throw new InvoiceFormatException(lineNumber, "quantité doit être positive");
            }
            return q;
        } catch (NumberFormatException e) {
            throw new InvoiceFormatException(lineNumber, "quantité illisible : " + text);
        }
    }

    private static BigDecimal parseDecimal(String text, int lineNumber) {
        try {
            return new BigDecimal(text.replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new InvoiceFormatException(lineNumber, "nombre illisible : " + text);
        }
    }

    private static BigDecimal cents(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
