/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.Experimental;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecContextBuilder;
import org.postgresql.api.codec.CodecLookup;

/**
 * Offline entry point for the codec API: builds a connectionless {@link CodecContext} and hands out
 * the default codec registry without binding a caller to the driver's internal context class.
 *
 * <p>This is the single {@code org.postgresql.jdbc} type an offline caller needs. Everything else —
 * {@link CodecContextBuilder}, {@link CodecContext}, {@link CodecLookup}, and
 * {@link org.postgresql.api.codec.Codecs} — lives in {@code org.postgresql.api.codec}. A working
 * offline context has to run driver code (the built-in codecs and timestamp handling), so the
 * factory stays here rather than in {@code api.codec}, which is guaranteed to compile and run
 * without the driver internals.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class OfflineCodecs {

  private OfflineCodecs() {
  }

  /**
   * Returns a builder for a connectionless {@link CodecContext} that encodes and decodes offline.
   *
   * @return a new offline context builder
   */
  public static CodecContextBuilder builder() {
    return PgCodecContext.offlineBuilder();
  }

  /**
   * Returns a fresh default codec registry, viewed through the read-only {@link CodecLookup} SPI.
   *
   * <p>Pass it to {@link CodecContextBuilder#registry(CodecLookup)} to share one registry across
   * several offline contexts instead of letting each {@link #builder()} build its own.</p>
   *
   * @return a default codec registry
   */
  public static CodecLookup defaultRegistry() {
    return PgCodecContext.newDefaultRegistry();
  }
}
