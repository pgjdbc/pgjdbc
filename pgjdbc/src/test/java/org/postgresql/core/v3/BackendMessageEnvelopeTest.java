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
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Drives the readers in {@link QueryExecutorImpl} from a canned reply, so the counts they take
 * off the wire can be given values a server would never send.
 */
@Isolated("Uses Locale.setDefault")
class BackendMessageEnvelopeTest {

  // The assertions match on message text, which GT.tr translates once these strings are
  // localized.
  private static Locale defaultLocale;

  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  private static class Script {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

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

    /** A message whose declared length is not the length of what follows it. */
    Script messageOfDeclaredLength(char type, int declaredLength, byte[] body) {
      out.write(type);
      out.write(declaredLength >>> 24);
      out.write(declaredLength >>> 16);
      out.write(declaredLength >>> 8);
      out.write(declaredLength);
      out.write(body, 0, body.length);
      return this;
    }

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

  private static QueryExecutor executorOf(Script script) throws SQLException, IOException {
    PGStream stream = new PGStream(new CannedSocketFactory(script.toBytes()),
        new HostSpec("localhost", 5432), 0, 8192);
    return new QueryExecutorImpl(stream, 0, new Properties());
  }

  private static class CollectingHandler extends ResultHandlerBase {
    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        ResultCursor cursor) {
    }

    @Override
    public void handleCommandStatus(String status, long updateCount, long insertOID) {
    }

    @Override
    public void handleWarning(SQLWarning warning) {
    }
  }

  /**
   * Runs one simple query against the scripted reply and returns whatever error came out of it,
   * or null if the reply was accepted.
   */
  private static @Nullable SQLException runQuery(Script script) throws IOException {
    try {
      QueryExecutor executor = executorOf(script);
      Query query = executor.createSimpleQuery("select 1");
      CollectingHandler handler = new CollectingHandler();
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

  /** One byte short of what a single field description needs. */
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
   * A tiny message claiming many fields would read its field descriptions out of the
   * messages that follow it.
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
   * Runs one describe-only parameterized query, the request a ParameterDescription answers.
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

  /** One type OID more than the length can hold. */
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

  /** One field format too few for the declared length. */
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
   * ParameterStatus is two C strings, so a reader that stops before the declared end is only
   * caught by comparing the position at the next message.
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

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
