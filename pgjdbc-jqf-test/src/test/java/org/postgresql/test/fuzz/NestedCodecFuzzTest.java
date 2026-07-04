/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.FuzzNode;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided round-trip properties for nested container shapes: a recursively nested composite
 * -- named, anonymous {@code RECORD}, or the mixed anonymous-holding-named shape PostgreSQL allows
 * -- and an array of composites (array-of-record). Arguments come from
 * {@link PgValueArgumentsFactory}, which drives jetCheck's {@code recursive} combinator from the
 * engine's guided byte stream.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class NestedCodecFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void structRoundTrip(FuzzNode struct) throws SQLException {
    CodecFuzzSupport.structRoundTrip(struct);
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void recordArrayRoundTrip(Object[][] rows) throws SQLException {
    CodecFuzzSupport.recordArrayRoundTrip(rows);
  }
}
