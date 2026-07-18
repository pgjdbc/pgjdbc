/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.FuzzArray;
import org.postgresql.fuzzkit.FuzzRecord;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided round-trip properties for the container shapes: a multi-dimensional {@code int4[]}
 * or {@code text[]} array on the {@code dims = {1, 2, 3}} and {@code leafRepr = {boxed, primitive}}
 * axes (with occasional SQL NULLs on the boxed leaf and, for text, quoting-sensitive characters) and
 * an anonymous {@code RECORD} whose field count and field types vary. Arguments come from
 * {@link PgValueArgumentsFactory}, which drives jetCheck's combinators from the engine's guided byte
 * stream; a {@link FuzzArray} carries its own descriptor OID, dimension, and leaf representation, so
 * one target covers both array types across both axes.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class JqfStructuralCodecFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void arrayRoundTrip(FuzzArray array) throws SQLException {
    CodecFuzzSupport.arrayRoundTrip(array.value, PgTypeDescriptors.array(array.arrayOid),
        array.leafRepr, array.ndim, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void anonymousRecordRoundTrip(FuzzRecord record) throws SQLException {
    CodecFuzzSupport.anonymousRecordRoundTrip(record.fieldOids, record.values);
  }
}
