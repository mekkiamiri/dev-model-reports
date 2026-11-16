import com.example.invoice.Invoice;
import com.example.invoice.InvoiceCalculator;
import com.example.invoice.InvoiceFormatException;

/** Tests d'acceptation cachés de la tâche prd-facture. L'agent ne les voit pas. */
public class AcceptanceTests {
    static final String H = "sku;quantity;unit_price;vat_rate\n";

    static Invoice run(String body) {
        return InvoiceCalculator.compute(H + body);
    }

    static void totals(Invoice inv, String ht, String vat, String ttc) {
        Check.eq(ht, inv.totalExclTax().toPlainString());
        Check.eq(vat, inv.totalVat().toPlainString());
        Check.eq(ttc, inv.totalInclTax().toPlainString());
    }

    static void errorAt(int line, String csv) {
        InvoiceFormatException e = Check.raises(InvoiceFormatException.class, () -> InvoiceCalculator.compute(csv));
        Check.eq(line, e.lineNumber());
    }

    public static void main(String[] args) {
        Check.start();
        Check.test("ligne simple", () -> {
            Invoice inv = run("A;2;10.00;20\n");
            totals(inv, "20.00", "4.00", "24.00");
            Check.eq(1, inv.lineCount());
        });
        Check.test("virgule décimale dans le prix et le taux", () -> totals(run("A;3;2,50;5,5\n"), "7.50", "0.41", "7.91"));
        Check.test("arrondi commercial de la ligne", () -> totals(run("A;1;0.005;0\n"), "0.01", "0.00", "0.01"));
        Check.test("remise de volume à 10", () -> totals(run("A;10;3.33;20\n"), "31.64", "6.33", "37.97"));
        Check.test("pas de remise à 9", () -> totals(run("A;9;10;20\n"), "90.00", "18.00", "108.00"));
        Check.test("remise de volume à 12", () -> totals(run("A;12;0.99;20\n"), "11.29", "2.26", "13.55"));
        Check.test("TVA calculée par taux, pas par ligne", () ->
                totals(run("A;1;0.05;10\nB;1;0.05;10\nC;1;0.05;10\n"), "0.15", "0.02", "0.17"));
        Check.test("plusieurs taux", () -> {
            Invoice inv = run("A;1;100;20\nB;2;10;5.5\nC;1;7;0\n");
            totals(inv, "127.00", "21.10", "148.10");
            Check.eq(3, inv.lineCount());
        });
        Check.test("lignes vides et commentaires ignorés", () -> {
            Invoice inv = run("\n# commentaire\n   \nA;1;1;20\n");
            totals(inv, "1.00", "0.20", "1.20");
            Check.eq(1, inv.lineCount());
        });
        Check.test("espaces autour des champs", () -> totals(run(" A ; 2 ; 1.5 ; 20 \n"), "3.00", "0.60", "3.60"));
        Check.test("facture vide", () -> {
            Invoice inv = InvoiceCalculator.compute("sku;quantity;unit_price;vat_rate");
            totals(inv, "0.00", "0.00", "0.00");
            Check.eq(0, inv.lineCount());
        });
        Check.test("fins de ligne CRLF", () ->
                totals(InvoiceCalculator.compute("sku;quantity;unit_price;vat_rate\r\nA;1;10;20\r\n"), "10.00", "2.00", "12.00"));
        Check.test("en-tête absent", () -> errorAt(1, "A;1;1;20\n"));
        Check.test("en-tête différent", () -> errorAt(1, "sku;qty;unit_price;vat_rate\nA;1;1;20\n"));
        Check.test("texte vide", () -> errorAt(1, ""));
        Check.test("3 champs, numéro de ligne après un commentaire", () -> errorAt(3, H + "# c\nA;1;1\n"));
        Check.test("5 champs", () -> errorAt(2, H + "A;1;1;20;x\n"));
        Check.test("quantité nulle", () -> errorAt(2, H + "A;0;1;20\n"));
        Check.test("quantité négative", () -> errorAt(3, H + "A;1;1;20\nB;-2;1;20\n"));
        Check.test("quantité décimale", () -> errorAt(2, H + "A;1.5;1;20\n"));
        Check.test("quantité illisible", () -> errorAt(2, H + "A;abc;1;20\n"));
        Check.test("prix négatif", () -> errorAt(2, H + "A;1;-1;20\n"));
        Check.test("prix illisible", () -> errorAt(2, H + "A;1;x;20\n"));
        Check.test("taux inconnu", () -> errorAt(4, H + "A;1;1;20\n\nB;1;1;19.6\n"));
        Check.test("taux nul accepté", () -> totals(run("A;4;2.5;0\n"), "10.00", "0.00", "10.00"));
        Check.finish();
    }
}
