import com.example.config.ConfigException;
import com.example.config.ConfigResolver;
import java.util.List;
import java.util.Map;

/** Tests d'acceptation cachés de la tâche prd-config. L'agent ne les voit pas. */
public class AcceptanceTests {

    static Map<String, String> r(String... layers) {
        return ConfigResolver.resolve(List.of(layers));
    }

    static void fails(String... layers) {
        Check.raises(ConfigException.class, () -> r(layers));
    }

    public static void main(String[] args) {
        Check.test("une couche simple", () -> Check.eq(Map.of("a", "1", "b", "deux"), r("a=1\nb = deux\n")));
        Check.test("espaces autour de la clé et de la valeur", () -> Check.eq(Map.of("cle", "valeur avec espaces"), r("   cle   =   valeur avec espaces   ")));
        Check.test("seul le premier = sépare", () -> Check.eq(Map.of("url", "a=b=c"), r("url = a=b=c")));
        Check.test("valeur vide", () -> Check.eq(Map.of("a", ""), r("a =")));
        Check.test("commentaires # et !", () -> Check.eq(Map.of("a", "1"), r("# c\n  ! autre\n\n   \na=1")));
        Check.test("dernière valeur dans une couche", () -> Check.eq(Map.of("a", "2"), r("a=1\na=2")));
        Check.test("fins de ligne CRLF", () -> Check.eq(Map.of("a", "1", "b", "2"), r("a=1\r\nb=2\r\n")));
        Check.test("couche prioritaire", () -> Check.eq(Map.of("a", "local", "b", "défaut"), r("a=défaut\nb=défaut", "a=env", "a=local")));
        Check.test("!unset retire la clé", () -> Check.eq(Map.of("b", "2"), r("a=1\nb=2", "a = !unset ")));
        Check.test("!unset puis redéfinie", () -> Check.eq(Map.of("a", "3"), r("a=1", "a=!unset", "a=3")));
        Check.test("aucune couche", () -> Check.eq(Map.of(), r()));
        Check.test("variable simple", () -> Check.eq("http://h:8080", r("host=h\nport=8080\nurl=http://${host}:${port}").get("url")));
        Check.test("variables en chaîne", () -> Check.eq("x/y/z", r("a=x\nb=${a}/y\nc=${b}/z").get("c")));
        Check.test("variable définie dans une autre couche", () -> Check.eq("prod-db", r("db=${env}-db", "env=prod").get("db")));
        Check.test("variable résolue après fusion", () -> Check.eq("local", r("x=défaut\ny=${x}", "x=local").get("y")));
        Check.test("défaut utilisé", () -> Check.eq("5432", r("port=${PORT:-5432}").get("port")));
        Check.test("défaut ignoré si la clé existe", () -> Check.eq("1", r("p=1\nq=${p:-9}").get("q")));
        Check.test("défaut vide", () -> Check.eq("[]", r("v=[${absent:-}]").get("v")));
        Check.test("défaut littéral, non interprété", () -> Check.eq("$x", r("v=${absent:-$x}").get("v")));
        Check.test("défaut après !unset", () -> Check.eq("d", r("a=1\nb=${a:-d}", "a=!unset").get("b")));
        Check.test("$$ donne $", () -> Check.eq("prix: 5$", r("v=prix: 5$$").get("v")));
        Check.test("$${x} n'est pas remplacé", () -> Check.eq("${x}", r("x=1\nv=$${x}").get("v")));
        Check.test("$ seul gardé", () -> Check.eq("a$b et fin$", r("v=a$b et fin$").get("v")));
        Check.test("la même variable deux fois", () -> Check.eq("1-1", r("a=1\nb=${a}-${a}").get("b")));
        Check.test("erreur : ligne sans =", () -> fails("a=1\nbizarre"));
        Check.test("erreur : clé vide", () -> fails(" = 1"));
        Check.test("erreur : variable inconnue", () -> fails("a=${nope}"));
        Check.test("erreur : accolade non fermée", () -> fails("a=${b", "b=1"));
        Check.test("erreur : nom vide", () -> fails("a=${}"));
        Check.test("erreur : auto-référence", () -> {
            ConfigException e = Check.raises(ConfigException.class, () -> r("a=${a}"));
            Check.isTrue(String.valueOf(e.getMessage()).contains("cycle"), "message sans « cycle » : " + e.getMessage());
        });
        Check.test("erreur : cycle indirect", () -> {
            ConfigException e = Check.raises(ConfigException.class, () -> r("a=${b}\nb=${c}\nc=x${a}"));
            Check.isTrue(String.valueOf(e.getMessage()).contains("cycle"), "message sans « cycle » : " + e.getMessage());
        });
        Check.test("pas de faux cycle sur un losange", () -> Check.eq("1+1", r("base=1\nl=${base}\nr=${base}\ntop=${l}+${r}").get("top")));
        Check.finish();
    }
}
