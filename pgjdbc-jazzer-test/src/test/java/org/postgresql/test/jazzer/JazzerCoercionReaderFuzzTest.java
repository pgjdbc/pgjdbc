/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.fuzzkit.CoercionCase;
import org.postgresql.fuzzkit.CoercionFuzzSupport;
import org.postgresql.fuzzkit.ReadOracle;
import org.postgresql.fuzzkit.SqlInputReader;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.fuzzkit.coercion.ScalarDescriptor;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

import java.sql.SQLException;

/**
 * The Jazzer counterpart of {@code CoercionReaderFuzzTest} in pgjdbc-jqf-test, driving the <b>same</b>
 * oracle: it builds a {@link CoercionCase} -- a field type from {@link PgTypeDescriptors#coercionScalars()},
 * a value of that type, an {@link SqlInputReader}, a {@code readObject(Class)} target, and the
 * {@code prefersJavaTime} flags -- and hands it to {@link CoercionFuzzSupport#run}, which asserts the read
 * outcome against the {@code ReadCoercions} registry across both wire formats. The registry's unspecified
 * cells keep the weak invariant the JQF target advertises: a reader returns or refuses with a
 * {@link SQLException}, never an unchecked leak.
 *
 * <p>Only the front-end differs from the JQF target. There the {@code CoercionCase} dimensions come from a
 * jetCheck generator inside {@code PgValueArgumentsFactory}; here they are drawn from a
 * {@link FuzzedDataProvider} inline, with the per-type value from {@link JazzerValues}. Everything past the
 * case -- the wire, the reader adapters, the outcome check -- is the shared fuzzkit oracle, so a divergence
 * either fuzzer reports is a divergence in the driver, not in the harness.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jazzer-test:test}; fuzz with
 * {@code gradle :pgjdbc-jazzer-test:test -Pjazzer.fuzz=1 --tests '*readerLeaksOnlySqlException'}.
 */
class JazzerCoercionReaderFuzzTest {

  private static final ScalarDescriptor[] DESCRIPTORS =
      PgTypeDescriptors.coercionScalars().toArray(new ScalarDescriptor[0]);
  private static final SqlInputReader[] READERS = SqlInputReader.values();

  @FuzzTest
  void readerLeaksOnlySqlException(@NotNull FuzzedDataProvider data) throws SQLException {
    ScalarDescriptor descriptor = DESCRIPTORS[data.consumeInt(0, DESCRIPTORS.length - 1)];
    Object value = JazzerValues.draw(data, descriptor.naturalClass());
    SqlInputReader reader = READERS[data.consumeInt(0, READERS.length - 1)];
    // The target class is meaningful only for readObject(Class); other readers ignore it, so it is null.
    Class<?> targetClass = reader == SqlInputReader.READ_OBJECT_AS
        ? ReadOracle.TARGET_CLASSES[data.consumeInt(0, ReadOracle.TARGET_CLASSES.length - 1)]
        : null;
    boolean[] prefersJavaTime = new boolean[5];
    for (int i = 0; i < prefersJavaTime.length; i++) {
      prefersJavaTime[i] = data.consumeBoolean();
    }
    CoercionFuzzSupport.run(new CoercionCase(descriptor, value, reader, targetClass, prefersJavaTime));
  }
}
