package com.example.paging;

import java.util.List;

/**
 * Découpe une liste en pages numérotées à partir de 1.
 *
 * <p>Règles : une liste vide a une page, vide (l'écran affiche « Page 1 sur 1 »).
 * Demander une page hors de [1, pageCount()] lève IllegalArgumentException.
 */
public final class Paginator<T> {
    private final List<T> items;
    private final int pageSize;

    public Paginator(List<T> items, int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize doit être positif");
        }
        this.items = List.copyOf(items);
        this.pageSize = pageSize;
    }

    public int pageCount() {
        int size = items.size();
        if (size == 0) {
            return 1; // une liste vide a une page, vide
        }
        // Division arrondie au supérieur, sans risque de débordement d'int.
        return size / pageSize + (size % pageSize == 0 ? 0 : 1);
    }

    public List<T> page(int number) {
        if (number < 1 || number > pageCount()) {
            throw new IllegalArgumentException("page hors limites : " + number);
        }
        int from = (number - 1) * pageSize;
        int to = from + Math.min(pageSize, items.size() - from);
        return items.subList(from, to);
    }

    public boolean hasNext(int number) {
        return number < pageCount();
    }

    public int totalItems() {
        return items.size();
    }
}
