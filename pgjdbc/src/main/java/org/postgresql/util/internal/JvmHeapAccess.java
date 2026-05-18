/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import java.lang.management.ManagementFactory;

/**
 * Isolated access to {@link ManagementFactory}.
 *
 * <p>Kept in its own class so that callers can be loaded on runtimes that omit {@code
 * java.lang.management} (most notably Android ART). Callers must guard invocations with
 * {@code try { ... } catch (NoClassDefFoundError | LinkageError e)} so the verifier never has
 * to resolve {@link ManagementFactory} unless this class is actually used.
 */
public final class JvmHeapAccess {

  private JvmHeapAccess() {
  }

  public static long maxHeapBytes() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
  }
}
