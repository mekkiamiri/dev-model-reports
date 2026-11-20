import com.example.config.ConfigException;
import com.example.config.ConfigResolver;

import java.util.List;
import java.util.Map;

public class ConfigResolverTest {
    static int failures = 0;

    static void eq(String label, Object expected, Object actual) {
        if (!expected.equals(actual)) {
            failures++;
            System.out.println("FAIL " + label + ": expected " + expected + " got " + actual);
        } else {
            System.out.println("ok   " + label);
        }
    }

    static void err(String label, String contains, String... layers) {
        try {
            Map<String, String> r = ConfigResolver.resolve(List.of(layers));
            failures++;
            System.out.println("FAIL " + label + ": no exception, got " + r);
        } catch (ConfigException e) {
            if (contains != null && !e.getMessage().contains(contains)) {
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
        eq("basic", Map.of("a", "1", "b", "x=y"), r("a = 1\n b=x=y  \n"));
        eq("crlf+comments", Map.of("a", "2"), r("# c\r\n  ! d\r\n\r\n   \r\na=1\r\na=2\r\n"));
        eq("override", Map.of("a", "2", "b", "1"), r("a=1\nb=1", "a=2"));
        eq("unset", Map.of("b", "1"), r("a=1\nb=1", "a = !unset "));
        eq("unset redefine", Map.of("a", "3"), r("a=1", "a=!unset", "a=3"));
        eq("unset not exact", Map.of("a", "!unset x"), r("a=!unset x"));
        eq("unset missing key", Map.of(), r("a=!unset"));
        eq("chain", Map.of("a", "x", "b", "x-x", "c", "[x-x]"), r("c=[${b}]\nb=${a}-${a}\na=x"));
        eq("default", Map.of("a", "def"), r("a=${nope:-def}"));
        eq("default literal", Map.of("a", "${x}"), r("a=${nope:-${x}}"));
        eq("default not interpreted", Map.of("a", "$x$$"), r("a=${nope:-$x$$}"));
        eq("default empty", Map.of("a", "[]"), r("a=[${nope:-}]"));
        eq("default unused", Map.of("a", "v", "b", "v"), r("a=v\nb=${a:-d}"));
        eq("default after unset", Map.of("b", "d"), r("a=v\nb=${a:-d}", "a=!unset"));
        eq("dollar dollar", Map.of("a", "${x}", "b", "$"), r("a=$${x}\nb=$$"));
        eq("lone dollar", Map.of("a", "$x $ end$"), r("a=$x $ end$"));
        eq("empty value", Map.of("a", "", "b", "<>"), r("a=\nb=<${a}>"));
        err("no =", null, "a=1\nbad line");
        err("empty key", null, " = 1");
        err("unknown", null, "a=${b}");
        err("unterminated", null, "a=${b");
        err("empty name", null, "a=${}");
        err("empty name default", null, "a=${:-x}");
        err("self cycle", "cycle", "a=${a}");
        err("indirect cycle", "cycle", "a=${b}\nb=${c}\nc=${a}");
        err("cycle via default", "cycle", "a=${b:-x}\nb=${a}");
        err("cycle unused key", "cycle", "z=1\na=${b}\nb=${a}");
        System.out.println(failures == 0 ? "ALL OK" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }
}
