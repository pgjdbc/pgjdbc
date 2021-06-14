/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.copy;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGConnection;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * OutputStream for buffered input into a PostgreSQL COPY FROM STDIN operation.
 */
public class PGCopyOutputStream extends OutputStream implements CopyIn {
  private @Nullable CopyIn op;
  private final byte[] copyBuffer;
  private final byte[] singleByteBuffer = new byte[1];
  private int at = 0;

  /**
   * Uses given connection for specified COPY FROM STDIN operation.
   *
   * @param connection database connection to use for copying (protocol version 3 required)
   * @param sql        COPY FROM STDIN statement
   * @throws SQLException if initializing the operation fails
   */
  public PGCopyOutputStream(PGConnection connection, String sql) throws SQLException {
    this(connection, sql, CopyManager.DEFAULT_BUFFER_SIZE);
  }

  /**
   * Uses given connection for specified COPY FROM STDIN operation.
   *
   * @param connection database connection to use for copying (protocol version 3 required)
   * @param sql        COPY FROM STDIN statement
   * @param bufferSize try to send this many bytes at a time
   * @throws SQLException if initializing the operation fails
   */
  public PGCopyOutputStream(PGConnection connection, String sql, int bufferSize)
      throws SQLException {
    this(connection.getCopyAPI().copyIn(sql), bufferSize);
  }

  /**
   * Use given CopyIn operation for writing.
   *
   * @param op COPY FROM STDIN operation
   */
  public PGCopyOutputStream(CopyIn op) {
    this(op, CopyManager.DEFAULT_BUFFER_SIZE);
  }

  /**
   * Use given CopyIn operation for writing.
   *
   * @param op         COPY FROM STDIN operation
   * @param bufferSize try to send this many bytes at a time
   */
  public PGCopyOutputStream(CopyIn op, int bufferSize) {
    this.op = op;
    copyBuffer = new byte[bufferSize];
  }

  private CopyIn getOp() {
    return castNonNull(op);
  }

  public void write(int b) throws IOException {
    checkClosed();
    if (b < 0 || b > 255) {
      throw new IOException(GT.tr("Cannot write to copy a byte of value {0}", b));
    }
    singleByteBuffer[0] = (byte) b;
    write(singleByteBuffer, 0, 1);
  }

  public void write(byte[] buf) throws IOException {
    write(buf, 0, buf.length);
  }

  public void write(byte[] buf, int off, int siz) throws IOException {
    checkClosed();
    try {
      writeToCopy(buf, off, siz);
    } catch (SQLException se) {
      throw new IOException("Write to copy failed.", se);
    }
  }

  private void checkClosed() throws IOException {
    if (op == null) {
      throw new IOException(GT.tr("This copy stream is closed."));
    }
  }

  public void close() throws IOException {
    // Don't complain about a double close.
    if (op == null) {
      return;
    }

    if (getOp().isActive()) {
      try {
        endCopy();
      } catch (SQLException se) {
        throw new IOException("Ending write to copy failed.", se);
      }
    }
    op = null;
  }

  public void flush() throws IOException {
    checkClosed();
    try {
      getOp().writeToCopy(copyBuffer, 0, at);
      at = 0;
      getOp().flushCopy();
    } catch (SQLException e) {
      throw new IOException("Unable to flush stream", e);
    }
  }

  public void writeToCopy(byte[] buf, int off, int siz) throws SQLException {
    if (at > 0
        && siz > copyBuffer.length - at) { // would not fit into rest of our buf, so flush buf
      getOp().writeToCopy(copyBuffer, 0, at);
      at = 0;
    }
    if (siz > copyBuffer.length) { // would still not fit into buf, so just pass it through
      getOp().writeToCopy(buf, off, siz);
    } else { // fits into our buf, so save it there
      System.arraycopy(buf, off, copyBuffer, at, siz);
      at += siz;
    }
  }

  public void writeToCopy(ByteStreamWriter from) throws SQLException {
    if (at > 0) {
      // flush existing buffer so order is preserved
      getOp().writeToCopy(copyBuffer, 0, at);
      at = 0;
    }
    getOp().writeToCopy(from);
  }

  public int getFormat() {
    return getOp().getFormat();
  }

  public int getFieldFormat(int field) {
    return getOp().getFieldFormat(field);
  }

  public void cancelCopy() throws SQLException {
    getOp().cancelCopy();
  }

  public int getFieldCount() {
    return getOp().getFieldCount();
  }

  public boolean isActive() {
    return op != null && getOp().isActive();
  }

  public void flushCopy() throws SQLException {
    getOp().flushCopy();
  }

  public long endCopy() throws SQLException {
    if (at > 0) {
      getOp().writeToCopy(copyBuffer, 0, at);
    }
    getOp().endCopy();
    return getHandledRowCount();
  }

  public long getHandledRowCount() {
    return getOp().getHandledRowCount();
  }

}
