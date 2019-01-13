/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.copy;

import org.postgresql.PGConnection;
import org.postgresql.util.GT;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * OutputStream for input into a PostgreSQL COPY FROM STDIN operation.
 */
public class PGCopyOutputStream extends OutputStream {
  private CopyIn op;
  private final byte[] singleByteBuffer = new byte[1];

  /**
   * Uses given connection for specified COPY FROM STDIN operation.
   *
   * @param connection database connection to use for copying (protocol version 3 required)
   * @param sql        COPY FROM STDIN statement
   * @throws SQLException if initializing the operation fails
   */
  public PGCopyOutputStream(PGConnection connection, String sql) throws SQLException {
    this(connection.getCopyAPI().copyIn(sql));
  }

  /**
   * Use given CopyIn operation for writing.
   *
   * @param op COPY FROM STDIN operation
   */
  public PGCopyOutputStream(CopyIn op) {
    this.op = op;
  }

  public void write(int b) throws IOException {
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
      op.writeToCopy(buf, off, siz);
    } catch (SQLException se) {
      IOException ioe = new IOException("Write to copy failed.");
      ioe.initCause(se);
      throw ioe;
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

    try {
      op.endCopy();
    } catch (SQLException se) {
      IOException ioe = new IOException("Ending write to copy failed.");
      ioe.initCause(se);
      throw ioe;
    } finally {
      op = null;
    }
  }

  public void flush() throws IOException {
    checkClosed();
    try {
      op.flushCopy();
    } catch (SQLException e) {
      IOException ioe = new IOException("Unable to flush stream");
      ioe.initCause(e);
      throw ioe;
    }
  }
}
