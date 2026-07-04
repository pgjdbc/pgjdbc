/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.CoercionCase;
import org.postgresql.fuzzkit.CoercionFuzzSupport;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided SQLData coercion property. It fuzzes the whole read matrix -- field type × value ×
 * {@link java.sql.SQLInput} reader method × {@code prefersJavaTime} flags × wire format -- and
 * asserts one contract invariant: a reader either returns a value or refuses with a
 * {@link SQLException}; it must never leak an unchecked exception. Matching writer/reader pairs (the
 * round-trip targets) are only the diagonal of this matrix; the off-diagonal is where coercion bugs
 * hide, and the invariant needs no value oracle to catch them.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=20000}.
 */
class CoercionReaderFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void readerLeaksOnlySqlException(CoercionCase coercion) throws SQLException {
    CoercionFuzzSupport.run(coercion);
  }
}
