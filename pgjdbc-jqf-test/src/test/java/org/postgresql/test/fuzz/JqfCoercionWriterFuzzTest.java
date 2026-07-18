/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.CoercionWriteCase;
import org.postgresql.fuzzkit.CoercionWriteSupport;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided SQLData write coercion property, the mirror of {@link JqfCoercionReaderFuzzTest}. It
 * fuzzes the whole write matrix -- attribute type × {@link java.sql.SQLOutput} writer × value × wire
 * format -- and asserts each encode outcome against the {@link org.postgresql.fuzzkit.coercion.WriteCoercions}
 * registry: a writer either encodes the value or refuses with the registry's {@code SQLState}; it must
 * never leak an unchecked exception. Matching writer/attribute pairs are only the diagonal; the
 * off-diagonal (for example {@code writeInt} into a {@code text} attribute) is where coercion bugs hide.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=20000}.
 */
class JqfCoercionWriterFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void writerLeaksOnlySqlException(CoercionWriteCase coercion) throws SQLException {
    CoercionWriteSupport.run(coercion);
  }
}
