/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

/**
 * Records {@link Socket#getSoTimeout()} as it stands at every read the driver performs.
 *
 * <p>Which timeout a protocol exchange ran under is otherwise observable only by racing the server:
 * a test that waits for a short timeout to expire passes or fails depending on how fast the server
 * answers.
 *
 * <p>Recordings are <em>per-connection</em>. A test allocates a {@link Recording} through
 * {@link #register()}, passes its {@link Recording#key()} via {@code PGProperty.SOCKET_FACTORY_ARG},
 * and reads the values back through that instance, so tests running in parallel do not share state.
 *
 * <p>{@link Recording#reset()} opens a new window: call it right before the exchange under test, and
 * read {@link Recording#minSoTimeoutOnRead()} right after, before a later exchange contributes.
 *
 * <p>Under SSL the recorded socket is the plain one the {@code SSLSocket} wraps. Both the reads and
 * {@code setSoTimeout} reach it, so the values describe the connection.
 */
public class TimeoutRecordingSocketFactory extends SocketFactory {

  /**
   * Socket timeouts observed on one connection. Owned by the test; the factory looks it up by
   * {@link #key} and writes into it.
   */
  public static final class Recording {
    private static final int NO_READ = -1;

    private final AtomicInteger minSoTimeoutOnRead = new AtomicInteger(NO_READ);
    private final AtomicInteger initialSoTimeout = new AtomicInteger();
    private final String key;

    private Recording(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }

    /** Drops what was recorded so far, so the next reads describe the exchange under test. */
    public void reset() {
      minSoTimeoutOnRead.set(NO_READ);
    }

    /**
     * Returns the smallest socket timeout in effect at a read since the last {@link #reset()}, in
     * milliseconds, or {@code -1} if the driver has not read since. Zero means the reads blocked
     * without a timeout.
     */
    public int minSoTimeoutOnRead() {
      return minSoTimeoutOnRead.get();
    }

    /**
     * Gives every socket this factory creates the timeout, without the driver being told. A
     * connection that leaves {@code socketTimeout} at its default reaches the server that way when
     * the socket comes from a factory of the caller's own, so anything that remembers the timeout
     * rather than reading it back has the two disagreeing.
     *
     * @param milliseconds the timeout to put on the socket
     */
    public void initialSoTimeout(int milliseconds) {
      initialSoTimeout.set(milliseconds);
    }

    void onRead(int soTimeout) {
      minSoTimeoutOnRead.accumulateAndGet(soTimeout,
          (seen, current) -> seen == NO_READ ? current : Math.min(seen, current));
    }
  }

  private static final ConcurrentMap<String, Recording> REGISTRY = new ConcurrentHashMap<>();

  /**
   * Allocates a fresh {@link Recording} and registers it so a factory built with its key can find
   * it. The caller should {@link #unregister(Recording)} at the end of the test to release the
   * registry slot.
   */
  public static Recording register() {
    String key = UUID.randomUUID().toString();
    Recording recording = new Recording(key);
    REGISTRY.put(key, recording);
    return recording;
  }

  public static void unregister(Recording recording) {
    REGISTRY.remove(recording.key);
  }

  private final Recording recording;

  public TimeoutRecordingSocketFactory(String key) {
    Recording recording = key == null ? null : REGISTRY.get(key);
    if (recording == null) {
      throw new IllegalArgumentException(
          "No TimeoutRecordingSocketFactory.Recording registered for key=" + key
              + ". Call TimeoutRecordingSocketFactory.register() and pass its key() via "
              + "PGProperty.SOCKET_FACTORY_ARG.");
    }
    this.recording = recording;
  }

  @Override
  public Socket createSocket() throws IOException {
    RecordingSocket socket = new RecordingSocket(recording);
    int initial = recording.initialSoTimeout.get();
    if (initial != 0) {
      socket.setSoTimeout(initial);
    }
    return socket;
  }

  @Override
  public Socket createSocket(String host, int port) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
      int localPort) {
    throw new UnsupportedOperationException();
  }

  private static final class RecordingSocket extends Socket {
    private final Recording recording;
    private @Nullable InputStream recordingIn;

    RecordingSocket(Recording recording) {
      this.recording = recording;
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
      InputStream recordingIn = this.recordingIn;
      if (recordingIn == null) {
        this.recordingIn = recordingIn = new RecordingInputStream(super.getInputStream(), this);
      }
      return recordingIn;
    }

    /**
     * Records the socket timeout in effect for the read that follows. Recording before the read
     * rather than after it covers a read that ends in {@link java.net.SocketTimeoutException}.
     */
    void beforeRead() throws IOException {
      recording.onRead(getSoTimeout());
    }
  }

  private static final class RecordingInputStream extends FilterInputStream {
    private final RecordingSocket socket;

    RecordingInputStream(InputStream in, RecordingSocket socket) {
      super(in);
      this.socket = socket;
    }

    @Override
    public int read() throws IOException {
      socket.beforeRead();
      return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      socket.beforeRead();
      return in.read(b, off, len);
    }
  }
}
