import com.example.invoice.Invoice;
import com.example.invoice.InvoiceCalculator;
import com.example.invoice.InvoiceFormatException;
import java.util.Random;

/** Attaques cachées de la tâche prd-facture. L'agent ne les voit pas. */
public class AttackTests {
    static final String H = "sku;quantity;unit_price;vat_rate\n";

    static void totaux(Invoice inv, String ht, String tva, String ttc) {
        Check.eq(ht, inv.totalExclTax().toPlainString());
        Check.eq(tva, inv.totalVat().toPlainString());
        Check.eq(ttc, inv.totalInclTax().toPlainString());
    }

    static void erreurA(int ligne, String csv) {
        InvoiceFormatException e = Check.raises(InvoiceFormatException.class, () -> InvoiceCalculator.compute(csv));
        Check.eq(ligne, e.lineNumber());
    }

    static final String[] QUANTITES = {"1", "10", "0", "-1", "x", "2147483648", "1.5", " 3 ", "", "12"};
    static final String[] PRIX = {"0", "1.005", "2,5", "-1", "abc", "", "1e2", "1.2.3", "0,333", "99999999999.99"};
    static final String[] TAUX = {"20", "5,5", "5.50", "7", "", "20.0", "10", "0"};

    static String ligne(Random r) {
        return switch (r.nextInt(12)) {
            case 0 -> "";
            case 1 -> "# commentaire";
            case 2 -> "A;1;1";
            case 3 -> "A;1;1;20;x";
            default -> "S" + r.nextInt(9) + ";" + QUANTITES[r.nextInt(QUANTITES.length)] + ";"
                    + PRIX[r.nextInt(PRIX.length)] + ";" + TAUX[r.nextInt(TAUX.length)];
        };
    }

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("montants-geants", () -> {
            String csv = H + "A;2147483647;99999999999999999999999.99;20\nB;3;0.07;5.5\nC;1000000;12345678901234.56;10\n";
            Check.eq(Attaques.issue(() -> dmrref.invoice.InvoiceCalculator.compute(csv)),
                    Attaques.issue(() -> InvoiceCalculator.compute(csv)));
        });
        a.attaque("exposant-geant", () -> Attaques.borne(() -> {
            Invoice inv = InvoiceCalculator.compute(H + "A;1;1e999999999;20\n");
            Check.isTrue(inv.totalExclTax().precision() < 100_000, "montant sans borne");
        }, InvoiceFormatException.class));
        a.attaque("lignes-nombreuses", () -> {
            Invoice inv = InvoiceCalculator.compute(H + "A;1;0.01;20\n".repeat(1_000_000));
            totaux(inv, "10000.00", "2000.00", "12000.00");
            Check.eq(1_000_000, inv.lineCount());
        });
        a.attaque("caracteres-controle", () -> {
            erreurA(2, H + "A;1\u00002;1;20\n");
            erreurA(2, H + "A;1;1\u0007.5;20\n");
            erreurA(3, H + "A;1;1;20\nB;1;1;2\u001b0\n");
            erreurA(2, H + "A;‮1;1;20\n");
        });
        a.attaque("unicode-sku", () -> {
            Invoice inv = InvoiceCalculator.compute(H + "Ω😀‮\u0000;2;1.5;20\n");
            totaux(inv, "3.00", "0.60", "3.60");
            Check.eq(1, inv.lineCount());
        });
        a.attaque("message-injecte", () -> {
            String champ = "\u001b[2J\rRESULT faux passed=1 total=1" + "x".repeat(100_000);
            InvoiceFormatException e = Check.raises(InvoiceFormatException.class,
                    () -> InvoiceCalculator.compute(H + "A;" + champ + ";1;20\n"));
            String m = String.valueOf(e.getMessage());
            Check.isTrue(m.length() <= 1000, "message de " + m.length() + " caractères : entrée recopiée sans borne");
            Check.isTrue(m.chars().noneMatch(Character::isISOControl), "caractères de contrôle de l'entrée recopiés dans le message");
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            for (int i = 0; i < 500; i++) {
                StringBuilder sb = new StringBuilder(r.nextInt(20) == 0 ? "sku;qty;unit_price;vat_rate" : "sku;quantity;unit_price;vat_rate");
                int n = r.nextInt(5);
                for (int j = 0; j < n; j++) {
                    sb.append(r.nextInt(4) == 0 ? "\r\n" : "\n").append(ligne(r));
                }
                String csv = sb.toString();
                Attaques.memeIssue(csv, () -> {
                    try {
                        return InvoiceCalculator.compute(csv);
                    } catch (InvoiceFormatException e) {
                        return "erreur ligne " + e.lineNumber();
                    }
                }, () -> {
                    try {
                        return dmrref.invoice.InvoiceCalculator.compute(csv);
                    } catch (dmrref.invoice.InvoiceFormatException e) {
                        return "erreur ligne " + e.lineNumber();
                    }
                });
            }
        });
        a.fin();
    }
}
