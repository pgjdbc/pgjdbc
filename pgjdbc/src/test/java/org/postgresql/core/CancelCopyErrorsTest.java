/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.copy.CopyIn;
import org.postgresql.core.v3.QueryExecutorImpl;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

class CancelCopyErrorsTest {
  @Test
  void stopsCancellingACopyAfterTheSecondError() throws Exception {
    ByteArrayOutputStream backend = new ByteArrayOutputStream();
    writeMessage(backend, PgMessageType.BACKEND_KEY_DATA_RESPONSE, new byte[]{0, 0, 0, 1, 0, 0, 0, 2});
    writeMessage(backend, PgMessageType.READY_FOR_QUERY_RESPONSE, new byte[]{'I'});
    writeMessage(backend, PgMessageType.COPY_IN_RESPONSE, new byte[]{0, 0, 0});
    for (int i = 0; i < 100; i++) {
      writeMessage(backend, PgMessageType.COPY_DATA, new byte[]{0});
    }
    writeMessage(backend, PgMessageType.ERROR_RESPONSE,
        "SERROR\0C57014\0Mcancelled\0\0".getBytes(StandardCharsets.US_ASCII));
    writeMessage(backend, PgMessageType.READY_FOR_QUERY_RESPONSE, new byte[]{'I'});
    CannedSocketFactory factory = new CannedSocketFactory(backend.toByteArray());
    PGStream stream = new PGStream(factory, new HostSpec("localhost", 5432), 0, 8192);
    QueryExecutorImpl executor = new QueryExecutorImpl(stream, 0, new Properties());
    CopyIn copyIn = (CopyIn) executor.startCopy("COPY t FROM STDIN", true);

    PSQLException e = assertThrows(PSQLException.class, copyIn::cancelCopy);

    assertEquals(GT.tr("Got {0} error responses to single copy cancel request", "2"),
        e.getMessage());
    assertTrue(executor.isClosed(), "the connection must not look reusable");
  }

  private static void writeMessage(ByteArrayOutputStream out, int type, byte[] body) {
    int length = 4 + body.length;
    out.write(type);
    out.write(new byte[]{(byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8),
        (byte) length}, 0, 4);
    out.write(body, 0, body.length);
  }
}
