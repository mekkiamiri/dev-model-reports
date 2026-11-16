import com.example.paging.Paginator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Tests d'acceptation cachés de la tâche bug-pagination. L'agent ne les voit pas. */
public class AcceptanceTests {
    static List<Integer> n(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    public static void main(String[] args) {
        Check.start();
        Check.test("cas du ticket : 20 éléments, 2 pages", () -> Check.eq(2, new Paginator<>(n(20), 10).pageCount()));
        Check.test("cas du ticket : pas de suivant en page 2", () -> Check.eq(false, new Paginator<>(n(20), 10).hasNext(2)));
        Check.test("cas du ticket : page 3 refusée", () ->
                Check.raises(IllegalArgumentException.class, () -> new Paginator<>(n(20), 10).page(3)));
        Check.test("25 éléments, 3 pages", () -> Check.eq(3, new Paginator<>(n(25), 10).pageCount()));
        Check.test("un seul élément", () -> Check.eq(1, new Paginator<>(n(1), 10).pageCount()));
        Check.test("taille de page 1", () -> Check.eq(7, new Paginator<>(n(7), 1).pageCount()));
        Check.test("liste exactement d'une page", () -> Check.eq(1, new Paginator<>(n(10), 10).pageCount()));
        Check.test("liste vide : une page", () -> Check.eq(1, new Paginator<>(List.of(), 10).pageCount()));
        Check.test("liste vide : page 1 vide", () -> Check.eq(List.of(), new Paginator<>(List.of(), 10).page(1)));
        Check.test("liste vide : page 2 refusée", () ->
                Check.raises(IllegalArgumentException.class, () -> new Paginator<>(List.of(), 10).page(2)));
        Check.test("page 0 refusée", () -> Check.raises(IllegalArgumentException.class, () -> new Paginator<>(n(5), 2).page(0)));
        Check.test("dernière page jamais vide", () -> {
            for (int size = 1; size <= 40; size++) {
                for (int ps = 1; ps <= 12; ps++) {
                    Paginator<Integer> p = new Paginator<>(n(size), ps);
                    Check.isTrue(!p.page(p.pageCount()).isEmpty(), "dernière page vide pour " + size + "/" + ps);
                }
            }
        });
        Check.test("chaque élément exactement une fois, dans l'ordre", () -> {
            for (int size = 0; size <= 30; size++) {
                for (int ps = 1; ps <= 8; ps++) {
                    Paginator<Integer> p = new Paginator<>(n(size), ps);
                    List<Integer> all = new ArrayList<>();
                    for (int i = 1; i <= p.pageCount(); i++) {
                        all.addAll(p.page(i));
                    }
                    Check.eq(n(size), all);
                }
            }
        });
        Check.test("hasNext cohérent", () -> {
            Paginator<Integer> p = new Paginator<>(n(30), 10);
            Check.eq(true, p.hasNext(1));
            Check.eq(true, p.hasNext(2));
            Check.eq(false, p.hasNext(3));
        });
        Check.test("totalItems inchangé", () -> Check.eq(20, new Paginator<>(n(20), 10).totalItems()));
        Check.test("taille négative refusée", () -> Check.raises(IllegalArgumentException.class, () -> new Paginator<>(n(3), -1)));
        Check.finish();
    }
}
