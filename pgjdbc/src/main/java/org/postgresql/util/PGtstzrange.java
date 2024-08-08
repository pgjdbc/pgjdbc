/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;

public class PGtstzrange extends PGrange<OffsetDateTime> implements Serializable, Cloneable {

  private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();
  public static final String PG_TYPE = "tstzrange";

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
  public PGtstzrange(@Nullable OffsetDateTime lowerBound, boolean lowerInclusive,
      @Nullable OffsetDateTime upperBound, boolean upperInclusive) {
    super(lowerBound, lowerInclusive, upperBound, upperInclusive);
    setType(PG_TYPE);
  }

  /**
   * Initialize a range with a given bounds.
   *
   * @param lowerBound the lower bound, inclusive, {@code null} if not set
   * @param upperBound the upper bound, exclusive, {@code null} if not set
   */
  public PGtstzrange(@Nullable OffsetDateTime lowerBound, @Nullable OffsetDateTime upperBound) {
    super(lowerBound, upperBound);
    setType(PG_TYPE);
  }

  /**
   * Required constructor, initializes an empty range.
   */
  public PGtstzrange() {
    setType(PG_TYPE);
  }

  /**
   * Initialize a range with a given range string representation.
   *
   * @param value String represented range (e.g. '["2019-08-07 12:23:40+00","2019-11-30
   *              18:29:23+00")')
   * @throws SQLException Is thrown if the string representation has an unknown format
   * @see #setValue(String)
   */
  public PGtstzrange(String value) throws SQLException {
    super(value);
    setType(PG_TYPE);
  }

  @Override
  protected @NonNull String serializeBound(OffsetDateTime value) {
    return OFFSET_DATE_TIME_FORMATTER.format(value);
  }

  @Override
  protected OffsetDateTime parseToken(String token) {
    return OffsetDateTime.parse(token, OFFSET_DATE_TIME_FORMATTER).atZoneSameInstant(SYSTEM_ZONE).toOffsetDateTime();
  }

  @Override
  protected boolean nullSafeEquals(@Nullable OffsetDateTime o1, @Nullable OffsetDateTime o2) {
    if (o1 == null) {
      return o2 == null;
    }
    if (o2 == null) {
      return false;
    }
    // We want to allow equivalent dates (different offset, but same instant in time) to be
    // considered equal
    return o1.isEqual(o2);
  }

  @SuppressWarnings("java:S2975")
  @Override
  public Object clone() throws CloneNotSupportedException {
    // squid:S2157 "Cloneables" should implement "clone
    return super.clone();
  }

  public static final DateTimeFormatter LOCAL_DATE_TIME_SPACE = new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .append(ISO_LOCAL_DATE)
      .appendLiteral(' ')
      .append(ISO_LOCAL_TIME)
      .toFormatter(Locale.ROOT);

  public static final DateTimeFormatter OFFSET_DATE_TIME_FORMATTER = new DateTimeFormatterBuilder()
      .appendLiteral('"')
      .append(LOCAL_DATE_TIME_SPACE)
      .parseLenient()
      .appendOffsetId()
      .parseStrict()
      .appendLiteral('"')
      .toFormatter(Locale.ROOT);
}
