package com.demo.thymeleaf.utils;

import org.thymeleaf.expression.Uris;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * A single {@code QueryString} instance is guaranteed to be in a valid state and can only have 1 operation performed.
 *
 * <p>All methods return the modified query string rather than return an intermediate state. {@code QueryString} acts
 * as the private implementation to the publicly available methods exposed to thymeleaf in {@code QueryStringHelper}.
 * </p>
 */
public class QueryString {

    // The unescaped query string
    private String originalQueryString;

    // Performs query string escape/unescaping.
    private Uris uris;

    // Representation of the query string to allow easier modifications across the range of operations.
    private Map<String, List<KeyValueIndex>> state;

    private QueryString(String originalQueryString, Uris uris) {
        this.originalQueryString = uris.unescapeQueryParam(originalQueryString);
        this.uris = uris;
        state = createState();
    }

    /**
     * Constructs a valid {@code QueryString} instance otherwise throws an {@code IllegalArgumentException}.
     *
     * @param queryString The query string with at least 1 key=value pair.
     * @param uris        Handles escaping/unescaping the string
     * @return A valid instance
     * @throws IllegalArgumentException If there is not at least 1 key=value pair.
     */
    public static QueryString of(String queryString, Uris uris) throws IllegalArgumentException {
        if (!hasAtLeastOneKeyValuePair(queryString)) {
            throw new IllegalArgumentException("Query string must have at least key=value pair");
        }
        // unescape every percent-encoded (%HH) sequences present in input to avoid re escaping.
        return new QueryString(queryString, uris);
    }

    /**
     * Validate initial query string prior to constructing instance.
     */
    private static boolean isValidPair(String pair) {
        String[] tokens = pair.split("=");
        // Need to handle edge case where if you split '=value', index 0 is an empty string.
        return tokens.length == 2 && !tokens[0].isEmpty();
    }

    /**
     * Validate initial query string prior to constructing instance.
     */
    private static boolean hasAtLeastOneKeyValuePair(String queryString) {
        if (queryString == null) {
            return false;
        }

        String[] multiplePairs = queryString.split("&");
        if (multiplePairs.length == 0) {
            // query string may only be a single key=value
            return isValidPair(queryString);
        }
        return Arrays.stream(multiplePairs).allMatch(QueryString::isValidPair);
    }

    /**
     * @return The unescaped query string.
     */
    public String getOriginalQueryString() {
        return originalQueryString;
    }

    /**
     * Transforms the internal state map back into a query string.
     *
     * <p>The fundamental concept is to use flatMap over the {@code KeyValueIndex} values. The actual hash map keys
     * are irrelevant given each value has the key embedded inside allowing flatMap to be used like this. If the flatMap
     * stream is empty, the key isn't included which is perfect for simplifying delete operations.</p>
     *
     * @return The new query string.
     */
    public String reconstructQueryString() {
        return state.entrySet().stream()
                .flatMap(e -> e.getValue().stream())
                .sorted(Comparator.comparing(KeyValueIndex::getOverallIndex))
                .map(keyValueIndex -> keyValueIndex.keyValue.escape(uris::escapeQueryParam))
                .collect(Collectors.joining("&"));
    }

    /**
     * Replaces the first occurrence of key with value.
     *
     * @param key   The target key.
     * @param value The new value.
     * @return The new query string.
     */
    public String replaceFirst(String key, String value) {
        if (key == null || value == null) {
            return reconstructQueryString();
        }
        List<KeyValueIndex> indices = state.get(key);
        if (indices != null && !indices.isEmpty()) {
            indices.set(0, indices.get(0).updateValue(value));
        }
        return reconstructQueryString();
    }

    /**
     * Replaces the first N values of the given key with the corresponding value.
     *
     * @param key    The target key.
     * @param values The replacement values corresponding to the relative value indexes for the target key.
     * @return The new query string.
     */
    public String replaceN(String key, List<String> values) {
        List<KeyValueIndex> indices = state.get(key);
        if (indices != null) {
            for (int i = 0; i < indices.size() && i < values.size(); i++) {
                // Only replace legal indexes
                indices.set(i, indices.get(i).updateValue(values.get(i)));
            }
        }
        return reconstructQueryString();
    }

