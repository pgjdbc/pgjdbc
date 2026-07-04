/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.FuzzArray;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.PgType;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Coverage-guided cross-format properties: for a generated value, the text and binary paths must
 * agree -- {@code decode(encodeText(v))} equals {@code decode(encodeBinary(v))}. This is independent
 * of the per-format round-trip: it catches the two formats disagreeing on a value even when each is
 * internally self-consistent. Anonymous {@code RECORD} is excluded, since its text literal carries
 * no field OIDs and cannot decode to a typed struct.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class CrossFormatCodecFuzzTest {

  @FuzzTest
  void int4CrossFormat(int value) throws SQLException {
    CodecFuzzSupport.crossFormat(value, PgTypeDescriptors.scalar(Oid.INT4).pgType(), Integer.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int8CrossFormat(long value) throws SQLException {
    CodecFuzzSupport.crossFormat(value, PgTypeDescriptors.scalar(Oid.INT8).pgType(), Long.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void float8CrossFormat(double value) throws SQLException {
    CodecFuzzSupport.crossFormat(value, PgTypeDescriptors.scalar(Oid.FLOAT8).pgType(), Double.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void textCrossFormat(String value) throws SQLException {
    CodecFuzzSupport.crossFormat(value, PgTypeDescriptors.scalar(Oid.TEXT).pgType(), String.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void numericCrossFormat(BigDecimal value) throws SQLException {
    CodecFuzzSupport.numericCrossFormat(value, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void byteaCrossFormat(byte[] value) throws SQLException {
    CodecFuzzSupport.byteaCrossFormat(value, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void arrayCrossFormat(FuzzArray array) throws SQLException {
    CodecFuzzSupport.arrayCrossFormat(array.value, PgTypeDescriptors.array(array.arrayOid),
        array.leafRepr, array.ndim, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void regularStructCrossFormat(int x, int y, String label) throws SQLException {
    PgType point = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();
    CodecFuzzSupport.structCrossFormat(point, new Object[]{x, y, label},
        CodecFuzzSupport.with(point));
  }
}
