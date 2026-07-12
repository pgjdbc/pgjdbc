/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.CodecContextBuilder;
import org.postgresql.api.codec.CodecLookup;
import org.postgresql.jdbc.OfflineCodecs;

/**
 * Shared offline-context factory for the fuzz targets. The built-in {@link CodecLookup} is
 * input-independent and expensive to build (it registers every built-in codec and warms a lookup
 * cache), yet the fuzz targets build a fresh context per input. A profiler shows that rebuild
 * dominates the fuzzers' allocation: with hundreds of thousands of executions per second, the
 * per-execution {@code new CodecRegistry()} is the single largest source of churn.
 *
 * <p>The registry is safe to share: {@link CodecContextBuilder#type} records per-context type
 * descriptors in a separate map that {@code build()} copies onto the context, so it never mutates the
 * registry, and the registry's own lookup cache is thread-safe. Building it once and injecting it with
 * {@link CodecContextBuilder#registry} leaves only the cheap per-context state (the type map and
 * timestamp settings) to allocate per input.
 */
public final class OfflineCodecContexts {

  private static final CodecLookup SHARED_BUILTINS = OfflineCodecs.defaultRegistry();

  private OfflineCodecContexts() {
  }

  /**
   * An offline-context builder that reuses the shared built-in {@link CodecLookup} instead of
   * building a fresh one. A drop-in replacement for {@link OfflineCodecs#builder()} on the
   * fuzz targets' hot path.
   *
   * @return a builder pre-configured with the shared built-in registry
   */
  public static CodecContextBuilder offlineBuilder() {
    return OfflineCodecs.builder().registry(SHARED_BUILTINS);
  }
}
