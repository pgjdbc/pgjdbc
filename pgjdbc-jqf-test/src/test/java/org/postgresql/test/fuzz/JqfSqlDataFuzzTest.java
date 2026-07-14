/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.FuzzSqlData;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided round-trip property for the {@link java.sql.SQLData} text and binary paths. A
 * generated {@link FuzzSqlData} writes one field per scalar {@link java.sql.SQLOutput} method and
 * reads it back through the matching {@link java.sql.SQLInput} method in both formats, so the
 * campaign drives the {@code PgSQLOutput} / {@code PgSQLInput} adapters: the field-offset
 * bookkeeping and text quoting, the NULL path behind {@code wasNull}, and each per-type codec.
 * Arguments come from {@link PgValueArgumentsFactory}, which builds the value from the guided byte
 * stream.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class JqfSqlDataFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void sqlDataRoundTrip(FuzzSqlData value) throws SQLException {
    CodecFuzzSupport.sqlDataRoundTrip(value);
  }
}
