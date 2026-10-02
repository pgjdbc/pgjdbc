/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.copy.CopyOperation;
import org.postgresql.copy.CopyOut;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.CannedSocketFactory;
import org.postgresql.core.Field;
import org.postgresql.core.PGStream;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.Tuple;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Feeds the readers in {@link QueryExecutorImpl} canned backend messages and checks that the driver
 * refuses any message whose declared length disagrees with what follows, rather than reading past
 * the message or allocating from a count the length cannot hold. A canned message is what lets a
 * test give a length or a count a value a server would never send.
 *
 * <p>A RowDescription, ParameterDescription or CopyOutResponse carries a field count, so its reader
 * compares that count against the declared length and refuses the message before it reads anything
 * from the count. A ParameterStatus carries no count, so a body that ends before the declared end
 * is caught instead by the position check at the next message type. A message the driver buffers
 * whole is refused above {@link PGStream#MAX_BUFFERED_MESSAGE_LENGTH}.</p>
 *
 * <p>Two messages are read differently. An ErrorResponse or NoticeResponse past that buffer limit
 * is truncated rather than refused, and a DataRow past {@code maxResultBuffer} is never read: the
 * driver reports the limit and drops the connection.</p>
 *
 * <p>Each assertion builds its expected text with {@link GT#tr}, the call the driver used, so the
 * tests hold in any locale.</p>
 */
class BackendMessageEnvelopeTest {

  /**
   * The longest ParameterDescription the driver accepts: 4 (length) + 2 (parameter count) + 4 per
   * parameter, for the 65535 parameters an unsigned int2 count can announce.
   */
  private static final int MAX_PARAMETER_DESCRIPTION_LENGTH = 6 + 4 * 0xFFFF;

  /**
   * The longest copy response the driver accepts: 4 (length) + 1 (overall format) + 2 (field
   * count) + 2 per field, for the 65535 fields an unsigned int2 count can announce.
   */
  private static final int MAX_COPY_RESPONSE_LENGTH = 7 + 2 * 0xFFFF;

  /** The refusal {@link PGStream#receiveMessageLength} builds for a length outside its range. */
  private static String lengthRefusal(String messageName, int declared, int min, int max) {
    return GT.tr("Backend declared a {0} message length of {1} bytes, expected {2} to {3} bytes.",
        messageName, String.valueOf(declared), String.valueOf(min), String.valueOf(max));
  }

  /** Builds the bytes of a scripted backend reply. */
  private static class Script {
    private final ByteArrayOutputStream out;

    Script() {
      this(32);
    }

    Script(int capacity) {
      out = new ByteArrayOutputStream(capacity);
    }

    /**
     * Appends a well-formed message: the type byte, a length that includes its own four bytes,
     * then the body.
     */
    Script message(char type, byte[] body) {
      out.write(type);
      int length = body.length + 4;
      out.write(length >>> 24);
      out.write(length >>> 16);
      out.write(length >>> 8);
      out.write(length);
      out.write(body, 0, body.length);
      return this;
    }

    /**
     * Appends a message whose length prefix declares {@code declaredLength} rather than the length
     * of {@code body}.
     */
    Script messageOfDeclaredLength(char type, int declaredLength, byte[] body) {
      out.write(type);
      out.write(declaredLength >>> 24);
      out.write(declaredLength >>> 16);
      out.write(declaredLength >>> 8);
      out.write(declaredLength);
      out.write(body, 0, body.length);
      return this;
    }

    /**
     * Appends the reply the {@link QueryExecutorImpl} constructor consumes, ending at
     * ReadyForQuery.
     */
    Script startup() {
      message('S', bytes(cstring("server_version"), cstring("17.0")));
      message('K', bytes(int4(1), int4(2)));
      message('Z', new byte[]{'I'});
      return this;
    }

    Script readyForQuery() {
      return message('Z', new byte[]{'I'});
    }

    byte[] toBytes() {
      return out.toByteArray();
    }
  }

  private static byte[] bytes(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  private static byte[] int4(int value) {
    return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
        (byte) value};
  }

  private static byte[] int2(int value) {
    return new byte[]{(byte) (value >>> 8), (byte) value};
  }

  /** Returns the UTF-8 bytes of {@code value} plus a zero byte. */
  private static byte[] cstring(String value) {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    byte[] result = new byte[encoded.length + 1];
    System.arraycopy(encoded, 0, result, 0, encoded.length);
    return result;
  }

  /** One field description with an empty name is the shortest the layout allows, 19 bytes. */
  private static byte[] fieldDescription() {
    return bytes(cstring(""), int4(0), int2(0), int4(23), int2(4), int4(-1), int2(0));
  }

  private static PGStream streamOf(Script script) throws IOException {
    return new PGStream(new CannedSocketFactory(script.toBytes()),
        new HostSpec("localhost", 5432), 0, 8192);
  }

  private static QueryExecutor executorOf(Script script) throws SQLException, IOException {
    return new QueryExecutorImpl(streamOf(script), 0, new Properties());
  }

  /** Keeps the last warning the query reported, and discards its rows and command status. */
  private static class CollectingHandler extends ResultHandlerBase {
    private @Nullable SQLWarning warning;

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        ResultCursor cursor) {
    }

    @Override
    public void handleCommandStatus(String status, long updateCount, long insertOID) {
    }

    @Override
    public void handleWarning(SQLWarning warning) {
      this.warning = warning;
    }
  }

  /**
   * Runs one simple query against the scripted reply and returns the error it produced, or null
   * if the reply was accepted.
   */
  private static @Nullable SQLException runQuery(Script script) throws IOException {
    try {
      return runQuery(executorOf(script), new CollectingHandler());
    } catch (SQLException e) {
      return e;
    }
  }

  private static @Nullable SQLException runQuery(QueryExecutor executor,
      CollectingHandler handler) {
    try {
      Query query = executor.createSimpleQuery("select 1");
      executor.execute(query, null, handler, 0, 0, QueryExecutor.QUERY_ONESHOT
          | QueryExecutor.QUERY_EXECUTE_AS_SIMPLE | QueryExecutor.QUERY_SUPPRESS_BEGIN);
      return handler.getException();
    } catch (SQLException e) {
      return e;
    }
  }

  @Test
  void acceptsARowDescriptionThatFitsItsFieldCount() throws Exception {
    Script script = new Script().startup()
        .message('T', bytes(int2(1), fieldDescription()))
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    assertNull(runQuery(script));
  }

  /** The one field description is a byte short, 18 bytes where 19 are needed. */
  @Test
  void rejectsARowDescriptionTooShortForItsFieldCount() throws Exception {
    byte[] shortField = new byte[fieldDescription().length - 1];
    Script script = new Script().startup()
        .message('T', bytes(int2(1), shortField))
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    SQLException e = runQuery(script);
    assertNotNull(e, "a RowDescription that cannot hold its fields must be refused");
    // 4 length + 2 count + the 18 bytes of the short description is a declared 24.
    assertEquals(GT.tr("RowDescription of {0} bytes cannot hold {1} field descriptions.",
        "24", "1"), rootCause(e).getMessage());
  }

  /**
   * The message declares 1664 fields and holds one field description. 1664 is the most fields a
   * server can send, because PostgreSQL refuses a target list with more entries, and without the
   * check the reader would take the other 1663 descriptions from the messages behind it.
   */
  @Test
  void rejectsARowDescriptionClaimingFieldsItCannotHold() throws Exception {
    Script script = new Script().startup()
        .message('T', bytes(int2(1664), fieldDescription()))
        .readyForQuery();

    SQLException e = runQuery(script);
    assertNotNull(e, "a RowDescription claiming 1664 fields must be refused");
    // 4 length + 2 count + the 19 bytes of the one description is a declared 25.
    assertEquals(GT.tr("RowDescription of {0} bytes cannot hold {1} field descriptions.",
        "25", "1664"), rootCause(e).getMessage());
  }

  /**
   * Runs one describe-only parameterized query and returns the error it produced, or null if the
   * reply was accepted. Only such a request draws a ParameterDescription.
   */
  private static @Nullable SQLException describeQuery(Script script) throws IOException {
    try {
      QueryExecutor executor = executorOf(script);
      CachedQuery cached = executor.createQuery("select ?", false, true);
      Query query = cached.query;
      CollectingHandler handler = new CollectingHandler();
      executor.execute(query, query.createParameterList(), handler, 0, 0,
          QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_DESCRIBE_ONLY
              | QueryExecutor.QUERY_SUPPRESS_BEGIN);
      return handler.getException();
    } catch (SQLException e) {
      return e;
    }
  }

  @Test
  void acceptsAParameterDescriptionThatHoldsItsParameterTypes() throws Exception {
    Script script = new Script().startup()
        .message('1', new byte[0])
        .message('t', bytes(int2(1), int4(23)))
        .message('n', new byte[0])
        .readyForQuery();

    assertNull(describeQuery(script));
  }

  /** The count claims two type OIDs where the declared 10 bytes hold the count and one OID. */
  @Test
  void rejectsAParameterDescriptionWhoseCountDoesNotFillIt() throws Exception {
    Script script = new Script().startup()
        .message('1', new byte[0])
        .messageOfDeclaredLength('t', 10, bytes(int2(2), int4(23)))
        .readyForQuery();

    SQLException e = describeQuery(script);
    assertNotNull(e, "a ParameterDescription that does not hold its types must be refused");
    assertEquals(GT.tr("ParameterDescription of {0} bytes does not hold exactly {1} parameter"
        + " types.", "10", "2"), rootCause(e).getMessage());
  }

  /**
   * A count of 65535 parameters fills {@link #MAX_PARAMETER_DESCRIPTION_LENGTH} exactly, so one
   * byte more is refused by the length check, before the count is read.
   */
  @Test
  void rejectsAParameterDescriptionAboveItsMaximumLength() throws Exception {
    Script script = new Script().startup()
        .message('1', new byte[0])
        .messageOfDeclaredLength('t', MAX_PARAMETER_DESCRIPTION_LENGTH + 1,
            bytes(int2(1), int4(23)))
        .readyForQuery();

    SQLException e = describeQuery(script);
    assertNotNull(e, "a ParameterDescription longer than its maximum must be refused");
    assertEquals(lengthRefusal("ParameterDescription", MAX_PARAMETER_DESCRIPTION_LENGTH + 1, 6,
        MAX_PARAMETER_DESCRIPTION_LENGTH), rootCause(e).getMessage());
  }

  @Test
  void acceptsACopyOutResponseThatHoldsItsFieldFormats() throws Exception {
    Script script = new Script().startup()
        .message('H', bytes(new byte[]{0}, int2(1), int2(0)))
        .message('c', new byte[0])
        .message('C', cstring("COPY 0"))
        .readyForQuery();

    QueryExecutor executor = executorOf(script);
    CopyOperation op = executor.startCopy("copy t to stdout", true);
    assertEquals(1, ((CopyOut) op).getFieldCount(), "field count of the copy operation");
  }

  /** The count claims one field format where the declared 11 bytes leave room for two. */
  @Test
  void rejectsACopyOutResponseWhoseFieldCountDoesNotFillIt() throws Exception {
    Script script = new Script().startup()
        .messageOfDeclaredLength('H', 11, bytes(new byte[]{0}, int2(1), int2(0)))
        .readyForQuery();

    QueryExecutor executor = executorOf(script);
    SQLException e = assertThrows(SQLException.class,
        () -> executor.startCopy("copy t to stdout", true));
    assertEquals(GT.tr("Copy response of {0} bytes does not hold exactly {1} field formats.",
        "11", "1"), rootCause(e).getMessage());
  }

  /**
   * A count of 65535 fields fills {@link #MAX_COPY_RESPONSE_LENGTH} exactly, so one byte more is
   * refused by the length check, before the count is read.
   */
  @Test
  void rejectsACopyOutResponseAboveItsMaximumLength() throws Exception {
    Script script = new Script().startup()
        .messageOfDeclaredLength('H', MAX_COPY_RESPONSE_LENGTH + 1,
            bytes(new byte[]{0}, int2(1), int2(0)))
        .readyForQuery();

    QueryExecutor executor = executorOf(script);
    SQLException e = assertThrows(SQLException.class,
        () -> executor.startCopy("copy t to stdout", true));
    assertEquals(lengthRefusal("CopyResponse", MAX_COPY_RESPONSE_LENGTH + 1, 7,
        MAX_COPY_RESPONSE_LENGTH), rootCause(e).getMessage());
  }

  /**
   * ParameterStatus is two C strings, so a reader that stops before the declared end is caught
   * only by the position check in {@link PGStream#receiveMessageType()}.
   */
  @Test
  void rejectsAParameterStatusThatDoesNotFillItsMessage() throws Exception {
    Script script = new Script()
        .messageOfDeclaredLength('S', 12, bytes(cstring("a"), cstring("b"), new byte[4]))
        .message('Z', new byte[]{'I'});

    IOException e = assertThrows(IOException.class, () -> executorOf(script));
    // The type byte and the 4 length bytes end at stream position 5, so the declared 12 puts the
    // end at 13, and the two strings leave the reader at 9.
    assertEquals(GT.tr("The previous backend message declared its end at byte {0} of the stream,"
        + " but its reader stopped at byte {1}.", "13", "9"), e.getMessage());
  }

  /**
   * ParameterStatus is buffered whole, so its limit is
   * {@link PGStream#MAX_BUFFERED_MESSAGE_LENGTH} and one byte more is refused rather than
   * truncated. A real server sends kilobytes at most.
   */
  @Test
  void rejectsAParameterStatusAboveTheBufferedMaximum() throws Exception {
    Script script = new Script()
        .messageOfDeclaredLength('S', PGStream.MAX_BUFFERED_MESSAGE_LENGTH + 1,
            bytes(cstring("a"), cstring("b")))
        .message('Z', new byte[]{'I'});

    IOException e = assertThrows(IOException.class, () -> executorOf(script));

    assertEquals(lengthRefusal("ParameterStatus", PGStream.MAX_BUFFERED_MESSAGE_LENGTH + 1, 6,
        PGStream.MAX_BUFFERED_MESSAGE_LENGTH), e.getMessage());
  }

  /**
   * A NotificationResponse is buffered whole as well, and its payload is under 8000 bytes at the
   * default block size, so the same limit refuses one byte more.
   */
  @Test
  void rejectsANotificationResponseAboveTheBufferedMaximum() throws Exception {
    Script script = new Script().startup()
        .messageOfDeclaredLength('A', PGStream.MAX_BUFFERED_MESSAGE_LENGTH + 1,
            bytes(int4(1), cstring("channel"), cstring("payload")))
        .readyForQuery();

    SQLException e = runQuery(script);
    assertNotNull(e, "a NotificationResponse longer than its maximum must be refused");
    assertEquals(lengthRefusal("NotificationResponse", PGStream.MAX_BUFFERED_MESSAGE_LENGTH + 1, 10,
        PGStream.MAX_BUFFERED_MESSAGE_LENGTH), rootCause(e).getMessage());
  }

  @Test
  void acceptsAParameterStatusThatFillsItsMessage() throws Exception {
    Script script = new Script()
        .message('S', bytes(cstring("a"), cstring("b")))
        .message('Z', new byte[]{'I'});

    assertNotNull(executorOf(script), "the connection must come up");
  }

  @Test
  void rejectsAReadyForQueryOfTheWrongLength() throws Exception {
    Script script = new Script().messageOfDeclaredLength('Z', 6, new byte[]{'I', 0});

    IOException e = assertThrows(IOException.class, () -> executorOf(script));
    assertEquals(lengthRefusal("ReadyForQuery", 6, 5, 5), e.getMessage());
  }

  /**
   * Returns the error fields for a message one byte longer than
   * {@link PGStream#MAX_BUFFERED_MESSAGE_LENGTH}. The query field comes last, so it is the field
   * the truncation cuts.
   */
  private static byte[] oversizedErrorFields() {
    byte[] head = bytes(cstring("SERROR"), cstring("C42601"), cstring("Mboom"), new byte[]{'q'});
    byte[] body = new byte[PGStream.MAX_BUFFERED_MESSAGE_LENGTH + 1 - 4];
    System.arraycopy(head, 0, body, 0, head.length);
    // The last two bytes stay zero, the string terminator and the field list terminator.
    Arrays.fill(body, head.length, body.length - 2, (byte) 'x');
    return body;
  }

  /** The fields before the truncation point are kept and the connection stays usable. */
  @Test
  void truncatesAnErrorResponsePastTheBufferMaximum() throws Exception {
    byte[] body = oversizedErrorFields();
    Script script = new Script(body.length + 64).startup()
        .message('E', body)
        .readyForQuery()
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    QueryExecutor executor = executorOf(script);
    SQLException e = runQuery(executor, new CollectingHandler());
    assertNotNull(e, "the truncated ErrorResponse must still reach the caller");
    ServerErrorMessage message = ((PSQLException) e).getServerErrorMessage();
    assertNotNull(message, "the error must carry its server fields");
    String query = message.getInternalQuery();
    assertNotNull(query, "the query field must survive the truncation");
    assertAll(
        () -> assertEquals("42601", message.getSQLState(), "SQLState field"),
        () -> assertEquals("boom", message.getMessage(), "message field"),
        () -> assertTrue(query.startsWith("xxx") && query.length() < body.length,
            "the query field must be kept and truncated"),
        () -> assertNull(runQuery(executor, new CollectingHandler()),
            "the connection must stay usable after the truncation"));
  }

  @Test
  void truncatesANoticeResponsePastTheBufferMaximum() throws Exception {
    byte[] body = oversizedErrorFields();
    Script script = new Script(body.length + 64).startup()
        .message('N', body)
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    CollectingHandler handler = new CollectingHandler();
    assertNull(runQuery(executorOf(script), handler), "the query must succeed");
    assertNotNull(handler.warning, "the truncated NoticeResponse must reach the handler");
    assertEquals("42601", handler.warning.getSQLState(), "SQLState of the warning");
  }

  /**
   * The truncation point falls between the two bytes of one character, and the tolerant decoding
   * has to deliver the notice anyway rather than fail the read.
   */
  @Test
  void truncatesANoticeResponseThroughAMultibyteCharacter() throws Exception {
    int buffered = PGStream.MAX_BUFFERED_MESSAGE_LENGTH - 4;
    byte[] body = new byte[buffered + 8];
    body[0] = 'M';
    Arrays.fill(body, 1, buffered - 1, (byte) 'x');
    // U+00E9 takes two bytes, and the truncation keeps its lead byte and drops the trail byte.
    body[buffered - 1] = (byte) 0xC3;
    body[buffered] = (byte) 0xA9;
    Arrays.fill(body, buffered + 1, body.length - 1, (byte) 'x');

    Script script = new Script(body.length + 64).startup()
        .message('N', body)
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    CollectingHandler handler = new CollectingHandler();
    assertNull(runQuery(executorOf(script), handler), "the query must succeed");
    assertNotNull(handler.warning, "the truncated NoticeResponse must reach the handler");
    String message = handler.warning.getMessage();
    assertNotNull(message, "the warning must carry its message field");
    assertTrue(message.startsWith("xxx"), message);
  }

  /**
   * The row is never read, so the stream is left in the middle of a message and cannot be used
   * again. That is why the limit drops the connection rather than reporting and carrying on.
   */
  @Test
  void dropsTheConnectionPastMaxResultBuffer() throws Exception {
    byte[] column = new byte[200];
    Script script = new Script().startup()
        .message('T', bytes(int2(1), fieldDescription()))
        .message('D', bytes(int2(1), int4(column.length), column))
        .message('C', cstring("SELECT 1"))
        .readyForQuery();

    PGStream stream = streamOf(script);
    stream.setMaxResultBuffer("100");
    QueryExecutor executor = new QueryExecutorImpl(stream, 0, new Properties());
    SQLException e = runQuery(executor, new CollectingHandler());
    assertNotNull(e, "the row past the limit must be reported");
    assertAll(
        // The declared 210 bytes leave 200 for column data, which is what the limit counts.
        () -> assertEquals(GT.tr("Result set exceeded maxResultBuffer limit. Received:  {0};"
            + " Current limit: {1}", "200", "100"), e.getMessage()),
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(),
            "SQLState of the refusal"),
        () -> assertNull(e.getNextException(), "the limit must be the only error reported"),
        () -> assertTrue(stream.isBroken(), "the stream must be marked broken"),
        () -> assertTrue(executor.isClosed(), "the executor must report itself closed"));
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
