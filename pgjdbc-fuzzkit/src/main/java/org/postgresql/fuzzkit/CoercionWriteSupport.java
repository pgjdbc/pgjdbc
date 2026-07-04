/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.Format;
import org.postgresql.fuzzkit.coercion.CoercionOutcome;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;

import java.sql.SQLException;
import java.util.TimeZone;

/**
 * Drives one {@link CoercionWriteCase} through a {@code PgSQLOutput} adapter and asserts the encode
 * outcome via {@link WriteOracle} against the {@code WriteCoercions} registry. The encoded wire is
 * discarded here; only the write outcome is asserted (the round-trip fuzzer reads it back).
 */
public final class CoercionWriteSupport {

  private CoercionWriteSupport() {
  }

  public static void run(CoercionWriteCase c) throws SQLException {
    int oid = c.attr.oid();
    PgType comp = FuzzComposites.singleField(oid);
    PgCodecContext ctx = (PgCodecContext) PgCodecContext.offlineBuilder()
        .type(comp)
        .timeZone(TimeZone.getDefault())
        .build();

    CoercionOutcome expected = WriteOracle.expected(oid, c.writer, c.value);
    for (Format format : Format.values()) {
      WriteOracle.verify(comp, ctx, format, c.writer, c.value, expected, c);
    }
  }
}
