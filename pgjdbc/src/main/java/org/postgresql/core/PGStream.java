/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import javax.net.SocketFactory;

/**
 * <p>Wrapper around the raw connection to the server that implements some basic primitives
 * (reading/writing formatted data, doing string encoding, etc).</p>
 *
 * <p>In general, instances of PGStream are not threadsafe; the caller must ensure that only one thread
 * at a time is accessing a particular PGStream instance.</p>
 */
public class PGStream implements Closeable, Flushable {
  private final SocketFactory socketFactory;
  private final HostSpec hostSpec;

  private final byte[] int4Buf;
  private final byte[] int2Buf;

  private Socket connection;
  private VisibleBufferedInputStream pgInput;
  private OutputStream pgOutput;
  private byte[] streamBuffer;

  private long nextStreamAvailableCheckTime;
  // This is a workaround for SSL sockets: sslInputStream.available() might return 0
  // so we perform "1ms reads" once in a while
  private int minStreamAvailableCheckDelay = 1000;

  private Encoding encoding;
  private Writer encodingWriter;

  /**
   * Constructor: Connect to the PostgreSQL back end and return a stream connection.
   *
   * @param socketFactory socket factory to use when creating sockets
   * @param hostSpec the host and port to connect to
   * @param timeout timeout in milliseconds, or 0 if no timeout set
   * @throws IOException if an IOException occurs below it.
   */
  public PGStream(SocketFactory socketFactory, HostSpec hostSpec, int timeout) throws IOException {
    this.socketFactory = socketFactory;
    this.hostSpec = hostSpec;

    Socket socket = socketFactory.createSocket();
    if (!socket.isConnected()) {
      // When using a SOCKS proxy, the host might not be resolvable locally,
      // thus we defer resolution until the traffic reaches the proxy. If there
      // is no proxy, we must resolve the host to an IP to connect the socket.
      InetSocketAddress address = hostSpec.shouldResolve()
          ? new InetSocketAddress(hostSpec.getHost(), hostSpec.getPort())
          : InetSocketAddress.createUnresolved(hostSpec.getHost(), hostSpec.getPort());
      socket.connect(address, timeout);
    }
    changeSocket(socket);
    setEncoding(Encoding.getJVMEncoding("UTF-8"));

    int2Buf = new byte[2];
    int4Buf = new byte[4];
  }

  /**
   * Constructor: Connect to the PostgreSQL back end and return a stream connection.
   *
   * @param socketFactory socket factory
   * @param hostSpec the host and port to connect to
   * @throws IOException if an IOException occurs below it.
   * @deprecated use {@link #PGStream(SocketFactory, org.postgresql.util.HostSpec, int)}
   */
  @Deprecated
  public PGStream(SocketFactory socketFactory, HostSpec hostSpec) throws IOException {
    this(socketFactory, hostSpec, 0);
  }

  public HostSpec getHostSpec() {
    return hostSpec;
  }

  public Socket getSocket() {
    return connection;
  }

  public SocketFactory getSocketFactory() {
    return socketFactory;
  }

  /**
   * Check for pending backend messages without blocking. Might return false when there actually are
   * messages waiting, depending on the characteristics of the underlying socket. This is used to
   * detect asynchronous notifies from the backend, when available.
   *
   * @return true if there is a pending backend message
   * @throws IOException if something wrong happens
   */
  public boolean hasMessagePending() throws IOException {
    if (pgInput.available() > 0) {
      return true;
    }
    // In certain cases, available returns 0, yet there are bytes
    long now = System.currentTimeMillis();
    if (now < nextStreamAvailableCheckTime && minStreamAvailableCheckDelay != 0) {
      // Do not use ".peek" too often
      return false;
    }
    nextStreamAvailableCheckTime = now + minStreamAvailableCheckDelay;
    int soTimeout = getNetworkTimeout();
    setNetworkTimeout(1);
    try {
      return pgInput.peek() != -1;
    } catch (SocketTimeoutException e) {
      return false;
    } finally {
      setNetworkTimeout(soTimeout);
    }
  }

  public void setMinStreamAvailableCheckDelay(int delay) {
    this.minStreamAvailableCheckDelay = delay;
  }

