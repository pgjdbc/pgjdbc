/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.gss.GSSInputStream;
import org.postgresql.gss.GSSOutputStream;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PGPropertyMaxResultBufferParser;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.internal.PgBufferedOutputStream;
import org.postgresql.util.internal.SourceStreamIOException;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.MessageProp;

import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;

import javax.net.SocketFactory;

/**
 * Wrapper around the raw connection to the server that implements some basic primitives
 * (reading/writing formatted data, doing string encoding, etc).
 *
 * <p>In general, instances of PGStream are not threadsafe; the caller must ensure that only one thread
 * at a time is accessing a particular PGStream instance.</p>
 */
public class PGStream implements Closeable, Flushable {
  private final SocketFactory socketFactory;
  private final HostSpec hostSpec;
  private final int maxSendBufferSize;
  private Socket connection;
  private VisibleBufferedInputStream pgInput;
  private PgBufferedOutputStream pgOutput;
  private @Nullable ProtocolVersion protocolVersion;

  private boolean finishedAuthenticationRequests = false;

  public boolean isGssEncrypted() {
    return gssEncrypted;
  }

  public boolean isFinishedAuthenticationRequests() {
    return finishedAuthenticationRequests;
  }

  public void setFinishedAuthenticationRequests() {
    this.finishedAuthenticationRequests = true;
  }

  boolean gssEncrypted;

  public void setSecContext(GSSContext secContext) throws GSSException {
    MessageProp messageProp =  new MessageProp(0, true);
    pgInput = new VisibleBufferedInputStream(new GSSInputStream(pgInput, secContext, messageProp ), 8192);
    // See https://www.postgresql.org/docs/current/protocol-flow.html#PROTOCOL-FLOW-GSSAPI
    // Note that the server will only accept encrypted packets from the client which are less than
    // 16kB; gss_wrap_size_limit() should be used by the client to determine the size of
    // the unencrypted message which will fit within this limit and larger messages should be
    // broken up into multiple gss_wrap() calls
    // See https://github.com/postgres/postgres/blob/acecd6746cdc2df5ba8dcc2c2307c6560c7c2492/src/backend/libpq/be-secure-gssapi.c#L348
    // Backend includes "int4 messageSize" into 16384 limit, so we subtract 4.
    pgOutput = new GSSOutputStream(pgOutput, secContext, messageProp, 16384 - 4);
    gssEncrypted = true;

  }

  private long nextStreamAvailableCheckTime;
  // This is a workaround for SSL sockets: sslInputStream.available() might return 0
  // so we perform "1ms reads" once in a while
  private int minStreamAvailableCheckDelay = 1000;

  private Encoding encoding;
  private Writer encodingWriter;

  private long maxResultBuffer = -1;
  private long resultBufferByteCount;

  private int maxRowSizeBytes = -1;

  /**
   * Set to {@code true} the first time a protocol-level hardening check rejects a
   * backend message. Once poisoned the stream is permanently desynced — even if the
   * underlying socket happens to be open, no further bytes from it can be trusted. The
   * flag is consulted by {@link #isClosed()} so a connection pool that asks
   * {@code isClosed()/isValid()} on borrow will discard the connection rather than
   * hand it to another caller. The matching socket close is best-effort, so a
   * subsequent forgotten {@code abort()} cannot leak the file descriptor either.
   */
  private volatile boolean poisoned;

  /**
   * Name of the protocol message currently being parsed, captured by the most recent
   * {@link #readMessageLength(String, int)} / {@link #readFixedMessageLength(String, int)}
   * call. Surfaced in error messages produced by the bounded-string helpers and by
   * {@link #endMessage()}, so the wire-level packet name does not have to be threaded
   * through every read site. {@code null} between messages.
   */
  private @Nullable String currentMessageName;

  /**
   * Declared total length (including the 4 length bytes) of the protocol message currently
   * being parsed. Captured alongside {@link #currentMessageName} for error reporting.
   * {@code 0} between messages.
   */
  private int currentMessageLength;

  /**
   * Stream position (in bytes consumed) at which the protocol message body started by the
   * most recent {@link #readMessageLength(String, int)} (or
   * {@link #readFixedMessageLength(String, int)}) call must end. {@code -1} means no
   * message is currently being tracked. Compared against
   * {@link VisibleBufferedInputStream#getPosition()} in {@link #endMessage()} to detect a
   * desynced stream where the declared envelope size and the actual reads disagree.
   */
  private long messageEndPosition = -1;

