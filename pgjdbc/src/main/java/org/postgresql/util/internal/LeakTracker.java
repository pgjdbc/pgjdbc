/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

public class LeakTracker<T> {
  private final ReferenceQueue<T> leakedResources = new ReferenceQueue<>();

  /**
   * We need to keep a strong reference to the {@link LeakTraceHandle} otherwise
   * it won't be enqueued to the reference queue.
   */
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private final ConcurrentMap<LeakTraceHandle<T>, Boolean> resourcesInUse =
      new ConcurrentHashMap<>();

  public static class LeakTraceHandle<T> extends PhantomReference<T> {
    public final Throwable stackTrace;

    public LeakTraceHandle(T resource, ReferenceQueue<T> queue, Throwable stackTrace) {
      super(resource, queue);
      this.stackTrace = stackTrace;
    }
  }

  public LeakTraceHandle<T> register(T resource, Throwable stackTrace) {
    LeakTraceHandle<T> ref = new LeakTraceHandle<T>(resource, leakedResources, stackTrace);
    // We need to keep the strong reference to Phantom so it will be enqueued when resource leaks
    resourcesInUse.put(ref, true);
    return ref;
  }

  public void unregister(LeakTraceHandle<T> ref) {
    // If the user calls #close, we remove the strong reference to the PhantomReference,
    // so it won't be enqueued
    resourcesInUse.remove(ref);
  }

  public void processReferences(Consumer<LeakTraceHandle<T>> action) {
    Reference<? extends T> handle;
    while ((handle = leakedResources.poll()) != null) {
      //noinspection unchecked
      action.accept((LeakTraceHandle<T>) handle);
    }
  }
}
