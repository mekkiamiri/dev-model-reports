import com.example.paging.Paginator;
import java.util.List;
import java.util.stream.IntStream;

public class VisibleTests {
    static List<Integer> n(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    public static void main(String[] args) {
        Check.test("25 éléments, 3 pages", () -> Check.eq(3, new Paginator<>(n(25), 10).pageCount()));
        Check.test("dernière page partielle", () -> Check.eq(List.of(21, 22, 23, 24, 25), new Paginator<>(n(25), 10).page(3)));
        Check.test("liste vide : une page vide", () -> {
            Paginator<Integer> p = new Paginator<>(List.of(), 10);
            Check.eq(1, p.pageCount());
            Check.eq(List.of(), p.page(1));
            Check.eq(false, p.hasNext(1));
        });
        Check.test("taille nulle refusée", () -> Check.raises(IllegalArgumentException.class, () -> new Paginator<>(n(3), 0)));
        Check.finish();
    }
}
