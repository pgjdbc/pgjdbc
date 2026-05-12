/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

/**
 * ThreadLocal counter for tracking nested encode/decode depth.
 *
 * <p>This class protects against stack overflow from deeply nested or
 * cyclically-referencing composite types. The maximum nesting depth is 64,
 * which should be sufficient for any reasonable use case.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * CodecDepth.enter();
 * try {
 *     // decode nested type
 * } finally {
 *     CodecDepth.exit();
 * }
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This class uses ThreadLocal, which is safe with
 * virtual threads due to the short lifespan of codec operations and proper
 * cleanup in finally blocks.</p>
 *
 * @since 42.8.0
 */
public final class CodecDepth {

  /**
   * Maximum allowed nesting depth for encode/decode operations.
   */
  public static final int MAX_DEPTH = 64;

  @SuppressWarnings("type.argument")
  private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

  private CodecDepth() {
    // Utility class
  }

  /**
   * Enters a nested encode/decode operation.
   *
   * <p>Call this at the beginning of any codec operation that may
   * recurse into nested types (arrays, composites, etc.).</p>
   *
   * @throws SQLException if maximum nesting depth is exceeded
   */
  public static void enter() throws SQLException {
    int depth = DEPTH.get() + 1;
    if (depth > MAX_DEPTH) {
      throw new PSQLException(
          GT.tr("Maximum type nesting depth exceeded: {0}", MAX_DEPTH),
          PSQLState.DATA_ERROR);
    }
    DEPTH.set(depth);
  }

  /**
   * Exits a nested encode/decode operation.
   *
   * <p>Call this in a finally block after {@link #enter()}.</p>
   */
  public static void exit() {
    int depth = DEPTH.get();
    if (depth > 0) {
      DEPTH.set(depth - 1);
    }
  }

  /**
   * Clears the depth counter for the current thread.
   *
   * <p>This should be called at the end of top-level operations
   * to clean up ThreadLocal state, especially important for thread pools.</p>
   */
  public static void clear() {
    DEPTH.remove();
  }

  /**
   * Returns the current nesting depth.
   *
   * <p>Primarily useful for debugging and testing.</p>
   *
   * @return the current depth (0 if not inside any codec operation)
   */
  public static int current() {
    return DEPTH.get();
  }
}
