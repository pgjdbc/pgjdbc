/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.core.v3.ConnectionFactoryImpl;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

class SaslAuthenticationRequestTest {
  @Test
  void refusesASecondSaslAuthenticationRequest() throws Exception {
    ByteArrayOutputStream backend = new ByteArrayOutputStream();
    for (int i = 0; i < 2; i++) {
      byte[] mechanisms = "SCRAM-SHA-256\0\0".getBytes(StandardCharsets.US_ASCII);
      backend.write(PgMessageType.AUTHENTICATION_RESPONSE);
      backend.write(new byte[]{0, 0, 0, (byte) (8 + mechanisms.length), 0, 0, 0, 10}, 0, 8);
      backend.write(mechanisms, 0, mechanisms.length);
    }
    CannedSocketFactory factory = new CannedSocketFactory(backend.toByteArray());
    PGStream stream = new PGStream(factory, new HostSpec("localhost", 5432), 0, 8192);
    Properties info = new Properties();
    PGProperty.PASSWORD.set(info, "test");

    PSQLException e = assertThrows(PSQLException.class, () -> authenticate(stream, info));

    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), e.toString());
    assertEquals(1, countPasswordMessages(factory.getWritten()),
        "the driver must send one SASLInitialResponse");
  }

  /** Walks the PasswordMessages the driver wrote. */
  private static int countPasswordMessages(byte[] written) {
    int count = 0;
    int pos = 0;
    while (pos < written.length) {
      assertEquals(PgMessageType.PASSWORD_REQUEST, written[pos], "message type at byte " + pos);
      int length = ((written[pos + 1] & 0xFF) << 24) | ((written[pos + 2] & 0xFF) << 16)
          | ((written[pos + 3] & 0xFF) << 8) | (written[pos + 4] & 0xFF);
      pos += 1 + length;
      count++;
    }
    assertEquals(written.length, pos, "the last message must end where the output ends");
    return count;
  }

  /** doAuthentication is private. */
  private static void authenticate(PGStream stream, Properties info) throws Exception {
    Method method = ConnectionFactoryImpl.class.getDeclaredMethod("doAuthentication",
        PGStream.class, String.class, String.class, Properties.class);
    method.setAccessible(true);
    try {
      method.invoke(null, stream, "localhost", "test", info);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }
}
