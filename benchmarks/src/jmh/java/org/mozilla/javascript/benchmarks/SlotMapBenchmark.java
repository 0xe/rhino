package org.mozilla.javascript.benchmarks;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.mozilla.javascript.EmbeddedSlotMap;
import org.mozilla.javascript.FastEmbeddedSlotMap;
import org.mozilla.javascript.HashSlotMap;
import org.mozilla.javascript.Slot;
import org.mozilla.javascript.SlotMap;
import org.openjdk.jmh.annotations.*;

@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SlotMapBenchmark {
    // Fixed seed for repeatability
    private static final Random rand = new Random(0);

    @State(Scope.Thread)
    public static class EmbeddedState {
        final EmbeddedSlotMap emptyMap = new EmbeddedSlotMap();
        final EmbeddedSlotMap size10Map = new EmbeddedSlotMap();
        final EmbeddedSlotMap size100Map = new EmbeddedSlotMap();
        final String[] randomKeys = new String[100];
        String size100LastKey;
        String size10LastKey;

        @Setup(Level.Trial)
        public void create() {
            String lastKey = null;
            for (int i = 0; i < 10; i++) {
                lastKey = insertRandomEntry(size10Map);
            }
            size10LastKey = lastKey;
            for (int i = 0; i < 100; i++) {
                lastKey = insertRandomEntry(size100Map);
            }
            size100LastKey = lastKey;
            for (int i = 0; i < 100; i++) {
                randomKeys[i] = makeRandomString();
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object embeddedInsert1Key(EmbeddedState state) {
        Slot newSlot = null;
        for (int i = 0; i < 100; i++) {
            newSlot = state.emptyMap.modify(null, state.randomKeys[i], 0, 0);
        }
        if (newSlot == null) {
            throw new AssertionError();
        }
        return newSlot;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object embeddedQueryKey10Entries(EmbeddedState state) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            slot = state.size10Map.query(state.size10LastKey, 0);
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object embeddedQueryKey100Entries(EmbeddedState state) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            slot = state.size100Map.query(state.size100LastKey, 0);
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    @State(Scope.Thread)
    public static class HashState {
        final HashSlotMap emptyMap = new HashSlotMap();
        final HashSlotMap size10Map = new HashSlotMap();
        final HashSlotMap size100Map = new HashSlotMap();
        final String[] randomKeys = new String[100];
        String size100LastKey;
        String size10LastKey;

        @Setup(Level.Trial)
        public void create() {
            String lastKey = null;
            for (int i = 0; i < 10; i++) {
                lastKey = insertRandomEntry(size10Map);
            }
            size10LastKey = lastKey;
            for (int i = 0; i < 100; i++) {
                lastKey = insertRandomEntry(size100Map);
            }
            size100LastKey = lastKey;
            for (int i = 0; i < 100; i++) {
                randomKeys[i] = makeRandomString();
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object hashInsert1Key(HashState state) {
        Slot newSlot = null;
        for (int i = 0; i < 100; i++) {
            newSlot = state.emptyMap.modify(null, state.randomKeys[i], 0, 0);
        }
        if (newSlot == null) {
            throw new AssertionError();
        }
        return newSlot;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object hashQueryKey10Entries(HashState state) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            slot = state.size10Map.query(state.size10LastKey, 0);
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object hashQueryKey100Entries(HashState state) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            slot = state.size100Map.query(state.size100LastKey, 0);
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    @State(Scope.Thread)
    public static class FastEmbeddedState {
        final FastEmbeddedSlotMap emptyMap = new FastEmbeddedSlotMap(16);
        final FastEmbeddedSlotMap size5Map = new FastEmbeddedSlotMap(8);
        final FastEmbeddedSlotMap size10Map = new FastEmbeddedSlotMap(16);
        final FastEmbeddedSlotMap size25Map = new FastEmbeddedSlotMap(32);
        final FastEmbeddedSlotMap size50Map = new FastEmbeddedSlotMap(64);
        final FastEmbeddedSlotMap size100Map = new FastEmbeddedSlotMap(128);
        final FastEmbeddedSlotMap size250Map = new FastEmbeddedSlotMap(256);
        final FastEmbeddedSlotMap size500Map = new FastEmbeddedSlotMap(512);
        
        final String[] randomKeys = new String[500];
        String size5LastKey;
        String size10LastKey;
        String size25LastKey;
        String size50LastKey;
        String size100LastKey;
        String size250LastKey;
        String size500LastKey;

        @Setup(Level.Trial)
        public void create() {
            // Initialize maps with different sizes
            size5LastKey = fillMap(size5Map, 5);
            size10LastKey = fillMap(size10Map, 10);
            size25LastKey = fillMap(size25Map, 25);
            size50LastKey = fillMap(size50Map, 50);
            size100LastKey = fillMap(size100Map, 100);
            size250LastKey = fillMap(size250Map, 250);
            size500LastKey = fillMap(size500Map, 500);
            
            // Generate random keys for testing
            for (int i = 0; i < randomKeys.length; i++) {
                randomKeys[i] = makeRandomString();
            }
        }
        
        private String fillMap(FastEmbeddedSlotMap map, int size) {
            String lastKey = null;
            for (int i = 0; i < size; i++) {
                lastKey = insertRandomEntry(map);
            }
            return lastKey;
        }
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedInsert1Key(FastEmbeddedState state) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            slot = state.emptyMap.modify(null, state.randomKeys[i], 0, 0);
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey5Entries(FastEmbeddedState state) {
        return queryKey(state.size5Map, state.size5LastKey);
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey10Entries(FastEmbeddedState state) {
        return queryKey(state.size10Map, state.size10LastKey);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey25Entries(FastEmbeddedState state) {
        return queryKey(state.size25Map, state.size25LastKey);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey50Entries(FastEmbeddedState state) {
        return queryKey(state.size50Map, state.size50LastKey);
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey100Entries(FastEmbeddedState state) {
        return queryKey(state.size100Map, state.size100LastKey);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey250Entries(FastEmbeddedState state) {
        return queryKey(state.size250Map, state.size250LastKey);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedQueryKey500Entries(FastEmbeddedState state) {
        return queryKey(state.size500Map, state.size500LastKey);
    }
    
    private Object queryKey(SlotMap map, String key) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            slot = map.query(key, 0);
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedComputeIfAbsent5Entries(FastEmbeddedState state) {
        return computeIfAbsent(state.size5Map, state.randomKeys, 5);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedComputeIfAbsent10Entries(FastEmbeddedState state) {
        return computeIfAbsent(state.size10Map, state.randomKeys, 10);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedComputeIfAbsent25Entries(FastEmbeddedState state) {
        return computeIfAbsent(state.size25Map, state.randomKeys, 25);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedComputeIfAbsent50Entries(FastEmbeddedState state) {
        return computeIfAbsent(state.size50Map, state.randomKeys, 50);
    }
    
    @Benchmark
    @OperationsPerInvocation(100)
    public Object fastEmbeddedComputeIfAbsent100Entries(FastEmbeddedState state) {
        return computeIfAbsent(state.size100Map, state.randomKeys, 100);
    }
    
    private Object computeIfAbsent(SlotMap map, String[] randomKeys, int keyRange) {
        Slot slot = null;
        for (int i = 0; i < 100; i++) {
            final String key = randomKeys[i % keyRange];
            slot = map.compute(null, key, 0, (k, index, existing) -> {
                if (existing == null) {
                    Slot newSlot = map.modify(null, key, 0, 0);
                    newSlot.setValue(key, null, null);
                    return newSlot;
                }
                return existing;
            });
        }
        if (slot == null) {
            throw new AssertionError();
        }
        return slot;
    }

    /** Make a new string between 1 and 50 characters out of random lower-case letters. */
    private static String makeRandomString() {
        int len = rand.nextInt(49) + 1;
        char[] c = new char[len];
        for (int cc = 0; cc < len; cc++) {
            c[cc] = (char) ('a' + rand.nextInt(25));
        }
        return new String(c);
    }

    /** Insert a random key and value into the map */
    private static String insertRandomEntry(SlotMap map) {
        String key = makeRandomString();
        Slot slot = map.modify(null, key, 0, 0);
        slot.setValue(key, null, null);
        return key;
    }
}
