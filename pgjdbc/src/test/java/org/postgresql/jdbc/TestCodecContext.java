/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.api.codec.CodecContext;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;

/**
 * Factory for creating CodecContext instances in unit tests without a database connection.
 */
public final class TestCodecContext {

  private TestCodecContext() {
  }

  /**
   * Creates a CodecContext for testing with default settings (UTC, UTF-8, no java.time preference).
   */
  public static CodecContext create() {
    return create(false, false, false, false, false);
  }

  /**
   * Creates a CodecContext for testing with specified java.time preferences.
   */
  public static CodecContext create(
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz) {
    return create(StandardCharsets.UTF_8,
        prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
        prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz);
  }

  /**
   * Creates a CodecContext for testing with specified charset and java.time preferences.
   */
  public static CodecContext create(Charset charset,
      boolean prefersJavaTimeForDate,
      boolean prefersJavaTimeForTime,
      boolean prefersJavaTimeForTimetz,
      boolean prefersJavaTimeForTimestamp,
      boolean prefersJavaTimeForTimestamptz) {
    TimestampUtils timestampUtils = new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
    return new PgCodecContext(timestampUtils, charset,
        prefersJavaTimeForDate, prefersJavaTimeForTime, prefersJavaTimeForTimetz,
        prefersJavaTimeForTimestamp, prefersJavaTimeForTimestamptz);
  }

  /**
   * Creates a CodecContext for testing with the {@code convertBooleanToNumeric}
   * flag set to the requested value (other flags default to false, UTF-8, UTC).
   */
  public static CodecContext withConvertBooleanToNumeric(boolean convertBooleanToNumeric) {
    TimestampUtils timestampUtils = new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
    return new PgCodecContext(timestampUtils, StandardCharsets.UTF_8,
        false, false, false, false, false, convertBooleanToNumeric);
  }

  /**
   * Returns a {@link TimestampUtils} configured identically to the test contexts (integer
   * datetimes, UTC). Codec tests use it to build expected values without reaching the codec
   * context's internal temporal engine.
   */
  public static TimestampUtils timestampUtils() {
    return new TimestampUtils(false, () -> TimeZone.getTimeZone("UTC"));
  }
}
