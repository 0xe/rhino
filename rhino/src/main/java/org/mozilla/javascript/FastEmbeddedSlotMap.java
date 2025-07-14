/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A faster implementation of SlotMap that uses open addressing with linear probing. This is an
 * alternative to EmbeddedSlotMap that may provide better performance for certain workloads.
 *
 * @see SlotMap
 * @see EmbeddedSlotMap
 */
public class FastEmbeddedSlotMap implements SlotMap {
    private static final int MIN_CAPACITY = 8;
    private static final float LOAD_FACTOR = 0.75f; // Optimized load factor
    private static final int MAX_CAPACITY = 1 << 30; // Max array size
    private static final int HASH_MULTIPLIER = 0x9e3779b9; // Golden ratio
    private static final int INITIAL_CAPACITY = 16; // Optimized initial capacity

    private Slot[] slots;
    private int size = 0;
    private int capacity;
    private int threshold;
    private Slot firstAdded;
    private Slot lastAdded;

    public FastEmbeddedSlotMap() {
        this(INITIAL_CAPACITY);
    }

    public FastEmbeddedSlotMap(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("Invalid initial capacity: " + initialCapacity);
        }
        // Calculate the next power of two size for better hash distribution
        this.capacity = initialCapacity <= MIN_CAPACITY 
            ? MIN_CAPACITY 
            : Integer.highestOneBit(initialCapacity) << 1;
        this.threshold = (int) (capacity * LOAD_FACTOR);
        this.slots = new Slot[capacity];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public Iterator<Slot> iterator() {
        return new SlotIterator();
    }

    @Override
    public Slot modify(SlotMapOwner owner, Object key, int index, int attributes) {
        ensureCapacity();
        int hash = (key != null) ? key.hashCode() : index;
        int idx = findSlot(key, hash, true);
        Slot existing = slots[idx];
        if (existing != null) {
            return existing;
        }

        Slot newSlot = new Slot(key, index, attributes);
        addInternal(newSlot, hash);
        return newSlot;
    }

