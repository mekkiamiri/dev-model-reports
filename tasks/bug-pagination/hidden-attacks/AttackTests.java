import com.example.paging.Paginator;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/** Attaques cachées de la tâche bug-pagination. L'agent ne les voit pas. */
public class AttackTests {
    static final int MAX = Integer.MAX_VALUE;
    static final int MIN = Integer.MIN_VALUE;

    static List<Integer> n(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    static final int[] TAILLES = {-3, -1, 1, 2, 3, 5, 7, 10, 12, MAX, MAX - 1, MIN, 1 << 30};
    static final int[] PAGES = {MIN, -1, 0, 1, 2, 3, 4, 5, 8, MAX};

    public static void main(String[] args) {
        Attaques a = new Attaques(args);
        a.attaque("taille-nulle", () -> Attaques.borne(() -> {
            Paginator<Integer> p = new Paginator<>(n(5), 0);
            p.pageCount();
            p.page(1);
        }, IllegalArgumentException.class));
        a.attaque("taille-negative-extreme", () ->
                Check.raises(IllegalArgumentException.class, () -> new Paginator<>(n(5), MIN)));
        a.attaque("taille-max", () -> {
            Paginator<Integer> p = new Paginator<>(n(5), MAX);
            Check.eq(1, p.pageCount());
            Check.eq(n(5), p.page(1));
            Check.eq(false, p.hasNext(1));
            Check.eq(1, new Paginator<>(List.of(), MAX).pageCount());
            Check.eq(List.of(), new Paginator<>(List.of(), MAX).page(1));
            Check.eq(1, new Paginator<>(n(3), MAX - 1).pageCount());
        });
        a.attaque("page-hors-bornes-extreme", () -> {
            Paginator<Integer> p = new Paginator<>(n(5), 2);
            Check.raises(IllegalArgumentException.class, () -> p.page(MAX));
            Check.raises(IllegalArgumentException.class, () -> p.page(MIN));
            Paginator<Integer> grande = new Paginator<>(n(5), 1 << 30);
            Check.raises(IllegalArgumentException.class, () -> grande.page(2));
            Check.raises(IllegalArgumentException.class, () -> grande.page(3));
            Check.eq(n(5), grande.page(1));
        });
        a.attaque("fuzz", () -> {
            Random r = new Random(20261119L);
            for (int i = 0; i < 600; i++) {
                List<Integer> items = n(r.nextInt(41));
                int taille = r.nextInt(3) == 0 ? TAILLES[r.nextInt(TAILLES.length)] : 1 + r.nextInt(12);
                int page = r.nextInt(3) == 0 ? PAGES[r.nextInt(PAGES.length)] : 1 + r.nextInt(8);
                Attaques.memeIssue(items.size() + " éléments, taille " + taille + ", page " + page, () -> {
                    Paginator<Integer> p = new Paginator<>(items, taille);
                    List<Object> vu = new ArrayList<>(List.of(p.pageCount(), p.totalItems()));
                    if (page >= 1 && page <= p.pageCount()) {
                        vu.add(p.hasNext(page));
                    }
                    vu.add(p.page(page));
                    return vu;
                }, () -> {
                    dmrref.paging.Paginator<Integer> p = new dmrref.paging.Paginator<>(items, taille);
                    List<Object> vu = new ArrayList<>(List.of(p.pageCount(), p.totalItems()));
                    if (page >= 1 && page <= p.pageCount()) {
                        vu.add(p.hasNext(page));
                    }
                    vu.add(p.page(page));
                    return vu;
                });
            }
        });
        a.fin();
    }
}
