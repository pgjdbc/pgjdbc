/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3.replication;

import static org.postgresql.core.PgMessageType.REPLICATION_KEEP_ALIVE;
import static org.postgresql.core.PgMessageType.REPLICATION_STATUS_REQUEST;
import static org.postgresql.core.PgMessageType.REPLICATION_XLOG_DATA;

import org.postgresql.copy.CopyDual;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.PGReplicationStream;
import org.postgresql.replication.ReplicationType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class V3PGReplicationStream implements PGReplicationStream {

  private static final Logger LOGGER = Logger.getLogger(V3PGReplicationStream.class.getName());
  public static final Instant POSTGRES_EPOCH_2000_01_01 = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
      .toInstant(ZoneOffset.UTC);

  private final CopyDual copyDual;
  private final long updateInterval;
  private final ReplicationType replicationType;
  private final boolean automaticFlush;
  private Instant lastStatusUpdate;
  private boolean closeFlag;

  private LogSequenceNumber lastServerLSN = LogSequenceNumber.INVALID_LSN;
  /**
   * Last receive LSN + payload size.
   */
  private volatile LogSequenceNumber lastReceiveLSN;
  private volatile LogSequenceNumber lastAppliedLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber lastFlushedLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber startOfLastMessageLSN = LogSequenceNumber.INVALID_LSN;
  private volatile LogSequenceNumber explicitlyFlushedLSN = LogSequenceNumber.INVALID_LSN;

  /**
   * @param copyDual         bidirectional copy protocol
   * @param startLSN         the position in the WAL that we want to initiate replication from
   *                         usually the currentLSN returned by calling pg_current_wal_lsn()for v10
   *                         above or pg_current_xlog_location() depending on the version of the
   *                         server
   * @param updateIntervalMs the number of millisecond between status packets sent back to the
   *                         server.  A value of zero disables the periodic status updates
   *                         completely, although an update will still be sent when requested by the
   *                         server, to avoid timeout disconnect.
   * @param replicationType  LOGICAL or PHYSICAL
   */
  public V3PGReplicationStream(CopyDual copyDual, LogSequenceNumber startLSN, long updateIntervalMs,
      boolean automaticFlush, ReplicationType replicationType
  ) {
    this.copyDual = copyDual;
    this.updateInterval = updateIntervalMs;
    this.lastStatusUpdate = Instant.now().minusMillis(updateIntervalMs);
    this.lastReceiveLSN = startLSN;
    this.automaticFlush = automaticFlush;
    this.replicationType = replicationType;
  }

  @Override
  public @Nullable ByteBuffer read() throws SQLException {
    checkClose();

    ByteBuffer payload = null;
    while (payload == null && copyDual.isActive()) {
      payload = readInternal(true);
    }

    return payload;
  }

  @Override
  public @Nullable ByteBuffer readPending() throws SQLException {
    checkClose();
    return readInternal(false);
  }

  @Override
  public LogSequenceNumber getLastReceiveLSN() {
    return lastReceiveLSN;
  }

  @Override
  public LogSequenceNumber getLastFlushedLSN() {
    return lastFlushedLSN;
  }

  @Override
  public LogSequenceNumber getLastAppliedLSN() {
    return lastAppliedLSN;
  }

  @Override
  public void setFlushedLSN(LogSequenceNumber flushed) {
    this.lastFlushedLSN = flushed;
  }

  @Override
  public void setAppliedLSN(LogSequenceNumber applied) {
    this.lastAppliedLSN = applied;
  }

  @Override
  public void forceUpdateStatus() throws SQLException {
    checkClose();
    updateStatusInternal(lastReceiveLSN, lastFlushedLSN, lastAppliedLSN, true);
  }

  @Override
  public boolean isClosed() {
    return closeFlag || !copyDual.isActive();
  }

  private @Nullable ByteBuffer readInternal(boolean block) throws SQLException {
    boolean updateStatusRequired = false;
    while (copyDual.isActive()) {

      ByteBuffer buffer = receiveNextData(block);

      if (updateStatusRequired || isTimeUpdate()) {
        timeUpdateStatus();
      }

      if (buffer == null) {
        return null;
      }

      int code = buffer.get();

      switch (code) {

        case REPLICATION_KEEP_ALIVE:
          updateStatusRequired = processKeepAliveMessage(buffer);
          updateStatusRequired |= updateInterval == 0;
          break;

        case REPLICATION_XLOG_DATA:
          return processXLogData(buffer);

        default:
          throw new PSQLException(
              GT.tr("Unexpected packet type during replication: {0}", Integer.toString(code)),
              PSQLState.PROTOCOL_VIOLATION
          );
      }
    }

    return null;
  }

  private @Nullable ByteBuffer receiveNextData(boolean block) throws SQLException {
    try {
      byte[] message = copyDual.readFromCopy(block);
      if (message != null) {
        return ByteBuffer.wrap(message);
      } else {
        return null;
      }
    } catch (PSQLException e) { //todo maybe replace on thread sleep?
      if (e.getCause() instanceof SocketTimeoutException) {
        //signal for keep alive
        return null;
      }

      throw e;
    }
  }

  private boolean isTimeUpdate() {
    /* a value of 0 disables automatic updates */
    if ( updateInterval == 0 ) {
      return false;
    }
    long diff = Instant.now().toEpochMilli() - lastStatusUpdate.toEpochMilli();
    return diff >= updateInterval;
  }

  private void timeUpdateStatus() throws SQLException {
    updateStatusInternal(lastReceiveLSN, lastFlushedLSN, lastAppliedLSN, false);
  }

  private void updateStatusInternal(
      LogSequenceNumber received, LogSequenceNumber flushed, LogSequenceNumber applied,
      boolean replyRequired)
      throws SQLException {
    byte[] reply = prepareUpdateStatus(received, flushed, applied, replyRequired);
    copyDual.writeToCopy(reply, 0, reply.length);
    copyDual.flushCopy();

    explicitlyFlushedLSN = flushed;
    lastStatusUpdate = Instant.now();
  }

  private byte[] prepareUpdateStatus(LogSequenceNumber received, LogSequenceNumber flushed,
      LogSequenceNumber applied, boolean replyRequired) {
    ByteBuffer byteBuffer = ByteBuffer.allocate(1 + 8 + 8 + 8 + 8 + 1);

    // update status is microseconds since 01/01/2000 as per https://www.postgresql.org/docs/devel/protocol-replication.html
    Instant now = Instant.now();

    // Calculate duration
    Duration duration = Duration.between(POSTGRES_EPOCH_2000_01_01, now);

    // Convert to microseconds
    long systemClock = duration.toNanos() / 1000;

    if (LOGGER.isLoggable(Level.FINEST)) {
      @SuppressWarnings("JavaUtilDate")
      Date clock = new Date(now.toEpochMilli());
      LOGGER.log(Level.FINEST, " FE=> StandbyStatusUpdate(received: {0}, flushed: {1}, applied: {2}, clock: {3})",
          new Object[]{received.asString(), flushed.asString(), applied.asString(), clock});
    }

    byteBuffer.put(REPLICATION_STATUS_REQUEST);
    byteBuffer.putLong(received.asLong());
    byteBuffer.putLong(flushed.asLong());
    byteBuffer.putLong(applied.asLong());
    byteBuffer.putLong(systemClock);
    if (replyRequired) {
      byteBuffer.put((byte) 1);
    } else {
      byteBuffer.put(received.equals(LogSequenceNumber.INVALID_LSN) ? (byte) 1 : (byte) 0);
    }

    lastStatusUpdate = now;
    return byteBuffer.array();
  }

  private boolean processKeepAliveMessage(ByteBuffer buffer) {
    lastServerLSN = LogSequenceNumber.valueOf(buffer.getLong());
    if (lastServerLSN.asLong() > lastReceiveLSN.asLong()) {
      lastReceiveLSN = lastServerLSN;
    }
    // if the client has confirmed flush of last XLogData msg and KeepAlive shows ServerLSN is still
    // advancing, we can safely advance FlushLSN to ServerLSN
    if (automaticFlush && explicitlyFlushedLSN.asLong() >= startOfLastMessageLSN.asLong()
        && lastServerLSN.asLong() > explicitlyFlushedLSN.asLong()
        && lastServerLSN.asLong() > lastFlushedLSN.asLong()) {
      lastFlushedLSN = lastServerLSN;
    }

    long lastServerClock = buffer.getLong();

    boolean replyRequired = buffer.get() != 0;

    if (LOGGER.isLoggable(Level.FINEST)) {
      @SuppressWarnings("JavaUtilDate")
      Date clockTime = new Date(
          TimeUnit.MILLISECONDS.convert(lastServerClock, TimeUnit.MICROSECONDS)
          + POSTGRES_EPOCH_2000_01_01.toEpochMilli());
      LOGGER.log(Level.FINEST, "  <=BE Keepalive(lastServerWal: {0}, clock: {1} needReply: {2})",
          new Object[]{lastServerLSN.asString(), clockTime, replyRequired});
    }

    return replyRequired;
  }

  private ByteBuffer processXLogData(ByteBuffer buffer) {
    long startLsn = buffer.getLong();
    startOfLastMessageLSN = LogSequenceNumber.valueOf(startLsn);
    lastServerLSN = LogSequenceNumber.valueOf(buffer.getLong());
    long systemClock = buffer.getLong();

    if (replicationType == ReplicationType.LOGICAL) {
      lastReceiveLSN = LogSequenceNumber.valueOf(startLsn);
    } else if (replicationType == ReplicationType.PHYSICAL) {
      int payloadSize = buffer.limit() - buffer.position();
      lastReceiveLSN = LogSequenceNumber.valueOf(startLsn + payloadSize);
    }

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, "  <=BE XLogData(currWal: {0}, lastServerWal: {1}, clock: {2})",
          new Object[]{lastReceiveLSN.asString(), lastServerLSN.asString(), systemClock});
    }

    return buffer.slice();
  }

  private void checkClose() throws PSQLException {
    if (isClosed()) {
      throw new PSQLException(GT.tr("This replication stream has been closed."),
          PSQLState.CONNECTION_DOES_NOT_EXIST);
    }
  }

  @Override
  public void close() throws SQLException {
    if (isClosed()) {
      return;
    }

    LOGGER.log(Level.FINEST, " FE=> StopReplication");

    copyDual.endCopy();

    closeFlag = true;
  }
}
