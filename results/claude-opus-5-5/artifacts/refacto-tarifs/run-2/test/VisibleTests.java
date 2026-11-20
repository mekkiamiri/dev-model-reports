import com.example.pricing.PriceService;
import java.math.BigDecimal;

public class VisibleTests {
    static final PriceService S = new PriceService();

    static String p(String type, int q, String unit, boolean first) {
        return S.price(type, q, new BigDecimal(unit), first).toPlainString();
    }

    public static void main(String[] args) {
        Check.test("standard simple", () -> Check.eq("30.00", p("STANDARD", 3, "10", false)));
        Check.test("standard première commande", () -> Check.eq("50.00", p("STANDARD", 6, "10", true)));
        Check.test("premium volume", () -> Check.eq("427.50", p("PREMIUM", 50, "10", false)));
        Check.test("grossiste minimum", () -> Check.raises(IllegalArgumentException.class, () -> p("WHOLESALE", 9, "10", false)));
        Check.test("type inconnu", () -> Check.raises(IllegalArgumentException.class, () -> p("VIP", 1, "10", false)));
        Check.finish();
    }
}
