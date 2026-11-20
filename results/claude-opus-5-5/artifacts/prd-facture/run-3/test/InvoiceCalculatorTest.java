import com.example.invoice.*;

public class InvoiceCalculatorTest {
    static int failures = 0;

    static void eq(String label, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + label + " : attendu " + expected + ", obtenu " + actual);
        }
    }

    static void check(String label, String csv, String ht, String vat, String ttc, int count) {
        Invoice inv = InvoiceCalculator.compute(csv);
        eq(label + " HT", ht, inv.totalExclTax().toPlainString());
        eq(label + " TVA", vat, inv.totalVat().toPlainString());
        eq(label + " TTC", ttc, inv.totalInclTax().toPlainString());
        eq(label + " count", count, inv.lineCount());
    }

    static void error(String label, String csv, int line) {
        try {
            InvoiceCalculator.compute(csv);
            failures++;
            System.out.println("FAIL " + label + " : pas d'exception");
        } catch (InvoiceFormatException e) {
            eq(label + " ligne", line, e.lineNumber());
        }
    }

    public static void main(String[] args) {
        String H = "sku;quantity;unit_price;vat_rate\n";
        check("vide", H, "0.00", "0.00", "0.00", 0);
        check("en-tête seul sans \\n", "sku;quantity;unit_price;vat_rate", "0.00", "0.00", "0.00", 0);
        check("simple", H + "A;2;10.00;20\n", "20.00", "4.00", "24.00", 1);
        check("virgule", H + "A ; 2 ; 12,5 ; 5,5\n", "25.00", "1.38", "26.38", 1);
        // 3 x 0.335 = 1.005 -> 1.01
        check("arrondi ligne", H + "A;3;0.335;0\n", "1.01", "0.00", "1.01", 1);
        // 10 x 1.00 = 10.00, remise -> 9.50 ; TVA 20 % = 1.90
        check("remise", H + "A;10;1;20\n", "9.50", "1.90", "11.40", 1);
        check("pas de remise à 9", H + "A;9;1;20\n", "9.00", "1.80", "10.80", 1);
        // TVA par taux : 3 lignes à 0.10 -> par ligne 0.02*3=0.06, sur somme 0.30*0.2=0.06 ;
        // 0.05 x3 à 10 % : ligne 0.01*3=0.03, somme 0.15*0.1=0.015 -> 0.02
        check("TVA agrégée", H + "A;1;0.05;10\nB;1;0.05;10\nC;1;0.05;10\n", "0.15", "0.02", "0.17", 3);
        check("multi-taux", H + "A;1;100;20\nB;1;100;5.5\nC;1;100;0\nD;1;100;10\n",
                "400.00", "35.50", "435.50", 4);
        check("ignorées + CRLF", "sku;quantity;unit_price;vat_rate\r\n\r\n# c\r\n   \r\nA;1;1;20\r\n",
                "1.00", "0.20", "1.20", 1);
        check("5,50 et 5.5 regroupés", H + "A;1;0.10;5.5\nB;1;0.10;5,50\n", "0.20", "0.01", "0.21", 2);
        check("prix nul", H + "A;1;0;20\n", "0.00", "0.00", "0.00", 1);

        error("null", null, 1);
        error("vide", "", 1);
        error("mauvais en-tête", "sku;qty;unit_price;vat_rate\nA;1;1;20\n", 1);
        error("champs", H + "A;1;1\n", 2);
        error("champs en trop", H + "\n# x\nA;1;1;20;\n", 4);
        error("quantité 0", H + "A;0;1;20\n", 2);
        error("quantité négative", H + "A;-1;1;20\n", 2);
        error("quantité décimale", H + "A;1.5;1;20\n", 2);
        error("prix négatif", H + "A;1;-1;20\n", 2);
        error("prix illisible", H + "A;1;abc;20\n", 2);
        error("prix vide", H + "A;1;;20\n", 2);
        error("taux", H + "A;1;1;19.6\n", 2);
        error("taux illisible", H + "A;1;1;20%\n", 2);

        System.out.println(failures == 0 ? "OK" : failures + " échec(s)");
        if (failures > 0) System.exit(1);
    }
}
