/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.VisibleBufferedInputStream;
import org.postgresql.util.ByteConverter;

import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;

/**
 * Checks that plaintext left over from an unwrapped frame is reported by {@code available()}. A
 * frame carries up to 16 KiB, more than a single read usually asks for, so the remainder waits in
 * the stream with the socket already empty. While it went unreported,
 * {@code PGStream.hasMessagePending} saw nothing and fell back to a timed probe that may answer
 * {@code false} for up to a second.
 */
class GSSInputStreamTest {
  /**
   * Length of the encrypted frame on the wire. The value is arbitrary: the stub below ignores what
   * it is handed and returns a fixed plaintext, the way a real context returns a payload whose
   * length is unrelated to the ciphertext.
   */
  private static final int ENCRYPTED_LENGTH = 8;

  /**
   * Longer than the buffer {@link #BUFFER_SIZE} gives the buffered stream, so one fill cannot take
   * the whole frame.
   */
  private static final byte[] PLAINTEXT = new byte[2000];

  private static final int BUFFER_SIZE = 1024;

  /**
   * A {@link GSSContext} that unwraps anything to {@link #PLAINTEXT}. Only {@code unwrap} is
   * reachable from {@link GSSInputStream}; every other method is a programming error here rather
   * than a case worth stubbing.
   */
  private static GSSContext unwrapsToPlaintext() {
    return (GSSContext) Proxy.newProxyInstance(
        GSSContext.class.getClassLoader(),
        new Class<?>[]{GSSContext.class},
        (proxy, method, args) -> {
          if ("unwrap".equals(method.getName())) {
            return PLAINTEXT.clone();
          }
          throw new UnsupportedOperationException(method.getName());
        });
  }

  /**
   * One frame: a four-byte length followed by that many bytes of ciphertext.
   */
  private static InputStream oneFrame() {
    byte[] wire = new byte[4 + ENCRYPTED_LENGTH];
    ByteConverter.int4(wire, 0, ENCRYPTED_LENGTH);
    return new ByteArrayInputStream(wire);
  }

  private static GSSInputStream gssStream() {
    return new GSSInputStream(oneFrame(), unwrapsToPlaintext(), new MessageProp(0, true));
  }

  @Test
  void nothingIsAvailableBeforeAFrameIsUnwrapped() throws IOException {
    assertEquals(0, gssStream().available(), "no frame read yet, so no plaintext to hand out");
  }

  @Test
  void plaintextLeftOverFromAFrameIsAvailable() throws IOException {
    GSSInputStream in = gssStream();

    assertEquals(4, in.read(new byte[4], 0, 4), "a read far shorter than the frame");
    assertEquals(PLAINTEXT.length - 4, in.available(),
        "the rest of the frame is decrypted and waiting");
  }

  @Test
  void nothingIsAvailableOnceTheFrameIsDrained() throws IOException {
    GSSInputStream in = gssStream();

    assertEquals(PLAINTEXT.length, in.read(new byte[PLAINTEXT.length], 0, PLAINTEXT.length),
        "the whole frame in one read");
    assertEquals(0, in.available(), "the frame is spent and the wrapped stream is empty");
  }

  /**
   * The buffered stream the driver reads through asks the wrapped stream only once its own buffer
   * runs dry, which is the moment the leftover plaintext has to become visible.
   */
  @Test
  void leftoverPlaintextIsVisibleThroughTheBufferedStream() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(gssStream(), BUFFER_SIZE);

    in.ensureBytes(1);
    for (int i = 0; i < BUFFER_SIZE; i++) {
      in.read();
    }

    assertEquals(PLAINTEXT.length - BUFFER_SIZE, in.available(),
        "the buffer is drained, so the count has to come from the frame behind it");
  }
}
