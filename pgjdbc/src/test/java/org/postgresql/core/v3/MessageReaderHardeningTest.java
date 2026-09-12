/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.postgresql.test.util.PgWire.concat;
import static org.postgresql.test.util.PgWire.cstring;
import static org.postgresql.test.util.PgWire.filler;
import static org.postgresql.test.util.PgWire.int1;
import static org.postgresql.test.util.PgWire.int2;
import static org.postgresql.test.util.PgWire.int4;
import static org.postgresql.test.util.PgWire.message;
import static org.postgresql.test.util.PgWire.messageWithLength;
import static org.postgresql.test.util.PgWire.readyForQuery;

import org.postgresql.core.CachedQuery;
import org.postgresql.core.Field;
import org.postgresql.core.PGStream;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.Tuple;
import org.postgresql.test.util.InMemorySocketFactory;
import org.postgresql.util.HostSpec;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Fails when the envelope arithmetic in a backend message reader of {@link QueryExecutorImpl}
 * lets through a message whose declared length and declared element count contradict each
 * other, or turns away one where they agree. That contradiction is what a desynced stream
 * looks like from inside a reader.
 *
 * <p>The driver writes its request and then reads the reply, so a canned byte script drives
 * these readers without a server: the executor's constructor consumes a ReadyForQuery, and
 * the message under test is what the following call finds on the stream.</p>
 */
class MessageReaderHardeningTest {

  /**
   * No implicit BEGIN, so the script describes one statement rather than two.
   */
  private static final int SELECT_FLAGS =
      QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_SUPPRESS_BEGIN;

  /** Collects rows and errors so a failed statement can be inspected after the call. */
  private static final class CollectingHandler extends ResultHandlerBase {
    final List<Tuple> rows = new ArrayList<>();
    Field @Nullable [] fields;

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        @Nullable ResultCursor cursor) {
      this.fields = fields;
      rows.addAll(tuples);
    }

