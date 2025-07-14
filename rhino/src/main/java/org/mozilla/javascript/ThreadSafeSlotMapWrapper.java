/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import java.util.Iterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A thread-safe wrapper around any SlotMap implementation. This ensures that all operations on the
 * underlying SlotMap are thread-safe.
 */
public class ThreadSafeSlotMapWrapper implements SlotMap {
    private final SlotMap delegate;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public ThreadSafeSlotMapWrapper(SlotMap delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("Delegate SlotMap cannot be null");
        }
        this.delegate = delegate;
    }

    @Override
    public int size() {
        readLock.lock();
        try {
            return delegate.size();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public boolean isEmpty() {
        readLock.lock();
        try {
            return delegate.isEmpty();
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Iterator<Slot> iterator() {
        readLock.lock();
        try {
            // Create a defensive copy of the iterator to avoid concurrent modification
            // Note: This may be expensive for large maps
            return new Iterator<Slot>() {
                private final Slot[] slots;
                private int index = 0;

                {
                    int size = delegate.size();
                    slots = new Slot[size];
                    int i = 0;
                    for (Slot slot : delegate) {
                        slots[i++] = slot;
                    }
                }

                @Override
                public boolean hasNext() {
                    return index < slots.length;
                }

                @Override
                public Slot next() {
                    if (!hasNext()) {
                        throw new java.util.NoSuchElementException();
                    }
                    return slots[index++];
                }
            };
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public Slot modify(SlotMapOwner owner, Object key, int index, int attributes) {
        writeLock.lock();
        try {
            return delegate.modify(owner, key, index, attributes);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public Slot query(Object key, int index) {
        readLock.lock();
        try {
            return delegate.query(key, index);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public <S extends Slot> S compute(
            SlotMapOwner owner, Object key, int index, SlotComputer<S> c) {
        writeLock.lock();
        try {
            return delegate.compute(owner, key, index, c);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void add(SlotMapOwner owner, Slot newSlot) {
        writeLock.lock();
        try {
            delegate.add(owner, newSlot);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public long readLock() {
        // Return a stamp that can be used to unlock
        readLock.lock();
        return 1L; // We don't use the stamp in this implementation
    }

    @Override
    public void unlockRead(long stamp) {
        readLock.unlock();
    }

    @Override
    public int dirtySize() {
        readLock.lock();
        try {
            return delegate.dirtySize();
        } finally {
            readLock.unlock();
        }
    }
}
