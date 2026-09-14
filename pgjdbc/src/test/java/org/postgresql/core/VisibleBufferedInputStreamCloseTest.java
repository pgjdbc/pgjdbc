/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Fails when a closed {@link VisibleBufferedInputStream} answers out of the buffer it was holding
 * when the connection went away.
 *
 * <p>Those bytes belong to a stream nothing can make progress on, so serving them hands the caller
 * protocol data that reads as ordinary data until the buffer runs out, at which point the failure
 * surfaces somewhere unrelated to the close that caused it. Every way in has to refuse instead,
 * which is why the ways in are enumerated below rather than sampled: the buffered paths share
 * {@code readMore}, but {@code read(byte[], int, int)}, {@code skip} and {@code available} each
 * reach the wrapped stream on their own.</p>
 *
 * <p>{@code readRaw} is deliberately absent. It is documented as reading without checking anything,
 * so its caller owes the check.</p>
 */
class VisibleBufferedInputStreamCloseTest {
  /**
   * Payload the streams serve. The zero byte is load-bearing: it lets
   * {@link VisibleBufferedInputStream#scanCStringLength()} finish out of the buffer alone, which
   * is the one read that can answer without going through {@code readMore}. Without it that path
   * runs out of buffer and refuses for the wrong reason.
   */
  private static final byte[] DATA = {1, 2, 0, 4, 5, 6, 7, 8};

  /** Every entry point that reads, so that none of them is left answering after a close. */
  enum Reader {
    READ,
    READ_ARRAY,
    PEEK,
    READ_INT2,
    READ_INT4,
    ENSURE_BYTES,
    ENSURE_BYTES_ZERO,
    ENSURE_BYTES_NON_BLOCKING,
    SCAN_C_STRING_LENGTH,
    SKIP,
    AVAILABLE;

    void read(VisibleBufferedInputStream in) throws IOException {
      switch (this) {
        case READ:
          in.read();
          break;
        case READ_ARRAY:
          in.read(new byte[4], 0, 4);
          break;
        case PEEK:
          in.peek();
          break;
        case READ_INT2:
          in.readInt2();
          break;
        case READ_INT4:
          in.readInt4();
          break;
        case ENSURE_BYTES:
          in.ensureBytes(1);
          break;
        case ENSURE_BYTES_ZERO:
          // Asks for nothing, so it answers without reaching readMore and needs its own guard
          in.ensureBytes(0);
          break;
        case ENSURE_BYTES_NON_BLOCKING:
          in.ensureBytes(1, false);
          break;
        case SCAN_C_STRING_LENGTH:
          in.scanCStringLength();
          break;
        case SKIP:
          in.skip(1);
          break;
        case AVAILABLE:
          in.available();
          break;
        default:
          throw new AssertionError("no way in is wired up for " + this);
      }
    }
  }

  /** Counts closes, so that closing twice can be told from closing once. */
  private static class Counting extends ByteArrayInputStream {
    int closes;

    Counting() {
      super(DATA);
    }

    @Override
    public void close() {
      closes++;
    }
  }