    @Override
    public void handleWarning(SQLWarning warning) {
      // not interesting here
    }
  }

  private static QueryExecutorImpl executorReading(byte[] script) throws Exception {
    PGStream pgStream = new PGStream(
        new InMemorySocketFactory(concat(readyForQuery('I'), script)),
        new HostSpec("localhost", 1), 0, 8192);
    return new QueryExecutorImpl(pgStream, 0, new Properties());
  }

  /**
   * Asserts that {@code expected} appears in the failure or anywhere in its cause chain.
   * A reader that throws mid-statement has its failure wrapped by the time it reaches the
   * caller.
   */
  private static void assertFailureMentions(Throwable thrown, String expected) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      String message = t.getMessage();
      if (message != null && message.contains(expected)) {
        return;
      }
    }
    throw new AssertionError("Expected a failure mentioning \"" + expected + "\", got: "
        + thrown, thrown);
  }

  private static Exception runSelect(QueryExecutorImpl executor) {
    return assertThrows(Exception.class, () -> {
      Query query = executor.createSimpleQuery("select 1");
      executor.execute(query, null, new CollectingHandler(), 0, 0, SELECT_FLAGS);
    });
  }

  // RowDescription -------------------------------------------------------------------

  /**
   * A RowDescription whose body is nothing but the field count. Well-formed messages carry
   * at least 19 bytes per field after it.
   */
  private static byte[] rowDescriptionHeaderOnly(int declaredLength, int fieldCount) {
    return messageWithLength('T', declaredLength, int2(fieldCount));
  }

  @Test
  void rowDescriptionRejectsANegativeFieldCount() throws Exception {
    // The count is a signed int16 and becomes both the Field[] length and a loop bound, so
    // without the check the next line throws NegativeArraySizeException instead.
    QueryExecutorImpl executor = executorReading(rowDescriptionHeaderOnly(6, 0xffff));

    assertFailureMentions(runSelect(executor), "negative field count");
  }

  @Test
  void rowDescriptionRejectsAFieldCountBeyondTheEnvelope() throws Exception {
    // One field needs 19 bytes: an empty name plus tableOid, attnum, typeOid, typlen,
    // typmod and the format code. This message declares one byte less than that, so the
    // reader would have run into the following message while filling the last field.
    QueryExecutorImpl executor = executorReading(rowDescriptionHeaderOnly(24, 1));

    assertFailureMentions(runSelect(executor), "requires at least");
  }

  @Test
  void rowDescriptionAcceptsTheSmallestWellFormedDescription() throws Exception {
    // The boundary the case above sits one byte below: 6 + 19 bytes for a single field with
    // an empty name. Without this control, tightening the per-field minimum by one byte
    // would reject legitimate traffic and no test would notice.
    byte[] field = concat(
        cstring(""),      // column label
        int4(0),          // table oid
        int2(0),          // attnum
        int4(23),         // type oid: int4
        int2(4),          // type length
        int4(-1),         // type modifier
        int2(0));         // format code: text
    byte[] script = concat(
        message('T', int2(1), field),
        message('C', cstring("SELECT 0")),
        readyForQuery('I'));

    QueryExecutorImpl executor = executorReading(script);
    CollectingHandler handler = new CollectingHandler();
    Query query = executor.createSimpleQuery("select 1");
    executor.execute(query, null, handler, 0, 0, SELECT_FLAGS);

    Field[] fields = handler.fields;
    assertNotNull(fields, "the described fields should have reached the result handler");
    assertEquals(1, fields.length);
    assertEquals(23, fields[0].getOID());
  }

  // ParameterDescription -------------------------------------------------------------

  @Test
  void parameterDescriptionRejectsALengthThatContradictsTheCount() throws Exception {
    // The envelope is fully determined by the parameter count: 6 + 4 bytes each. A message
    // that declares two parameters in the space of one leaves the reader inside the next
    // message once it has read them.
    byte[] script = messageWithLength('t', 10, int2(2), int4(23));

    QueryExecutorImpl executor = executorReading(script);
    Exception thrown = assertThrows(Exception.class, () -> {
      CachedQuery cachedQuery = executor.createQuery("select ?", false, true);
      ParameterList parameters = cachedQuery.query.createParameterList();
      executor.execute(cachedQuery.query, parameters, new CollectingHandler(), 0, 0,
          QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_DESCRIBE_ONLY
              | QueryExecutor.QUERY_SUPPRESS_BEGIN);
    });

    assertFailureMentions(thrown, "requires message size");
  }

  // CopyInResponse / CopyOutResponse --------------------------------------------------

  /** A copy response: overall format byte, field count, then a format code per field. */
  private static byte[] copyInResponse(int declaredLength, int fieldCount) {
    return messageWithLength('G', declaredLength, int1(0), int2(fieldCount));
  }

  @Test
  void copyResponseRejectsANegativeFieldCount() throws Exception {
    // Same shape as the RowDescription count: signed int16, used as an array size.
    QueryExecutorImpl executor = executorReading(copyInResponse(7, 0xffff));

    Exception thrown = assertThrows(Exception.class,
        () -> executor.startCopy("copy t from stdin", true));
    assertFailureMentions(thrown, "negative field count");
  }

  @Test
  void copyResponseRejectsALengthThatContradictsTheCount() throws Exception {
    // The envelope is exactly 7 + 2 bytes per field, so a declared length that does not
    // match the count is a desync rather than an unknown extension.
    QueryExecutorImpl executor = executorReading(copyInResponse(7, 1));

    Exception thrown = assertThrows(Exception.class,
        () -> executor.startCopy("copy t from stdin", true));
    assertFailureMentions(thrown, "requires message size");
  }

  // FunctionCallResponse ---------------------------------------------------------------

  private static byte[] functionCallResponse(int declaredLength, int valueLength) {
    return messageWithLength('V', declaredLength, int4(valueLength));
  }

  @SuppressWarnings("deprecation")
  private static Exception runFastpathCall(QueryExecutorImpl executor) {
    return assertThrows(Exception.class,
        () -> executor.fastpathCall(1, executor.createFastpathParameters(0), true));
  }

  @Test
  void functionCallResponseRejectsANegativeValueLength() throws Exception {
    // The protocol gives -1 to NULL and non-negative values to everything else. Any other
    // negative leaves no way to read the value, and it used to reach new byte[valueLen].
    QueryExecutorImpl executor = executorReading(functionCallResponse(12, -2));

    assertFailureMentions(runFastpathCall(executor), "negative value length");
  }

  @Test
  void functionCallResponseRejectsAValueLengthBeyondTheEnvelope() throws Exception {
    // The single-value form of the issue #4015 field overrun: a value cannot be longer than
    // the message that carries it, and the driver allocated the declared length before
    // finding out.
    QueryExecutorImpl executor = executorReading(functionCallResponse(12, 5));

    assertFailureMentions(runFastpathCall(executor), "exceeds message size");
  }

  @Test
  void functionCallResponseAcceptsAValueThatFillsTheEnvelope() throws Exception {
    // The boundary: a value that occupies the envelope exactly. Without it, an off-by-one
    // in the comparison would reject the largest legal return value.
    byte[] value = filler(4);
    byte[] script = concat(
        message('V', int4(value.length), value),
        readyForQuery('I'));

    QueryExecutorImpl executor = executorReading(script);
    @SuppressWarnings("deprecation")
    byte[] returned = executor.fastpathCall(1, executor.createFastpathParameters(0), true);
    assertNotNull(returned);
    assertEquals(value.length, returned.length);
  }
}