    /**
     * The list of {@code StateChangeInstruction}s determines the replacement values.
     * This method is separated to clean up {@link #replaceNth(Map)}
     *
     * @param instructions Instructions for creating the new state.
     * @return The resulting query string.
     */
    private String applyStateChangeInstructions(List<StateChangeInstruction> instructions) {
        for (StateChangeInstruction instruction : instructions) {
            List<KeyValueIndex> indices = state.get(instruction.key);
            if (indices != null) {
                boolean isWithinBounds = instruction.relativeIndex >= 0 && instruction.relativeIndex < indices.size();
                if (isWithinBounds) {
                    KeyValueIndex replacement = indices.get(instruction.relativeIndex).updateValue(instruction.newValue);
                    indices.set(instruction.relativeIndex, replacement);
                }
            }
        }
        return reconstructQueryString();
    }

    /**
     * <ol>
     * <li>Accepts a map of instructions - {@code {sort:{0:'stars,asc', 1:'address,desc', 2: 'country,asc'}}}</li>
     * <li>Does the sing and cast dance to convert into a list of {@code StateChangeInstruction}s for easier handling.
     * <pre>
     *         [{key='sort', relativeIndex=0, newValue='stars,asc'},
     *          {key='sort', relativeIndex=1, newValue='address,desc'},
     *          {key='sort', relativeIndex=2, newValue='country,asc'}]
     *     </pre>
     * </li>
     * <li>{@link #applyStateChangeInstructions(List)} performs the actual replacements and returns the new query string</li>
     * </ol>
     *
     * @param stateChangeInstructions The map of instructions produced by the SpEL expression.
     * @return The new query string.
     */
    public String replaceNth(Map<Object, Map<Object, Object>> stateChangeInstructions) {
        if (stateChangeInstructions == null) {
            return reconstructQueryString();
        }

        // Casts into domain object for handling by other methods.
        List<StateChangeInstruction> instructions = stateChangeInstructions.entrySet().stream()
                .flatMap(entry -> {
                    // This will be the top level key to modify such as 'sort'
                    String key = (String) entry.getKey();

                    // Refers to the inner map containing the actual keys to update.
                    // {0: 'stars,asc', 1: 'address,desc', 2: 'country,asc'}
                    Map<Object, Object> keyValueStateChanges = entry.getValue();


                    return keyValueStateChanges.entrySet().stream()
                            .map(entry2 -> new StateChangeInstruction(key, (int) entry2.getKey(), (String) entry2.getValue()));
                })
                .collect(Collectors.toList());

        return applyStateChangeInstructions(instructions);
    }

    /**
     * Removes the first occurrence of the supplied key. If there are no duplicate keys, the entire key will be removed.
     *
     * @param key The target key.
     * @return The new query string.
     */
    public String removeFirst(String key) {
        List<KeyValueIndex> indices = state.get(key);
        if (indices != null && !indices.isEmpty()) {
            indices.remove(0);
        }
        return reconstructQueryString();
    }

    /**
     * Removes every key and associated values.
     *
     * @param keys The target keys.
     * @return The new query string.
     */
    public String removeAll(List<String> keys) {
        if (keys != null) {
            keys.forEach(state::remove);
        }
        return reconstructQueryString();
    }

    /**
     * The same concept as dropN in functional languages where the first n occurrences of the target key are removed.
     *
     * @param key The target key.
     * @param n Total occurrences of the key to remove.
     * @return The new query string.
     */
    public String removeN(String key, int n) {
        List<KeyValueIndex> indices = state.get(key);

        if (n <= 0) {
            return reconstructQueryString();
        }

        if (indices != null) {
            if (n >= indices.size()) {
                // enhancement to remove all in O(1) to avoid iterating and deleting each element.
                return removeAll(new ArrayList<>(Collections.singletonList(key)));
            } else {
                int removedCount = 0;
                Iterator<KeyValueIndex> iterator = indices.iterator();
                while (iterator.hasNext()) {
                    iterator.next();
                    if (removedCount < n) {
                        iterator.remove();
                        removedCount++;
                    }
                }
            }
        }
        return reconstructQueryString();
    }


    /**
     * Removes the nth relative index of the given key. Example of relative index is below where you simply visualise
     * a key as having all its values contained in an array and providing the corresponding index.
     *
     * <pre>
     *     a=100&b=200&a=300
     *
     *           0     1   (relative indexes)
     *     a = [100, 300]
     *
     *     removeNth('a', 1) => a=100&b=200
     * </pre>
     *
     * @param key The target key.
     * @param nthIndex The relative index to remove.
     * @return The new query string.
     */
    public String removeNth(String key, int nthIndex) {
        List<KeyValueIndex> indices = state.get(key);
        if (indices != null && !indices.isEmpty() && nthIndex >= 0 && nthIndex < indices.size()) {
            indices.remove(nthIndex);
        }
        return reconstructQueryString();
    }

