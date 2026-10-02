package android.util;

import java.util.ArrayList;
import java.util.List;

public class SparseArray<E> {
    private final List<E> values = new ArrayList<>();

    public void put(int key, E value) {
        values.add(value);
    }

    public int size() {
        return values.size();
    }

    public E valueAt(int index) {
        return values.get(index);
    }
}
