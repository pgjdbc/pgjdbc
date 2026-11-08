/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.VisibleBufferedInputStream;

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
 * Packet handling on the encrypted transport, which only runs once GSS encryption is on: the packet
 * length check and the count of decrypted bytes that {@code available()} reports. The context is a
 * stub whose unwrap returns its input, so packets are read without a Kerberos realm.
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

  /** A context whose unwrap returns its input unchanged. */
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

  private static byte[] packet(int declaredLength, int payloadBytes) {
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

  private static byte[] wire(byte[]... packets) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] packet : packets) {
      out.write(packet, 0, packet.length);
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
    GSSInputStream in = streamOf(packet(MAX_PAYLOAD_SIZE + 1, 0), violated);

    IOException e = assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertTrue(e.getMessage().contains("GSS packet"), e.getMessage());
    assertTrue(violated.get(), "the refusal must run the protocol violation callback");
  }

  @Test
  void rejectsAZeroLengthPacket() {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(packet(0, 0), violated);

    assertThrows(IOException.class, () -> in.read(new byte[16], 0, 16));

    assertTrue(violated.get());
  }

  /** A packet at the payload maximum is sent in full and must not be refused. */
  @Test
  void acceptsAPacketAtThePayloadMaximum() throws IOException {
    AtomicBoolean violated = new AtomicBoolean();
    GSSInputStream in = streamOf(packet(MAX_PAYLOAD_SIZE, MAX_PAYLOAD_SIZE), violated);

    byte[] buffer = new byte[16];
    int read = in.read(buffer, 0, buffer.length);

    assertEquals(buffer.length, read);
    assertEquals('x', buffer[0]);
    assertFalse(violated.get());
  }

  @Test
  void nothingIsAvailableBeforeAPacketIsUnwrapped() throws IOException {
    GSSInputStream in = streamOf(packet(2000, 2000), new AtomicBoolean());

    assertEquals(0, in.available(), "available() before the first read");
  }

  /**
   * A packet carries up to 16380 encrypted bytes, more than one read usually asks for, so the rest of
   * its decrypted bytes stay in the stream while the socket is already empty.
   * {@code PGStream.hasMessagePending} checks {@code available()} first, and sets a 1 ms socket
   * timeout to probe the stream only when it returns 0.
   */
  @Test
  void decryptedBytesLeftOverFromAPacketAreAvailable() throws IOException {
    GSSInputStream in = streamOf(packet(2000, 2000), new AtomicBoolean());

    assertEquals(4, in.read(new byte[4], 0, 4), "read(buffer, 0, 4)");
    assertEquals(1996, in.available(), "available() after reading 4 of 2000 decrypted bytes");
  }

  /**
   * A packet yields no decrypted bytes until all of it has arrived, so the bytes of an incomplete packet in
   * the wrapped stream cannot be read without blocking.
   */
  @Test
  void anIncompletePacketBehindADrainedOneIsNotAvailable() throws IOException {
    GSSInputStream in = streamOf(wire(packet(2000, 2000), packet(16, 8)), new AtomicBoolean());

    assertEquals(2000, in.read(new byte[2000], 0, 2000), "read(buffer, 0, 2000)");
    assertEquals(0, in.available(),
        "available() with 12 of the 20 bytes of the next packet in the wrapped stream");
  }

  /**
   * The buffered stream the driver reads through asks the wrapped stream only once its own buffer
   * runs dry, and that is when the leftover decrypted bytes have to become visible.
   */
  @Test
  void leftoverDecryptedBytesAreAvailableThroughTheBufferedStream() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(streamOf(packet(2000, 2000), new AtomicBoolean()), 1024);

    in.ensureBytes(1);
    for (int i = 0; i < 1024; i++) {
      in.read();
    }

    assertEquals(976, in.available(),
        "available() after reading 1024 of 2000 decrypted bytes through a 1024-byte buffer");
  }
}
