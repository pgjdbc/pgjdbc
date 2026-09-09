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
  /**
   * Largest message the driver accepts. {@code 0x3FFFFFFF} is the backend's
   * {@code MaxAllocSize}, the most it can allocate for an outgoing message.
   */
  public static final int MAX_MESSAGE_LENGTH = 0x3FFFFFFF;

  /**
   * Cap for message types that never carry bulk data: CommandComplete, AuthenticationRequest,
   * AuthenticationGSSContinue, BackendKeyData, NegotiateProtocolVersion and CopyDone. The value
   * matches PostgreSQL's {@code MAX_STARTUP_PACKET_LENGTH}, the 10000 byte ceiling the backend
   * puts on a startup packet, reused here as a bound for a short control message. It sits within
   * what libpq accepts, which is 2000 bytes for {@code 'R'} and {@code 'v'} during setup and
   * 30000 for any message outside {@code VALID_LONG_MESSAGE_TYPE} after it.
   */
  public static final int MAX_SMALL_MESSAGE_LENGTH = 10000;

  /**
   * Largest body buffered whole by {@link #receiveString(int)} or
   * {@link #receiveErrorString(int)}, where the buffer maximum is the real limit. DataRow and
   * CopyData read straight into their destination instead.
   *
   * <p>ErrorResponse and NoticeResponse accept any length, buffer this much of the body and drain
   * the rest, so a long one is truncated rather than refused. ParameterStatus and
   * NotificationResponse are refused above this cap. A NOTIFY payload is at most 8000 bytes, and
   * libpq itself drops the connection on a ParameterStatus above 30000 bytes.</p>
   *
   * <p>The body is four bytes shorter than the message, so a message at exactly this cap still
   * fits the buffer maximum.</p>
   */
  public static final int MAX_BUFFERED_MESSAGE_LENGTH =
      Math.min(MAX_MESSAGE_LENGTH, VisibleBufferedInputStream.MAX_BUFFER_SIZE);

  /**
   * Cap for the text bearing messages that arrive before authentication, where a five byte
   * header from an unauthenticated peer decides the allocation. libpq refuses a message outside
   * {@code VALID_LONG_MESSAGE_TYPE} whose declared length exceeds 30000 bytes, and the declared
   * length counts itself, so this is the same bound.
   */
  public static final int MAX_PRE_AUTH_MESSAGE_LENGTH = 30000;

  /**
   * Cap on the exchanges in each of the three loops that run before authentication finishes.
   * Those are the authentication loop itself and the two GSS handshakes. Message sizes are
   * bounded but iteration counts are not, so a server that answers every token with another
   * keeps the client going indefinitely. Sixty four is an order of magnitude above any real
   * handshake. The longest is SASL at four.
   */
  public static final int MAX_AUTH_ROUND_TRIPS = 64;

  private final SocketFactory socketFactory;
  private final HostSpec hostSpec;
  private final int maxSendBufferSize;
  private Socket connection;
  private VisibleBufferedInputStream pgInput;
  private PgBufferedOutputStream pgOutput;
  private @Nullable ProtocolVersion protocolVersion;
  private boolean broken;

  /**
   * Stream position at which the message being read ends, or -1 when no declared length is
   * outstanding. Set by {@link #receiveMessageLength} and checked by {@link #receiveMessageType}.
   */
  private long messageEnd = -1;

  /**
   * Builds the callback handed to the buffered and GSS streams so their own refusals mark this
   * stream broken. A method rather than a field, because an anonymous class in a field
   * initializer captures the enclosing instance before it is initialized, which the Checker
   * Framework rejects for the setBroken call.
   */
  private Runnable markBroken() {
    return new Runnable() {
      @Override
      public void run() {
        setBroken();
      }
    };
  }

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
    // The replacement stream starts its own byte count. Nothing is outstanding, because the
    // handshake is not message framed.
    messageEnd = -1;
    pgInput = new VisibleBufferedInputStream(
        new GSSInputStream(pgInput, secContext, messageProp, markBroken()), 8192, markBroken());
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

  private long maxResultBuffer = -1;
  private long resultBufferByteCount;

  private int maxRowSizeBytes = -1;

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
    // The replacement stream starts its own byte count.
    messageEnd = -1;

    // Submitted by Jason Venner <jason@idiom.com>. Disable Nagle
    // as we are selective about flushing output only when we
    // really need to.
    connection.setTcpNoDelay(true);

    pgInput = new VisibleBufferedInputStream(connection.getInputStream(), 8192, markBroken());
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
   * Receives a message length and checks it. Everything the driver reads or allocates for the
   * message is sized from this field. The length includes the four bytes of the field itself.
   *
   * @param packetName wire-protocol name of the message, used in the error
   * @param minLength smallest length the message layout permits, at least 4
   * @param maxLength largest length accepted for the message type
   * @return the message length
   * @throws IOException if the declared length is out of range
   */
  public int receiveMessageLength(String packetName, int minLength, int maxLength)
      throws IOException {
    int length = receiveInteger4();
    if (length < minLength || length > maxLength) {
      throw protocolViolation(GT.tr(
          "Backend declared a {0} message length of {1} bytes, expected {2} to {3} bytes.",
          packetName, String.valueOf(length), String.valueOf(minLength),
          String.valueOf(maxLength)));
    }
    // The length counts its own four bytes, which have just been read.
    messageEnd = pgInput.getPosition() + length - 4;
    return length;
  }

  /**
   * Receives the type byte of the next backend message, having first checked that the previous
   * message was consumed exactly. A reader that sizes a buffer from a raw
   * {@link #receiveInteger4()}, or that stops short of its declared length, fails here rather
   * than by misreading what follows.
   *
   * <p>Only messages whose length was read through {@link #receiveMessageLength} are checked. The
   * parts of the stream that are not message framed, namely the single byte replies to the SSL
   * and GSS encryption requests and the raw token exchange of the GSS handshake, are read with
   * {@link #receiveChar()} and are unaffected.</p>
   *
   * @return the message type byte
   * @throws IOException if the previous message was not consumed exactly, or on an I/O error
   */
  public int receiveMessageType() throws IOException {
    long end = messageEnd;
    if (end >= 0) {
      messageEnd = -1;
      long position = pgInput.getPosition();
      if (position != end) {
        throw protocolViolation(GT.tr(
            "The previous backend message declared its end at byte {0} of the stream, but its"
                + " reader stopped at byte {1}.", String.valueOf(end), String.valueOf(position)));
      }
    }
    return receiveChar();
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
   * Receives a null-terminated string from the backend. If we don't see a null, then we assume
   * something has gone wrong.
   *
   * @return string from back end
   * @throws IOException if an I/O error occurs, or end of file
   */
  public String receiveString() throws IOException {
    int len = scanCStringLength();
    String res = encoding.decode(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Receives a null-terminated string from the backend and attempts to decode to a
   * {@link Encoding#decodeCanonicalized(byte[], int, int) canonical} {@code String}.
   * If we don't see a null, then we assume something has gone wrong.
   *
   * @return string from back end
   * @throws IOException if an I/O error occurs, or end of file
   * @see Encoding#decodeCanonicalized(byte[], int, int)
   */
  public String receiveCanonicalString() throws IOException {
    int len = scanCStringLength();
    String res = encoding.decodeCanonicalized(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Receives a null-terminated string from the backend and attempts to decode to a
   * {@link Encoding#decodeCanonicalizedIfPresent(byte[], int, int) canonical} {@code String}.
   * If we don't see a null, then we assume something has gone wrong.
   *
   * @return string from back end
   * @throws IOException if an I/O error occurs, or end of file
   * @see Encoding#decodeCanonicalizedIfPresent(byte[], int, int)
   */
  public String receiveCanonicalStringIfPresent() throws IOException {
    int len = scanCStringLength();
    String res = encoding.decodeCanonicalizedIfPresent(pgInput.getBuffer(), pgInput.getIndex(), len - 1);
    pgInput.skip(len);
    return res;
  }

  /**
   * Scans the next C string, looking no further than the end of the message holding it. Without
   * a bound the scan grows the read buffer to its maximum before failing, an allocation an
   * unauthenticated peer can force for the cost of a header.
   *
   * @return the length of the string including its terminator
   * @throws IOException if the message holds no terminator, or on an I/O error
   */
  private int scanCStringLength() throws IOException {
    if (messageEnd < 0) {
      // The startup exchange and the GSS handshake run outside any declared message.
      return pgInput.scanCStringLength();
    }
    long remaining = messageEnd - pgInput.getPosition();
    if (remaining <= 0) {
      throw protocolViolation(GT.tr("String starts at or past the end of its message."));
    }
    return pgInput.scanCStringLength((int) Math.min(remaining, Integer.MAX_VALUE));
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
    // 4 (length) + 2 (field count)
    int messageSize = receiveMessageLength("DataRow", 6, MAX_MESSAGE_LENGTH);
    int nf = receiveInteger2();
    //size = messageSize - 4 bytes of message size - 2 bytes of field count - 4 bytes for each column length
    // Cannot overflow, nf is an unsigned int2.
    int dataToReadSize = messageSize - 4 - 2 - 4 * nf;
    if (dataToReadSize < 0) {
      throw protocolViolation(GT.tr("DataRow of {0} bytes cannot hold {1} column lengths.",
          String.valueOf(messageSize), String.valueOf(nf)));
    }
    setMaxRowSizeBytes(dataToReadSize);

    byte[][] answer = new byte[nf][];

    increaseByteCounter(dataToReadSize);
    OutOfMemoryError oom = null;
    int remaining = dataToReadSize;
    for (int i = 0; i < nf; i++) {
      int size = receiveInteger4();
      if (size != -1) {
        // -1 is the only negative with a meaning, and no column exceeds what is left.
        if (size < 0 || size > remaining) {
          throw protocolViolation(GT.tr("DataRow column of {0} bytes does not fit in the {1} bytes"
              + " left of the message.", String.valueOf(size), String.valueOf(remaining)));
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

    if (oom != null) {
      throw oom;
    }
    if (remaining != 0) {
      throw protocolViolation(GT.tr("DataRow of {0} bytes has {1} unread bytes.",
          String.valueOf(messageSize), String.valueOf(remaining)));
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
    if (!broken) {
      // Flushing a desynced stream pushes the tail of a half written request at a peer that
      // is already discarding it.
      pgOutput.close();
    }
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
        // The row is not read, so the stream is off its message boundary. The connection is
        // dropped here on purpose rather than refused at the next dispatch.
        setBroken();
        throw new PSQLException(GT.tr(
          "Result set exceeded maxResultBuffer limit. Received:  {0}; Current limit: {1}",
          String.valueOf(resultBufferByteCount), String.valueOf(maxResultBuffer)), PSQLState.COMMUNICATION_ERROR);
      }
    }
  }

  /**
   * Whether a length or a count off the wire has been refused on this stream.
   *
   * @return true once the stream is known to be out of step with the protocol
   */
  public boolean isBroken() {
    return broken;
  }

  /**
   * Records that the stream is no longer aligned with the protocol and drops the socket.
   * Nothing after a refused length can be interpreted, so the connection must not be handed to
   * another caller: {@link #isClosed()} reports it closed from here on, which is what a pool
   * testing on borrow looks at.
   *
   * <p>The socket is closed here rather than through {@link #close()}, which would flush
   * {@code pgOutput} at a peer that is already discarding it. {@code SO_LINGER 0} makes the close
   * an immediate reset. If it fails, the descriptor is released on the regular close path.</p>
   */
  public void setBroken() {
    if (broken) {
      return;
    }
    broken = true;
    try {
      connection.setSoLinger(true, 0);
    } catch (Exception e) {
      // A close without SO_LINGER 0 is graceful rather than immediate, which is acceptable.
    }
    try {
      connection.close();
    } catch (IOException e) {
      // QueryExecutorCloseAction closes the socket again on the regular close path.
    }
  }

  /**
   * Marks the stream broken and builds the exception for a refused length or count. Every
   * refusal goes through here, so none of them leaves a connection that looks reusable.
   *
   * @param message the already translated message
   * @return the exception to throw
   */
  public IOException protocolViolation(String message) {
    setBroken();
    return new IOException(message);
  }

  public boolean isClosed() {
    return broken || connection.isClosed();
  }
}
