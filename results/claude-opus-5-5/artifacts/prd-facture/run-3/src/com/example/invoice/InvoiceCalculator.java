package com.example.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Calcule les totaux d'une facture à partir d'un export CSV. */
public final class InvoiceCalculator {

    private static final String[] HEADER = {"sku", "quantity", "unit_price", "vat_rate"};
    private static final Pattern INTEGER = Pattern.compile("[+-]?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+([.,]\\d*)?|[.,]\\d+)");
    private static final BigDecimal[] ALLOWED_RATES = {
        new BigDecimal("0"), new BigDecimal("5.5"), new BigDecimal("10"), new BigDecimal("20")
    };
    private static final BigDecimal VOLUME_THRESHOLD = BigDecimal.TEN;
    private static final BigDecimal DISCOUNT_FACTOR = new BigDecimal("0.95");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final char BOM = 0xFEFF;

    private InvoiceCalculator() {}

    public static Invoice compute(String csv) {
        if (csv == null || csv.isEmpty()) {
            throw new InvoiceFormatException(1, "en-tête absent");
        }
        if (csv.charAt(0) == BOM) {
            csv = csv.substring(1);
        }
        String[] lines = csv.split("\r?\n", -1);
        checkHeader(lines[0]);

        // Somme HT par taux ; les taux sont normalisés vers les constantes autorisées.
        Map<BigDecimal, BigDecimal> htByRate = new TreeMap<>();
        BigDecimal totalHt = money(BigDecimal.ZERO);
        int lineCount = 0;

        for (int i = 1; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = stripCr(lines[i]);
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split(";", -1);
            if (fields.length != 4) {
                throw new InvoiceFormatException(lineNo,
                        "4 champs attendus, " + fields.length + " trouvé(s)");
            }
            BigDecimal quantity = parseQuantity(fields[1].strip(), lineNo);
            BigDecimal unitPrice = parsePrice(fields[2].strip(), lineNo);
            BigDecimal rate = parseRate(fields[3].strip(), lineNo);

            BigDecimal ht = money(quantity.multiply(unitPrice));
            if (quantity.compareTo(VOLUME_THRESHOLD) >= 0) {
                ht = money(ht.multiply(DISCOUNT_FACTOR));
            }
            htByRate.merge(rate, ht, BigDecimal::add);
            totalHt = totalHt.add(ht);
            lineCount++;
        }

        BigDecimal totalVat = money(BigDecimal.ZERO);
        for (Map.Entry<BigDecimal, BigDecimal> e : htByRate.entrySet()) {
            totalVat = totalVat.add(money(e.getValue().multiply(e.getKey()).divide(HUNDRED)));
        }
        return new Invoice(money(totalHt), money(totalVat), money(totalHt.add(totalVat)), lineCount);
    }

    private static void checkHeader(String rawHeader) {
        String header = stripCr(rawHeader);
        if (header.isBlank()) {
            throw new InvoiceFormatException(1, "en-tête absent");
        }
        String[] fields = header.split(";", -1);
        boolean ok = fields.length == HEADER.length;
        for (int k = 0; ok && k < HEADER.length; k++) {
            ok = HEADER[k].equals(fields[k].strip());
        }
        if (!ok) {
            throw new InvoiceFormatException(1,
                    "en-tête attendu '" + String.join(";", HEADER) + "', reçu '" + header + "'");
        }
    }

    private static BigDecimal parseQuantity(String s, int lineNo) {
        if (!INTEGER.matcher(s).matches()) {
            throw new InvoiceFormatException(lineNo, "quantité illisible : '" + s + "'");
        }
        BigDecimal q = new BigDecimal(s);
        if (q.signum() <= 0) {
            throw new InvoiceFormatException(lineNo, "quantité nulle ou négative : " + s);
        }
        return q;
    }

    private static BigDecimal parsePrice(String s, int lineNo) {
        BigDecimal p = parseDecimal(s, lineNo, "prix unitaire");
        if (p.signum() < 0) {
            throw new InvoiceFormatException(lineNo, "prix négatif : " + s);
        }
        return p;
    }

    private static BigDecimal parseRate(String s, int lineNo) {
        BigDecimal r = parseDecimal(s, lineNo, "taux de TVA");
        for (BigDecimal allowed : ALLOWED_RATES) {
            if (allowed.compareTo(r) == 0) {
                return allowed;
            }
        }
        throw new InvoiceFormatException(lineNo, "taux de TVA non autorisé : " + s);
    }

    private static BigDecimal parseDecimal(String s, int lineNo, String what) {
        if (!DECIMAL.matcher(s).matches()) {
            throw new InvoiceFormatException(lineNo, what + " illisible : '" + s + "'");
        }
        // BigDecimal accepte "+1", "1." et ".5" ; seule la virgule doit être normalisée.
        return new BigDecimal(s.replace(',', '.'));
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
