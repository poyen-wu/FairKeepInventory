package com.fairkeepinventory.util;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * A Map that:
 *  - Orders keys using orderComparator (for iteration / "first" element).
 *  - Treats keys as equal using equalsPredicate (for get/put/remove/merge).
 *
 * Ordering is stable with respect to orderComparator:
 * if orderComparator.compare(a, b) == 0, insertion order decides.
 */
public final class StableOrderingMap<K, V> extends AbstractMap<K, V> {

    private final Comparator<? super K> orderComparator;
    private final BiPredicate<? super K, ? super K> equalsPredicate;

    private final List<Entry<K, V>> entries = new ArrayList<>();

    public StableOrderingMap(
            Comparator<? super K> orderComparator,
            BiPredicate<? super K, ? super K> equalsPredicate
    ) {
        this.orderComparator = Objects.requireNonNull(orderComparator, "orderComparator");
        this.equalsPredicate = Objects.requireNonNull(equalsPredicate, "equalsPredicate");
    }

    private static final class Entry<K, V> implements Map.Entry<K, V> {
        private final K key;
        private V value;

        private Entry(K key, V value) {
            this.key = key;
            this.value = value;
        }

        @Override public K getKey() {
            return key;
        }

        @Override public V getValue() {
            return value;
        }

        @Override public V setValue(V newValue) {
            V old = this.value;
            this.value = newValue;
            return old;
        }
    }

    private int indexOfKey(Object keyObj) {
        if (keyObj == null) {
            return -1;
        }
        @SuppressWarnings("unchecked")
        K key = (K) keyObj;

        for (int i = 0; i < entries.size(); i++) {
            K existingKey = entries.get(i).getKey();
            if (equalsPredicate.test(existingKey, key)) {
                return i;
            }
        }
        return -1;
    }

    private int findInsertionIndex(K key) {
        int size = entries.size();

        for (int i = 0; i < size; i++) {
            K existingKey = entries.get(i).getKey();
            int cmp = orderComparator.compare(key, existingKey);

            // Insert before the first element that is strictly "greater".
            if (cmp < 0) {
                return i;
            }
            // If cmp == 0, we skip over it to keep existing entries first
            // (stable ordering among ties).
        }

        // If nothing was "greater", insert at the end.
        return size;
    }

    @Override
    public V get(Object key) {
        int idx = indexOfKey(key);
        return idx >= 0 ? entries.get(idx).getValue() : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOfKey(key) >= 0;
    }

    @Override
    public V put(K key, V value) {
        int idx = indexOfKey(key);
        if (idx >= 0) {
            // Update existing entry; order is unchanged.
            return entries.get(idx).setValue(value);
        }

        // New key: insert in stable order.
        int insertAt = findInsertionIndex(key);
        entries.add(insertAt, new Entry<>(key, value));
        return null;
    }

    @Override
    public V remove(Object key) {
        int idx = indexOfKey(key);
        if (idx < 0) {
            return null;
        }
        return entries.remove(idx).getValue();
    }

    @Override
    public void clear() {
        entries.clear();
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        // Backed by 'entries', in the current stable sorted order.
        return new AbstractSet<Map.Entry<K, V>>() {
            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
                return new Iterator<Map.Entry<K, V>>() {
                    private int cursor = 0;
                    private int lastReturned = -1;

                    @Override
                    public boolean hasNext() {
                        return cursor < entries.size();
                    }

                    @Override
                    public Map.Entry<K, V> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        lastReturned = cursor++;
                        return entries.get(lastReturned);
                    }

                    @Override
                    public void remove() {
                        if (lastReturned < 0) {
                            throw new IllegalStateException();
                        }
                        entries.remove(lastReturned);
                        cursor = lastReturned;
                        lastReturned = -1;
                    }
                };
            }

            @Override
            public int size() {
                return entries.size();
            }
        };
    }

    @Override
    public Collection<V> values() {
        return new AbstractCollection<V>() {
            @Override
            public Iterator<V> iterator() {
                Iterator<Map.Entry<K, V>> it = entrySet().iterator();
                return new Iterator<V>() {
                    @Override
                    public boolean hasNext() {
                        return it.hasNext();
                    }

                    @Override
                    public V next() {
                        return it.next().getValue();
                    }

                    @Override
                    public void remove() {
                        it.remove();
                    }
                };
            }

            @Override
            public int size() {
                return entries.size();
            }
        };
    }
}
