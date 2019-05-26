/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.copy;

import org.postgresql.PGConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * InputStream for reading from a PostgreSQL COPY TO STDOUT operation.
 */
public class PGCopyInputStream extends InputStream implements CopyOut {
  private CopyOut op;
  private byte[] bytes;
  private int curPosition;
  private int len;

  /**
   * Uses given connection for specified COPY TO STDOUT operation.
   *
   * @param connection database connection to use for copying (protocol version 3 required)
   * @param sql COPY TO STDOUT statement
   * @throws SQLException if initializing the operation fails
   */
  public PGCopyInputStream(PGConnection connection, String sql) throws SQLException {
    this(connection.getCopyAPI().copyOut(sql));
  }

  /**
   * Use given CopyOut operation for reading.
   *
   * @param op COPY TO STDOUT operation
   */
  public PGCopyInputStream(CopyOut op) {
    this.op = op;
  }

  // this does too much as it can change bytes.
  private boolean isBufAvailable() throws IOException {
    if (curPosition >= 0 && curPosition >= len) {
      try {
        bytes = op.readFromCopy();
      } catch (SQLException sqle) {
        throw new IOException(GT.tr("Copying from database failed: {0}", sqle));
      }
      if (bytes == null) {
        // make sure the test above will be true next time around
        curPosition = -1;
        len = -1;
        return false;
      } else {
        curPosition = 0;
        len = bytes.length;
        return true;
      }
    }
    return bytes != null;
  }

  private void checkClosed() throws IOException {
    if (op == null) {
      throw new IOException(GT.tr("This copy stream is closed."));
    }
  }


  public int available() throws IOException {
    checkClosed();
    return (bytes != null ? len - curPosition : 0);
  }

  public int read() throws IOException {
    checkClosed();
    return isBufAvailable() ? (bytes[curPosition++] & 0xFF)  : -1;
  }

  public int read(byte[] dest) throws IOException {
    return read(dest, 0, dest.length);
  }

  public int read(byte[] dest, int off, int siz) throws IOException {
    checkClosed();
    if (siz == 0) {
      return 0;
    }
    int bytesRead = 0;
    while (bytesRead < siz && isBufAvailable()) {
      //we want to copy lesser of remaining requested or available in the buffer
      final int toCopy = Math.min(siz - bytesRead, len - curPosition);
      System.arraycopy(this.bytes, curPosition, bytes, off + bytesRead, toCopy);
      bytesRead += toCopy;
      curPosition += toCopy;
    }
    //since we check size first, bytesRead being 0 means we have reached end of stream
    return bytesRead > 0 ? bytesRead : -1;
  }

  public byte[] readFromCopy() throws SQLException {
    final byte[] result;
    try {
      if (isBufAvailable()) {
        // if the entirety of current buffer is available, just hand back reference to it
        if (curPosition == 0 && len == bytes.length) {
          result = bytes;
        } else {
          result = Arrays.copyOfRange(bytes, curPosition, len);
        }
        //either way, we have completely used up the buffer
        curPosition = len;
      } else {
        result = null;
      }
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Read from copy failed."), PSQLState.CONNECTION_FAILURE);
    }
    return result;
  }

  @Override
  public byte[] readFromCopy(boolean block) throws SQLException {
    return readFromCopy();
  }

  public void close() throws IOException {
    // Don't complain about a double close.
    if (op == null) {
      return;
    }

    if (op.isActive()) {
      try {
        op.cancelCopy();
      } catch (SQLException se) {
        IOException ioe = new IOException("Failed to close copy reader.");
        ioe.initCause(se);
        throw ioe;
      }
    }
    op = null;
  }

  public void cancelCopy() throws SQLException {
    op.cancelCopy();
  }

  public int getFormat() {
    return op.getFormat();
  }

  public int getFieldFormat(int field) {
    return op.getFieldFormat(field);
  }

  public int getFieldCount() {
    return op.getFieldCount();
  }

  public boolean isActive() {
    return op != null && op.isActive();
  }

  public long getHandledRowCount() {
    return op.getHandledRowCount();
  }
}
