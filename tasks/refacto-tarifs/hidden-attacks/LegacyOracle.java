

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Copie du code d origine : oracle de comportement (tests cachés). */
final class LegacyOracle {

    public BigDecimal price(String customerType, int quantity, BigDecimal unitPrice, boolean firstOrder) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantité invalide");
        }
        BigDecimal total;
        if (customerType.equals("STANDARD")) {
            total = unitPrice.multiply(BigDecimal.valueOf(quantity));
            if (quantity >= 100) {
                total = total.multiply(new BigDecimal("0.95"));
            }
            if (firstOrder) {
                if (total.compareTo(new BigDecimal("50")) >= 0) {
                    total = total.subtract(new BigDecimal("10"));
                }
            }
            total = total.setScale(2, RoundingMode.HALF_UP);
            if (total.signum() < 0) {
                total = BigDecimal.ZERO.setScale(2);
            }
            return total;
        } else if (customerType.equals("PREMIUM")) {
            total = unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(new BigDecimal("0.90"));
            if (quantity >= 50) {
                total = total.multiply(new BigDecimal("0.95"));
            }
            total = total.setScale(2, RoundingMode.HALF_UP);
            if (total.signum() < 0) {
                total = BigDecimal.ZERO.setScale(2);
            }
            return total;
        } else if (customerType.equals("WHOLESALE")) {
            if (quantity < 10) {
                throw new IllegalArgumentException("minimum 10 unités pour un grossiste");
            }
            total = unitPrice.multiply(BigDecimal.valueOf(quantity)).multiply(new BigDecimal("0.80"));
            if (quantity >= 500) {
                total = total.multiply(new BigDecimal("0.97"));
            }
            if (firstOrder) {
                total = total.subtract(new BigDecimal("25"));
            }
            total = total.setScale(2, RoundingMode.HALF_UP);
            if (total.signum() < 0) {
                total = BigDecimal.ZERO.setScale(2);
            }
            return total;
        } else {
            throw new IllegalArgumentException("type de client inconnu : " + customerType);
        }
    }
}
