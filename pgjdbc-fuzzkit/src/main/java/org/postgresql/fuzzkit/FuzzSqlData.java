/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Objects;

/**
 * A {@link SQLData} value that reads and writes one field for each scalar {@link SQLInput} /
 * {@link SQLOutput} method. Round-tripping it drives the {@code PgSQLOutput} / {@code PgSQLInput}
 * adapters on both wire paths (text and binary): the field-offset bookkeeping, the composite
 * literal quoting on the text side, the NULL handling behind {@link SQLInput#wasNull()}, and the
 * per-type codecs. The object fields are nullable so the NULL path is exercised; the primitive
 * fields never are.
 */
public final class FuzzSqlData implements SQLData {

  public int intValue;
  public long longValue;
  public short shortValue;
  public boolean boolValue;
  public float floatValue;
  public double doubleValue;
  public String text;
  public BigDecimal numeric;
  public byte[] bytes;
  public Date date;
  public Time time;
  public Timestamp timestamp;

  public FuzzSqlData() {
  }

  @Override
  public String getSQLTypeName() {
    return "public.fuzz_sqldata";
  }

  @Override
  public void readSQL(SQLInput stream, String typeName) throws SQLException {
    intValue = stream.readInt();
    longValue = stream.readLong();
    shortValue = stream.readShort();
    boolValue = stream.readBoolean();
    floatValue = stream.readFloat();
    doubleValue = stream.readDouble();
    text = stream.readString();
    numeric = stream.readBigDecimal();
    bytes = stream.readBytes();
    date = stream.readDate();
    time = stream.readTime();
    timestamp = stream.readTimestamp();
  }

  @Override
  public void writeSQL(SQLOutput stream) throws SQLException {
    stream.writeInt(intValue);
    stream.writeLong(longValue);
    stream.writeShort(shortValue);
    stream.writeBoolean(boolValue);
    stream.writeFloat(floatValue);
    stream.writeDouble(doubleValue);
    stream.writeString(text);
    stream.writeBigDecimal(numeric);
    stream.writeBytes(bytes);
    stream.writeDate(date);
    stream.writeTime(time);
    stream.writeTimestamp(timestamp);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FuzzSqlData)) {
      return false;
    }
    FuzzSqlData that = (FuzzSqlData) o;
    return intValue == that.intValue
        && longValue == that.longValue
        && shortValue == that.shortValue
        && boolValue == that.boolValue
        && Float.compare(floatValue, that.floatValue) == 0
        && Double.compare(doubleValue, that.doubleValue) == 0
        && Objects.equals(text, that.text)
        && numericEquals(numeric, that.numeric)
        && Arrays.equals(bytes, that.bytes)
        && Objects.equals(date, that.date)
        && Objects.equals(time, that.time)
        && Objects.equals(timestamp, that.timestamp);
  }

  // numeric compares by value: PostgreSQL preserves scale, but comparing by scale would flag a
  // harmless 1.0 vs 1.00 as a mismatch.
  private static boolean numericEquals(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return a == b;
    }
    return a.compareTo(b) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(intValue, longValue, shortValue, boolValue, floatValue, doubleValue, text,
        numeric == null ? null : numeric.stripTrailingZeros(), Arrays.hashCode(bytes), date, time,
        timestamp);
  }
}
