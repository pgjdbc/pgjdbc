/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.GT;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Feeds {@link GSSInputStream} packets whose declared length is on one side or the other of the
 * range it accepts, 1 to {@link #MAX_PAYLOAD_SIZE} bytes, and checks that a length outside that
 * range is refused and that the refusal runs the protocol violation callback before it throws.
 *
 * <p>The stream is installed only on a GSS encrypted connection, so the context here is a stub
 * whose unwrap returns the bytes it is given; that is enough to reach the length handling without a
 * Kerberos realm.</p>
 *
 * <p>Each assertion builds its expected text with {@link GT#tr}, the call the driver used, so the
 * tests hold in any locale.</p>
 */
class GSSInputStreamTest {

  /**
   * The largest declared length {@link GSSInputStream} accepts. PostgreSQL's
   * PQ_GSS_MAX_PACKET_SIZE of 16 kB counts the 4 length bytes, so the payload maximum is four
   * bytes smaller.
   */
  private static final int MAX_PAYLOAD_SIZE = 16 * 1024 - 4;

  /** The refusal {@link GSSInputStream} builds for a declared length outside its range. */
  private static String refusalFor(int declaredLength) {
    return GT.tr("Backend declared a GSS packet of {0} bytes, the maximum is {1}.",
        String.valueOf(declaredLength), String.valueOf(MAX_PAYLOAD_SIZE));
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

    assertAll(
        () -> assertEquals(refusalFor(MAX_PAYLOAD_SIZE + 1), e.getMessage()),
        () -> assertTrue(violated.get(),
            "the protocol violation callback must have run before the throw"));
  }

  /** A packet of no bytes carries no token to unwrap, so zero is outside the range as well. */
  @Test
  void rejectsAZeroLengthPacket() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(0, 0), violated);

    IOException e = assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertAll(
        () -> assertEquals(refusalFor(0), e.getMessage()),
        () -> assertTrue(violated.get(),
            "the protocol violation callback must have run before the throw"));
  }

  /**
   * The length is read as a signed int4, so the four header bytes can declare a negative length.
   * The same check refuses it, and the refusal quotes that negative length rather than only naming
   * the maximum.
   */
  @Test
  void rejectsANegativeLengthPacket() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(-1, 0), violated);

    IOException e = assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertAll(
        () -> assertEquals(refusalFor(-1), e.getMessage()),
        () -> assertTrue(violated.get(),
            "the protocol violation callback must have run before the throw"));
  }

  /** A packet at the payload maximum is sent in full and must not be refused. */
  @Test
  void acceptsAPacketAtThePayloadMaximum() throws IOException {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(frame(MAX_PAYLOAD_SIZE, MAX_PAYLOAD_SIZE), violated);

    byte[] buffer = new byte[16];
    int read = in.read(buffer, 0, buffer.length);

    assertAll(
        () -> assertEquals(buffer.length, read, "bytes returned by the read"),
        () -> assertEquals('x', buffer[0], "first unwrapped byte"),
        () -> assertFalse(violated.get(), "the protocol violation callback must not have run"));
  }
}
