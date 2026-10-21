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
 * A GSS packet is refused unless its declared length is between 1 and {@link #MAX_PAYLOAD_SIZE}
 * bytes, and a refusal runs the protocol violation callback before it throws.
 *
 * <p>{@link GSSInputStream} is installed only on a GSS encrypted connection. The context here is a
 * stub whose unwrap returns the bytes it is given, so the length handling is exercised without a
 * Kerberos realm.</p>
 */
@Isolated("Uses Locale.setDefault")
class GSSInputStreamTest {

  /**
   * The largest declared length {@link GSSInputStream} accepts. PostgreSQL's
   * PQ_GSS_MAX_PACKET_SIZE of 16 kB counts the 4 length bytes, so the payload maximum is four
   * bytes smaller.
   */
  private static final int MAX_PAYLOAD_SIZE = 16 * 1024 - 4;

  private static Locale defaultLocale;

  /**
   * We force the root locale because the assertions below match the English text GT.tr returns,
   * and a translated default locale would fail them. The guard is partial: GT resolves its bundle
   * once, in a static initializer, so setting the locale here only reaches GT when this class is
   * the first to load it. What saves the assertions today is that no catalog carries a translation
   * of the messages they match.
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

  /**
   * Returns a context whose unwrap returns the range of bytes it is given. The length handling
   * calls only unwrap, so every other method returns null.
   */
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

  /**
   * Returns a packet whose 4 byte header declares {@code declaredLength}, followed by
   * {@code payloadBytes} bytes of payload. The two need not agree.
   */
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
