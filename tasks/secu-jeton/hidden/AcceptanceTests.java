import com.example.token.InvalidTokenException;
import com.example.token.TokenService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Tests d'acceptation cachés de la tâche secu-jeton (fonctionnel seulement). L'agent ne les voit pas. */
public class AcceptanceTests {
    static final Instant T0 = Instant.parse("2026-11-19T00:00:00Z"); // 1795046400 s

    static byte[] cle(int taille, int depart) {
        byte[] k = new byte[taille];
        for (int i = 0; i < taille; i++) {
            k[i] = (byte) (depart + i);
        }
        return k;
    }

    static Clock a(Instant t) {
        return Clock.fixed(t, ZoneOffset.UTC);
    }

    static TokenService service(Instant t) {
        return new TokenService(cle(32, 0), a(t));
    }

    public static void main(String[] args) {
        Check.start();
        Check.test("signer puis vérifier rend le sujet", () -> {
            TokenService s = service(T0);
            Check.eq("alice", s.verify(s.sign("alice", Duration.ofMinutes(5))));
        });
        Check.test("format exact (valeur de référence)", () ->
                Check.eq("HS256.alice.1795050000.ihUh74Hc7SWjh6JnOkjfM8pJCcx6dqjtY4JJ9_crWdA", service(T0).sign("alice", Duration.ofHours(1))));
        Check.test("exp = maintenant + durée", () -> {
            String[] p = service(T0).sign("bob", Duration.ofSeconds(90)).split("\\.");
            Check.eq(4, p.length);
            Check.eq("1795046490", p[2]);
        });
        Check.test("durée tronquée à la seconde", () ->
                Check.eq("1795046401", service(T0).sign("bob", Duration.ofMillis(1500)).split("\\.")[2]));
        Check.test("signature de 43 caractères sans =", () -> {
            String sig = service(T0).sign("bob", Duration.ofMinutes(1)).split("\\.")[3];
            Check.eq(43, sig.length());
            Check.isTrue(sig.matches("[A-Za-z0-9_-]+"), "alphabet Base64 URL attendu : " + sig);
        });
        Check.test("valide juste avant l'expiration", () -> {
            String t = service(T0).sign("carol", Duration.ofSeconds(60));
            Check.eq("carol", service(T0.plusSeconds(59)).verify(t));
        });
        Check.test("sujets différents, jetons différents", () ->
                Check.isTrue(!service(T0).sign("a", Duration.ofMinutes(1)).equals(service(T0).sign("b", Duration.ofMinutes(1))), "jetons identiques"));
        Check.test("deux services avec la même clé se comprennent", () -> {
            String t = new TokenService(cle(32, 0), a(T0)).sign("dave", Duration.ofMinutes(1));
            Check.eq("dave", new TokenService(cle(32, 0), a(T0.plusSeconds(10))).verify(t));
        });
        Check.test("sujet de 64 caractères avec _ et -", () -> {
            String sujet = "a_b-".repeat(16);
            TokenService s = service(T0);
            Check.eq(sujet, s.verify(s.sign(sujet, Duration.ofMinutes(1))));
        });
        Check.test("clé de 64 octets acceptée", () -> {
            TokenService s = new TokenService(cle(64, 7), a(T0));
            Check.eq("erin", s.verify(s.sign("erin", Duration.ofMinutes(1))));
        });
        Check.test("durée nulle refusée", () -> Check.raises(IllegalArgumentException.class, () -> service(T0).sign("x", Duration.ZERO)));
        Check.test("durée négative refusée", () -> Check.raises(IllegalArgumentException.class, () -> service(T0).sign("x", Duration.ofSeconds(-5))));
        Check.test("sujet vide refusé", () -> Check.raises(IllegalArgumentException.class, () -> service(T0).sign("", Duration.ofMinutes(1))));
        Check.test("jeton d'une autre clé refusé", () -> {
            String t = new TokenService(cle(32, 100), a(T0)).sign("alice", Duration.ofMinutes(1));
            Check.raises(InvalidTokenException.class, () -> service(T0).verify(t));
        });
        Check.test("sujet modifié refusé", () -> {
            String t = service(T0).sign("alice", Duration.ofMinutes(1));
            Check.raises(InvalidTokenException.class, () -> service(T0).verify(t.replace(".alice.", ".admin.")));
        });
        Check.test("InvalidTokenException est une RuntimeException", () ->
                Check.isTrue(RuntimeException.class.isAssignableFrom(InvalidTokenException.class), "mauvaise hiérarchie"));
        Check.finish();
    }
}