  /**
   * Captures the name and declared length of a message that has just been read by
   * {@link #readMessageLength(String, int)} / {@link #readFixedMessageLength(String, int)},
   * so subsequent bounded-string reads and {@link #endMessage()} can quote them in error
   * messages without the caller threading the values through every receive site.
   */
  private void beginMessage(String packetName, int messageLength) {
    this.currentMessageName = packetName;
    this.currentMessageLength = messageLength;
    this.messageEndPosition = pgInput.getPosition() + (messageLength - 4);
  }

  /**
   * Returns the name of the message currently being parsed, or a placeholder if no
   * message is tracked. Used internally by error messages.
   */
  private String currentMessageNameForError() {
    String name = currentMessageName;
    return name != null ? name : "unknown";
  }

  /**
   * Verifies that the protocol message body started by the most recent
   * {@link #readMessageLength(String, int) readMessageLength} (or
   * {@link #readFixedMessageLength(String, int) readFixedMessageLength}) call was fully
   * consumed. Throws {@link IOException} when the caller read fewer or more body bytes
   * than the message envelope declared, which is the signature of a desynced stream
   * (e.g. a corrupted ParameterStatus that contains a name and value but extra trailing
   * bytes that would otherwise be misread as the next message header).
   *
   * <p>The packet name is the one captured at {@code readMessageLength} time, so callers
   * do not have to repeat it. Resets the tracker regardless of outcome, so a subsequent
   * {@code readMessageLength} call starts fresh.</p>
   *
   * @throws IOException if the message body was not exactly consumed
   */
  public void endMessage() throws IOException {
    long expected = messageEndPosition;
    String name = currentMessageNameForError();
    messageEndPosition = -1;
    currentMessageName = null;
    currentMessageLength = 0;
    if (expected < 0) {
      return;
    }
    long actual = pgInput.getPosition();
    if (actual != expected) {
      throw poison(new IOException(GT.tr(
          "Protocol error. {0} message has {1} unread bytes.",
          name, expected - actual)));
    }
  }

  /**
   * Marks the stream as desynced and best-effort closes the underlying socket. Returns
   * the supplied exception so call sites can write {@code throw pgStream.poison(new ...(...))}
   * fluently. The generic signature supports both {@link IOException} thrown by
   * PGStream's internal hardening checks and {@link org.postgresql.util.PSQLException}
   * (e.g. {@link org.postgresql.util.PSQLState#PROTOCOL_VIOLATION}) thrown by the
   * higher layers (auth, cancel-key, startup negotiation, ...). After this call
   * {@link #isClosed()} reports {@code true}, so even if the regular abort path is
   * somehow skipped the connection cannot be reused.
   */
  public <T extends Throwable> T poison(T reason) {
    poisoned = true;
    try {
      // Force an immediate TCP RST rather than a graceful FIN/ACK exchange. close() on
      // a graceful path can block waiting for the OS to flush queued bytes when
      // SO_LINGER > 0; on a poisoned connection we have no reason to wait, and any
      // bytes still in our send buffer are part of a request the server is already
      // about to discard. setSoLinger(true, 0) makes the subsequent close() emit an
      // RST and drop both input and output buffers immediately.
      try {
        connection.setSoLinger(true, 0);
      } catch (SocketException ignore) {
        // Some socket types refuse SO_LINGER (already-closed sockets, certain SSL
        // wrappers); fall through to plain close().
      }
      connection.close();
    } catch (IOException ignore) {
      // best-effort: the socket may already be closed, or the close itself may fail —
      // either way the stream is already marked as poisoned.
    }
    return reason;
  }

  /**
   * Constructor: Connect to the PostgreSQL back end and return a stream connection.
   *
   * @param socketFactory socket factory to use when creating sockets
   * @param hostSpec the host and port to connect to
   * @param timeout timeout in milliseconds, or 0 if no timeout set
   * @throws IOException if an IOException occurs below it.
   * @deprecated use {@link #PGStream(SocketFactory, org.postgresql.util.HostSpec, int, int)}
   */
  @Deprecated
  @SuppressWarnings({"method.invocation", "initialization.fields.uninitialized"})
  public PGStream(SocketFactory socketFactory, HostSpec hostSpec, int timeout) throws IOException {
    this(socketFactory, hostSpec, timeout, 8192);
  }

