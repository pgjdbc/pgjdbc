/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.CoercionRoundTripCase;
import org.postgresql.fuzzkit.CoercionRoundTripSupport;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided SQLData round-trip property. A value is written into a composite attribute and read
 * back; the write leg is checked against {@code WriteCoercions} and the read leg against
 * {@code ReadCoercions}, and neither may leak. The write may be off-diagonal (for example
 * {@code writeString} into a {@code time} attribute, read back via {@code readTime}); an identity pair
 * -- the type's own writer and reader -- additionally asserts the written value survives the round-trip.
 * It composes the oracles of {@link CoercionWriterFuzzTest} and {@link CoercionReaderFuzzTest} and adds
 * value fidelity, which those value-independent fuzzers cannot check.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=20000}.
 */
class CoercionRoundTripFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void valueSurvivesRoundTrip(CoercionRoundTripCase coercion) throws SQLException {
    CoercionRoundTripSupport.run(coercion);
  }
}