  /**
   * Buffers the whole payload, so the buffer is full at the moment of the close. A stream that
   * refused only when it had nothing buffered would pass everything below with the buffer empty.
   */
  private static VisibleBufferedInputStream primed(InputStream wrapped) throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    in.ensureBytes(DATA.length);
    return in;
  }

  @ParameterizedTest
  @EnumSource(Reader.class)
  void everyWayInRefusesAfterClose(Reader reader) throws IOException {
    VisibleBufferedInputStream in = primed(new Counting());
    in.close();

    IOException e = assertThrows(IOException.class, () -> reader.read(in),
        reader + " after close");
    // Not an English substring: every test task runs under user.language=TR, so the message is
    // whatever the catalogue has for this msgid
    assertEquals(GT.tr("Stream is closed."), e.getMessage(),
        reader + ": the failure names the close");
  }

  /**
   * A close touches neither the array nor the read position. {@code Connection.close()} takes no
   * lock, so it can run on a thread other than the reader, and both fields otherwise have a single
   * writer. {@code PGStream} asks {@link VisibleBufferedInputStream#ensureBytes(int)} for bytes in
   * one statement and reads them through {@link VisibleBufferedInputStream#getBuffer()} and
   * {@link VisibleBufferedInputStream#getIndex()} in the next, so moving the position between the
   * two decodes the wrong bytes instead of failing. The refusal is what makes the buffer
   * unreachable; it needs no help from the fields.
   */
  @Test
  void aCloseTouchesNeitherTheArrayNorThePosition() throws IOException {
    VisibleBufferedInputStream in = primed(new Counting());
    in.read();
    in.read();
    byte[] held = in.getBuffer();
    int position = in.getIndex();
    assertEquals(2, position, "the position the reader is at");

    in.close();

    assertSame(held, in.getBuffer(), "the array a caller may already be holding");
    assertEquals(position, in.getIndex(), "the position a reader may be about to read from");
  }

  @Test
  void closingTwiceClosesTheWrappedStreamOnce() throws IOException {
    Counting wrapped = new Counting();
    VisibleBufferedInputStream in = primed(wrapped);

    in.close();
    in.close();

    assertEquals(1, wrapped.closes, "closes of the wrapped stream");
  }

  @Test
  void closingClosesTheWrappedStream() throws IOException {
    Counting wrapped = new Counting();
    VisibleBufferedInputStream in = primed(wrapped);

    in.close();

    assertEquals(1, wrapped.closes, "closes of the wrapped stream");
  }

  /**
   * A connection that has already dropped fails the output close first, because
   * {@code FilterOutputStream.close} flushes. Letting that skip the input close would leave the
   * input stream answering out of the buffer in exactly the case this refusal exists for.
   */
  @Test
  void aFailingOutputCloseStillClosesTheInputAndTheSocket() throws IOException {
    BrokenOutputSocketFactory sockets = new BrokenOutputSocketFactory();
    PGStream stream = new PGStream(sockets, new HostSpec("localhost", 5432), 0, 8192);
    // Buffers the rest of the payload behind the byte it returns
    assertEquals(DATA[0], stream.receiveChar(), "first byte, before the close");

    IOException closeFailure =
        assertThrows(IOException.class, stream::close, "close of a stream that cannot flush");
    assertEquals("Broken pipe", closeFailure.getMessage(),
        "the write failure is what comes back, not what closing after it produced");

    // Bare IOException would also match the EOFException receiveChar throws once the payload runs
    // out, which is the other way this assertion could pass
    IOException readFailure = assertThrows(IOException.class, stream::receiveChar,
        "reading after a close whose first step failed");
    assertEquals(GT.tr("Stream is closed."), readFailure.getMessage(),
        "the read is refused rather than run out");

    assertEquals(1, sockets.socketCloses, "closes of the socket itself");
  }

  /**
   * When two of the three closes fail, the first is the one that says what went wrong with the
   * connection and the rest are what closing it in that state does. Reporting the last would hand
   * the caller the consequence and drop the cause, and {@code PgConnection.close()} wraps whatever
   * comes back into the exception the application sees.
   */
  @Test
  void theFirstFailureOfACloseIsTheOneReported() throws IOException {
    PGStream stream = new PGStream(new BrokenOutputSocketFactory(true),
        new HostSpec("localhost", 5432), 0, 8192);

    IOException e = assertThrows(IOException.class, stream::close,
        "close where both the flush and the socket fail");

    assertEquals("Broken pipe", e.getMessage(), "the failure reported");
    assertEquals(1, e.getSuppressed().length, "the later failures, kept rather than dropped");
    assertEquals("Socket close failed", e.getSuppressed()[0].getMessage(), "the later failure");
  }

  /**
   * The close the driver actually runs. {@link QueryExecutorCloseAction} says goodbye and flushes
   * before it closes, and on a connection that has already dropped the flush is what fails -- so
   * the close has to run anyway, or the socket stays open until the JDK reclaims it and the input
   * stream goes on answering out of its buffer. An integration suite cannot produce this, because
   * it needs a connection that breaks between the goodbye and the close.
   */
  @Test
  void aFailingGoodbyeStillClosesTheStream() throws IOException {
    BrokenOutputSocketFactory sockets = new BrokenOutputSocketFactory();
    PGStream stream = new PGStream(sockets, new HostSpec("localhost", 5432), 0, 8192);
    // Buffers the rest of the payload behind the byte it returns
    assertEquals(DATA[0], stream.receiveChar(), "first byte, before the close");
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    IOException e = assertThrows(IOException.class, action::close,
        "close of a connection that cannot say goodbye");
    assertEquals("Broken pipe", e.getMessage(),
        "the write failure is what comes back, not what closing after it produced");

    assertEquals(1, sockets.socketCloses, "closes of the socket itself");
    IOException readFailure = assertThrows(IOException.class, stream::receiveChar,
        "reading after a close whose goodbye failed");
    assertEquals(GT.tr("Stream is closed."), readFailure.getMessage(),
        "the read is refused rather than run out");
  }

  /**
   * A socket reached through the {@code socketFactory} connection property is written by someone
   * other than the driver, so the output close can raise something that is not an
   * {@link IOException}. The other two closes still have to run: a type-selective catch would let
   * it out and leave the input stream answering and the socket open.
   */
  @Test
  void anUncheckedFailureOfTheOutputCloseStillClosesTheRest() throws IOException {
    BrokenOutputSocketFactory sockets = new BrokenOutputSocketFactory(false, true);
    PGStream stream = new PGStream(sockets, new HostSpec("localhost", 5432), 0, 8192);
    assertEquals(DATA[0], stream.receiveChar(), "first byte, before the close");

    assertThrows(IllegalStateException.class, stream::close,
        "close where the output raises something unchecked");

    assertEquals(1, sockets.socketCloses, "closes of the socket itself");
    IOException readFailure = assertThrows(IOException.class, stream::receiveChar,
        "reading after a close that raised something unchecked");
    assertEquals(GT.tr("Stream is closed."), readFailure.getMessage(),
        "the read is refused rather than run out");
  }

  @Test
  void closingAStreamTwiceClosesTheSocketOnce() throws IOException {
    BrokenOutputSocketFactory sockets = new BrokenOutputSocketFactory();
    PGStream stream = new PGStream(sockets, new HostSpec("localhost", 5432), 0, 8192);

    assertThrows(IOException.class, stream::close, "the first close, which cannot flush");
    stream.close();

    assertEquals(1, sockets.socketCloses, "closes of the socket itself");
  }

  /**
   * The goodbye failing is what says the connection went wrong; closing after that failing is what
   * closing a broken connection does. Reporting the second would hand the caller the consequence,
   * and this is the frame whose exception {@code PgConnection.close()} wraps for the application.
   */
  @Test
  void theFirstFailureOfAGoodbyeIsTheOneReported() throws IOException {
    BrokenOutputSocketFactory sockets = new BrokenOutputSocketFactory();
    PGStream stream = new PGStream(sockets, new HostSpec("localhost", 5432), 0, 8192);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    IOException e = assertThrows(IOException.class, action::close,
        "close of a connection that cannot say goodbye");

    assertEquals("Broken pipe", e.getMessage(), "the goodbye is what failed first");
    assertEquals(1, e.getSuppressed().length, "the later failure, kept rather than dropped");
    assertEquals("Socket closed", e.getSuppressed()[0].getMessage(),
        "what closing after it produced");
  }

  /**
   * The two branches of {@link PGStream#rethrow(Throwable)} that no close in this file produces.
   * An {@link Error} has to come back as itself: {@code QueryExecutorBase.close()} catches
   * {@link IOException} and logs it at FINEST, so wrapping one would swallow a failure that should
   * take the thread down. The wrapper is for a checked exception these closes cannot raise, which
   * is why nothing else reaches it.
   */
  @Test
  void rethrowRaisesTheTypeItWasGiven() {
    StackOverflowError error = new StackOverflowError();
    assertSame(error, assertThrows(StackOverflowError.class, () -> PGStream.rethrow(error)),
        "an Error comes back as itself");

    Exception checked = new Exception("neither IOException nor unchecked");
    assertSame(checked, assertThrows(IOException.class, () -> PGStream.rethrow(checked)).getCause(),
        "anything else is wrapped, cause intact");
  }

  /**
   * A stream that rethrows one stored instance hands the same object back twice, and suppressing a
   * throwable under itself raises {@link IllegalArgumentException} -- which would end the sequence
   * of closes the accumulator exists to keep going.
   */
  @Test
  void anAccumulatedFailureIsNotSuppressedUnderItself() {
    IOException once = new IOException("Broken pipe");

    assertSame(once, PGStream.alsoFailed(once, once), "the same instance twice");
    assertEquals(0, once.getSuppressed().length, "nothing was suppressed under itself");
  }

  @Test
  void readingUpToTheCloseIsUnaffected() throws IOException {
    VisibleBufferedInputStream in = primed(new Counting());

    assertEquals(DATA[0], in.read(), "first byte");
    assertEquals(DATA[1], in.read(), "second byte");
    assertEquals(2, in.getIndex(), "the read position moved with them");
    in.close();
  }

  /**
   * Hands the driver a socket that serves {@link #DATA} and refuses to flush, which is how a
   * connection that has already dropped behaves on the way out.
   */
  private static final class BrokenOutputSocketFactory extends SocketFactory {
    /** Whether closing the socket fails too, which is the second failure of the same close. */
    private final boolean socketCloseFails;
    /**
     * Whether the write fails with something unchecked. A socket reached through the
     * {@code socketFactory} connection property is written by someone other than the driver, so
     * what comes out of it is not limited to {@link IOException}.
     */
    private final boolean writeFailsUnchecked;
    int socketCloses;
    int soTimeout;
    int writeFailures;

    BrokenOutputSocketFactory() {
      this(false, false);
    }

    BrokenOutputSocketFactory(boolean socketCloseFails) {
      this(socketCloseFails, false);
    }

    BrokenOutputSocketFactory(boolean socketCloseFails, boolean writeFailsUnchecked) {
      this.socketCloseFails = socketCloseFails;
      this.writeFailsUnchecked = writeFailsUnchecked;
    }

    /**
     * Names the failures apart, so that a caller reporting the wrong one of two is visible. The
     * first is the one that says the connection went wrong; a later one is what writing to it
     * again does.
     */
    private IOException nextWriteFailure() {
      return new IOException(writeFailures++ == 0 ? "Broken pipe" : "Socket closed");
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        private final InputStream input = new ByteArrayInputStream(DATA);
        private final OutputStream output = new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw fail();
          }

          @Override
          public void flush() throws IOException {
            throw fail();
          }

          private IOException fail() {
            if (writeFailsUnchecked) {
              throw new IllegalStateException("Broken pipe");
            }
            return nextWriteFailure();
          }
        };

        @Override
        public boolean isConnected() {
          return true;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
        }

        @Override
        public void setTcpNoDelay(boolean on) {
        }

        @Override
        public int getSoTimeout() {
          return soTimeout;
        }

        @Override
        public void setSoTimeout(int timeout) {
          soTimeout = timeout;
        }

        @Override
        public int getSendBufferSize() {
          return 8192;
        }

        @Override
        public InputStream getInputStream() {
          return input;
        }

        @Override
        public OutputStream getOutputStream() {
          return output;
        }

        @Override
        public void close() throws IOException {
          socketCloses++;
          if (socketCloseFails) {
            throw new IOException("Socket close failed");
          }
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
        int localPort) {
      return createSocket();
    }
  }
}
