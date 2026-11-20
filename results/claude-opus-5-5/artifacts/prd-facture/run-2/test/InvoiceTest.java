import com.example.invoice.*;

public class InvoiceTest {
    static int failures = 0;
    static final String H = "sku;quantity;unit_price;vat_rate\n";

    static void eq(String name, Object expected, Object actual) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            failures++;
            System.out.println("FAIL " + name + " : attendu " + expected + ", obtenu " + actual);
        }
    }

    static void ok(String name, String csv, String ht, String vat, String ttc, int n) {
        try {
            Invoice i = InvoiceCalculator.compute(csv);
            eq(name + " ht", ht, i.totalExclTax());
            eq(name + " vat", vat, i.totalVat());
            eq(name + " ttc", ttc, i.totalInclTax());
            eq(name + " n", n, i.lineCount());
        } catch (RuntimeException e) {
            failures++;
            System.out.println("FAIL " + name + " : " + e);
        }
    }

    static void err(String name, String csv, int line) {
        try {
            InvoiceCalculator.compute(csv);
            failures++;
            System.out.println("FAIL " + name + " : pas d'exception");
        } catch (InvoiceFormatException e) {
            eq(name + " line", line, e.lineNumber());
        }
    }

    public static void main(String[] a) {
        ok("vide", H, "0.00", "0.00", "0.00", 0);
        ok("vide sans \\n", "sku;quantity;unit_price;vat_rate", "0.00", "0.00", "0.00", 0);
        ok("arrondi", H + "A;2;10.005;20\n", "20.01", "4.00", "24.01", 1);
        ok("remise + virgule", H + "B;10;1,99;5,5\n", "18.91", "1.04", "19.95", 1);
        ok("tva agrégée", H + "a;1;0.05;10\nb;1;0.05;10\nc;1;0.05;10\n", "0.15", "0.02", "0.17", 3);
        ok("crlf+ignorées+espaces", "sku;quantity;unit_price;vat_rate\r\n\r\n# c\r\n   \r\n x ; 3 ; 1.5 ; 0 \r\n",
                "4.50", "0.00", "4.50", 1);
        ok("multi-taux", H + "a;1;100;20\nb;1;100;10\nc;1;100;5.5\nd;1;100;0\n", "400.00", "35.50", "435.50", 4);
        ok("9 vs 10", H + "a;9;1;20\nb;10;1;20\n", "18.50", "3.70", "22.20", 2);

        err("en-tête absent", "", 1);
        err("en-tête faux", "sku;qty;unit_price;vat_rate\na;1;1;20\n", 1);
        err("données sans en-tête", "a;1;1;20\n", 1);
        err("champs", H + "\n# x\na;1;1\n", 4);
        err("champs 5", H + "a;1;1;20;\n", 2);
        err("qte 0", H + "a;0;1;20\n", 2);
        err("qte neg", H + "a;-1;1;20\n", 2);
        err("qte decimale", H + "a;1.5;1;20\n", 2);
        err("prix neg", H + "a;1;-1;20\n", 2);
        err("prix illisible", H + "a;1;abc;20\n", 2);
        err("prix vide", H + "a;1;;20\n", 2);
        err("taux", H + "a;1;1;19.6\n", 2);
        err("taux illisible", H + "ok;1;1;20\na;1;1;x\n", 3);

        System.out.println(failures == 0 ? "OK" : failures + " échec(s)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
