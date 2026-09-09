/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The packet length check on the encrypted transport, which only runs once GSS encryption is on.
 * The context is a stub whose unwrap returns its input, so the length handling is exercised
 * without a Kerberos realm.
 */
@Isolated("Uses Locale.setDefault")
class GSSInputStreamTest {

  /** PQ_GSS_MAX_PACKET_SIZE less the length word. */
  private static final int MAX_PAYLOAD_SIZE = 16 * 1024 - 4;

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

  /** A context whose unwrap hands back its input unchanged. */
  private static GSSContext echoContext() {
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        if ("unwrap".equals(method.getName())) {
          byte[] buf = (byte[]) args[0];
          int off = (Integer) args[1];
          int len = (Integer) args[2];
          return Arrays.copyOfRange(buf, off, off + len);
        }
        return null;
      }
    };
    return (GSSContext) Proxy.newProxyInstance(GSSInputStreamTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, handler);
  }

  private static byte[] frame(int declaredLength, int payloadBytes) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(declaredLength >>> 24);
    out.write(declaredLength >>> 16);
    out.write(declaredLength >>> 8);
    out.write(declaredLength);
    for (int i = 0; i < payloadBytes; i++) {
      out.write('x');
    }
    return out.toByteArray();
  }

  private static GSSInputStream streamOf(byte[] bytes, AtomicBoolean violated) {
    return new GSSInputStream(new ByteArrayInputStream(bytes), echoContext(),
        new MessageProp(0, true),
        new Runnable() {
          @Override
          public void run() {
            violated.set(true);
          }
        });
  }

  @Test
  void rejectsAPacketAboveThePayloadMaximum() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(MAX_PAYLOAD_SIZE + 1, 0), violated);

    IOException e = assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertTrue(e.getMessage().contains("GSS packet"), e.getMessage());
    assertTrue(violated.get(), "the refusal must run the protocol violation callback");
  }

  @Test
  void rejectsAZeroLengthPacket() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(0, 0), violated);

    assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertTrue(violated.get());
  }

  /** A packet at the payload maximum is sent in full and must not be refused. */
  @Test
  void acceptsAPacketAtThePayloadMaximum() throws IOException {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(MAX_PAYLOAD_SIZE, MAX_PAYLOAD_SIZE), violated);

    byte[] buffer = new byte[16];
    int read = in.read(buffer, 0, buffer.length);

    assertEquals(buffer.length, read);
    assertEquals('x', buffer[0]);
    assertFalse(violated.get());
  }
}