    @Override
    public Slot query(Object key, int index) {
        if (size == 0) {
            return null;
        }
        int hash = (key != null) ? key.hashCode() : index;
        int idx = findSlot(key, hash, false);
        return slots[idx];
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S extends Slot> S compute(
            SlotMapOwner owner, Object key, int index, SlotComputer<S> c) {
        ensureCapacity();
        int hash = (key != null) ? key.hashCode() : index;
        int idx = findSlot(key, hash, true);
        Slot existing = slots[idx];

        S newSlot = c.compute(key, index, (S) existing);
        if (newSlot == existing) {
            return (S) existing;
        }

        if (newSlot == null) {
            // Remove the slot
            if (existing != null) {
                removeFromOrderedList(existing);
                slots[idx] = null;
                size--;
                rehash();
            }
            return null;
        }

        // Add or replace the slot
        if (existing == null) {
            addInternal(newSlot, hash);
        } else {
            replaceInOrderedList(existing, newSlot);
            slots[idx] = newSlot;
        }
        return newSlot;
    }

    @Override
    public void add(SlotMapOwner owner, Slot newSlot) {
        ensureCapacity();
        int hash = (newSlot.name != null) ? newSlot.name.hashCode() : newSlot.indexOrHash;
        int idx = findSlot(newSlot.name, hash, true);

        if (slots[idx] == null) {
            // Add to the end of the ordered list (single-linked)
            if (lastAdded != null) {
                lastAdded.orderedNext = newSlot;
            } else {
                firstAdded = newSlot;
            }
            lastAdded = newSlot;
            slots[idx] = newSlot;
            size++;
        }
    }

    private int getIndex(int hash) {
        // Mix the bits to improve distribution of hash codes
        hash ^= (hash >>> 20) ^ (hash >>> 12);
        hash ^= (hash >>> 7) ^ (hash >>> 4);
        
        // Use power-of-two length tables with a simple mask
        return hash & (capacity - 1);
    }

    private void ensureCapacity() {
        if (size >= threshold) {
            resize();
        }
    }

    private void resize() {
        // Double the capacity but don't exceed max capacity
        int newCapacity = (capacity < (MAX_CAPACITY >>> 1)) 
            ? capacity << 1 
            : MAX_CAPACITY;
            
        if (newCapacity <= capacity) {
            throw new IllegalStateException("Maximum capacity reached");
        }
        
        Slot[] oldSlots = slots;
        slots = new Slot[newCapacity];
        int oldCapacity = capacity;
        capacity = newCapacity;
        threshold = (int) (capacity * LOAD_FACTOR);
        size = 0;
        
        // Rehash all entries
        for (int i = 0; i < oldCapacity; i++) {
            Slot slot = oldSlots[i];
            if (slot != null && slot.value != null) {
                int hash = (slot.name != null) ? slot.name.hashCode() : slot.indexOrHash;
                addInternal(slot, hash);
            }
        }
    }

    private int findSlot(Object key, int hash, boolean forInsert) {
        int idx = hash & (capacity - 1);
        int startIdx = idx;
        
        while (true) {
            Slot slot = slots[idx];
            if (slot == null) {
                return forInsert ? idx : -1; // Return -1 if not found and not inserting
            }
            if (slot.name == key || (key != null && key.equals(slot.name))) {
                return idx;
            }
            idx = (idx + 1) & (capacity - 1);
            if (idx == startIdx) {
                return -1; // Table is full, should have been resized
            }
        }
    }
    
    private void addInternal(Slot slot, int hash) {
        int idx = findSlot(slot.name, hash, true);
        if (idx < 0) {
            throw new IllegalStateException("Could not find slot for insertion");
        }
        
        // Add to the end of the ordered list
        if (lastAdded != null) {
            lastAdded.orderedNext = slot;
        } else {
            firstAdded = slot;
        }
        lastAdded = slot;
        
        slots[idx] = slot;
        size++;
    }

    private void rehash() {
        Slot[] oldSlots = slots;
        slots = new Slot[capacity];
        size = 0;
        firstAdded = lastAdded = null;

        for (Slot slot : oldSlots) {
            if (slot != null && slot.value != null) {
                int hash = (slot.name != null) ? slot.name.hashCode() : slot.indexOrHash;
                int idx = findSlot(slot.name, hash, true);
                if (idx >= 0) {
                    slots[idx] = slot;
                    size++;
                    
                    // Rebuild ordered list (single-linked)
                    if (lastAdded != null) {
                        lastAdded.orderedNext = slot;
                    } else {
                        firstAdded = slot;
                    }
                    lastAdded = slot;
                    
                    // Clear the next pointer to avoid potential cycles
                    slot.orderedNext = null;
                }
            }
        }
    }

    private void removeFromOrderedList(Slot slot) {
        if (slot == firstAdded) {
            firstAdded = slot.orderedNext;
            if (firstAdded == null) {
                lastAdded = null;
            }
        } else {
            // Find the previous slot and update its next pointer
            Slot prev = firstAdded;
            while (prev != null && prev.orderedNext != slot) {
                prev = prev.orderedNext;
            }
            if (prev != null) {
                prev.orderedNext = slot.orderedNext;
                if (slot == lastAdded) {
                    lastAdded = prev;
                }
            }
        }
        slot.orderedNext = null;
    }
    
    private void replaceInOrderedList(Slot oldSlot, Slot newSlot) {
        // Preserve the next pointer
        newSlot.orderedNext = oldSlot.orderedNext;
        
        // Update the previous slot's next reference
        if (oldSlot == firstAdded) {
            firstAdded = newSlot;
        } else {
            // Find the previous slot and update its next pointer
            Slot prev = firstAdded;
            while (prev != null && prev.orderedNext != oldSlot) {
                prev = prev.orderedNext;
            }
            if (prev != null) {
                prev.orderedNext = newSlot;
            }
        }
        
        // Update lastAdded if needed
        if (oldSlot == lastAdded) {
            lastAdded = newSlot;
        }
        
        // Clear the old slot's next reference
        oldSlot.orderedNext = null;
    }

    private class SlotIterator implements Iterator<Slot> {
        private Slot current = firstAdded;
        private Slot lastReturned = null;

        @Override
        public boolean hasNext() {
            return current != null;
        }

        @Override
        public Slot next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            lastReturned = current;
            current = current.orderedNext;
            return lastReturned;
        }
    }
}
