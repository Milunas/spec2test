package io.github.spec2test.runtime;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Runtime helper methods for generated test code.
 *
 * TLA+ is value-based (immutable). Java uses mutable collections.
 * These methods provide value-semantic operations that return new collections
 * without modifying the originals — bridging the semantic gap.
 *
 * All operations produce deep copies to ensure TLA+-style value semantics.
 */
public final class Spec2TestRuntime {

    private Spec2TestRuntime() {}

    // ── Set Operations ───────────────────────────────────────────────────

    /** S ∪ T — set union (returns new set) */
    public static <T> Set<T> union(Set<T> a, Set<T> b) {
        var result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    /** S ∩ T — set intersection (returns new set) */
    public static <T> Set<T> intersect(Set<T> a, Set<T> b) {
        var result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    /** S \ T — set difference (returns new set) */
    public static <T> Set<T> setMinus(Set<T> a, Set<T> b) {
        var result = new HashSet<>(a);
        result.removeAll(b);
        return result;
    }

    /** SUBSET S — power set */
    public static <T> Set<Set<T>> powerSet(Set<T> set) {
        var result = new HashSet<Set<T>>();
        result.add(new HashSet<>());
        for (T elem : set) {
            var newSubsets = new HashSet<Set<T>>();
            for (Set<T> subset : result) {
                var extended = new HashSet<>(subset);
                extended.add(elem);
                newSubsets.add(extended);
            }
            result.addAll(newSubsets);
        }
        return result;
    }

    /** UNION S — union of all sets in a set of sets */
    public static <T> Set<T> unionAll(Set<Set<T>> sets) {
        var result = new HashSet<T>();
        for (Set<T> s : sets) {
            result.addAll(s);
        }
        return result;
    }

    /** S × T — Cartesian product (returns set of pairs/lists) */
    @SafeVarargs
    public static <T> Set<List<T>> cartesianProduct(Set<T>... sets) {
        Set<List<T>> result = new HashSet<>();
        result.add(new ArrayList<>());
        for (Set<T> set : sets) {
            var newResult = new HashSet<List<T>>();
            for (List<T> existing : result) {
                for (T elem : set) {
                    var extended = new ArrayList<>(existing);
                    extended.add(elem);
                    newResult.add(extended);
                }
            }
            result = newResult;
        }
        return result;
    }

    // ── Sequence Operations ──────────────────────────────────────────────

    /** Append(s, e) — returns new list with element appended */
    public static <T> List<T> append(List<T> seq, T elem) {
        var result = new ArrayList<>(seq);
        result.add(elem);
        return result;
    }

    /** Tail(s) — returns new list without the first element */
    public static <T> List<T> tail(List<T> seq) {
        if (seq.isEmpty()) throw new IllegalStateException("Tail of empty sequence");
        return new ArrayList<>(seq.subList(1, seq.size()));
    }

    /** SubSeq(s, m, n) — 1-indexed subsequence */
    public static <T> List<T> subSeq(List<T> seq, int from, int to) {
        return new ArrayList<>(seq.subList(Math.max(0, from - 1), Math.min(seq.size(), to)));
    }

    /** s \o t — sequence concatenation */
    public static <T> List<T> seqConcat(List<T> a, List<T> b) {
        var result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    /** SelectSeq(s, test) — filter sequence elements */
    public static <T> List<T> selectSeq(List<T> seq, java.util.function.Predicate<T> test) {
        return seq.stream().filter(test).collect(Collectors.toList());
    }

    // ── Function (Map) Operations ────────────────────────────────────────

    /** [f EXCEPT ![k] = v] — returns new map with one key updated */
    public static <K, V> Map<K, V> mapWith(Map<K, V> base, K key, V value) {
        var result = new HashMap<>(base);
        result.put(key, value);
        return result;
    }

    /** [f EXCEPT ![k1] = v1, ![k2] = v2] — multiple updates */
    @SafeVarargs
    public static <K, V> Map<K, V> mapWith(Map<K, V> base, Object... keyValuePairs) {
        @SuppressWarnings("unchecked")
        var result = new HashMap<K, V>(base);
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            K key = (K) keyValuePairs[i];
            @SuppressWarnings("unchecked")
            V value = (V) keyValuePairs[i + 1];
            result.put(key, value);
        }
        return result;
    }

    // ── Deep Copy ────────────────────────────────────────────────────────

    /** Deep copy a state value (collections are recursively copied) */
    @SuppressWarnings("unchecked")
    public static <T> T deepCopy(T value) {
        if (value instanceof Set<?> set) {
            return (T) new HashSet<>(set);
        }
        if (value instanceof List<?> list) {
            return (T) new ArrayList<>(list);
        }
        if (value instanceof Map<?, ?> map) {
            return (T) new HashMap<>(map);
        }
        return value; // Primitives/immutables are returned as-is
    }
}
