/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

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
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * The driver reads each backend message within the length that message declared, no more and no
 * less. It refuses a field or parameter count that the length cannot hold, before it allocates or
 * reads anything from that count. A reader that stops short of the length is caught at the next
 * message type.
 *
 * <p>Two messages are read differently. An ErrorResponse or NoticeResponse longer than
 * {@link PGStream#MAX_BUFFERED_MESSAGE_LENGTH} is truncated rather than refused, and a DataRow past
 * {@code maxResultBuffer} is never read: the driver reports the limit and drops the connection.</p>
 *
 * <p>Each test drives the readers in {@link QueryExecutorImpl} from a canned reply, so a test can
 * give a count or a length a value a server would never send.</p>
 */
@Isolated("Uses Locale.setDefault")
class BackendMessageEnvelopeTest {

  private static Locale defaultLocale;

  /**
   * We force the root locale because the assertions below match the English text GT.tr returns,
   * and a translated default locale would fail them. The guard is partial: GT resolves its bundle
   * once, in a static initializer, so setting the locale here only reaches GT when this class is
   * the first to load it. Of the messages these assertions match, only the maxResultBuffer limit
   * has translations, and each of those keeps the property name the assertion looks for.
   */
  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
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

  /**
   * Sends a RowDescription whose one field description is a byte short, 18 where 19 are needed, and
   * expects it to be refused.
   */
  @Test
  void rejectsARowDescriptionTooShortForItsFieldCount() throws Exception {
    byte[] shortField = new byte[fieldDescription().length - 1];
    Script script = new Script().startup()
        .message('T', bytes(int2(1), shortField))
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    SQLException e = runQuery(script);
    assertNotNull(e, "a RowDescription that cannot hold its fields must be refused");
    assertTrue(rootCause(e).getMessage().contains("cannot hold"), rootCause(e).getMessage());
  }

  /**
   * Sends a RowDescription that declares 1664 fields in a message holding one field description,
   * and expects it to be refused. 1664 is the most fields a server can send, because PostgreSQL
   * refuses a target list with more entries, and without the check the reader would take the other
   * 1663 descriptions from the messages behind it.
   */
  @Test
  void rejectsARowDescriptionClaimingFieldsItCannotHold() throws Exception {
    Script script = new Script().startup()
        .message('T', bytes(int2(1664), fieldDescription()))
        .readyForQuery();

    SQLException e = runQuery(script);
    assertNotNull(e);
    assertTrue(rootCause(e).getMessage().contains("cannot hold"), rootCause(e).getMessage());
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

  /**
   * Sends a ParameterDescription whose count claims two type OIDs where the declared 10 bytes hold
   * the count and a single OID, and expects it to be refused.
   */
  @Test
  void rejectsAParameterDescriptionWhoseCountDoesNotFillIt() throws Exception {
    Script script = new Script().startup()
        .message('1', new byte[0])
        .messageOfDeclaredLength('t', 10, bytes(int2(2), int4(23)))
        .readyForQuery();

    SQLException e = describeQuery(script);
    assertNotNull(e, "a ParameterDescription that does not hold its types must be refused");
    assertTrue(rootCause(e).getMessage().contains("parameter types"), rootCause(e).getMessage());
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
    assertEquals(1, ((CopyOut) op).getFieldCount());
  }

  /**
   * Sends a CopyOutResponse whose count claims one field format where the declared 11 bytes leave
   * room for two, and expects it to be refused.
   */
  @Test
  void rejectsACopyOutResponseWhoseFieldCountDoesNotFillIt() throws Exception {
    Script script = new Script().startup()
        .messageOfDeclaredLength('H', 11, bytes(new byte[]{0}, int2(1), int2(0)))
        .readyForQuery();

    QueryExecutor executor = executorOf(script);
    SQLException e = assertThrows(SQLException.class,
        () -> executor.startCopy("copy t to stdout", true));
    assertTrue(rootCause(e).getMessage().contains("field formats"), rootCause(e).getMessage());
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
    assertTrue(e.getMessage().contains("stopped at byte"), e.getMessage());
  }

  @Test
  void acceptsAParameterStatusThatFillsItsMessage() throws Exception {
    Script script = new Script()
        .message('S', bytes(cstring("a"), cstring("b")))
        .message('Z', new byte[]{'I'});

    assertNotNull(executorOf(script));
  }

  @Test
  void rejectsAReadyForQueryOfTheWrongLength() throws Exception {
    Script script = new Script().messageOfDeclaredLength('Z', 6, new byte[]{'I', 0});

    IOException e = assertThrows(IOException.class, () -> executorOf(script));
    assertTrue(e.getMessage().contains("ReadyForQuery"), e.getMessage());
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
    assertNotNull(e);
    ServerErrorMessage message = ((PSQLException) e).getServerErrorMessage();
    assertNotNull(message);
    assertEquals("42601", message.getSQLState());
    assertEquals("boom", message.getMessage());
    String query = message.getInternalQuery();
    assertNotNull(query);
    assertTrue(query.startsWith("xxx") && query.length() < body.length,
        "the query field must be kept and truncated");

    assertNull(runQuery(executor, new CollectingHandler()));
  }

  @Test
  void truncatesANoticeResponsePastTheBufferMaximum() throws Exception {
    byte[] body = oversizedErrorFields();
    Script script = new Script(body.length + 64).startup()
        .message('N', body)
        .message('C', cstring("SELECT 0"))
        .readyForQuery();

    CollectingHandler handler = new CollectingHandler();
    assertNull(runQuery(executorOf(script), handler));
    assertNotNull(handler.warning);
    assertEquals("42601", handler.warning.getSQLState());
  }

  /**
   * Truncates a NoticeResponse in the middle of a two byte UTF-8 character and expects the tolerant
   * decoding to deliver the notice rather than fail the read.
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
    assertNull(runQuery(executorOf(script), handler));
    assertNotNull(handler.warning);
    String message = handler.warning.getMessage();
    assertNotNull(message);
    assertTrue(message.startsWith("xxx"), message);
  }

  /**
   * A DataRow past maxResultBuffer reports the limit and drops the connection. The row is never
   * read, so the stream is left in the middle of a message and cannot be used again.
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
    assertNotNull(e);
    assertTrue(e.getMessage().contains("maxResultBuffer"), e.getMessage());
    assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState());
    assertNull(e.getNextException(), "the limit must be the only error reported");
    assertTrue(stream.isBroken());
    assertTrue(executor.isClosed());
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
