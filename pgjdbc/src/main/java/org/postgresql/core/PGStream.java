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
 * Reads and writes formatted protocol data over the raw connection to the server, converting
 * strings to and from the connection's encoding.
 *
 * <p>Each reader of a backend message must read its length through
 * {@link #readMessageLength(String, int)}, {@link #readFixedMessageLength(String, int)} or
 * {@link #readPreAuthMessageLength(String, int, int)}, check any further length it reads from the
 * body against the bytes the message has left, and close the message with {@link #endMessage()}.
 * A reader that skips any of these leaves the stream off a message boundary, and the next
 * message type is then read from inside the previous message's body.</p>
 *
 * <p>The maxima this class enforces come from three places: the protocol
 * ({@link #MAX_MESSAGE_SIZE}), a pgjdbc default where the protocol fixes none
 * ({@link #DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE}), and a connection property such as
 * {@code maxResultBuffer}. An error message names the property
 * wherever one applies.</p>
 *
 * <p>In general, instances of PGStream are not threadsafe; the caller must ensure that only one thread
 * at a time is accessing a particular PGStream instance.</p>
 */
public class PGStream implements Closeable, Flushable {
  /**
   * Largest valid length, in bytes, of a single protocol message: the PostgreSQL backend's
   * {@code MaxAllocSize}, 1 GiB - 1 ({@value #MAX_MESSAGE_SIZE} bytes). A length field above
   * this value, or below the minimum for its message type, means the stream is corrupted or
   * out of sync. The protocol fixes this limit, and every PostgreSQL wire-compatible backend
   * shares it (CockroachDB, YugabyteDB, Redshift, Greenplum, ...).
   */
  public static final int MAX_MESSAGE_SIZE = 0x3fffffff;

  /**
   * Largest declared length, in bytes, that pgjdbc accepts by default for a backend message
   * whose body is server-generated text: ErrorResponse, NoticeResponse, CommandComplete,
   * ParameterStatus, NotificationResponse. RowDescription has its own; see
   * {@link #MAX_ROW_DESCRIPTION_SIZE}.
   *
   * <p>The protocol fixes no maximum for these. libpq applies none to ErrorResponse,
   * NoticeResponse, and NotificationResponse after startup: they sit in its
   * {@code VALID_LONG_MESSAGE_TYPE} set, which exempts them from its 30000-byte limit. A server
   * can therefore emit an arbitrarily large {@code RAISE NOTICE} payload or error
   * {@code DETAIL} today, so the default has to clear any workload that already works.
   * 64 MB ({@value #DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE} bytes) does that while still
   * bounding the allocation a length from an out-of-sync stream can drive.
   *
   * <p>Decimal rather than binary: the violation message names the property, and that
   * property is parsed by {@link PGPropertyMaxResultBufferParser}, whose suffixes are
   * decimal.
   *
   * @see #setMaxServerTextMessageSize(String)
   */
  public static final int DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE = 64_000_000;

  /**
   * Largest declared length, in bytes, that pgjdbc accepts for RowDescription: 8 MiB
   * ({@value #MAX_ROW_DESCRIPTION_SIZE} bytes). A PostgreSQL RowDescription is at most about
   * 133 KiB (1664 columns x (63-byte label + NUL + 18 fixed bytes)). A fork that raised the
   * column limit to 65535, the largest count the unsigned int16 field holds, would reach about
   * 5.1 MiB.
   * 8 MiB leaves room for any such backend and still rejects nearly every length read from a
   * stream out of sync.
   */
  public static final int MAX_ROW_DESCRIPTION_SIZE = 8 * 1024 * 1024;

  /**
   * Largest declared length, in bytes, that pgjdbc accepts for AuthenticationRequest and
   * AuthenticationGSSContinue: {@value #MAX_AUTHENTICATION_MESSAGE_SIZE} bytes, 8 bytes of
   * header plus an 8000-byte payload. The payload is a SCRAM, MD5 or GSS continuation token.
   *
   * <p>Both messages arrive before authentication completes, from a peer that is not yet
   * trusted, so no connection property raises this limit.
   *
   * <p>8000 bytes is much more than a server sends here. A SCRAM challenge and an MD5 salt are
   * only tens of bytes, and a GSS continuation carries the server's half of the handshake, not the
   * client's ticket. This limit applies only to that server-to-client direction. The large
   * Kerberos tickets, which can reach 64 kB when a Windows AD PAC is included, are sent the other
   * way, from client to server, where the backend applies its own limit of
   * {@code PG_MAX_AUTH_TOKEN_LENGTH} (65535 bytes) instead. libpq rejects either message above
   * 2000 bytes in {@code fe-connect.c}, so a server that needed more than this limit could not
   * authenticate psql either.
   */
  public static final int MAX_AUTHENTICATION_MESSAGE_SIZE = 8 + 8000;

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

  /**
   * Encrypts the rest of this connection through an established GSSAPI context.
   *
   * <p>Both directions are replaced by wrapping streams and {@link #isGssEncrypted()} reports
   * {@code true} afterwards. The caller must call this on a message boundary, because message
   * tracking restarts against the new input stream.</p>
   *
   * @param secContext an established GSS context
   * @throws GSSException if {@link GSSContext#getWrapSizeLimit(int, boolean, int)} fails while
   *                      sizing the outgoing stream's buffer
   */
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
    // The new pgInput counts positions from zero, so a message end recorded against the stream
    // it wraps does not apply to it. The framed dialogue resumes at this position.
    resetMessageTracker();
  }

  private long nextStreamAvailableCheckTime;
  // This is a workaround for SSL sockets: sslInputStream.available() might return 0
  // so we perform "1ms reads" once in a while
  private int minStreamAvailableCheckDelay = 1000;

  private Encoding encoding;

  private long maxResultBuffer = -1;
  private long resultBufferByteCount;

  /**
   * Largest declared length, in bytes, that {@link #checkServerTextMessageSize(String, int)}
   * accepts for a server-generated text message. Always positive;
   * {@link #setMaxServerTextMessageSize(String)} sets it.
   */
  private long maxServerTextMessageSize = DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE;

  private int maxRowSizeBytes = -1;

  /**
   * Whether this stream has been abandoned as unusable. Set by {@link #markBroken(Throwable)}
   * and never cleared, because no further byte from the socket can be trusted even where the
   * socket is still open. {@link #isClosed()} returns {@code true} while the flag is set, so a
   * pool that calls {@code isClosed()} or {@code isValid()} on borrow discards the connection.
   */
  private volatile boolean broken;

  /**
   * Selects whether this stream enforces the limits {@link ProtocolHardeningMode} can switch
   * off. Defaults to {@link ProtocolHardeningMode#CURRENT}, the JVM-wide mode read from the
   * {@value ProtocolHardeningMode#SYSTEM_PROPERTY} system property.
   * {@link #setProtocolHardeningMode} overrides it for this stream alone.
   */
  private ProtocolHardeningMode protocolHardeningMode = ProtocolHardeningMode.CURRENT;

  /**
   * Name of the protocol message being parsed, as passed to {@link #beginMessage}, or
   * {@code null} between messages. The errors that report a framing failure quote it.
   */
  private @Nullable String currentMessageName;

  /**
   * Declared total length, in bytes, of the protocol message being parsed, counting the four
   * bytes of the length field itself. Recorded alongside {@link #currentMessageName} and
   * quoted by the errors that report a framing failure. {@code 0} between messages.
   */
  private int currentMessageLength;

  /**
   * Stream position, in bytes consumed, at which the body of the tracked message must end, or
   * {@code -1} while no message is tracked. {@link #beginMessage} sets it, and
   * {@link #endMessage()} compares it against {@link VisibleBufferedInputStream#getPosition()}.
   */
  private long messageEndPosition = -1;

  /**
   * Opens a message for {@link #endMessage()} to close, and records its name and declared length
   * for later error messages.
   *
   * <p>{@link #messageEndPosition} is set {@code messageLength - 4} bytes past the current
   * position, because the declared length counts the four bytes of the length field itself. The
   * caller must therefore call this right after reading the length field.</p>
   */
  private void beginMessage(String messageName, int messageLength) {
    this.currentMessageName = messageName;
    this.currentMessageLength = messageLength;
    this.messageEndPosition = pgInput.getPosition() + (messageLength - 4);
  }

  /**
   * Returns the name of the message being parsed, or {@code "unknown"} where no message is
   * tracked.
   */
  private String currentMessageNameForError() {
    String name = currentMessageName;
    return name != null ? name : "unknown";
  }

  /**
   * Closes the message most recently opened by
   * {@link #readMessageLength(String, int, int) readMessageLength},
   * {@link #readFixedMessageLength(String, int) readFixedMessageLength}, or
   * {@link #readPreAuthMessageLength(String, int, int) readPreAuthMessageLength},
   * and checks that exactly as many body bytes were read as the message declared.
   *
   * <p>The message is closed whatever the outcome, so the next {@code readMessageLength} opens a
   * new one.</p>
   *
   * <p>A body read short or long means the stream is out of sync: the extra bytes after the
   * value of a corrupted ParameterStatus, for example, would be read as the next message
   * header.</p>
   *
   * @throws IOException if fewer or more body bytes were read than the message declared; the
   *                     stream is marked broken and its socket closed first, so the connection
   *                     cannot be reused
   */
  public void endMessage() throws IOException {
    long expected = messageEndPosition;
    String name = currentMessageNameForError();
    resetMessageTracker();
    if (expected < 0) {
      return;
    }
    long actual = pgInput.getPosition();
    // A short read and an overrun throw separate errors, each with a positive byte count, so the
    // error text shows which of the two happened.
    if (actual < expected) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has {1} unread bytes.",
          name, String.valueOf(expected - actual))));
    }
    if (actual > expected) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message was read {1} bytes past its declared length.",
          name, String.valueOf(actual - expected))));
    }
  }

  /**
   * Discards the tracked message without checking that its body was consumed. The recorded
   * message boundary stays as it is.
   */
  private void resetMessageTracker() {
    messageEndPosition = -1;
    currentMessageName = null;
    currentMessageLength = 0;
  }

  /**
   * Marks the stream broken and closes the underlying socket on a best-effort basis.
   *
   * <p>After this call {@link #isClosed()} returns {@code true}, so the connection cannot be
   * reused even by a caller that skips the regular abort path. Closing the socket may fail and
   * leave its descriptor open; {@link #isSocketClosed()} reports that case to the regular close
   * path, which then releases the descriptor.</p>
   *
   * @param reason the exception that reports the failure
   * @return {@code reason} itself, keeping its own static type, so a call site can write
   *     {@code throw pgStream.markBroken(new ...(...))} without a cast
   */
  public <T extends Throwable> T markBroken(T reason) {
    broken = true;
    try {
      // SO_LINGER with a zero timeout makes the close() below emit a TCP RST rather than a
      // graceful FIN, discarding both socket buffers instead of flushing the send side.
      // Nothing there is worth delivering: the queued bytes belong to a request the server
      // is about to discard along with the connection.
      try {
        connection.setSoLinger(true, 0);
      } catch (SocketException ignore) {
        // Some socket types refuse SO_LINGER (already-closed sockets, certain SSL
        // wrappers). The close() below runs either way.
      }
      connection.close();
    } catch (IOException ignore) {
      // close() may fail and leave the descriptor open. The stream is marked broken either
      // way.
    }
    return reason;
  }

  /**
   * Sets the {@link ProtocolHardeningMode} for this stream alone. Tests use this to exercise a
   * non-default mode; production code sets the {@value ProtocolHardeningMode#SYSTEM_PROPERTY}
   * system property instead, so that every connection the JVM opens picks up the same policy.
   */
  void setProtocolHardeningMode(ProtocolHardeningMode mode) {
    this.protocolHardeningMode = mode;
  }

  /**
   * Rejects a message longer than {@code limit}, one of the limits pgjdbc applies where the
   * protocol fixes no maximum. The failure is an {@link IOException}, so it escapes
   * {@code processResults} and the query executor aborts the connection, rather than reporting
   * a per-query {@code SQLException}. The error names {@code propertyName} and
   * {@value ProtocolHardeningMode#SYSTEM_PROPERTY} as the two remedies.
   *
   * @param messageName protocol message name used in the error message
   * @param msgLen value of the message length field
   * @param limit largest valid value of the length field, in bytes
   * @param propertyName connection property that raises this limit
   * @throws IOException if {@code msgLen} is over {@code limit}, unless
   *     {@link ProtocolHardeningMode#DISABLE} is in force; the stream is marked broken by
   *     {@link #markBroken(Throwable)} first
   */
  private void checkLimit(String messageName, int msgLen, long limit, String propertyName)
      throws IOException {
    if (msgLen <= limit || protocolHardeningMode == ProtocolHardeningMode.DISABLE) {
      return;
    }
    throw markBroken(new IOException(GT.tr(
        "Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes. Raise the {3} connection property if the backend legitimately sends more, or set -D{4}=disable to skip these limits altogether.",
        messageName, String.valueOf(msgLen), String.valueOf(limit), propertyName,
        ProtocolHardeningMode.SYSTEM_PROPERTY)));
  }

  /**
   * Rejects a message longer than the {@code maxServerTextMessageSize} limit.
   * {@link ProtocolHardeningMode#DISABLE} switches this check off, so a reader that runs
   * before the peer has authenticated must instead take its length through
   * {@link #readPreAuthMessageLength(String, int, int)}, which no mode switches off.
   *
   * @throws IOException if the message is longer than the limit and the mode leaves the
   *                     check in force
   */
  public void checkServerTextMessageSize(String messageName, int msgLen) throws IOException {
    checkLimit(messageName, msgLen, maxServerTextMessageSize, "maxServerTextMessageSize");
  }

  /**
   * Rejects a RowDescription longer than {@link #MAX_ROW_DESCRIPTION_SIZE}. An unsigned int16
   * carries the field count, so a backend that keeps PostgreSQL's NAMEDATALEN cannot reach
   * that length whatever its column limit. The limit is therefore fixed rather than
   * configurable, and {@link ProtocolHardeningMode#DISABLE} does not switch it off.
   *
   * @throws IOException if the message is longer than {@link #MAX_ROW_DESCRIPTION_SIZE}
   */
  public void checkRowDescriptionSize(int msgLen) throws IOException {
    if (msgLen > MAX_ROW_DESCRIPTION_SIZE) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. RowDescription message has length {0}, which exceeds the pgjdbc limit of {1} bytes.",
          String.valueOf(msgLen), String.valueOf(MAX_ROW_DESCRIPTION_SIZE))));
    }
  }

  /**
   * Returns the limit, in bytes, on a server-generated text message. The configured
   * {@code maxServerTextMessageSize} is a {@code long} and may name a larger number than any
   * protocol message can have, so the result is clamped to {@link #MAX_MESSAGE_SIZE}.
   */
  public int getMaxServerTextMessageSize() {
    return (int) Math.min(maxServerTextMessageSize, MAX_MESSAGE_SIZE);
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
   * Switches this stream to a new socket, leaving any existing socket open. The new socket is
   * expected to delegate to the existing one, as an SSL socket does.
   *
   * <p>The replacement input stream restarts its byte counter, so any message being tracked is
   * forgotten and a fresh message boundary is recorded at the new stream's start.</p>
   *
   * @param socket the new socket to change to
   * @throws IOException if the new socket's streams cannot be opened or its options cannot be set
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
    resetMessageTracker();
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
    this.encoding = encoding;
  }

  /**
   * Get a Writer instance that encodes directly onto the underlying stream.
   *
   * <p>The returned Writer should not be closed. {@link Writer#flush()} must be called before
   * switching back to the {@code PGStream} write methods, but it won't actually flush output all
   * the way out -- call {@link #flush} to ensure all output has been pushed to the server.</p>
   *
   * @return a Writer that encodes onto the underlying stream
   * @throws IOException if something goes wrong.
   * @deprecated the driver writes encoded bytes directly and no longer routes output through an
   *     encoding {@link Writer}. This method is unused and will be removed in a future release.
   */
  @Deprecated
  public Writer getEncodingWriter() throws IOException {
    if (encoding == null) {
      throw new IOException("No encoding has been set on this connection");
    }
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
    return encoding.getEncodingWriter(interceptor);
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
   * readMessageLength(messageName, minLength, MAX_MESSAGE_SIZE)}.
   *
   * <p>The length field counts its own four bytes, so the body that follows it is
   * {@code length - 4} bytes long. A prefix that counts only the payload describes no message
   * this method can track; read that one with {@link #readUntrackedLength(String, int, int)}
   * instead.</p>
   *
   * @param messageName protocol message name used in the error message
   * @param minLength inclusive minimum valid value of the length field; must be at least 4
   * @return the validated length
   * @throws IllegalArgumentException if {@code minLength} is below 4; no byte is read and the
   *                                  stream is not marked broken
   * @throws IOException if the length is out of range; the stream is marked broken and its
   *                     socket closed first
   */
  public int readMessageLength(String messageName, int minLength) throws IOException {
    return readMessageLength(messageName, minLength, MAX_MESSAGE_SIZE);
  }

  /**
   * Reads a 4-byte length prefix, validates it is within {@code [minLength, maxLength]}, and
   * opens the message for {@link #endMessage()} to close. No {@link ProtocolHardeningMode}
   * switches either bound off, so {@code maxLength} is for a limit the protocol itself fixes,
   * such as 264 bytes for BackendKeyData.
   *
   * <p>A limit pgjdbc picks is checked after this call instead, by
   * {@link #checkServerTextMessageSize(String, int)} or {@link #checkRowDescriptionSize(int)}.
   * pgjdbc picks a limit for a message that carries status, metadata, or diagnostics rather than
   * result data, such as ErrorResponse or RowDescription, so that a length read from a stream out
   * of sync cannot size a large allocation. A message that carries result data, such as DataRow,
   * CopyData, or FunctionCallResponse, gets no such limit. DataRow is bounded instead by
   * {@code maxResultBuffer}, checked in {@link #receiveTupleV3()}.</p>
   *
   * <p>The length field counts its own four bytes; see
   * {@link #readMessageLength(String, int)}.</p>
   *
   * @param messageName protocol message name used in the error message
   * @param minLength inclusive minimum valid value of the length field; must be at least 4 and
   *                  at most {@code maxLength}
   * @param maxLength inclusive maximum valid value of the length field;
   *                  must be ≤ {@link #MAX_MESSAGE_SIZE}
   * @return the validated length
   * @throws IllegalArgumentException if the bounds break the rules above; no byte is read and the
   *                                  stream is not marked broken
   * @throws IOException if the length is out of range; the stream is marked broken and its
   *                     socket closed first
   */
  public int readMessageLength(String messageName, int minLength, int maxLength) throws IOException {
    int len = validateMessageLength(messageName, 4, minLength, maxLength);
    beginMessage(messageName, len);
    return len;
  }

  /**
   * Reads and validates a 4-byte length prefix that counts only the payload after it, and opens
   * no message. The bounds are checked as in {@link #readMessageLength(String, int, int)}, and a
   * message left open by an earlier read is discarded without a check.
   *
   * <p>A tracked message would end 4 bytes early here, because tracking assumes the length
   * counts its own four bytes. The GSS encryption handshake token is the only such prefix in the
   * v3 protocol.</p>
   *
   * @param messageName protocol message name used in the error message
   * @param minLength inclusive minimum valid value of the length field; must be at least 0 and
   *                  at most {@code maxLength}
   * @param maxLength inclusive maximum valid value of the length field;
   *                  must be ≤ {@link #MAX_MESSAGE_SIZE}
   * @return the validated length
   * @throws IllegalArgumentException if the bounds break the rules above; no byte is read and the
   *                                  stream is not marked broken
   * @throws IOException if the length is out of range; the stream is marked broken and its
   *                     socket closed first
   */
  public int readUntrackedLength(String messageName, int minLength, int maxLength)
      throws IOException {
    int len = validateMessageLength(messageName, 0, minLength, maxLength);
    resetMessageTracker();
    return len;
  }

  /**
   * Reads the 4-byte length field and returns it once it is within {@code minLength} and
   * {@code maxLength}, both inclusive. Opens no message: a caller that wants one tracked
   * calls {@link #beginMessage} itself.
   *
   * <p>The bounds are checked before any byte is read. A wrong pair of bounds is a defect in the
   * driver rather than in the backend, so it leaves the stream untouched.</p>
   *
   * @param messageName protocol message name used in the error message
   * @param lowestValid smallest {@code minLength} the length field allows: 4 for a length that
   *                    counts its own four bytes, 0 for one that counts only the payload
   * @param minLength inclusive minimum valid value of the length field
   * @param maxLength inclusive maximum valid value of the length field
   * @throws IllegalArgumentException if {@code minLength} is below {@code lowestValid} or above
   *                                  {@code maxLength}, or {@code maxLength} is above
   *                                  {@link #MAX_MESSAGE_SIZE}
   * @throws IOException if the length is outside that range; the stream is marked broken and
   *                     its socket closed first
   */
  private int validateMessageLength(String messageName, int lowestValid, int minLength,
      int maxLength) throws IOException {
    if (minLength < lowestValid || minLength > maxLength || maxLength > MAX_MESSAGE_SIZE) {
      throw new IllegalArgumentException(GT.tr(
          "{0} length bounds {1}..{2} must lie within {3}..{4}",
          messageName, String.valueOf(minLength), String.valueOf(maxLength),
          String.valueOf(lowestValid), String.valueOf(MAX_MESSAGE_SIZE)));
    }
    int len = receiveInteger4();
    if (len < minLength || len > maxLength) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
          messageName, String.valueOf(len), String.valueOf(minLength),
          String.valueOf(maxLength))));
    }
    return len;
  }

  /**
   * Reads a message length, rejects a value above a limit pgjdbc applies before the peer has
   * authenticated, and opens the message for {@link #endMessage()} to close.
   *
   * <p>Other validation matches {@link #readMessageLength(String, int, int)}. A length over
   * {@code limit} fails with its own error, which names the limit as pgjdbc's. Otherwise an
   * operator would see "expected between 5 and 1048576" and have to guess whether 1048576 comes
   * from the protocol.</p>
   *
   * <p>No connection property raises this limit, and {@link ProtocolHardeningMode#DISABLE} does
   * not switch it off, because the peer is not yet authenticated. The error message states that
   * the limit cannot be relaxed.</p>
   *
   * @param messageName protocol message name used in the error message
   * @param minLength inclusive minimum valid value of the length field; must be at least 4
   * @param limit the limit pgjdbc applies to this message before authentication
   * @return the validated length
   * @throws IllegalArgumentException if {@code minLength} is below 4; no byte is read and the
   *                                  stream is not marked broken
   * @throws IOException if the length is out of range; the stream is marked broken and its
   *                     socket closed first
   */
  public int readPreAuthMessageLength(String messageName, int minLength, int limit)
      throws IOException {
    int len = validateMessageLength(messageName, 4, minLength, MAX_MESSAGE_SIZE);
    if (len > limit) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
          messageName, String.valueOf(len), String.valueOf(limit))));
    }
    beginMessage(messageName, len);
    return len;
  }

  /**
   * Reads a 4-byte length prefix that must equal {@code expectedLength}, and opens the message
   * for {@link #endMessage()} to close.
   *
   * <p>The length field counts its own four bytes, so {@code expectedLength} is 4 for a
   * message with an empty body. A length that differs marks the stream broken and closes its
   * socket.</p>
   *
   * @param messageName protocol message name used in the error message
   * @param expectedLength the exact value the length field must have, at least 4
   * @throws IOException if the length differs from {@code expectedLength}
   */
  public void readFixedMessageLength(String messageName, int expectedLength) throws IOException {
    int len = receiveInteger4();
    if (len != expectedLength) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has length {1}, expected {2}.",
          messageName, String.valueOf(len), String.valueOf(expectedLength))));
    }
    beginMessage(messageName, expectedLength);
  }

  /**
   * Receives a two byte integer from the backend as an unsigned integer (0..65535). Some v3
   * protocol fields of this width are signed, such as the type length, which is {@code -1} for a
   * variable-length type. A caller reading a signed field must cast the result to {@code short}.
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
   * Scans the next NUL-terminated C-string and returns its length, including the trailing NUL,
   * without consuming it.
   *
   * <p>The scan is always bounded, so a stream out of sync cannot keep growing the buffer and
   * reading into it. The bound is the part of the message opened by {@link #beginMessage} that is
   * not yet consumed, or {@link #MAX_MESSAGE_SIZE} when no message is tracked.</p>
   *
   * @throws IOException if no NUL arrives within the bound, if the tracked message has no bytes
   *                     left, at end of stream, or on I/O error; the stream is marked broken first
   */
  private int scanBoundedCStringLength() throws IOException {
    // Every IOException from the scan leaves the stream unusable: the message has no NUL within
    // the bytes it has left, the input reached end of stream, or the read failed. So every
    // IOException marks the stream broken before it propagates.
    try {
      if (messageEndPosition < 0) {
        return pgInput.scanCStringLength(
            MAX_MESSAGE_SIZE, "unknown", MAX_MESSAGE_SIZE);
      }
      long remaining = messageEndPosition - pgInput.getPosition();
      int budget = (int) Math.min(remaining, MAX_MESSAGE_SIZE);
      return pgInput.scanCStringLength(
          budget, currentMessageNameForError(), currentMessageLength);
    } catch (IOException e) {
      throw markBroken(e);
    }
  }

  /**
   * Reads a NUL-terminated C-string from the backend. The scan is always bounded, as
   * {@link #scanBoundedCStringLength()} describes, and a scan that fails marks the stream broken.
   *
   * @return the decoded string
   * @throws IOException if no NUL is found within the bounds, at end of stream, or on I/O error
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
   * The scan is always bounded, as {@link #scanBoundedCStringLength()} describes, and a scan
   * that fails marks the stream broken.
   *
   * @return string from back end
   * @throws IOException if no NUL is found within the bounds, at end of stream, or on I/O error
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
   * The scan is always bounded, as {@link #scanBoundedCStringLength()} describes, and a scan
   * that fails marks the stream broken.
   *
   * @return string from back end
   * @throws IOException if no NUL is found within the bounds, at end of stream, or on I/O error
   * @see Encoding#decodeCanonicalizedIfPresent(byte[], int, int)
   */
  public String receiveCanonicalStringIfPresent() throws IOException {
    int len = scanBoundedCStringLength();
    String res = encoding.decodeCanonicalizedIfPresent(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Reads a tuple from the back end. A tuple is a two dimensional array of bytes. This variant
   * reads the V3 protocol's tuple representation.
   *
   * @return tuple from the back end
   * @throws IOException if a data I/O error occurs
   * @throws SQLException if read more bytes than set maxResultBuffer
   */
  public Tuple receiveTupleV3() throws IOException, OutOfMemoryError, SQLException {
    // A DataRow length counts its own 4 bytes, then 2 for nf and 4 per field: minimum 6.
    int messageSize = readMessageLength("DataRow", 6);
    // The field count is an unsigned int16, as libpq reads it. The protocol fixes no smaller
    // maximum, and forks such as CockroachDB, YugabyteDB, and Redshift need not share
    // PostgreSQL's column limit, so the declared message length is the only bound on nf.
    int nf = receiveInteger2();
    //size = messageSize - 4 bytes of message size - 2 bytes of field count - 4 bytes for each column length
    int dataToReadSize = messageSize - 4 - 2 - 4 * nf;
    if (dataToReadSize < 0) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. DataRow field count {0} requires at least {1} bytes for per-field length prefixes, but the message size is only {2}.",
          String.valueOf(nf), String.valueOf(4 * nf), String.valueOf(messageSize))));
    }
    setMaxRowSizeBytes(dataToReadSize);

    byte[][] answer = new byte[nf][];

    increaseByteCounter(dataToReadSize);
    OutOfMemoryError oom = null;
    int remaining = dataToReadSize;
    for (int i = 0; i < nf; i++) {
      int size = receiveInteger4();
      if (size != -1) {
        if (size < -1) {
          // The wire protocol assigns exactly two meanings to the per-field length: -1 is
          // NULL, any non-negative value is the byte count.
          throw markBroken(new IOException(GT.tr(
              "Protocol error. DataRow field {0} has negative length {1}.",
              String.valueOf(i), String.valueOf(size))));
        }
        if (size > remaining) {
          // The scenario from issue #4015: a field claiming more bytes than the row
          // still holds drove a ~1.7 GB allocation and an indefinite socket read.
          throw markBroken(new IOException(GT.tr(
              "Protocol error. DataRow field {0} length {1} exceeds remaining row bytes {2}.",
              String.valueOf(i), String.valueOf(size), String.valueOf(remaining))));
        }
        remaining -= size;
        byte[] field;
        try {
          field = new byte[size];
        } catch (OutOfMemoryError oome) {
          // This catch covers only the allocation, because skipping the whole field is correct
          // only while none of its body has been read.
          oom = oome;
          skip(size);
          continue;
        }
        try {
          receive(field, 0, size);
        } catch (OutOfMemoryError oome) {
          // VisibleBufferedInputStream doubles its buffer while it reads, so receive can run
          // out of memory with part of the field already consumed. The number of bytes it
          // consumed is unknown, so the reader cannot return to a message boundary.
          throw markBroken(oome);
        }
        answer[i] = field;
      }
    }

    // The loop rejects a field longer than the bytes remaining, and endMessage rejects a
    // DataRow whose fields end before its declared length.
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

  /**
   * Discards a given number of bytes from the backend.
   *
   * @param size number of bytes to discard
   * @throws EOFException if the connection ends before that many bytes arrive
   * @throws IOException if a data I/O error occurs
   */
  public void skip(int size) throws IOException {
    long s = 0;
    while (s < size) {
      long skipped = pgInput.skip(size - s);
      if (skipped == 0) {
        // InputStream.skip() may return 0 for two different reasons: the stream still
        // has data but chose not to skip any right now, or the stream has reached
        // end-of-stream and will return 0 on every future call. We cannot tell which
        // from skip() alone, so we read one byte: read() blocks until a byte is
        // available and returns -1 only at end-of-stream, which is the reliable signal.
        if (pgInput.read() == -1) {
          throw new EOFException();
        }
        // The byte we just read is one of the bytes we were asked to discard, so count
        // it. It also fills the underlying buffer, so the next skip() can discard many
        // bytes at once instead of one per read.
        skipped = 1;
      }
      s += skipped;
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
    pgOutput.flush();
  }

  /**
   * Waits for end of stream from the backend. A byte arriving instead marks the stream broken
   * and closes its socket.
   *
   * @throws IOException if an I/O error occurs
   * @throws SQLException if the backend sends a byte instead of closing
   */
  public void receiveEOF() throws SQLException, IOException {
    int c = pgInput.read();
    if (c < 0) {
      return;
    }
    throw markBroken(new PSQLException(GT.tr("Expected an EOF from server, got: {0}", c),
        PSQLState.COMMUNICATION_ERROR));
  }

  /**
   * Closes the connection.
   *
   * @throws IOException if an I/O Error occurs
   */
  @Override
  public void close() throws IOException {
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
   * Sets the {@code maxResultBuffer} limit on the result-set bytes this stream buffers.
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
   * Sets the limit on a backend message whose body is server-generated text (ErrorResponse,
   * NoticeResponse, CommandComplete, ParameterStatus, NotificationResponse), parsed the same
   * way as {@code maxResultBuffer}.
   *
   * @param value size with an optional unit or heap-percent suffix; {@code null} or unparsed
   *              leaves {@link #DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE} in effect
   * @throws PSQLException if the value cannot be parsed
   */
  public void setMaxServerTextMessageSize(@Nullable String value) throws PSQLException {
    long parsed = PGPropertyMaxResultBufferParser.parseProperty(value);
    maxServerTextMessageSize = parsed > 0 ? parsed : DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE;
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
   * Adds to the running count of result-set bytes, and marks the stream broken through
   * {@link #markBroken(Throwable)} once that count passes the max result buffer limit.
   *
   * @param value size of bytes to add to byte buffer.
   * @throws SQLException exception returned when result buffer count is bigger than max result
   *                      buffer.
   */
  private void increaseByteCounter(long value) throws SQLException {
    if (maxResultBuffer != -1) {
      resultBufferByteCount += value;
      if (resultBufferByteCount > maxResultBuffer) {
        throw markBroken(new PSQLException(GT.tr(
          "Result set exceeded maxResultBuffer limit. Received:  {0}; Current limit: {1}",
          String.valueOf(resultBufferByteCount), String.valueOf(maxResultBuffer)), PSQLState.COMMUNICATION_ERROR));
      }
    }
  }

  /**
   * Reports whether this connection is unusable. {@code true} once the socket is closed, and
   * also once {@link #markBroken(Throwable)} has flagged the stream broken, even where the
   * socket itself is still open.
   */
  public boolean isClosed() {
    return broken || connection.isClosed();
  }

  /**
   * Reports whether the underlying socket is closed, ignoring the broken flag that
   * {@link #isClosed()} also consults. {@link #markBroken(Throwable)} closes the socket only on
   * a best-effort basis, so a broken stream can still hold an open descriptor.
   */
  public boolean isSocketClosed() {
    return connection.isClosed();
  }
}