  /**
   * Switch this stream to using a new socket. Any existing socket is <em>not</em> closed; it's
   * assumed that we are changing to a new socket that delegates to the original socket (e.g. SSL).
   *
   * @param socket the new socket to change to
   * @throws IOException if something goes wrong
   */
  public void changeSocket(Socket socket) throws IOException {
    this.connection = socket;

    // Submitted by Jason Venner <jason@idiom.com>. Disable Nagle
    // as we are selective about flushing output only when we
    // really need to.
    connection.setTcpNoDelay(true);

    // Buffer sizes submitted by Sverre H Huseby <sverrehu@online.no>
    pgInput = new VisibleBufferedInputStream(connection.getInputStream(), 8192);
    pgOutput = new BufferedOutputStream(connection.getOutputStream(), 8192);

    if (encoding != null) {
      setEncoding(encoding);
    }
  }

  public Encoding getEncoding() {
    return encoding;
  }

  /**
   * Change the encoding used by this connection.
   *
   * @param encoding the new encoding to use
   * @throws IOException if something goes wrong
   */
  public void setEncoding(Encoding encoding) throws IOException {
    if (this.encoding != null && this.encoding.name().equals(encoding.name())) {
      return;
    }
    // Close down any old writer.
    if (encodingWriter != null) {
      encodingWriter.close();
    }

    this.encoding = encoding;

    // Intercept flush() downcalls from the writer; our caller
    // will call PGStream.flush() as needed.
    OutputStream interceptor = new FilterOutputStream(pgOutput) {
      public void flush() throws IOException {
      }

      public void close() throws IOException {
        super.flush();
      }
    };

    encodingWriter = encoding.getEncodingWriter(interceptor);
  }

  /**
   * <p>Get a Writer instance that encodes directly onto the underlying stream.</p>
   *
   * <p>The returned Writer should not be closed, as it's a shared object. Writer.flush needs to be
   * called when switching between use of the Writer and use of the PGStream write methods, but it
   * won't actually flush output all the way out -- call {@link #flush} to actually ensure all
   * output has been pushed to the server.</p>
   *
   * @return the shared Writer instance
   * @throws IOException if something goes wrong.
   */
  public Writer getEncodingWriter() throws IOException {
    if (encodingWriter == null) {
      throw new IOException("No encoding has been set on this connection");
    }
    return encodingWriter;
  }

  /**
   * Sends a single character to the back end.
   *
   * @param val the character to be sent
   * @throws IOException if an I/O error occurs
   */
  public void sendChar(int val) throws IOException {
    pgOutput.write(val);
  }

  /**
   * Sends a 4-byte integer to the back end.
   *
   * @param val the integer to be sent
   * @throws IOException if an I/O error occurs
   */
  public void sendInteger4(int val) throws IOException {
    int4Buf[0] = (byte) (val >>> 24);
    int4Buf[1] = (byte) (val >>> 16);
    int4Buf[2] = (byte) (val >>> 8);
    int4Buf[3] = (byte) (val);
    pgOutput.write(int4Buf);
  }

  /**
   * Sends a 2-byte integer (short) to the back end.
   *
   * @param val the integer to be sent
   * @throws IOException if an I/O error occurs or {@code val} cannot be encoded in 2 bytes
   */
  public void sendInteger2(int val) throws IOException {
    if (val < Short.MIN_VALUE || val > Short.MAX_VALUE) {
      throw new IOException("Tried to send an out-of-range integer as a 2-byte value: " + val);
    }

    int2Buf[0] = (byte) (val >>> 8);
    int2Buf[1] = (byte) val;
    pgOutput.write(int2Buf);
  }

  /**
   * Send an array of bytes to the backend.
   *
   * @param buf The array of bytes to be sent
   * @throws IOException if an I/O error occurs
   */
  public void send(byte[] buf) throws IOException {
    pgOutput.write(buf);
  }

  /**
   * Send a fixed-size array of bytes to the backend. If {@code buf.length < siz}, pad with zeros.
   * If {@code buf.lengh > siz}, truncate the array.
   *
   * @param buf the array of bytes to be sent
   * @param siz the number of bytes to be sent
   * @throws IOException if an I/O error occurs
   */
  public void send(byte[] buf, int siz) throws IOException {
    send(buf, 0, siz);
  }

