import com.example.invoice.Invoice;
import com.example.invoice.InvoiceCalculator;
import com.example.invoice.InvoiceFormatException;

public class InvoiceCalculatorTest {

    static int failures = 0;
    static final String H = "sku;quantity;unit_price;vat_rate\n";

    public static void main(String[] args) {
        ok("empty invoice", H, "0.00", "0.00", "0.00", 0);
        ok("header only no newline", "sku;quantity;unit_price;vat_rate", "0.00", "0.00", "0.00", 0);
        ok("simple", H + "A;2;10;20\n", "20.00", "4.00", "24.00", 1);
        ok("comma decimal", H + "A;1;12,5;5,5\n", "12.50", "0.69", "13.19", 1);
        ok("half up line", H + "A;1;0.005;0\n", "0.01", "0.00", "0.01", 1);
        // 10 x 1.99 = 19.90 -> remise 18.905 -> 18.91 ; TVA 20% = 3.782 -> 3.78
        ok("discount", H + "A;10;1.99;20\n", "18.91", "3.78", "22.69", 1);
        ok("discount threshold 9", H + "A;9;1;0\n", "9.00", "0.00", "9.00", 1);
        // TVA groupée : 3 x 0.10 à 5.5% => base 0.30 -> 0.0165 -> 0.02 (ligne par ligne : 0.01*3=0.03)
        ok("vat grouped", H + "A;1;0.10;5.5\nB;1;0.10;5,5\nC;1;0.10;5.50\n", "0.30", "0.02", "0.32", 3);
        ok("crlf, blanks, comments, spaces",
                "sku;quantity;unit_price;vat_rate\r\n\r\n# comment\r\n   \r\n A ; 3 ; 1.10 ; 10 \r\n",
                "3.30", "0.33", "3.63", 1);
        ok("multiple rates", H + "A;1;100;20\nB;2;50;10\nC;1;10;0\n", "210.00", "30.00", "240.00", 3);

        err("null", null, 1);
        err("empty", "", 1);
        err("bad header", "sku;qty;unit_price;vat_rate\nA;1;1;20\n", 1);
        err("header not first", "\n" + H + "A;1;1;20\n", 1);
        err("field count", H + "A;1;1\n", 2);
        err("field count trailing sep", H + "A;1;1;20;\n", 2);
        err("line numbering counts ignored", H + "\n# c\nA;1;1;20\nB;x;1;20\n", 5);
        err("qty zero", H + "A;0;1;20\n", 2);
        err("qty negative", H + "A;-1;1;20\n", 2);
        err("qty decimal", H + "A;1.5;1;20\n", 2);
        err("price negative", H + "A;1;-1;20\n", 2);
        err("price unreadable", H + "A;1;abc;20\n", 2);
        err("price empty", H + "A;1;;20\n", 2);
        err("rate invalid", H + "A;1;1;19.6\n", 2);
        err("rate unreadable", H + "A;1;1;x\n", 2);

        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void ok(String name, String csv, String ht, String vat, String ttc, int count) {
        try {
            Invoice inv = InvoiceCalculator.compute(csv);
            if (!inv.totalExclTax().toPlainString().equals(ht)
                    || !inv.totalVat().toPlainString().equals(vat)
                    || !inv.totalInclTax().toPlainString().equals(ttc)
                    || inv.lineCount() != count) {
                fail(name, "got " + inv);
            }
        } catch (RuntimeException e) {
            fail(name, "unexpected " + e);
        }
    }

    static void err(String name, String csv, int line) {
        try {
            Invoice inv = InvoiceCalculator.compute(csv);
            fail(name, "expected exception, got " + inv);
        } catch (InvoiceFormatException e) {
            if (e.lineNumber() != line) {
                fail(name, "expected line " + line + ", got " + e.lineNumber() + " (" + e.getMessage() + ")");
            }
        } catch (RuntimeException e) {
            fail(name, "wrong exception " + e);
        }
    }

    static void fail(String name, String msg) {
        failures++;
        System.out.println("FAIL " + name + ": " + msg);
    }
}
