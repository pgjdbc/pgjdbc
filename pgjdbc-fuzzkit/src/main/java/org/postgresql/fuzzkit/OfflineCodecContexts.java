/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.PgCodecContext;

/**
 * Shared offline-context factory for the fuzz targets. The built-in {@link CodecRegistry} is
 * input-independent and expensive to build (it registers every built-in codec and warms a lookup
 * cache), yet the fuzz targets build a fresh {@link PgCodecContext} per input. A profiler shows that
 * rebuild dominates the fuzzers' allocation: with hundreds of thousands of executions per second, the
 * per-execution {@code new CodecRegistry()} is the single largest source of churn.
 *
 * <p>The registry is safe to share: {@link PgCodecContext.OfflineBuilder#type} records per-context type
 * descriptors in a separate map that {@code build()} copies onto the context, so it never mutates the
 * registry, and the registry's own lookup cache is thread-safe. Building it once and injecting it with
 * {@link PgCodecContext.OfflineBuilder#registry} leaves only the cheap per-context state (the type map
 * and {@code TimestampUtils}) to allocate per input.
 */
public final class OfflineCodecContexts {

  private static final CodecRegistry SHARED_BUILTINS = new CodecRegistry();

  private OfflineCodecContexts() {
  }

  /**
   * An offline-context builder that reuses the shared built-in {@link CodecRegistry} instead of
   * building a fresh one. A drop-in replacement for {@link PgCodecContext#offlineBuilder()} on the
   * fuzz targets' hot path.
   *
   * @return a builder pre-configured with the shared built-in registry
   */
  public static PgCodecContext.OfflineBuilder offlineBuilder() {
    return PgCodecContext.offlineBuilder().registry(SHARED_BUILTINS);
  }
}