  /**
   * Constructor: Connect to the PostgreSQL back end and return a stream connection.
   *
   * @param socketFactory socket factory to use when creating sockets
   * @param hostSpec the host and port to connect to
   * @param timeout timeout in milliseconds, or 0 if no timeout set
   * @param maxSendBufferSize maximum amount of bytes buffered before sending to the backend
   * @throws IOException if an IOException occurs below it.
   */
  @SuppressWarnings({"method.invocation", "initialization.fields.uninitialized"})
  public PGStream(SocketFactory socketFactory, HostSpec hostSpec, int timeout,
      int maxSendBufferSize) throws IOException {
    this.socketFactory = socketFactory;
    this.hostSpec = hostSpec;
    this.maxSendBufferSize = maxSendBufferSize;

    Socket socket = createSocket(timeout);
    changeSocket(socket);
    setEncoding(Encoding.getJVMEncoding("UTF-8"));
  }

  @SuppressWarnings({"method.invocation", "initialization.fields.uninitialized"})
  public PGStream(PGStream pgStream, int timeout) throws IOException {

    /*
    Some defaults
     */
    int sendBufferSize = 1024;
    int receiveBufferSize = 1024;
    int soTimeout = 0;
    boolean keepAlive = false;
    boolean tcpNoDelay = true;

    /*
    Get the existing values before closing the stream
     */
    try {
      sendBufferSize = pgStream.getSocket().getSendBufferSize();
      receiveBufferSize = pgStream.getSocket().getReceiveBufferSize();
      soTimeout = pgStream.getSocket().getSoTimeout();
      keepAlive = pgStream.getSocket().getKeepAlive();
      tcpNoDelay = pgStream.getSocket().getTcpNoDelay();

    } catch ( SocketException ex ) {
      // ignore it
    }
    //close the existing stream
    pgStream.close();

    this.socketFactory = pgStream.socketFactory;
    this.hostSpec = pgStream.hostSpec;
    this.maxSendBufferSize = pgStream.maxSendBufferSize;

    Socket socket = createSocket(timeout);
    changeSocket(socket);
    setEncoding(Encoding.getJVMEncoding("UTF-8"));
    // set the buffer sizes and timeout
    socket.setReceiveBufferSize(receiveBufferSize);
    socket.setSendBufferSize(sendBufferSize);
    setNetworkTimeout(soTimeout);
    socket.setKeepAlive(keepAlive);
    socket.setTcpNoDelay(tcpNoDelay);
  }

