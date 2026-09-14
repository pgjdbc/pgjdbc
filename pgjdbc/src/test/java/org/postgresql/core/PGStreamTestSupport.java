/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.FakeSocketFactory;
import org.postgresql.util.HostSpec;

import java.io.IOException;

/**
 * Runs a {@link PGStream} over a {@link FakeSocket}, with no server behind it.
 */
public final class PGStreamTestSupport {
  private PGStreamTestSupport() {
  }

  /**
   * Opens a stream that reads from {@code socket}. The 8192 sizes the send buffer.
   */
  public static PGStream openStream(FakeSocket socket) {
    try {
      return new PGStream(new FakeSocketFactory(socket), new HostSpec("localhost", 5432), 0, 8192);
    } catch (IOException e) {
      throw new AssertionError("a fake socket cannot fail to open", e);
    }
  }

  /**
   * Asserts the state {@link PGStream#markBroken(Throwable)} leaves: {@code isClosed()} is true
   * and the socket was closed with SO_LINGER on and a zero timeout.
   */
  public static void assertBroken(PGStream stream, FakeSocket socket) {
    assertAll(
        () -> assertTrue(stream.isClosed(), "isClosed()"),
        () -> assertTrue(socket.closed, "socket closed"),
        () -> assertTrue(socket.lingerSet, "SO_LINGER set"),
        () -> assertTrue(socket.lingerOn, "SO_LINGER on"),
        () -> assertEquals(0, socket.lingerSeconds, "SO_LINGER timeout"));
  }

  /**
   * Sets the {@link ProtocolHardeningMode} of {@code stream} alone, for a test outside this
   * package.
   */
  public static void setProtocolHardeningMode(PGStream stream, ProtocolHardeningMode mode) {
    stream.setProtocolHardeningMode(mode);
  }
}
