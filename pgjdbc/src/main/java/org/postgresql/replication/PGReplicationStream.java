/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import org.postgresql.replication.fluent.CommonOptions;
import org.postgresql.replication.fluent.logical.LogicalReplicationOptions;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.ByteBuffer;
import java.sql.SQLException;

/**
 * Not tread safe replication stream (though certain methods can be safely called by different
 * threads). After complete streaming should be close, for free resource on backend. Periodical
 * status update work only when use {@link PGReplicationStream#read()} method. It means that
 * process wal record should be fast as possible, because during process wal record lead to
 * disconnect by timeout from server.
 */
public interface PGReplicationStream
    extends AutoCloseable
    /* hi, checkstyle */ {

  /**
   * <p>Read next wal record from backend. It method can be block until new message will not get
   * from server.</p>
   *
   * <p>A single WAL record is never split across two XLogData messages. When a WAL record crosses a
   * WAL page boundary, and is therefore already split using continuation records, it can be split
   * at the page boundary. In other words, the first main WAL record and its continuation records
   * can be sent in different XLogData messages.</p>
   *
   * @return not null byte array received by replication protocol, return ByteBuffer wrap around
   *     received byte array with use offset, so, use {@link ByteBuffer#array()} carefully
   * @throws SQLException when some internal exception occurs during read from stream
   */
  @Nullable ByteBuffer read() throws SQLException;

  /**
   * <p>Read next WAL record from backend. This method does not block and in contrast to {@link
   * PGReplicationStream#read()}. If message from backend absent return null. It allow periodically
   * check message in stream and if they absent sleep some time, but it time should be less than
   * {@link CommonOptions#getStatusInterval()} to avoid disconnect from the server.</p>
   *
   * <p>A single WAL record is never split across two XLogData messages. When a WAL record crosses a
   * WAL page boundary, and is therefore already split using continuation records, it can be split
   * at the page boundary. In other words, the first main WAL record and its continuation records
   * can be sent in different XLogData messages.</p>
   *
   * @return byte array received by replication protocol or NULL if pending message from server
   *     absent. Returns ByteBuffer wrap around received byte array with use offset, so, use {@link
   *     ByteBuffer#array()} carefully.
   * @throws SQLException when some internal exception occurs during read from stream
   */
  @Nullable ByteBuffer readPending() throws SQLException;

  /**
   * <p>Parameter updates by execute {@link PGReplicationStream#read()} method.</p>
   *
   * <p>It is safe to call this method in a thread different than the main thread. However, usually this
   * method is called in the main thread after a successful {@link PGReplicationStream#read()} or
   * {@link PGReplicationStream#readPending()}, to get the LSN corresponding to the received record.</p>
   *
   * @return NOT NULL LSN position that was receive last time via {@link PGReplicationStream#read()}
   *     method
   */
  LogSequenceNumber getLastReceiveLSN();

  /**
   * <p>Last flushed LSN sent in update message to backend. Parameter updates only via {@link
   * PGReplicationStream#setFlushedLSN(LogSequenceNumber)}</p>
   *
   * <p>It is safe to call this method in a thread different than the main thread.</p>
   *
   * @return NOT NULL location of the last WAL flushed to disk in the standby.
   */
  LogSequenceNumber getLastFlushedLSN();

  /**
   * <p>Last applied lsn sent in update message to backed. Parameter updates only via {@link
   * PGReplicationStream#setAppliedLSN(LogSequenceNumber)}</p>
   *
   * <p>It is safe to call this method in a thread different than the main thread.</p>
   *
   * @return not null location of the last WAL applied in the standby.
   */
  LogSequenceNumber getLastAppliedLSN();

  /**
   * <p>Set flushed LSN. This parameter will be sent to backend on next update status iteration. Flushed
   * LSN position help backend define which WAL can be recycled.</p>
   *
   * <p>It is safe to call this method in a thread different than the main thread. The updated value
   * will be sent to the backend in the next status update run.</p>
   *
   * @param flushed NOT NULL location of the last WAL flushed to disk in the standby.
   * @see PGReplicationStream#forceUpdateStatus()
   */
  void setFlushedLSN(LogSequenceNumber flushed);

  /**
   * <p>Inform backend which LSN has been applied on standby.
   * Feedback will send to backend on next update status iteration.</p>
   *
   * <p>It is safe to call this method in a thread different than the main thread. The updated value
   * will be sent to the backend in the next status update run.</p>
   *
   * @param applied NOT NULL location of the last WAL applied in the standby.
   * @see PGReplicationStream#forceUpdateStatus()
   */
  void setAppliedLSN(LogSequenceNumber applied);

  /**
   * Force send last received, flushed and applied LSN status to backend. You cannot send LSN status
   * explicitly because {@link PGReplicationStream} sends the status to backend periodically by
   * configured interval via {@link LogicalReplicationOptions#getStatusInterval}
   *
   * @throws SQLException when some internal exception occurs during read from stream
   * @see LogicalReplicationOptions#getStatusInterval()
   */
  void forceUpdateStatus() throws SQLException;

  /**
   * @return {@code true} if replication stream was already close, otherwise return {@code false}
   */
  boolean isClosed();

  /**
   * <p>Stop replication changes from server and free resources. After that connection can be reuse
   * to another queries. Also after close current stream they cannot be used anymore.</p>
   *
   * <p><b>Note:</b> This method can spend much time for logical replication stream on postgresql
   * version 9.6 and lower, because postgresql have bug - during decode big transaction to logical
   * form and during wait new changes postgresql ignore messages from client. As workaround you can
   * close replication connection instead of close replication stream. For more information about it
   * problem see mailing list thread <a href="http://www.postgresql.org/message-id/CAFgjRd3hdYOa33m69TbeOfNNer2BZbwa8FFjt2V5VFzTBvUU3w@mail.gmail.com">
   * Stopping logical replication protocol</a></p>
   *
   * @throws SQLException when some internal exception occurs during end streaming
   */
  void close() throws SQLException;
}
