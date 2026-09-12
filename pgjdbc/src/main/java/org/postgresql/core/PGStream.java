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
 * Reads and writes formatted protocol data over the raw connection to the server, and encodes
 * and decodes strings on the way through.
 *
 * <p>Each reader of a backend message must read its length through
 * {@link #readMessageLength(String, int)}, {@link #readFixedMessageLength(String, int)} or
 * {@link #readPreAuthMessageLength(String, int, int)}, check any further length it reads from the
 * body against the bytes the envelope has left, and close the envelope with {@link #endMessage()}.
 * A reader that skips any of these leaves the stream off a message boundary, so the next
 * {@link #receiveMessageType()} throws rather than mistaking a body byte for a message type.</p>
 *
 * <p>Two words for a maximum, kept apart. A <i>ceiling</i> is a maximum this class applies to a
 * message it reads, whether the protocol fixes it ({@link #MAX_MESSAGE_SIZE}) or pgjdbc picks it
 * ({@link #DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE}). A <i>limit</i> is a number owned elsewhere: a
 * connection property such as {@code maxResultBuffer}, or a backend constant such as
 * {@code PG_MAX_AUTH_TOKEN_LENGTH}. A property can supply the number a ceiling applies, so
 * {@code maxCopyDataSize} is a limit where an error message names it and a ceiling where this
 * class enforces it.</p>
 *
 * <p>In general, instances of PGStream are not threadsafe; the caller must ensure that only one thread
 * at a time is accessing a particular PGStream instance.</p>
 */
public class PGStream implements Closeable, Flushable {
  /**
   * PostgreSQL backend's {@code MaxAllocSize} (1 GB - 1): the largest legal size of a single
   * protocol message. Any length field that exceeds this value, or that falls below the
   * message's minimum, indicates a corrupted or desynced stream. This is a protocol-fixed
   * ceiling and is shared by every PostgreSQL wire-compatible backend (CockroachDB,
   * YugabyteDB, Redshift, Greenplum, ...).
   */
  public static final int MAX_MESSAGE_SIZE = 0x3fffffff;

  /**
   * Default ceiling pgjdbc applies to a backend message whose body is server-generated text:
   * ErrorResponse, NoticeResponse, CommandComplete, ParameterStatus, NotificationResponse.
   * RowDescription has its own; see {@link #MAX_ROW_DESCRIPTION_SIZE}.
   *
   * <p>The protocol fixes no maximum for these, and libpq applies none either: they sit in
   * its {@code VALID_LONG_MESSAGE_TYPE} set, which exists to exempt them from its 30000-byte
   * limit. A server can therefore emit an arbitrarily large {@code RAISE NOTICE} payload or
   * error {@code DETAIL} today, so the default has to clear any workload that already works.
   * 64 MB does that while still bounding the allocation a desynced length can drive.
   *
   * <p>Decimal for the same reason as {@link #DEFAULT_MAX_COPY_DATA_SIZE}: the violation
   * message names the property, and that property is parsed by
   * {@link PGPropertyMaxResultBufferParser}, whose suffixes are decimal.
   *
   * @see #setMaxServerTextMessageSize(String)
   */
  public static final int DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE = 64_000_000;

  /**
   * Default ceiling pgjdbc applies to RowDescription. A PostgreSQL RowDescription tops out near
   * 133 KiB (1664 columns x (63-byte label + NUL + 18 fixed bytes)), and a fork that raises both
   * the column limit and NAMEDATALEN reaches 2.6 MiB at 32767 columns, so 8 MiB clears every
   * plausible one while still catching a desynced length.
   */
  public static final int MAX_ROW_DESCRIPTION_SIZE = 8 * 1024 * 1024;

  /**
   * Ceiling pgjdbc applies to NegotiateProtocolVersion. The message lists the startup-packet
   * options the backend did not recognise, so it cannot be larger than the startup packet the
   * driver just sent: every option in it is a parameter name the driver chose. Those are GUC
   * names, and a startup packet is a few hundred bytes, so 1 MiB is orders of magnitude over
   * anything reachable and does not vary with the workload.
   *
   * <p>Keep this ceiling at or below {@link #MAX_CSTRING_LENGTH}, and keep every other ceiling
   * that applies before authentication there too. No mode lifts the per-string ceiling before
   * the peer has authenticated, so a C-string stays bounded there only while the envelope
   * around it is no wider than one string may be.
   */
  public static final int MAX_NEGOTIATE_PROTOCOL_VERSION_SIZE = 1 << 20;

  /**
   * Ceiling, in bytes including the trailing NUL, that pgjdbc applies to one NUL-terminated
   * string inside a backend message. No connection property raises it: the widest field that
   * reaches a string scan is a NOTIFY payload, which the backend caps at 8000 bytes.
   * {@link ProtocolHardeningMode#DISABLE} raises it to {@link #MAX_MESSAGE_SIZE}, leaving the
   * message envelope as the only bound, because a GUC value is the one scanned field the
   * server gives no length of its own.
   *
   * <p>The envelope bounds the sum of a message's fields, but not how much the driver holds
   * while it looks for the NUL of a single one: {@link #receiveString()} decodes straight out
   * of the read buffer, so the scan cannot discard as it goes, and the buffer doubles until
   * the scan runs out of budget. Without this ceiling a message that declares a size near its
   * own ceiling and sends no NUL would grow the buffer past that ceiling before being
   * rejected; with it the buffer stops at 2 MiB. ErrorResponse and NoticeResponse are
   * unaffected, since their bodies are read as a block rather than scanned.
   */
  public static final int MAX_CSTRING_LENGTH = 1 << 20;

  /**
   * Ceiling pgjdbc applies to AuthenticationRequest and AuthenticationGSSContinue: 8 bytes of
   * header plus an 8000-byte payload. The payload is a SCRAM, MD5 or GSS continuation token.
   *
   * <p>Both messages arrive before authentication, so the peer has proved nothing yet and no
   * connection property raises this ceiling.
   *
   * <p>8000 bytes is much more than a server sends here. A SCRAM challenge and an MD5 salt are
   * only tens of bytes, and a GSS continuation carries the server's half of the handshake, not the
   * client's ticket. This ceiling applies only to that server-to-client direction. The large
   * Kerberos tickets, which can reach 64 kB when a Windows AD PAC is included, are sent the other
   * way, from client to server, where the backend applies its own limit of
   * {@code PG_MAX_AUTH_TOKEN_LENGTH} (65535 bytes) instead. libpq rejects either message above
   * 2000 bytes in {@code fe-connect.c}, so a server that needed more than this ceiling could not
   * authenticate psql either.
   */
  public static final int MAX_AUTHENTICATION_MESSAGE_SIZE = 8 + 8000;

  /**
   * Largest row body the driver will pull off the wire and discard in order to keep a
   * connection usable after a {@code maxResultBuffer} rejection. Skipping trades traffic and
   * time for a live connection, and the trade only pays while the amount is small enough to
   * be uninteresting -- 64 MB is under a second on any link a database runs over, and rows
   * that big are already unusual. Above it, discarding could mean pulling up to
   * {@link #MAX_MESSAGE_SIZE} per row for as many rows as the peer cares to send, and a
   * length that far out is far likelier to be a desync than a fat row, so the connection is
   * closed instead.
   *
   * <p>Decimal for the same reason as {@link #DEFAULT_MAX_COPY_DATA_SIZE}: an operator
   * comparing this threshold against their own {@code maxResultBuffer} reads both in the
   * units that property's parser uses.</p>
   */
  static final int MAX_RECOVERABLE_SKIP = 64_000_000;

  /**
   * Ceiling pgjdbc applies to a single CopyData message when {@code maxCopyDataSize} is not
   * configured. CopyData carries user rows, so the driver cannot derive a bound from the
   * protocol; without one, {@code new byte[len]} takes its size straight off the wire, which is
   * what issue #4015 flags: a wire-supplied length driving the allocation. 64 MB is far above a
   * real COPY row (usually well under a megabyte, or tens of megabytes with large objects). A
   * desynced length, by contrast, is essentially a random value up to {@code MAX_MESSAGE_SIZE}
   * (about 1 GB), so roughly 15 out of 16 such lengths fall above 64 MB and are caught on the
   * first CopyData message.
   *
   * <p>Decimal rather than binary on purpose. The violation message tells the operator to
   * raise {@code maxCopyDataSize}, and that property is parsed by
   * {@link PGPropertyMaxResultBufferParser}, whose suffixes are decimal ({@code K} is 1000).
   * Were this 64 MiB, answering the message with {@code maxCopyDataSize=64M} would quietly
   * lower the ceiling by 3 MB rather than leave it alone.</p>
   *
   * <p>{@link ProtocolHardeningMode#DISABLE} can switch this ceiling off, along with the other
   * ceilings the driver picks rather than derives from the protocol. Setting
   * {@code maxCopyDataSize} takes it out of the mode's hands: the value is then the user's own,
   * and only the user can lower it.</p>
   */
  static final int DEFAULT_MAX_COPY_DATA_SIZE = 64_000_000;

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
    // The new VisibleBufferedInputStream starts its byte counter at zero, so an envelope
    // captured against the previous stream would point at an unrelated absolute position.
    resetMessageTracker();
    markMessageBoundary();
  }

  private long nextStreamAvailableCheckTime;
  // This is a workaround for SSL sockets: sslInputStream.available() might return 0
  // so we perform "1ms reads" once in a while
  private int minStreamAvailableCheckDelay = 1000;

  private Encoding encoding;

  private long maxResultBuffer = -1;
  private long resultBufferByteCount;

  /**
   * User-configured ceiling on a single CopyData message, or {@code -1} when unset. See
   * {@link #DEFAULT_MAX_COPY_DATA_SIZE} for what applies in the unset case.
   */
  private long maxCopyDataSize = -1;

  /** Ceiling on server-generated text messages; see {@link #setMaxServerTextMessageSize(String)}. */
  private long maxServerTextMessageSize = DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE;

  /**
   * Set once a row over {@code maxResultBuffer} has been reported, so the rest are skipped
   * without raising a second exception. Cleared by {@link #clearOversizedRowReport()} on
   * every ReadyForQuery, which makes the scope one Sync rather than one result set: a simple
   * query that returns several result sets reports the first over-sized row across all of
   * them. Harmless, because the exception fails the whole {@code execute()} before the later
   * result sets are handed to the caller.
   */
  private boolean reportedOversizedRow;

  private int maxRowSizeBytes = -1;

  /**
   * Becomes {@code true} the first time a protocol-level hardening check rejects a
   * backend message. Once broken the stream is permanently desynced: even if the
   * underlying socket happens to be open, no further bytes from it can be trusted. The
   * flag is consulted by {@link #isClosed()} so a connection pool that asks
   * {@code isClosed()/isValid()} on borrow will discard the connection rather than
   * hand it to another caller. The matching socket close is best-effort; when it fails
   * the descriptor is released by the regular close path, which consults
   * {@link #isSocketClosed()} rather than {@link #isClosed()}.
   */
  private volatile boolean broken;

  /**
   * Selects what happens when a backend message exceeds one of the driver's ceilings.
   * Defaults to the JVM-wide {@link ProtocolHardeningMode#CURRENT}, which is itself sourced
   * from the {@value ProtocolHardeningMode#SYSTEM_PROPERTY} system property. Exposed via a
   * setter ({@link #setProtocolHardeningMode}) primarily for tests that need to
   * exercise {@link ProtocolHardeningMode#DISABLE} without touching the JVM-wide
   * state.
   */
  private ProtocolHardeningMode protocolHardeningMode = ProtocolHardeningMode.CURRENT;

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
   * Stream position at which the backend dialogue is known to sit on a message boundary.
   * Advanced by {@link #endMessage()} once an envelope has been consumed exactly, and by
   * {@link #markMessageBoundary()} where the dialogue steps outside message framing (the
   * SSL and GSS encryption negotiations). Compared against the current position by
   * {@link #receiveMessageType()}.
   */
  private long messageBoundaryPosition;

  /**
   * Name of the message that {@link #endMessage()} last closed, quoted by the
   * message-boundary check so the failure names the reader that left the stream misplaced.
   * {@code null} until the first message has been read.
   */
  private @Nullable String lastMessageName;

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
   * consumed. Throws {@link IOException} when the caller has read fewer or more body bytes
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
    resetMessageTracker();
    if (expected < 0) {
      return;
    }
    long actual = pgInput.getPosition();
    // Under-read and over-read get separate messages: a single signed difference would
    // print "-1 unread bytes" for an over-read, which reads as a driver bug rather than as
    // the desync it is.
    if (actual < expected) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has {1} unread bytes.",
          name, String.valueOf(expected - actual))));
    }
    if (actual > expected) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message was read {1} bytes past its declared envelope.",
          name, String.valueOf(actual - expected))));
    }
    messageBoundaryPosition = actual;
    lastMessageName = name;
  }

  /**
   * Declares that the stream sits on a backend message boundary, so the next
   * {@link #receiveMessageType()} accepts the current position.
   *
   * <p>Reserved for the parts of the dialogue that are not message-framed: the SSL and GSS
   * encryption negotiations answer a request packet with a bare byte or with a length
   * prefix that counts only the payload, so neither goes through
   * {@link #readMessageLength(String, int)} and neither leaves an envelope for
   * {@link #endMessage()} to close. Call it where the framed dialogue resumes. Regular
   * message readers must not: for them the boundary is what the check exists to prove.</p>
   */
  public void markMessageBoundary() {
    messageBoundaryPosition = pgInput.getPosition();
  }

  /**
   * Rejects a stream that does not sit where the preceding message ended, since the byte
   * about to be read as a message type would come from the middle of the wire.
   *
   * <p>Three situations reach this point, and each names a different culprit. A body left
   * partly unread means the read was interrupted, and the usual cause is a connection that
   * failed mid-message: the driver must not run another query over it, which is what it
   * used to do. A body read in full with the envelope still open means the reader skipped
   * {@link #endMessage()}. No envelope at all means the reader consumed its length or its
   * body outside the bounded API. The last two are driver bugs, not wire corruption: a
   * reader that uses the API lands on the boundary whatever the server sent, since a
   * server-supplied length that disagrees with the bytes on the wire is rejected by
   * {@code endMessage()} first.</p>
   */
  private void checkMessageBoundary() throws IOException {
    long position = pgInput.getPosition();
    if (position == messageBoundaryPosition) {
      return;
    }
    if (messageEndPosition >= 0) {
      if (position < messageEndPosition) {
        throw markBroken(new IOException(GT.tr(
            "Protocol error. Reading the {0} message stopped with {1} bytes of its body unread, so the connection is no longer positioned on a message boundary.",
            currentMessageNameForError(), String.valueOf(messageEndPosition - position))));
      }
      throw markBroken(new IOException(GT.tr(
          "Protocol error. The {0} message was read without a closing endMessage call, which is a pgjdbc defect.",
          currentMessageNameForError())));
    }
    throw markBroken(new IOException(GT.tr(
        "Protocol error. The stream is {0} bytes away from the end of the {1} message, so the next byte is not a message type. Read every backend message through readMessageLength or readFixedMessageLength, and close it with endMessage.",
        String.valueOf(Math.abs(position - messageBoundaryPosition)),
        lastMessageName == null ? "preceding" : lastMessageName)));
  }

  /**
   * Discards the current message envelope without verifying it was consumed. Used where the
   * length prefix does not describe a self-inclusive envelope the driver can track, and by
   * the paths that replace {@link #pgInput} underneath the tracker (which resets
   * {@link VisibleBufferedInputStream#getPosition()} to zero and would leave
   * {@code messageEndPosition} pointing into the previous stream).
   */
  private void resetMessageTracker() {
    messageEndPosition = -1;
    currentMessageName = null;
    currentMessageLength = 0;
  }

  /**
   * Marks the stream broken and closes the underlying socket on a best-effort basis.
   * Returns the supplied exception so call sites can write
   * {@code throw pgStream.markBroken(new ...(...))} fluently. The generic signature
   * supports both {@link IOException} thrown by
   * PGStream's internal hardening checks and {@link org.postgresql.util.PSQLException}
   * (e.g. {@link org.postgresql.util.PSQLState#PROTOCOL_VIOLATION}) thrown by the
   * higher layers (auth, cancel-key, startup negotiation, ...). After this call
   * {@link #isClosed()} reports {@code true}, so even if the regular abort path is
   * somehow skipped the connection cannot be reused.
   *
   * <p>Closing the socket is best-effort and may fail, which is why
   * {@link #isSocketClosed()} exists: the regular close path checks it and releases the
   * descriptor that this method could not.</p>
   */
  public <T extends Throwable> T markBroken(T reason) {
    broken = true;
    try {
      // Force an immediate TCP RST rather than a graceful FIN/ACK exchange. close() on
      // a graceful path can block waiting for the OS to flush queued bytes when
      // SO_LINGER > 0; on a broken connection we have no reason to wait, and any
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
      // Best-effort: the socket may already be closed, or the close itself may fail.
      // Either way the stream is already marked broken.
    }
    return reason;
  }

  /**
   * Returns what this stream does when a backend message exceeds one of the driver's ceilings.
   */
  ProtocolHardeningMode getProtocolHardeningMode() {
    return protocolHardeningMode;
  }

  /**
   * Overrides the {@link ProtocolHardeningMode} for this stream. Intended for
   * tests that need to exercise non-default behaviours without altering the
   * JVM-wide setting. Production code should rely on the system property
   * ({@value ProtocolHardeningMode#SYSTEM_PROPERTY}) so that every connection
   * the JVM opens picks up the same policy.
   */
  void setProtocolHardeningMode(ProtocolHardeningMode behaviour) {
    this.protocolHardeningMode = behaviour;
  }

  /**
   * Builds the failure message for a ceiling violation, or returns {@code null} when
   * {@link ProtocolHardeningMode#DISABLE} is in force and the caller should carry on.
   *
   * <p>A caller that fails with a {@link PSQLException} rather than an {@link IOException} must
   * pass the returned message on unchanged. CopyData needs that: {@code readFromCopy} rewrites an
   * {@code IOException} into a generic connection-failure message that hides which ceiling was
   * hit.</p>
   *
   * @param message localised error message (already passed through {@code GT.tr})
   * @param propertyName connection property that raises this ceiling
   * @return the message to fail with, or {@code null} if the caller should continue
   */
  private @Nullable String ceilingFailureMessage(String message, String propertyName) {
    if (protocolHardeningMode == ProtocolHardeningMode.DISABLE) {
      return null;
    }
    return ProtocolHardeningMode.appendSilenceHint(message, propertyName);
  }

  /**
   * Rejects a message longer than one of the ceilings pgjdbc applies where the protocol
   * fixes no maximum. The exception is an {@link IOException}, so the upstream
   * {@code processResults} loop treats it as fatal rather than as a per-query error.
   *
   * @param propertyName connection property that raises this ceiling
   * @throws IOException unless the length is within the ceiling or the ceilings are disabled
   */
  private void checkCeiling(String packetName, int msgLen, long cap, String propertyName)
      throws IOException {
    if (msgLen <= cap) {
      return;
    }
    String failure = ceilingFailureMessage(GT.tr(
        "Protocol error. {0} message has length {1} which exceeds the pgjdbc ceiling of {2} bytes.",
        packetName, String.valueOf(msgLen), String.valueOf(cap)), propertyName);
    if (failure != null) {
      throw markBroken(new IOException(failure));
    }
  }

  /**
   * Applies the {@code maxServerTextMessageSize} ceiling to a message read after
   * authentication.
   *
   * @throws IOException if the message exceeds the ceiling
   */
  public void checkServerTextMessageSize(String packetName, int msgLen) throws IOException {
    checkCeiling(packetName, msgLen, maxServerTextMessageSize, "maxServerTextMessageSize");
  }

  /**
   * Applies the RowDescription ceiling. The field count is an unsigned int16, so a
   * RowDescription cannot exceed {@link #MAX_ROW_DESCRIPTION_SIZE} unless the backend uses
   * identifiers several times longer than PostgreSQL's NAMEDATALEN. The ceiling is therefore
   * fixed rather than configurable, and no mode relaxes it.
   *
   * @throws IOException if the message exceeds the ceiling
   */
  public void checkRowDescriptionSize(int msgLen) throws IOException {
    if (msgLen > MAX_ROW_DESCRIPTION_SIZE) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. RowDescription message has length {0} which exceeds the pgjdbc ceiling of {1} bytes.",
          String.valueOf(msgLen), String.valueOf(MAX_ROW_DESCRIPTION_SIZE))));
    }
  }

  /**
   * The server-text ceiling, clamped to {@link #MAX_MESSAGE_SIZE} so it can be passed as a
   * hard maximum to {@link #readPreAuthMessageLength(String, int, int)}.
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
    // Same reasoning as in setSecContext: the replacement stream restarts its byte counter,
    // so any envelope endpoint captured against the old one is meaningless now.
    resetMessageTracker();
    markMessageBoundary();
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
   * Receives the one-byte type tag that opens a backend message.
   *
   * <p>A reader that dispatches on a backend message type must take the tag from here, not from
   * {@link #receiveChar()}. That is what makes the envelope rule self-enforcing at run time: the
   * tag is only in the right place if the preceding message closed its envelope.</p>
   *
   * @return the message type tag
   * @throws IOException if an I/O error occurs, or if the stream is not positioned on a
   *         message boundary
   */
  public int receiveMessageType() throws IOException {
    checkMessageBoundary();
    return receiveChar();
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
   * readMessageLength(packetName, minLength, MAX_MESSAGE_SIZE)}.
   *
   * <p>The length field must be self-inclusive (it counts the 4 length bytes themselves),
   * so {@code minLength} is ≥ 4. A prefix that counts only the payload does not describe
   * an envelope this method can track; read it with
   * {@link #readUntrackedLength(String, int, int)} instead.</p>
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
   * {@code [minLength, maxLength]}. Both bounds are unconditional; {@code maxLength} is for a
   * ceiling the protocol itself fixes, such as the backend's
   * {@code PQ_GSS_AUTH_BUFFER_SIZE - sizeof(uint32)} for the GSS encryption handshake token.
   *
   * <p>A ceiling pgjdbc invents goes through {@link #checkServerTextMessageSize(String, int)}
   * or {@link #checkRowDescriptionSize(int)} after this call instead. Which messages get one
   * follows a single rule: a message whose content the protocol itself bounds gets a ceiling,
   * because a length far above that bound is evidence of a desync rather than of a large
   * result. A message that carries user data of unbounded size (DataRow, CopyData,
   * FunctionCallResponse) does not, since any ceiling pgjdbc invented would reject legitimate
   * traffic. Those are bounded by a limit the user owns instead: DataRow by
   * {@code maxResultBuffer}, checked in {@link #receiveTupleV3()}, and CopyData by
   * {@code maxCopyDataSize}, checked in {@link #checkCopyDataSize(int)}.</p>
   *
   * <p>The length field must be self-inclusive; see
   * {@link #readMessageLength(String, int)}.</p>
   *
   * @param packetName protocol message name used in the error message
   * @param minLength inclusive minimum legal value of the length field
   * @param maxLength inclusive maximum legal value of the length field;
   *                  must be ≤ {@link #MAX_MESSAGE_SIZE}; encode an unconditional
   *                  ceiling here, not one pgjdbc invented
   * @return the validated length
   * @throws IOException if the length is out of range
   */
  public int readMessageLength(String packetName, int minLength, int maxLength) throws IOException {
    int len = validateMessageLength(packetName, minLength, maxLength);
    // Capture name + declared length + envelope endpoint so subsequent bounded-string
    // reads and endMessage() do not need them threaded through as parameters.
    beginMessage(packetName, len);
    return len;
  }

  /**
   * Reads and validates a 4-byte length prefix that counts only the payload that follows,
   * without starting an envelope. The bounds are enforced exactly as in
   * {@link #readMessageLength(String, int, int)}, but no envelope is tracked, and the caller must
   * not close one with {@link #endMessage()}: {@code length - 4} is not the body size here, so
   * envelope arithmetic would be off by 4 bytes and every bounded C-string read inside the message
   * would inherit the error.
   *
   * <p>The GSS encryption handshake is the only such prefix in the v3 dialogue. Any
   * envelope left over from an earlier message is discarded, so a later bounded read cannot
   * inherit a stale budget.</p>
   *
   * @param packetName protocol message name used in the error message
   * @param minLength inclusive minimum legal value of the length field
   * @param maxLength inclusive maximum legal value of the length field
   * @return the validated length
   * @throws IOException if the length is out of range
   */
  public int readUntrackedLength(String packetName, int minLength, int maxLength)
      throws IOException {
    int len = validateMessageLength(packetName, minLength, maxLength);
    resetMessageTracker();
    return len;
  }

  private int validateMessageLength(String packetName, int minLength, int maxLength)
      throws IOException {
    int len = receiveInteger4();
    if (len < minLength || len > maxLength) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
          packetName, String.valueOf(len), String.valueOf(minLength),
          String.valueOf(maxLength))));
    }
    return len;
  }

  /**
   * Reads a message length and rejects a value above a ceiling pgjdbc applies before the peer
   * has authenticated. Same validation as {@link #readMessageLength(String, int, int)}, but
   * the error names the ceiling as pgjdbc's own and points at the property that raises it, so
   * an operator is not left reading "expected between 5 and 1048576" and guessing whether
   * 1048576 comes from the protocol.
   *
   * <p>{@link ProtocolHardeningMode#DISABLE} cannot switch these ceilings off: the peer has
   * proved nothing yet, so the remedy is to raise the property for the connection that needs
   * it rather than to switch the ceilings off for the JVM.</p>
   *
   * @param packetName protocol message name used in the error message
   * @param minLength inclusive minimum legal value of the length field
   * @param cap the ceiling pgjdbc applies to this message before authentication
   * @return the validated length
   * @throws IOException if the length is out of range
   */
  public int readPreAuthMessageLength(String packetName, int minLength, int cap)
      throws IOException {
    int len = validateMessageLength(packetName, minLength, MAX_MESSAGE_SIZE);
    if (len > cap) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has length {1} which exceeds the pgjdbc ceiling of {2} bytes applied before authentication. This ceiling cannot be relaxed.",
          packetName, String.valueOf(len), String.valueOf(cap))));
    }
    beginMessage(packetName, len);
    return len;
  }

  /**
   * Same as {@link #readPreAuthMessageLength(String, int, int)}, for a ceiling the user can
   * raise: the error names the connection property that does so.
   *
   * @param packetName protocol message name used in the error message
   * @param minLength inclusive minimum legal value of the length field
   * @param cap the ceiling pgjdbc applies to this message before authentication
   * @param propertyName connection property that raises this ceiling
   * @return the validated length
   * @throws IOException if the length is out of range
   */
  public int readPreAuthMessageLength(String packetName, int minLength, int cap,
      String propertyName) throws IOException {
    int len = validateMessageLength(packetName, minLength, MAX_MESSAGE_SIZE);
    if (len > cap) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has length {1} which exceeds the pgjdbc ceiling of {2} bytes applied before authentication. Raise the {3} connection property if the backend legitimately sends more.",
          packetName, String.valueOf(len), String.valueOf(cap), propertyName)));
    }
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
      throw markBroken(new IOException(GT.tr(
          "Protocol error. {0} message has length {1}, expected {2}.",
          packetName, String.valueOf(len), String.valueOf(expectedLength))));
    }
    beginMessage(packetName, expectedLength);
  }

  /**
   * Receives a two byte integer from the backend as an unsigned integer (0..65535).
   * Most int2 fields in the v3 protocol are signed, so a caller reading one must cast the
   * result to {@code short} to recover the value the backend actually sent.
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
   * buffer-grow-and-read loop. Two bounds apply and the smaller one wins. The first is the
   * remaining envelope of the message being parsed
   * ({@link #readMessageLength(String, int) readMessageLength}'s declared length minus
   * everything already consumed), or {@link #MAX_MESSAGE_SIZE} when no envelope is tracked.
   * The second is {@link #MAX_CSTRING_LENGTH} on the string itself, which
   * {@link ProtocolHardeningMode#DISABLE} raises to {@link #MAX_MESSAGE_SIZE} where an
   * envelope is tracked.
   */
  private int scanBoundedCStringLength() throws IOException {
    // VisibleBufferedInputStream.scanCStringLength throws plain IOException on two
    // failure modes that also signal a desynced stream: the C-string overruns its
    // declared budget without a NUL, or the underlying socket EOFs mid-scan. Both
    // mean the next read would land inside a message we cannot locate the boundary
    // of, so route any IOException through markBroken to set the broken flag at the
    // throw point. Without the wrap, isClosed() would still return false until the
    // upstream caller eventually invoked abort().

    // DISABLE lifts this ceiling like any other pgjdbc ceiling, but only where an envelope
    // bounds the scan in its place.
    boolean modeReaches = messageEndPosition >= 0;
    int fieldCap = modeReaches && protocolHardeningMode == ProtocolHardeningMode.DISABLE
        ? MAX_MESSAGE_SIZE : MAX_CSTRING_LENGTH;
    try {
      if (messageEndPosition < 0) {
        return pgInput.scanCStringLength(
            MAX_MESSAGE_SIZE, fieldCap, "<no envelope>", MAX_MESSAGE_SIZE);
      }
      long remaining = messageEndPosition - pgInput.getPosition();
      if (remaining <= 0) {
        throw new IOException(GT.tr(
            "Protocol error. {0} message of {1} bytes has no remaining envelope budget.",
            currentMessageNameForError(), String.valueOf(currentMessageLength)));
      }
      int budget = (int) Math.min(remaining, MAX_MESSAGE_SIZE);
      return pgInput.scanCStringLength(
          budget, fieldCap, currentMessageNameForError(), currentMessageLength);
    } catch (VisibleBufferedInputStream.CStringCeilingException e) {
      // Name the escape hatch only where it would have helped. Where the mode does not reach
      // this ceiling, pointing at it misdirects whoever triages the failure, and under DISABLE
      // it would tell them to set the value they already set.
      throw markBroken(modeReaches
          ? new IOException(ProtocolHardeningMode.appendSilenceHint(e.getMessage()))
          : e);
    } catch (IOException e) {
      throw markBroken(e);
    }
  }

  /**
   * Reads a NUL-terminated C-string from the backend. The scan is always bounded; see
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
   * The scan is always bounded; see {@link #scanBoundedCStringLength()} for the budget
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
   * The scan is always bounded; see {@link #scanBoundedCStringLength()} for the budget
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
   * <p>The caller must surface the exception raised for the first over-sized row: a caller that
   * swallows it and keeps the rows it did get hands the application a silently truncated result
   * set.</p>
   *
   * @return tuple from the back end, or {@code null} when the row was skipped for exceeding
   *         {@code maxResultBuffer} and the failure has already been reported
   * @throws IOException if a data I/O error occurs
   * @throws SQLException if read more bytes than set maxResultBuffer
   */
  public @Nullable Tuple receiveTupleV3() throws IOException, OutOfMemoryError, SQLException {
    // DataRow envelope: 4 (self) + 2 (nf) + nf * 4 (per-field lengths), minimum 6.
    int messageSize = readMessageLength("DataRow", 6);
    // The backend sends nf as a signed int16, so cast receiveInteger2()'s unsigned result
    // back to short to read it. The protocol does not pin a specific maximum column count
    // (forks such as CockroachDB/YugabyteDB/Redshift may differ from PostgreSQL's own
    // limit), so bound nf only via the message envelope below.
    int nf = (short) receiveInteger2();
    if (nf < 0) {
      // nf is a signed int16, and is used below as an array size and as envelope
      // arithmetic input. Reading it as unsigned would be a deliberate compatibility change.
      throw markBroken(new IOException(GT.tr(
          "Protocol error. DataRow has negative field count {0} (message size {1}).",
          String.valueOf(nf), String.valueOf(messageSize))));
    }
    //size = messageSize - 4 bytes of message size - 2 bytes of field count - 4 bytes for each column length
    int dataToReadSize = messageSize - 4 - 2 - 4 * nf;
    if (dataToReadSize < 0) {
      throw markBroken(new IOException(GT.tr(
          "Protocol error. DataRow field count {0} requires at least {1} bytes for per-field length prefixes, but message size is only {2}.",
          String.valueOf(nf), String.valueOf(4 * nf), String.valueOf(messageSize))));
    }
    if (maxResultBuffer > 0 && dataToReadSize > maxResultBuffer) {
      // A user-configured resource limit, not a protocol violation, so this does not go
      // through markBroken: none of the row body has been read, the envelope says exactly
      // how much of it there is, and skipping that much puts the reader back on a message
      // boundary. The query still fails -- processResults hands the exception to the
      // handler and handleCompletion rethrows it -- but the connection survives, so an
      // application that retries with a smaller fetchSize does not need a new one.
      //
      // Recovery is only worth it while the amount to discard stays small; see
      // MAX_RECOVERABLE_SKIP. Every over-sized row in a result set gets skipped, not just
      // the first: a rule that closed the connection on the second one would fire on an
      // ordinary query over a table with two wide rows, and the caller would see the
      // recoverable message from the first row while holding a dead connection -- the
      // handler keeps the first exception and rethrows that one from handleCompletion.
      long unreadBody = messageSize - 6L;
      if (unreadBody > MAX_RECOVERABLE_SKIP) {
        throw markBroken(new PSQLException(GT.tr(
            "Result set exceeded maxResultBuffer limit. A row of {0} bytes against a limit of {1} cannot be skipped, so the connection is closed.",
            String.valueOf(unreadBody), String.valueOf(maxResultBuffer)),
            PSQLState.COMMUNICATION_ERROR));
      }
      skip((int) unreadBody);
      endMessage();
      if (reportedOversizedRow) {
        // Already reported. Raising one exception per skipped row would
        // grow ResultHandlerBase's chain without bound -- each link carries a stack trace,
        // roughly a kilobyte held until the query ends -- and a wide enough result set would
        // exhaust the heap while reporting that the driver refused to spend the heap. The
        // query still fails: handleCompletion rethrows the first exception, which is the one
        // the caller wants anyway.
        return null;
      }
      reportedOversizedRow = true;
      throw new PSQLException(GT.tr(
          "Result set exceeded maxResultBuffer limit. A single row of {0} bytes exceeds the limit of {1}.",
          String.valueOf(dataToReadSize), String.valueOf(maxResultBuffer)),
          PSQLState.COMMUNICATION_ERROR);
    }
    // Deliberately after the maxResultBuffer check, so a rejected row feeds neither the
    // adaptive-fetch row-size estimate (no fetchSize makes a single over-sized row fit) nor
    // the cumulative counter (its bytes were skipped, not buffered).
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
          // envelope still holds drove a ~1.7 GB allocation and an indefinite socket read.
          throw markBroken(new IOException(GT.tr(
              "Protocol error. DataRow field {0} length {1} exceeds remaining row bytes {2}.",
              String.valueOf(i), String.valueOf(size), String.valueOf(remaining))));
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
   * Sets the ceiling on a single CopyData message, parsed the same way as
   * {@code maxResultBuffer} so that {@code 64M} and {@code 5p} mean the same thing in both.
   *
   * @param value size expressed in bytes, with an optional unit or heap-percent suffix;
   *              {@code null} leaves the built-in {@link #DEFAULT_MAX_COPY_DATA_SIZE}
   *              in effect
   * @throws PSQLException if the value cannot be parsed
   */
  public void setMaxCopyDataSize(@Nullable String value) throws PSQLException {
    maxCopyDataSize = PGPropertyMaxResultBufferParser.parseProperty(value);
  }

  /**
   * Sets the ceiling on ErrorResponse, NoticeResponse, CommandComplete, ParameterStatus and
   * NotificationResponse, parsed the same way as {@code maxResultBuffer}.
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
   * Applies the CopyData ceiling to a message length already validated by
   * {@link #readMessageLength(String, int)}. A configured {@code maxCopyDataSize} is the
   * user's own number, so {@link ProtocolHardeningMode#DISABLE} does not override it. With
   * the property unset, {@link #DEFAULT_MAX_COPY_DATA_SIZE} applies instead.
   *
   * <p>This also bounds the logical and physical replication streams, which the backend
   * delivers as CopyData.</p>
   *
   * @param msgLen the declared message length
   * @throws PSQLException if the message exceeds the applicable ceiling; an {@link IOException}
   *                       would reach the caller as "Database connection failed when reading from
   *                       copy", which buries the limit in the cause
   */
  public void checkCopyDataSize(int msgLen) throws IOException, SQLException {
    if (maxCopyDataSize > 0) {
      if (msgLen > maxCopyDataSize) {
        // Unlike the maxResultBuffer check on DataRow, this does not skip the message and
        // carry on, even though the stream is equally recoverable here. Silently dropping a
        // CopyData means losing a COPY row, and unlike a result set there is no
        // handleCompletion to fail the operation afterwards -- so the COPY has to fail, and
        // once it does there is nothing left to keep the connection for.
        throw markBroken(new PSQLException(GT.tr(
            "CopyData message has length {0} which exceeds the maxCopyDataSize limit of {1} bytes.",
            String.valueOf(msgLen), String.valueOf(maxCopyDataSize)),
            PSQLState.COMMUNICATION_ERROR));
      }
      return;
    }
    if (msgLen <= DEFAULT_MAX_COPY_DATA_SIZE) {
      return;
    }
    // A PSQLException rather than an IOException for the same reason as the hard path above:
    // readFromCopy rewrites an IOException into "Database connection failed when reading
    // from copy", which buries the limit in the cause.
    String failure = ceilingFailureMessage(GT.tr(
        "Protocol error. CopyData message has length {0} which exceeds the built-in ceiling of {1} bytes.",
        String.valueOf(msgLen), String.valueOf(DEFAULT_MAX_COPY_DATA_SIZE)), "maxCopyDataSize");
    if (failure != null) {
      throw markBroken(new PSQLException(failure, PSQLState.COMMUNICATION_ERROR));
    }
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

  /**
   * Re-arms the report of a row over {@code maxResultBuffer}, so the next Sync raises its own
   * exception rather than skipping in silence. A reader that consumes a ReadyForQuery must call
   * this, since ReadyForQuery is the boundary the flag is scoped to; it cannot ride along in
   * {@link #clearResultBufferCount()}, which runs only for simple-query executes.
   */
  public void clearOversizedRowReport() {
    reportedOversizedRow = false;
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
   * also once {@link #markBroken(Throwable)} has flagged the stream as desynced, even where the
   * socket itself is still open.
   */
  public boolean isClosed() {
    return broken || connection.isClosed();
  }

  /**
   * Reports whether the underlying socket is closed, ignoring the broken flag. The regular close
   * path uses this to decide whether the descriptor still needs releasing, since
   * {@link #markBroken(Throwable)} closes the socket only on a best-effort basis.
   */
  public boolean isSocketClosed() {
    return connection.isClosed();
  }
}
