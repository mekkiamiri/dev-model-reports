import com.example.config.ConfigException;
import com.example.config.ConfigResolver;

import java.util.List;
import java.util.Map;

public class ConfigResolverTest {
    static int failures = 0;

    static void eq(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + label + ": expected <" + expected + "> got <" + actual + ">");
        } else {
            System.out.println("ok   " + label);
        }
    }

    static void err(String label, String mustContain, String... layers) {
        try {
            Map<String, String> m = ConfigResolver.resolve(List.of(layers));
            failures++;
            System.out.println("FAIL " + label + ": no exception, got " + m);
        } catch (ConfigException e) {
            if (mustContain != null && !e.getMessage().contains(mustContain)) {
                failures++;
                System.out.println("FAIL " + label + ": message " + e.getMessage());
            } else {
                System.out.println("ok   " + label + " (" + e.getMessage() + ")");
            }
        }
    }

    static Map<String, String> r(String... layers) {
        return ConfigResolver.resolve(List.of(layers));
    }

    public static void main(String[] args) {
        eq("basic", Map.of("a", "1", "b", "x=y=z"), r("  a =  1 \n b= x=y=z "));
        eq("comments", Map.of("a", "1"), r("# c\n  ! d\n\n   \na=1\r\n"));
        eq("crlf", Map.of("a", "1", "b", "2"), r("a=1\r\nb=2\r\n"));
        eq("dup", Map.of("a", "2"), r("a=1\na=2"));
        eq("override", Map.of("a", "2", "b", "1"), r("a=1\nb=1", "a=2"));
        eq("unset", Map.of("b", "1"), r("a=1\nb=1", "a = !unset "));
        eq("unset redefine", Map.of("a", "3"), r("a=1", "a=!unset", "a=3"));
        eq("unset not exact", Map.of("a", "!unsetx"), r("a=!unsetx"));
        eq("empty value", Map.of("a", ""), r("a="));
        eq("chain", Map.of("a", "x", "b", "x-x", "c", "[x-x]"), r("c=[${b}]\nb=${a}-${a}\na=x"));
        eq("default", Map.of("a", "dflt"), r("a=${nope:-dflt}"));
        eq("default empty", Map.of("a", "<>"), r("a=<${nope:-}>"));
        eq("default literal", Map.of("a", "$${b"), r("a=${nope:-$${b}"));
        eq("default ignored", Map.of("a", "v", "b", "v"), r("a=v\nb=${a:-d}"));
        eq("dollar escape", Map.of("a", "${x}", "b", "$"), r("a=$${x}\nb=$$"));
        eq("lone dollar", Map.of("a", "$a $ 5$"), r("a=$a $ 5$"));
        eq("var from earlier layer", Map.of("a", "1", "b", "1"), r("a=1", "b=${a}"));
        err("no eq", null, "abc");
        err("empty key", null, " = 1");
        err("unknown", null, "a=${b}");
        err("unterminated", null, "a=${b");
        err("empty name", null, "a=${}");
        err("empty name default", null, "a=${:-x}");
        err("self cycle", "cycle", "a=${a}");
        err("indirect cycle", "cycle", "a=${b}\nb=${c}\nc=${a}");
        err("unset var", null, "a=1\nb=${a}", "a=!unset");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }
}