    /**
     * Similar to {@link #removeNth(String, int)}, except multiple values can be removed for a matching key through
     * providing many relative indexes while maintaining their ordering in the original query string.
     *
     * <pre>
     *     a=100&b=200&a=300&a=500
     *
     *           0     1    2   (relative indexes)
     *     a = [100, 300, 500]
     *
     *     removeNth('a', [0, 2]) => b=200&a=300
     * </pre>
     *
     * @param key The target key.
     * @param relativeIndexes The relative indexes to remove.
     * @return The new query string.
     */
    public String removeManyNth(String key, List<Integer> relativeIndexes) {
        List<KeyValueIndex> indices = state.get(key);
        if (indices != null && !indices.isEmpty()) {
            int index = 0;
            Iterator<KeyValueIndex> iterator = indices.iterator();
            while (iterator.hasNext()) {
                iterator.next();

                if (relativeIndexes.contains(index)) {
                    iterator.remove();
                }
                index++;
            }
        }
        return reconstructQueryString();
    }

    /**
     * Removes the target key if it has a matching value
     *
     * <pre>
     *     a=500&b=700&a=700
     *
     *     removeKeyMatchingValue('a', '700') => a=500&b=700
     * </pre>
     * @param key The target key to delete if the value matches.
     * @param valueMatch The value to match triggering deletion.
     * @return The new query string.
     */
    public String removeKeyMatchingValue(String key, String valueMatch) {
        List<KeyValueIndex> indices = state.get(key);

        if (indices != null) {
            List<KeyValueIndex> newIndices = indices.stream()
                    .filter(kv -> !kv.keyValue.value.equals(valueMatch))
                    .collect(Collectors.toList());

            state.put(key, newIndices);
        }

        return reconstructQueryString();
    }

    /**
     * Similar to {@link #removeKeyMatchingValue(String, String)} except ALL keys are deleted if they have
     * a matching value.
     *
     * <pre>
     *     a=500&b=700&a=700
     *
     *     removeKeyMatchingValue('700') => a=500
     * </pre>
     * @param valueMatch The value to match triggering deletion.
     * @return The new query string.
     */
    public String removeAnyKeyMatchingValue(String valueMatch) {
        for (Map.Entry<String, List<KeyValueIndex>> entry : state.entrySet()) {
            List<KeyValueIndex> newIndices = entry.getValue().stream()
                    .filter(kv -> !kv.keyValue.value.equals((valueMatch)))
                    .collect(Collectors.toList());

            state.put(entry.getKey(), newIndices);
        }
        return reconstructQueryString();
    }

    /**
     * Gets the value associated with the first occurrence of the given key.
     *
     * <pre>
     *     a=500&b=600&a=700
     *
     *     getFirstValue('a') => 500
     * </pre>
     * @param key The target key.
     * @return The associated value if found otherwise null.
     */
    public String getFirstValue(String key) {
        List<KeyValueIndex> indices = state.get(key);
        if (indices == null || indices.isEmpty()) {
            return null;
        }
        return indices.get(0).keyValue.value;
    }

    /**
     * Gets all value associated with the the given key.
     *
     * <pre>
     *     a=500&b=600&a=700
     *
     *     getFirstValue('a') => [500, 700]
     * </pre>
     * @param key The target key.
     * @return The associated values if found otherwise an empty list.
     */
    public List<String> getAllValues(String key) {
        List<KeyValueIndex> indices = state.get(key);
        if (indices == null || indices.isEmpty()) {
            return new ArrayList<>();
        }
        return indices.stream().map(kv -> kv.keyValue.value).collect(Collectors.toList());
    }

    /**
     * Adds the given key and value to the end of the query string.
     *
     * <pre>
     *     a=500&b=600
     *     add('c', '700') => a=500&b=600&c=700
     * </pre>
     *
     * <p>Note: If the key already exists with the given value it is ignored.</p>
     *
     * @param key The new key.
     * @param value The new value.
     * @return The new query string.
     */
    public String add(String key, String value) {
        if (key == null || value == null || getAllValues(key).contains(value)) {
            return reconstructQueryString();
        }

        KeyValueIndex newKeyValue = new KeyValueIndex(getNextOverallIndex(), new KeyValue(key, value));
        List<KeyValueIndex> newKeyValueList = new ArrayList<>(Collections.singletonList(newKeyValue));

        state.merge(key, newKeyValueList, (existingList, newList) -> {
            existingList.addAll(newList);
            return existingList;
        });

        return reconstructQueryString();
    }

