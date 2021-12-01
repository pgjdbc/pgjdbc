package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

public class PGtsrange extends PGrange<LocalDateTime> implements Serializable, Cloneable {

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound     the lower bound, {@code null} if not set
   * @param lowerInclusive {@code true} to make the lower bound is inclusive,
   *                       {@code false} to make the lower bound is exclusive
   * @param upperBound     the upper bound, {@code null} if not set
   * @param upperInclusive {@code true} to make the upper bound is inclusive,
   *                       {@code false} to make the upper bound is exclusive
   */
  public PGtsrange(@Nullable LocalDateTime lowerBound, boolean lowerInclusive,
      @Nullable LocalDateTime upperBound, boolean upperInclusive) {
    super(lowerBound, lowerInclusive, upperBound, upperInclusive);
    setType("tsrange");
  }

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, inclusive, {@code null} if not set
   * @param upperBound the upper bound, exclusive, {@code null} if not set
   */
  public PGtsrange(@Nullable LocalDateTime lowerBound, @Nullable LocalDateTime upperBound) {
    super(lowerBound, upperBound);
    setType("tsrange");
  }

  /**
   * Required constructor, initializes an empty range.
   */
  public PGtsrange() {
    setType("tsrange");
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '["2019-08-07 12:23:40","2019-11-30 18:28:04")')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  public PGtsrange(String value) throws SQLException {
    super(value);
    setType("tsrange");
  }

  @Override
  protected @NonNull String serializeBound(LocalDateTime value) {
    return FORMATTER.format(value);
  }

  @Override
  protected LocalDateTime parseToken(String token) {
    return LocalDateTime.parse(token, FORMATTER);
  }

  private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
          .appendLiteral('"')
          .appendPattern("yyyy-MM-dd HH:mm:ss")
          .optionalStart()
          .appendLiteral(".")
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, false)
          .optionalEnd()
          .appendLiteral('"')
          .toFormatter(Locale.ENGLISH);
}
