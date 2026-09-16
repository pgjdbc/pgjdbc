/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proxy server that can hold a backend message open, so that the driver sits partway through one
 * for as long as the test wants.
 *
 * <p>It forwards both directions untouched until one of the two stalls arms it.
 * {@link #stallInsideNextMessage(long)} delivers the next message in two parts, the type byte on
 * its own and then, after a pause, the length and the body: the driver has consumed the type byte
 * and is waiting for the rest, which is where a read deadline of its own does damage, since the
 * bytes it already took are not in the stream any more.
 * {@link #stallBeforeNextMessage(long)} pauses ahead of the type byte instead, leaving the driver
 * waiting with nothing consumed — the same wait, at a point it can still come back from.
 *
 * <p>The proxy reads the v3 framing to find that point, so the connection through it has to be
 * plain: pass {@code sslmode=disable} and {@code gssEncMode=disable}, or the traffic is encrypted
 * and the framing is not there to be found.
 */
public class MessageStallProxyServer implements Closeable {
  private final ServerSocket serverSock;
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final AtomicLong stallMillis = new AtomicLong();
  private final AtomicLong boundaryStallMillis = new AtomicLong();
  private final AtomicLong typeStallMillis = new AtomicLong();
  private final AtomicInteger stallBeforeType = new AtomicInteger();
  private final AtomicReference<byte @Nullable []> injection = new AtomicReference<>();
  private volatile boolean keepRunning = true;

  public MessageStallProxyServer(String destHost, int destPort) throws IOException {
    this.serverSock = new ServerSocket(0);
    this.serverSock.setSoTimeout(100);
    doAsync(() -> {
      while (keepRunning) {
        try {
          Socket client = serverSock.accept();
          sockets.add(client);
          Socket server = new Socket(destHost, destPort);
          sockets.add(server);
          doAsync(() -> forward(client, server));
          doAsync(() -> forwardBackendMessages(server, client));
        } catch (SocketTimeoutException ignore) {
          // the accept timeout is what lets this loop notice close()
        } catch (IOException e) {
          return;
        }
      }
    });
  }

  public int getServerPort() {
    return serverSock.getLocalPort();
  }

  /**
   * Splits the next backend message, delivering its type byte and then pausing before the rest.
   * Applies to one message; call it again to arm the proxy for another.
   *
   * @param millis how long to hold the message open
   */
  public void stallInsideNextMessage(long millis) {
    stallMillis.set(millis);
  }

  /**
   * Pauses before the next backend message instead of inside it, so the driver waits with nothing
   * consumed. Forwarding whole messages is what makes this a boundary: the driver's buffer can only
   * end where a message does.
   *
   * @param millis how long to wait before starting the next message
   */
  public void stallBeforeNextMessage(long millis) {
    boundaryStallMillis.set(millis);
  }

  /**
   * Pauses before the next backend message of the given type, leaving the driver waiting for that
   * one with everything ahead of it already delivered. Use it to hold back the message that ends
   * an exchange, such as the ReadyForQuery that follows an ErrorResponse.
   *
   * @param messageType the v3 type byte to hold back, for example {@code 'Z'}
   * @param millis      how long to wait before sending it
   */
  public void stallBeforeMessageType(int messageType, long millis) {
    stallBeforeType.set(messageType);
    typeStallMillis.set(millis);
  }

  /**
   * Sends the bytes to the driver ahead of the next real backend message, so a test can put a
   * message on the wire that the server would not send. The driver reads it as if the backend had.
   *
   * @param raw a complete v3 message: the type byte, a self-inclusive four-byte length, the body
   */
  public void injectBeforeNextMessage(byte[] raw) {
    injection.set(raw.clone());
  }

  @Override
  public void close() {
    keepRunning = false;
    for (Socket socket : sockets) {
      try {
        socket.close();
      } catch (IOException ignore) {
        // closing the rest matters more than this one
      }
    }
    try {
      serverSock.close();
    } catch (IOException ignore) {
      // nothing left to do about it
    }
  }

  /** Copies one direction byte for byte, with no interest in what the bytes mean. */
  private void forward(Socket source, Socket dest) {
    try {
      InputStream in = source.getInputStream();
      OutputStream out = dest.getOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while (keepRunning && (read = in.read(buffer)) >= 0) {
        out.write(buffer, 0, read);
        out.flush();
      }
    } catch (IOException ignore) {
      // the test closes the sockets under us
    }
  }

  /**
   * Copies the backend's direction one message at a time, so that an armed stall lands after the
   * type byte and before the length.
   */
  private void forwardBackendMessages(Socket source, Socket dest) {
    try {
      InputStream in = source.getInputStream();
      OutputStream out = dest.getOutputStream();
      byte[] header = new byte[4];
      while (keepRunning) {
        byte[] inject = injection.getAndSet(null);
        if (inject != null) {
          out.write(inject);
          out.flush();
        }
        long boundaryStall = boundaryStallMillis.getAndSet(0);
        if (boundaryStall > 0) {
          Thread.sleep(boundaryStall);
        }
        int type = in.read();
        if (type < 0) {
          return;
        }
        if (type == stallBeforeType.get()) {
          long typeStall = typeStallMillis.getAndSet(0);
          if (typeStall > 0) {
            Thread.sleep(typeStall);
          }
        }
        out.write(type);
        out.flush();

        long stall = stallMillis.getAndSet(0);
        if (stall > 0) {
          Thread.sleep(stall);
        }

        if (!readFully(in, header, header.length)) {
          return;
        }
        out.write(header);
        // The length counts itself, so the body is what is left after the four bytes
        int length = (header[0] & 0xFF) << 24 | (header[1] & 0xFF) << 16
            | (header[2] & 0xFF) << 8 | header[3] & 0xFF;
        if (!copy(in, out, length - 4)) {
          return;
        }
        out.flush();
      }
    } catch (IOException ignore) {
      // the test closes the sockets under us
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static boolean readFully(InputStream in, byte[] target, int length) throws IOException {
    int done = 0;
    while (done < length) {
      int read = in.read(target, done, length - done);
      if (read < 0) {
        return false;
      }
      done += read;
    }
    return true;
  }

  private static boolean copy(InputStream in, OutputStream out, int length) throws IOException {
    byte[] buffer = new byte[Math.min(8192, Math.max(length, 1))];
    int left = length;
    while (left > 0) {
      int read = in.read(buffer, 0, Math.min(buffer.length, left));
      if (read < 0) {
        return false;
      }
      out.write(buffer, 0, read);
      left -= read;
    }
    return true;
  }

  private static void doAsync(RunnableWithException task) {
    Thread thread = new Thread(() -> {
      try {
        task.run();
      } catch (Exception ignore) {
        // a proxy thread that dies takes its connection down, which the test sees
      }
    });
    thread.setDaemon(true);
    thread.start();
  }

  private interface RunnableWithException {
    void run() throws Exception;
  }
}
