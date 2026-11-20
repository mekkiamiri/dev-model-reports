# Contrat d'interface

Paquet : `com.example.invoice`. Ces signatures publiques sont obligatoires ; le reste est libre.

```java
public record Invoice(BigDecimal totalExclTax, BigDecimal totalVat, BigDecimal totalInclTax, int lineCount) {}

public final class InvoiceCalculator {
    public static Invoice compute(String csv);           // lève InvoiceFormatException
}

public class InvoiceFormatException extends RuntimeException {
    public InvoiceFormatException(int lineNumber, String message);
    public int lineNumber();                             // numéro de ligne, 1 = en-tête
}
```

Les fins de ligne peuvent être `\n` ou `\r\n`.
