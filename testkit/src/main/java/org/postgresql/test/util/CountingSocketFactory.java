/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.SocketFactory;

/**
 * A {@link SocketFactory} that wraps sockets to count "roundtrips" as write→read direction
 * transitions on the underlying socket. One or more writes followed by one or more reads is
 * one roundtrip: the client stopped writing and waited for the server. Ten consecutive Sync
 * messages with no intervening read count as zero roundtrips.
 *
 * <p>Counters are <em>per-connection</em>, not static. A test allocates a {@link Counters}
 * instance via {@link #register()}, passes its {@link Counters#key()} via
 * {@code PGProperty.SOCKET_FACTORY_ARG}, and reads cumulative values back through that
 * instance. Two tests running in parallel each get their own {@code Counters}.
 *
 * <p>All counters are cumulative. Tests read deltas: snapshot values before an operation,
 * run it, snapshot after, and assert on the difference.
 *
 * <p>Under SSL, roundtrip counts still reflect real wire direction changes (SSLSocket uses
 * the underlying plain socket's streams), but handshake traffic contributes to the counters —
 * snapshot after the connection is established.
 */
public class CountingSocketFactory extends SocketFactory {

  /**
   * Per-connection counters. Owned by the test; the factory looks it up by {@link #key} and
   * writes into it.
   */
  public static final class Counters {
    public final AtomicLong roundtrips = new AtomicLong();
    public final AtomicLong bytesOut = new AtomicLong();
    public final AtomicLong bytesIn = new AtomicLong();

    private final String key;

    private Counters(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }
  }

  private static final ConcurrentMap<String, Counters> REGISTRY = new ConcurrentHashMap<>();

  /**
   * Allocates a fresh {@link Counters} and registers it so a factory instantiated with its
   * key can find it. The caller should {@link #unregister(Counters)} at the end of the test
   * to release the registry slot.
   */
  public static Counters register() {
    String key = UUID.randomUUID().toString();
    Counters c = new Counters(key);
    REGISTRY.put(key, c);
    return c;
  }

  public static void unregister(Counters counters) {
    REGISTRY.remove(counters.key);
  }

  private final Counters counters;

  public CountingSocketFactory(String key) {
    Counters c = key == null ? null : REGISTRY.get(key);
    if (c == null) {
      throw new IllegalArgumentException(
          "No CountingSocketFactory.Counters registered for key=" + key
              + ". Call CountingSocketFactory.register() and pass its key() via "
              + "PGProperty.SOCKET_FACTORY_ARG.");
    }
    this.counters = c;
  }

  @Override
  public Socket createSocket() {
    return new CountingSocket(counters);
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

  private enum Dir { NONE, WRITE, READ }

  private static final class CountingSocket extends Socket {
    private final Counters counters;
    private final AtomicReference<Dir> lastDir = new AtomicReference<>(Dir.NONE);
    private @Nullable InputStream countingIn;
    private @Nullable OutputStream countingOut;

    CountingSocket(Counters counters) {
      this.counters = counters;
    }

    @Override
    public synchronized InputStream getInputStream() throws IOException {
      InputStream countingIn = this.countingIn;
      if (countingIn == null) {
        this.countingIn = countingIn = new CountingInputStream(super.getInputStream(), this);
      }
      return countingIn;
    }

    @Override
    public synchronized OutputStream getOutputStream() throws IOException {
      OutputStream countingOut = this.countingOut;
      if (countingOut == null) {
        this.countingOut = countingOut = new CountingOutputStream(super.getOutputStream(), this);
      }
      return countingOut;
    }

    void onWrite(int n) {
      if (n <= 0) {
        return;
      }
      counters.bytesOut.addAndGet(n);
      lastDir.set(Dir.WRITE);
    }

    void onRead(int n) {
      if (n <= 0) {
        return;
      }
      counters.bytesIn.addAndGet(n);
      if (lastDir.getAndSet(Dir.READ) == Dir.WRITE) {
        counters.roundtrips.incrementAndGet();
      }
    }
  }

  private static final class CountingInputStream extends FilterInputStream {
    private final CountingSocket socket;

    CountingInputStream(InputStream in, CountingSocket socket) {
      super(in);
      this.socket = socket;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      socket.onRead(b == -1 ? 0 : 1);
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int n = in.read(b, off, len);
      socket.onRead(n);
      return n;
    }
  }

  private static final class CountingOutputStream extends OutputStream {
    private final OutputStream out;
    private final CountingSocket socket;

    CountingOutputStream(OutputStream out, CountingSocket socket) {
      this.out = out;
      this.socket = socket;
    }

    @Override
    public void write(int b) throws IOException {
      out.write(b);
      socket.onWrite(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
      socket.onWrite(len);
    }

    @Override
    public void flush() throws IOException {
      out.flush();
    }

    @Override
    public void close() throws IOException {
      out.close();
    }
  }
}