  /**
   * Send a fixed-size array of bytes to the backend. If {@code length < siz}, pad with zeros. If
   * {@code length > siz}, truncate the array.
   *
   * @param buf the array of bytes to be sent
   * @param off offset in the array to start sending from
   * @param siz the number of bytes to be sent
   * @throws IOException if an I/O error occurs
   */
  public void send(byte[] buf, int off, int siz) throws IOException {
    int bufamt = buf.length - off;
    pgOutput.write(buf, off, bufamt < siz ? bufamt : siz);
    for (int i = bufamt; i < siz; ++i) {
      pgOutput.write(0);
    }
  }

  /**
   * Receives a single character from the backend, without advancing the current protocol stream
   * position.
   *
   * @return the character received
   * @throws IOException if an I/O Error occurs
   */
  public int peekChar() throws IOException {
    int c = pgInput.peek();
    if (c < 0) {
      throw new EOFException();
    }
    return c;
  }

  /**
   * Receives a single character from the backend.
   *
   * @return the character received
   * @throws IOException if an I/O Error occurs
   */
  public int receiveChar() throws IOException {
    int c = pgInput.read();
    if (c < 0) {
      throw new EOFException();
    }
    return c;
  }

  /**
   * Receives a four byte integer from the backend.
   *
   * @return the integer received from the backend
   * @throws IOException if an I/O error occurs
   */
  public int receiveInteger4() throws IOException {
    if (pgInput.read(int4Buf) != 4) {
      throw new EOFException();
    }

    return (int4Buf[0] & 0xFF) << 24 | (int4Buf[1] & 0xFF) << 16 | (int4Buf[2] & 0xFF) << 8
        | int4Buf[3] & 0xFF;
  }

  /**
   * Receives a two byte integer from the backend.
   *
   * @return the integer received from the backend
   * @throws IOException if an I/O error occurs
   */
  public int receiveInteger2() throws IOException {
    if (pgInput.read(int2Buf) != 2) {
      throw new EOFException();
    }

    return (int2Buf[0] & 0xFF) << 8 | int2Buf[1] & 0xFF;
  }

  /**
   * Receives a fixed-size string from the backend.
   *
   * @param len the length of the string to receive, in bytes.
   * @return the decoded string
   * @throws IOException if something wrong happens
   */
  public String receiveString(int len) throws IOException {
    if (!pgInput.ensureBytes(len)) {
      throw new EOFException();
    }

    String res = encoding.decode(pgInput.getBuffer(), pgInput.getIndex(), len);
    pgInput.skip(len);
    return res;
  }

  /**
   * Receives a fixed-size string from the backend, and tries to avoid "UTF-8 decode failed"
   * errors.
   *
   * @param len the length of the string to receive, in bytes.
   * @return the decoded string
   * @throws IOException if something wrong happens
   */
  public EncodingPredictor.DecodeResult receiveErrorString(int len) throws IOException {
    if (!pgInput.ensureBytes(len)) {
      throw new EOFException();
    }

    EncodingPredictor.DecodeResult res;
    try {
      String value = encoding.decode(pgInput.getBuffer(), pgInput.getIndex(), len);
      // no autodetect warning as the message was converted on its own
      res = new EncodingPredictor.DecodeResult(value, null);
    } catch (IOException e) {
      res = EncodingPredictor.decode(pgInput.getBuffer(), pgInput.getIndex(), len);
      if (res == null) {
        Encoding enc = Encoding.defaultEncoding();
        String value = enc.decode(pgInput.getBuffer(), pgInput.getIndex(), len);
        res = new EncodingPredictor.DecodeResult(value, enc.name());
      }
    }
    pgInput.skip(len);
    return res;
  }

