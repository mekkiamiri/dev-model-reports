package com.example.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Calcule les totaux d'une facture à partir d'un export CSV. */
public final class InvoiceCalculator {

    private static final String[] HEADER = {"sku", "quantity", "unit_price", "vat_rate"};
    private static final List<BigDecimal> ALLOWED_RATES = List.of(
            new BigDecimal("0"), new BigDecimal("5.5"), new BigDecimal("10"), new BigDecimal("20"));
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)");
    private static final BigDecimal VOLUME_THRESHOLD = BigDecimal.TEN;
    private static final BigDecimal DISCOUNT_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final char BOM = (char) 0xFEFF;

    private InvoiceCalculator() {}

    public static Invoice compute(String csv) {
        if (csv == null) {
            throw new InvoiceFormatException(1, "en-tête absent (entrée nulle)");
        }
        String[] lines = csv.split("\n", -1);

        checkHeader(lines[0]);

        // Somme des montants HT par taux (clé comparée numériquement).
        Map<BigDecimal, BigDecimal> htByRate = new TreeMap<>();
        int lineCount = 0;

        for (int i = 1; i < lines.length; i++) {
            int lineNumber = i + 1;
            String line = stripCr(lines[i]);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(";", -1);
            if (fields.length != 4) {
                throw new InvoiceFormatException(lineNumber,
                        "4 champs attendus, " + fields.length + " trouvé(s)");
            }
            BigDecimal quantity = parseQuantity(fields[1].strip(), lineNumber);
            BigDecimal unitPrice = parsePrice(fields[2].strip(), lineNumber);
            BigDecimal rate = parseRate(fields[3].strip(), lineNumber);

            BigDecimal ht = round(quantity.multiply(unitPrice));
            if (quantity.compareTo(VOLUME_THRESHOLD) >= 0) {
                ht = round(ht.multiply(DISCOUNT_FACTOR));
            }
            htByRate.merge(rate, ht, BigDecimal::add);
            lineCount++;
        }

        BigDecimal totalHt = round(BigDecimal.ZERO);
        BigDecimal totalVat = round(BigDecimal.ZERO);
        for (Map.Entry<BigDecimal, BigDecimal> e : htByRate.entrySet()) {
            BigDecimal sum = e.getValue();
            totalHt = totalHt.add(sum);
            totalVat = totalVat.add(round(sum.multiply(e.getKey()).divide(HUNDRED)));
        }
        totalHt = round(totalHt);
        totalVat = round(totalVat);
        return new Invoice(totalHt, totalVat, round(totalHt.add(totalVat)), lineCount);
    }

    private static void checkHeader(String rawLine) {
        String line = stripCr(rawLine);
        if (!line.isEmpty() && line.charAt(0) == BOM) {
            line = line.substring(1);
        }
        if (line.isBlank()) {
            throw new InvoiceFormatException(1, "en-tête absent");
        }
        String[] fields = line.split(";", -1);
        boolean ok = fields.length == HEADER.length;
        for (int i = 0; ok && i < HEADER.length; i++) {
            ok = HEADER[i].equals(fields[i].strip());
        }
        if (!ok) {
            throw new InvoiceFormatException(1,
                    "en-tête attendu « sku;quantity;unit_price;vat_rate », reçu « " + line + " »");
        }
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static BigDecimal parseQuantity(String s, int lineNumber) {
        if (!INTEGER.matcher(s).matches()) {
            throw new InvoiceFormatException(lineNumber, "quantité illisible : « " + s + " »");
        }
        BigDecimal q = new BigDecimal(s);
        if (q.signum() <= 0) {
            throw new InvoiceFormatException(lineNumber, "quantité nulle ou négative : " + s);
        }
        return q;
    }

    private static BigDecimal parsePrice(String s, int lineNumber) {
        BigDecimal p = parseDecimal(s, lineNumber, "prix unitaire");
        if (p.signum() < 0) {
            throw new InvoiceFormatException(lineNumber, "prix négatif : " + s);
        }
        return p;
    }

    private static BigDecimal parseRate(String s, int lineNumber) {
        BigDecimal r = parseDecimal(s, lineNumber, "taux de TVA");
        for (BigDecimal allowed : ALLOWED_RATES) {
            if (allowed.compareTo(r) == 0) {
                return allowed;
            }
        }
        throw new InvoiceFormatException(lineNumber, "taux de TVA non autorisé : " + s);
    }

    private static BigDecimal parseDecimal(String s, int lineNumber, String what) {
        if (!DECIMAL.matcher(s).matches()) {
            throw new InvoiceFormatException(lineNumber, what + " illisible : « " + s + " »");
        }
        String normalized = s.replace(',', '.');
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return new BigDecimal(normalized);
    }

    private static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