    /**
     * Adds all key value pairs to the end of the query string.
     *
     * @param keyValuePairs The key/value pairs to add.
     * @return The new query string.
     */
    public String addAll(List<List<String>> keyValuePairs) {
        if (keyValuePairs != null) {
            keyValuePairs.stream()
                    .map(KeyValue::fromPair)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .map(kv -> kv.toIndex(getNextOverallIndex()))
                    .forEach(kvi ->
                            state.merge(kvi.keyValue.key, new ArrayList<>(Arrays.asList(kvi)), (existingList, newList) -> {
                                existingList.addAll(newList);
                                return existingList;
                            }));
        }

        // After the mutation of the state map, rebuild it.
        return reconstructQueryString();
    }

    /**
     * Contains the result of casting a dynamic untyped map entry produced by a SpEL expression enabling simpler
     * processing. The below example would create 3 {@code StateChangeInstruction}s for each sort field.
     *
     * <pre>
     *     This:
     *     {sort: {0: 'stars,asc', 1: 'address,desc', 2: 'country,asc'}}
     *
     *     Becomes This:
     *     [{key='sort', relativeIndex=0, newValue='stars,asc'},
     *      {key='sort', relativeIndex=1, newValue='address,desc'},
     *      {key='sort', relativeIndex=2, newValue='country,asc'}]
     * </pre>
     */
    private static class StateChangeInstruction {
        private String key;
        private int relativeIndex;
        private String newValue;

        private StateChangeInstruction(String key, int relativeIndex, String newValue) {
            this.key = key;
            this.relativeIndex = relativeIndex;
            this.newValue = newValue;
        }

        @Override
        public String toString() {
            return "StateChangeInstruction{" +
                    "key='" + key + '\'' +
                    ", relativeIndex=" + relativeIndex +
                    ", newValue='" + newValue + '\'' +
                    '}';
        }
    }


    /**
     * The {@code originalQueryString} string is transformed into a state map for easier manipulation across all operations.
     * The value of the state map is of type {@code KeyValueIndex} which tracks a key/value pairs index position in
     * the overall {@code originalQueryString} string. This ensures the reconstructed query string maintains
     * its original ordering.
     * <br>
     * <br>
     *
     * <p>Lastly, the value type {@code KeyValueIndex} is contained within a list which naturally tracks relative key
     * ordering. In the context of the example below, its possible to perform an update that replaces the second
     * occurrence of the sort key to have the value {@code address} using the following pseudo code.</p>
     *
     * <p>{@code stateMap["sort"].relativeIndex(1).update("address")}</p>
     *
     * <br>
     *
     * <p>This example {@code originalQueryString} string is transformed into the following map.
     * {@code suburb=Melbourne&postcode=3000&page=0&sort=stars,desc&country=AU&sort=name}</p>
     *
     * <pre>
     * country = [4 -> country=AU]
     * postcode = [1 -> postcode=3000]
     * suburb = [0 -> suburb=Melbourne]
     * sort = [3 -> sort=stars,desc, 5 -> sort=name]
     * page = [2 -> page=0]
     * </pre>
     *
     * <p>You can read {@code sort = [3 -> sort=stars,desc, 5 -> sort=name]} as saying</p>
     * <ul>
     * <li>{@code sort=stars,desc} is at index 3 in the original query string and has relative order 0
     * (its the first sort key to appear)</li>
     * <li>{@code sort=name} is at index 5 in the original query string and has relative order 1
     * (its the second sort key to appear)</li>
     * </ul>
     *
     * @return The state map.
     */
    private Map<String, List<KeyValueIndex>> createState() {
        return Pattern.compile("&").splitAsStream(originalQueryString)
                .map(KeyValue::fromKeyValue)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(new QueryCollector());
    }

    /**
     *
     * @return Unmodifiable state map.
     */
    public Map<String, List<KeyValueIndex>> getState() {
        return Collections.unmodifiableMap(state);
    }