  /**
   * Receives a null-terminated string from the backend. If we don't see a null, then we assume
   * something has gone wrong.
   *
   * @return string from back end
   * @throws IOException if an I/O error occurs, or end of file
   */
  public String receiveString() throws IOException {
    int len = pgInput.scanCStringLength();
    String res = encoding.decode(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Read a tuple from the back end. A tuple is a two dimensional array of bytes. This variant reads
   * the V3 protocol's tuple representation.
   *
   * @return tuple from the back end
   * @throws IOException if a data I/O error occurs
   */
  public byte[][] receiveTupleV3() throws IOException, OutOfMemoryError {
    // TODO: use msgSize
    int msgSize = receiveInteger4();
    int nf = receiveInteger2();
    byte[][] answer = new byte[nf][];

    OutOfMemoryError oom = null;
    for (int i = 0; i < nf; ++i) {
      int size = receiveInteger4();
      if (size != -1) {
        try {
          answer[i] = new byte[size];
          receive(answer[i], 0, size);
        } catch (OutOfMemoryError oome) {
          oom = oome;
          skip(size);
        }
      }
    }

    if (oom != null) {
      throw oom;
    }

    return answer;
  }

  /**
   * Reads in a given number of bytes from the backend.
   *
   * @param siz number of bytes to read
   * @return array of bytes received
   * @throws IOException if a data I/O error occurs
   */
  public byte[] receive(int siz) throws IOException {
    byte[] answer = new byte[siz];
    receive(answer, 0, siz);
    return answer;
  }

  /**
   * Reads in a given number of bytes from the backend.
   *
   * @param buf buffer to store result
   * @param off offset in buffer
   * @param siz number of bytes to read
   * @throws IOException if a data I/O error occurs
   */
  public void receive(byte[] buf, int off, int siz) throws IOException {
    int s = 0;

    while (s < siz) {
      int w = pgInput.read(buf, off + s, siz - s);
      if (w < 0) {
        throw new EOFException();
      }
      s += w;
    }
  }

  public void skip(int size) throws IOException {
    long s = 0;
    while (s < size) {
      s += pgInput.skip(size - s);
    }
  }

  /**
   * Copy data from an input stream to the connection.
   *
   * @param inStream the stream to read data from
   * @param remaining the number of bytes to copy
   * @throws IOException if a data I/O error occurs
   */
  public void sendStream(InputStream inStream, int remaining) throws IOException {
    int expectedLength = remaining;
    if (streamBuffer == null) {
      streamBuffer = new byte[8192];
    }

    while (remaining > 0) {
      int count = (remaining > streamBuffer.length ? streamBuffer.length : remaining);
      int readCount;

      try {
        readCount = inStream.read(streamBuffer, 0, count);
        if (readCount < 0) {
          throw new EOFException(
              GT.tr("Premature end of input stream, expected {0} bytes, but only read {1}.",
                  expectedLength, expectedLength - remaining));
        }
      } catch (IOException ioe) {
        while (remaining > 0) {
          send(streamBuffer, count);
          remaining -= count;
          count = (remaining > streamBuffer.length ? streamBuffer.length : remaining);
        }
        throw new PGBindException(ioe);
      }

      send(streamBuffer, readCount);
      remaining -= readCount;
    }
  }


  /**
   * Flush any pending output to the backend.
   *
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void flush() throws IOException {
    if (encodingWriter != null) {
      encodingWriter.flush();
    }
    pgOutput.flush();
  }

  /**
   * Consume an expected EOF from the backend.
   *
   * @throws IOException if an I/O error occurs
   * @throws SQLException if we get something other than an EOF
   */
  public void receiveEOF() throws SQLException, IOException {
    int c = pgInput.read();
    if (c < 0) {
      return;
    }
    throw new PSQLException(GT.tr("Expected an EOF from server, got: {0}", c),
        PSQLState.COMMUNICATION_ERROR);
  }

  /**
   * Closes the connection.
   *
   * @throws IOException if an I/O Error occurs
   */
  @Override
  public void close() throws IOException {
    if (encodingWriter != null) {
      encodingWriter.close();
    }

    pgOutput.close();
    pgInput.close();
    connection.close();
  }

  public void setNetworkTimeout(int milliseconds) throws IOException {
    connection.setSoTimeout(milliseconds);
  }

  public int getNetworkTimeout() throws IOException {
    return connection.getSoTimeout();
  }
}