  /**
   * Constructor: Connect to the PostgreSQL back end and return a stream connection.
   *
   * @param socketFactory socket factory
   * @param hostSpec the host and port to connect to
   * @throws IOException if an IOException occurs below it.
   * @deprecated use {@link #PGStream(SocketFactory, org.postgresql.util.HostSpec, int, int)}
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

    boolean available = false;

    // In certain cases, available returns 0, yet there are bytes
    if (pgInput.available() > 0) {
      return true;
    }
    long now = System.nanoTime() / 1000000;

    if (now < nextStreamAvailableCheckTime && minStreamAvailableCheckDelay != 0) {
      // Do not use ".peek" too often
      return false;
    }

    int soTimeout = getNetworkTimeout();
    connection.setSoTimeout(1);
    try {
      if (!pgInput.ensureBytes(1, false)) {
        return false;
      }
      available = pgInput.peek() != -1;
    } catch (SocketTimeoutException e) {
      return false;
    } finally {
      connection.setSoTimeout(soTimeout);
    }

    /*
    If none available then set the next check time
    In the event that there more async bytes available we will continue to get them all
    see issue 1547 https://github.com/pgjdbc/pgjdbc/issues/1547
     */
    if (!available) {
      nextStreamAvailableCheckTime = now + minStreamAvailableCheckDelay;
    }
    return available;
  }

  public void setMinStreamAvailableCheckDelay(int delay) {
    this.minStreamAvailableCheckDelay = delay;
  }

  private Socket createSocket(int timeout) throws IOException {
    Socket socket = null;
    try {
      socket = socketFactory.createSocket();
      String localSocketAddress = hostSpec.getLocalSocketAddress();
      if (localSocketAddress != null) {
        socket.bind(new InetSocketAddress(InetAddress.getByName(localSocketAddress), 0));
      }
      if (!socket.isConnected()) {
        // When using a SOCKS proxy, the host might not be resolvable locally,
        // thus we defer resolution until the traffic reaches the proxy. If there
        // is no proxy, we must resolve the host to an IP to connect the socket.
        InetSocketAddress address = hostSpec.shouldResolve()
            ? new InetSocketAddress(hostSpec.getHost(), hostSpec.getPort())
            : InetSocketAddress.createUnresolved(hostSpec.getHost(), hostSpec.getPort());
        socket.connect(address, timeout);
      }
      return socket;
    } catch ( Exception ex ) {
      if (socket != null) {
        try {
          socket.close();
        } catch ( Exception ex1 ) {
          ex.addSuppressed(ex1);
        }
      }
      throw ex;
    }
  }

  /**
   * Switch this stream to using a new socket. Any existing socket is <em>not</em> closed; it's
   * assumed that we are changing to a new socket that delegates to the original socket (e.g. SSL).
   *
   * @param socket the new socket to change to
   * @throws IOException if something goes wrong
   */
  public void changeSocket(Socket socket) throws IOException {
    assert connection != socket : "changeSocket is called with the current socket as argument."
        + " This is a no-op, however, it re-allocates buffered streams, so refrain from"
        + " excessive changeSocket calls";

    this.connection = socket;

    // Submitted by Jason Venner <jason@idiom.com>. Disable Nagle
    // as we are selective about flushing output only when we
    // really need to.
    connection.setTcpNoDelay(true);

    pgInput = new VisibleBufferedInputStream(connection.getInputStream(), 8192);
    int sendBufferSize = Math.min(maxSendBufferSize, Math.max(8192, socket.getSendBufferSize()));
    pgOutput = new PgBufferedOutputStream(connection.getOutputStream(), sendBufferSize);

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
      @Override
      public void flush() throws IOException {
      }

      @Override
      public void close() throws IOException {
        super.flush();
      }
    };

    encodingWriter = encoding.getEncodingWriter(interceptor);
  }

  /**
   * Get a Writer instance that encodes directly onto the underlying stream.
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
    pgOutput.writeInt4(val);
  }

  /**
   * Sends a 2-byte integer (short) to the back end.
   *
   * @param val the integer to be sent
   * @throws IOException if an I/O error occurs or {@code val} cannot be encoded in 2 bytes
   */
  public void sendInteger2(int val) throws IOException {
    if (val < 0 || val > 65535) {
      throw new IllegalArgumentException("Tried to send an out-of-range integer as a 2-byte unsigned int value: " + val);
    }
    pgOutput.writeInt2(val);
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
   * If {@code buf.length > siz}, truncate the array.
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
    pgOutput.write(buf, off, Math.min(bufamt, siz));
    if (siz > bufamt) {
      pgOutput.writeZeros(siz - bufamt);
    }
  }

  /**
   * Send a fixed-size array of bytes to the backend. If {@code length < siz}, pad with zeros. If
   * {@code length > siz}, truncate the array.
   *
   * @param writer the stream writer to invoke to send the bytes
   * @throws IOException if an I/O error occurs
   */
  public void send(ByteStreamWriter writer) throws IOException {
    final FixedLengthOutputStream fixedLengthStream = new FixedLengthOutputStream(writer.getLength(), pgOutput);
    try {
      writer.writeTo(new ByteStreamWriter.ByteStreamTarget() {
        @Override
        public OutputStream getOutputStream() {
          return fixedLengthStream;
        }
      });
    } catch (IOException ioe) {
      throw ioe;
    } catch (Exception re) {
      throw new IOException("Error writing bytes to stream", re);
    }
    pgOutput.writeZeros(fixedLengthStream.remaining());
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
   * PostgreSQL backend's {@code MaxAllocSize} (1 GB - 1): the largest legal size of a single
   * protocol message. Any length field that exceeds this value, or that falls below the
   * message's minimum, indicates a corrupted or desynced stream. This is a protocol-level
   * bound and is shared by every PostgreSQL wire-compatible backend (CockroachDB,
   * YugabyteDB, Redshift, Greenplum, ...).
   */
  public static final int MAX_MESSAGE_SIZE = 0x3fffffff;

  /**
   * Receives a four byte integer from the backend.
   *
   * @return the integer received from the backend
   * @throws IOException if an I/O error occurs
   */
  public int receiveInteger4() throws IOException {
    return pgInput.readInt4();
  }

  /**
   * Reads a 4-byte length prefix and validates it against {@link #MAX_MESSAGE_SIZE}.
   * Equivalent to {@link #readMessageLength(String, int, int)
   * readMessageLength(packetName, minLength, MAX_MESSAGE_SIZE)}.
   *
   * <p>For standard PostgreSQL protocol messages the length field is self-inclusive (it
   * counts the 4 length bytes themselves), so {@code minLength} is typically ≥ 4. Callers
   * reading non-self-inclusive length prefixes (e.g. the GSS encryption handshake token
   * length) can pass {@code minLength = 0}.</p>
   *
   * @param packetName protocol message name used in the error message
   * @param minLength inclusive minimum legal value of the length field
   * @return the validated length
   * @throws IOException if the length is out of range
   */
  public int readMessageLength(String packetName, int minLength) throws IOException {
    return readMessageLength(packetName, minLength, MAX_MESSAGE_SIZE);
  }

  /**
   * Reads a 4-byte length prefix and validates it is within
   * {@code [minLength, maxLength]}. Throws {@link IOException} on violation, so the caller
   * tears the connection down instead of using the wire-provided length to drive an
   * allocation or a skip. Callers should pass the tightest protocol-level upper bound they
   * know (for messages with a protocol-defined maximum well below {@link #MAX_MESSAGE_SIZE}),
   * so a desynced stream is detected as early as possible.
   *
   * <p>If the user has configured {@code maxResultBuffer}, lengths exceeding it are also
   * rejected — the user has declared an upper bound on memory they are willing to spend
   * on a single result, and a single backend message larger than that bound cannot be
   * processed within the budget.</p>
   *
   * @param packetName protocol message name used in the error message
   * @param minLength inclusive minimum legal value of the length field
   * @param maxLength inclusive maximum legal value of the length field;
   *                  must be ≤ {@link #MAX_MESSAGE_SIZE}
   * @return the validated length
   * @throws IOException if the length is out of range
   */
  public int readMessageLength(String packetName, int minLength, int maxLength) throws IOException {
    int len = receiveInteger4();
    if (len < minLength || len > maxLength) {
      throw poison(new IOException(GT.tr(
          "Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
          packetName, len, minLength, maxLength)));
    }
    if (maxResultBuffer > 0 && len > maxResultBuffer) {
      throw poison(new IOException(GT.tr(
          "Protocol error. {0} message has length {1} which exceeds maxResultBuffer cap of {2} bytes.",
          packetName, len, maxResultBuffer)));
    }
    // Capture name + declared length + envelope endpoint so subsequent bounded-string
    // reads and endMessage() do not need them threaded through as parameters.
    beginMessage(packetName, len);
    return len;
  }

  /**
   * Reads and validates a fixed-length protocol message length prefix. Throws
   * {@link IOException} when the length is not exactly {@code expectedLength}.
   *
   * @param packetName protocol message name used in the error message
   * @param expectedLength the exact length the message must have
   * @throws IOException if the length differs from {@code expectedLength}
   */
  public void readFixedMessageLength(String packetName, int expectedLength) throws IOException {
    int len = receiveInteger4();
    if (len != expectedLength) {
      throw poison(new IOException(GT.tr(
          "Protocol error. {0} message has length {1}, expected {2}.",
          packetName, len, expectedLength)));
    }
    beginMessage(packetName, expectedLength);
  }

  /**
   * Receives a two byte integer from the backend as an unsigned integer (0..65535).
   *
   * @return the integer received from the backend
   * @throws IOException if an I/O error occurs
   */
  public int receiveInteger2() throws IOException {
    return pgInput.readInt2();
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
   * Scans the next NUL-terminated C-string and returns its length (including the trailing
   * NUL). The scan is always bounded so a desynced stream cannot drive an unbounded
   * buffer-grow-and-read loop. The bound is the remaining envelope of the message
   * currently being parsed ({@link #readMessageLength(String, int) readMessageLength}'s
   * declared length minus everything already consumed) when one is tracked, otherwise
   * {@link #MAX_MESSAGE_SIZE}. The {@code maxResultBuffer} cap is enforced one level up
   * by {@link #readMessageLength(String, int, int)}, so envelope-tracked strings inherit
   * it transitively via the message length.
   */
  private int scanBoundedCStringLength() throws IOException {
    if (messageEndPosition < 0) {
      return pgInput.scanCStringLength(
          MAX_MESSAGE_SIZE, "<no envelope>", MAX_MESSAGE_SIZE);
    }
    long remaining = messageEndPosition - pgInput.getPosition();
    if (remaining <= 0) {
      throw poison(new IOException(GT.tr(
          "Protocol error. {0} message of {1} bytes has no remaining envelope budget.",
          currentMessageNameForError(), currentMessageLength)));
    }
    return pgInput.scanCStringLength(
        (int) remaining, currentMessageNameForError(), currentMessageLength);
  }

  /**
   * Reads a NUL-terminated C-string from the backend. The scan is always bounded — see
   * {@link #scanBoundedCStringLength()} for the budget selection rules.
   *
   * @return the decoded string
   * @throws IOException if no NUL is found within the budget, or on I/O error
   */
  public String receiveString() throws IOException {
    int len = scanBoundedCStringLength();
    String res = encoding.decode(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Receives a null-terminated string from the backend and attempts to decode to a
   * {@link Encoding#decodeCanonicalized(byte[], int, int) canonical} {@code String}.
   * The scan is always bounded — see {@link #scanBoundedCStringLength()} for the budget
   * selection rules.
   *
   * @return string from back end
   * @throws IOException if no NUL is found within the budget, or on I/O error
   * @see Encoding#decodeCanonicalized(byte[], int, int)
   */
  public String receiveCanonicalString() throws IOException {
    int len = scanBoundedCStringLength();
    String res = encoding.decodeCanonicalized(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Receives a null-terminated string from the backend and attempts to decode to a
   * {@link Encoding#decodeCanonicalizedIfPresent(byte[], int, int) canonical} {@code String}.
   * The scan is always bounded — see {@link #scanBoundedCStringLength()} for the budget
   * selection rules.
   *
   * @return string from back end
   * @throws IOException if no NUL is found within the budget, or on I/O error
   * @see Encoding#decodeCanonicalizedIfPresent(byte[], int, int)
   */
  public String receiveCanonicalStringIfPresent() throws IOException {
    int len = scanBoundedCStringLength();
    String res = encoding.decodeCanonicalizedIfPresent(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Read a tuple from the back end. A tuple is a two dimensional array of bytes. This variant reads
   * the V3 protocol's tuple representation.
   *
   * @return tuple from the back end
   * @throws IOException if a data I/O error occurs
   * @throws SQLException if read more bytes than set maxResultBuffer
   */
  public Tuple receiveTupleV3() throws IOException, OutOfMemoryError, SQLException {
    // DataRow envelope: 4 (self) + 2 (nf) + nf * 4 (per-field lengths), minimum 6.
    int messageSize = readMessageLength("DataRow", 6);
    // receiveInteger2() returns a signed 16-bit value. The protocol does not pin a specific
    // maximum column count (forks such as CockroachDB/YugabyteDB/Redshift may differ from
    // PostgreSQL's own limit), so bound nf only via the message envelope below.
    int nf = receiveInteger2();
    // The stream is desynced: we cannot locate the next message boundary, so we must not
    // continue reading. Throw IOException so the caller closes (aborts) the connection
    // instead of treating this as a per-query error and looping over garbage bytes.
    if (nf < 0) {
      throw poison(new IOException(GT.tr(
          "Protocol error. DataRow has negative field count {0} (message size {1}).",
          nf, messageSize)));
    }
    //size = messageSize - 4 bytes of message size - 2 bytes of field count - 4 bytes for each column length
    int dataToReadSize = messageSize - 4 - 2 - 4 * nf;
    if (dataToReadSize < 0) {
      throw poison(new IOException(GT.tr(
          "Protocol error. DataRow field count {0} requires at least {1} bytes for per-field length prefixes, but message size is only {2}.",
          nf, 4 * nf, messageSize)));
    }
    setMaxRowSizeBytes(dataToReadSize);

    byte[][] answer = new byte[nf][];

    increaseByteCounter(dataToReadSize);
    OutOfMemoryError oom = null;
    int remaining = dataToReadSize;
    for (int i = 0; i < nf; i++) {
      int size = receiveInteger4();
      if (size != -1) {
        // Field length is inconsistent with the row envelope — stream is desynced.
        // See comment above: IOException triggers a connection abort upstream.
        if (size < -1) {
          throw poison(new IOException(GT.tr(
              "Protocol error. DataRow field {0} has negative length {1}.",
              i, size)));
        }
        if (size > remaining) {
          throw poison(new IOException(GT.tr(
              "Protocol error. DataRow field {0} length {1} exceeds remaining row bytes {2}.",
              i, size, remaining)));
        }
        remaining -= size;
        try {
          answer[i] = new byte[size];
          receive(answer[i], 0, size);
        } catch (OutOfMemoryError oome) {
          oom = oome;
          skip(size);
        }
      }
    }

    // Envelope must be fully consumed; any leftover would indicate that the claimed
    // message size exceeded the sum of the field lengths, leaving bytes in the stream
    // that would misalign the next message header.
    endMessage();

    if (oom != null) {
      throw oom;
    }

    return new Tuple(answer);
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
   * @throws IOException if error occurs when writing the data to the output stream
   * @throws SourceStreamIOException if error occurs when reading the data from the input stream
   */
  public void sendStream(InputStream inStream, int remaining) throws IOException {
    pgOutput.write(inStream, remaining);
  }

  /**
   * Writes the given amount of zero bytes to the output stream
   * @param length the number of zeros to write
   * @throws IOException in case writing to the output stream fails
   * @throws SourceStreamIOException in case reading from the source stream fails
   */
  public void sendZeros(int length) throws IOException {
    pgOutput.writeZeros(length);
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
    throw poison(new PSQLException(GT.tr("Expected an EOF from server, got: {0}", c),
        PSQLState.COMMUNICATION_ERROR));
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
    pgInput.setTimeoutRequested(milliseconds != 0);
  }

  public int getNetworkTimeout() throws IOException {
    return connection.getSoTimeout();
  }

  /**
   * Method to set MaxResultBuffer inside PGStream.
   *
   * @param value value of new max result buffer as string (cause we can expect % or chars to use
   *              multiplier)
   * @throws PSQLException exception returned when occurred parsing problem.
   */
  public void setMaxResultBuffer(@Nullable String value) throws PSQLException {
    maxResultBuffer = PGPropertyMaxResultBufferParser.parseProperty(value);
  }

  /**
   * Get MaxResultBuffer from PGStream.
   *
   * @return size of MaxResultBuffer
   */
  public long getMaxResultBuffer() {
    return maxResultBuffer;
  }

  /**
   * The idea behind this method is to keep in maxRowSize the size of biggest read data row. As
   * there may be many data rows send after each other for a query, then value in maxRowSize would
   * contain value noticed so far, because next data rows and their sizes are not read for that
   * moment. We want it increasing, because the size of the biggest among data rows will be used
   * during computing new adaptive fetch size for the query.
   *
   * @param rowSizeBytes new value to be set as maxRowSizeBytes
   */
  public void setMaxRowSizeBytes(int rowSizeBytes) {
    if (rowSizeBytes > maxRowSizeBytes) {
      maxRowSizeBytes = rowSizeBytes;
    }
  }

  /**
   * Get actual max row size noticed so far.
   *
   * @return value of max row size
   */
  public int getMaxRowSizeBytes() {
    return maxRowSizeBytes;
  }

  /**
   * Clear value of max row size noticed so far.
   */
  public void clearMaxRowSizeBytes() {
    maxRowSizeBytes = -1;
  }

  /**
   * Clear count of byte buffer.
   */
  public void clearResultBufferCount() {
    resultBufferByteCount = 0;
  }

  public @Nullable ProtocolVersion getProtocolVersion() {
    return protocolVersion;
  }

  public void setProtocolVersion(ProtocolVersion protocolVersion) {
    this.protocolVersion = protocolVersion;
  }

  /**
   * Increase actual count of buffer. If buffer count is bigger than max result buffer limit, then
   * gonna return an exception.
   *
   * @param value size of bytes to add to byte buffer.
   * @throws SQLException exception returned when result buffer count is bigger than max result
   *                      buffer.
   */
  private void increaseByteCounter(long value) throws SQLException {
    if (maxResultBuffer != -1) {
      resultBufferByteCount += value;
      if (resultBufferByteCount > maxResultBuffer) {
        throw poison(new PSQLException(GT.tr(
          "Result set exceeded maxResultBuffer limit. Received:  {0}; Current limit: {1}",
          String.valueOf(resultBufferByteCount), String.valueOf(maxResultBuffer)), PSQLState.COMMUNICATION_ERROR));
      }
    }
  }

  public boolean isClosed() {
    return poisoned || connection.isClosed();
  }
}