    /**
     * Since a {@code Map} is used for state, an overall index is used to reconstruct the query string
     * in the exact same order. For example, if the current query string has 5 keys and we want to add to the end,
     * the next overall index would be 5 given 0 based indexing.
     *
     * @return The next available index representing the max overall index + 1.
     */
    public int getNextOverallIndex() {
        return state.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream())
                .map(KeyValueIndex::getOverallIndex)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    /**
     * Represents key value pairs and provides static factory methods to create valid instances.
     */
    public static class KeyValue {
        private String key;
        private String value;

        private KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public static Optional<KeyValue> fromPair(List<String> pair) {
            if (pair.size() == 2 && !pair.get(0).isEmpty() && !pair.get(1).isEmpty()) {
                return Optional.of(new KeyValue(pair.get(0), pair.get(1)));
            }
            return Optional.empty();
        }

        public static Optional<KeyValue> fromKeyValue(String keyValue) {
            List<String> pair = Arrays.stream(keyValue.split("=")).collect(Collectors.toList());
            return fromPair(pair);
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        /**
         * The state map is in unescaped form to simplify equality comparisons. This method
         * escapes key/value pairs in its own step during query string reconstruction.
         *
         * @param escapeMapper The mapping function.
         * @return The escaped key value pair in {@code key=value} format.
         */
        public String escape(Function<String, String> escapeMapper) {
            return escapeMapper.apply(key) + "=" + escapeMapper.apply(value);
        }

        /**
         *
         * @param overallIndex The overall index this {@code key=value} pair is located within the query string.
         * @return A {@code KeyValueIndex} which wraps this instance by providing an index.
         */
        public KeyValueIndex toIndex(int overallIndex) {
            return new KeyValueIndex(overallIndex, this);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyValue keyValue = (KeyValue) o;
            return Objects.equals(key, keyValue.key) &&
                    Objects.equals(value, keyValue.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, value);
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }
    }

    /**
     * The {@code overallIndex} is simply the wrapped {@code KeyValue}s position in the original query string. This
     * allows the state map to be reconstructed into the same order as the original query string.
     */
    public static class KeyValueIndex {
        private int overallIndex;
        private KeyValue keyValue;

        public KeyValueIndex(int overallIndex, KeyValue keyValue) {
            this.overallIndex = overallIndex;
            this.keyValue = keyValue;
        }

        public int getOverallIndex() {
            return overallIndex;
        }

        public KeyValue getKeyValue() {
            return keyValue;
        }

        public KeyValueIndex updateValue(String value) {
            return new KeyValueIndex(overallIndex, new KeyValue(keyValue.key, value));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            KeyValueIndex that = (KeyValueIndex) o;
            return overallIndex == that.overallIndex &&
                    Objects.equals(keyValue, that.keyValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(overallIndex, keyValue);
        }

        @Override
        public String toString() {
            return overallIndex + " -> " + keyValue;
        }
    }

    /**
     * Creates the state map. If java had a zipWithIndex method this could be avoided. Basically a mutable index is
     * kept to assign each {@code KeyValue} its original index position in the query string.
     */
    public static class QueryCollector implements
            Collector<KeyValue, Map<String, List<KeyValueIndex>>, Map<String, List<KeyValueIndex>>> {

        private int index = 0;

        private KeyValueIndex createKeyValueIndex(KeyValue kv) {
            KeyValueIndex keyValueIndex = new KeyValueIndex(index, kv);
            index++;
            return keyValueIndex;
        }

        @Override
        public Supplier<Map<String, List<KeyValueIndex>>> supplier() {
            return HashMap::new;
        }

        @Override
        public BiConsumer<Map<String, List<KeyValueIndex>>, KeyValue> accumulator() {
            return (stateMap, keyValue) ->
                    stateMap.merge(keyValue.key, new ArrayList<>(Arrays.asList(createKeyValueIndex(keyValue))),
                            (existingList, newList) -> {
                                existingList.addAll(newList);
                                return existingList;
                            });
        }

        @Override
        public BinaryOperator<Map<String, List<KeyValueIndex>>> combiner() {
            /*
             * Unfortunately there is no mapWithIndex on streams, so to get around that a mutable index is kept
             * which needs to run sequentially to maintain the original key=value originalQueryString string ordering.
             */
            return (a, b) -> {
                throw new UnsupportedOperationException("Parallel streams not permitted");
            };
        }

        @Override
        public Function<Map<String, List<KeyValueIndex>>, Map<String, List<KeyValueIndex>>> finisher() {
            return Function.identity();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return EnumSet.noneOf(Characteristics.class);
        }
    }
}