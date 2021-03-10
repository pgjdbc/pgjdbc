/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/*
 * Implementation of Blob interface backed by Postgres bytea fields.
 *
 * See PgBlob.java for implementation using Postgres Large Objects.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

public class PgBlobBytea implements java.sql.Blob {

  private byte[] data;

  protected static final int MAX_BYTES = 1073741824; // 1GiB max size of a bytea

  public PgBlobBytea() {
    this.data = new byte[0];
  }

  public PgBlobBytea(byte[] in) throws SQLException {
    if (in != null && in.length > MAX_BYTES) {
      throw new PSQLException(GT.tr("Input data too long for bytea type Blob {0}", in.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    this.data = castNonNull(in);
  }

  @Override
  public void	free() 	 {
    this.data = new byte[0];
  }

  @Override
  public InputStream getBinaryStream() {
    if (this.data != null) {
      return new ByteArrayInputStream(this.data);
    }
    return new ByteArrayInputStream(new byte[0]);
  }

  @Override
  public InputStream getBinaryStream​(long pos, long length) throws SQLException {
    if (this.data == null) {
      return new ByteArrayInputStream(new byte[0]);
    }
    if (pos < 1) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (length < 0 || pos + length - 1 > data.length) {
      throw new PSQLException(GT.tr("Invalid length parameter {0}", length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    byte [] part = new byte[(int)length];
    System.arraycopy(this.data, (int) pos - 1, part, 0, (int) length);
    return new ByteArrayInputStream(part);
  }

  @Override
  public byte[]	getBytes​(long pos, int length) throws SQLException {
    if (this.data == null) {
      return this.data;
    }
    if (pos < 1) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (length < 0) {
      throw new PSQLException(GT.tr("Invalid length parameter {0}", length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    pos--; // adjust for 1 offset
    // don't run off the end of the array
    if ((int)pos + length > this.data.length) {
      length = this.data.length - (int) pos;
    }
    byte [] part = new byte[length];
    System.arraycopy(this.data, (int) pos, part, 0, length);
    return part;
  }

  @Override
  public long length() {
    return this.data == null ? 0 : this.data.length;
  }

  @Override
  public long	position​(byte[] pattern, long start) throws SQLException {
    if (this.data == null) {
      return -1;
    }
    if (start < 1 || start > data.length) {
      throw new PSQLException(GT.tr("Invalid start parameter {0}", start),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    int res = indexOf(this.data, pattern, (int) start - 1);
    if (res >= 0) {
      res = res + 1;
    }
    return res;
  }

  @Override
  public long position​(Blob pattern, long start) throws SQLException {
    return position(pattern.getBytes(1, (int) pattern.length()), start);
  }

  @Override
  public OutputStream setBinaryStream​(long pos) throws SQLException {
    return new ByteaBlobOutputStream(pos);
  }

  @Override
  public int setBytes​(long pos, byte[] bytes) throws SQLException {
    return setBytes(pos, bytes, 0, bytes.length);
  }

  @Override
  public int setBytes​(long pos, byte[] bytes, int offset, int len) throws SQLException {
    if (this.data == null) {
      if (pos == 1) {
        this.data = new byte[0];
      } else {
        throw new PSQLException(GT.tr("Called setBytes on null Blob"),
                              PSQLState.INVALID_PARAMETER_VALUE);
      }
    }

    if (pos < 1 || pos > data.length + 1) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0} for length {1}", pos, data.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (offset < 0) {
      throw new PSQLException(GT.tr("Invalid offset parameter {0}", offset),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (offset + len > bytes.length) {
      throw new PSQLException(GT.tr("Invalid offset/len parameters {0}/{1} for length {2}", offset, len, bytes.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    pos--;
    // extend the array if necessary
    if (pos + len > data.length) {
      this.data = Arrays.copyOf(this.data, (int) pos + len);
    }
    System.arraycopy(bytes, offset, this.data, (int) pos, len);
    return len;
  }

  @Override
  public void	truncate​(long len) throws SQLException {
    if (this.data == null) {
      throw new PSQLException(GT.tr("Called truncate on null Blob}"),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (len <= data.length) {
      this.data = Arrays.copyOf(this.data, (int) len);
    } else {
      throw new PSQLException(GT.tr("Called truncate with length too long for string of length {0}", data.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  // shamelessly stolen from
  // http://www.java2s.com/Code/CSharp/File-Stream/Searchabytearrayforasubbytearray.htm
  // nice single loop solution
  private static int indexOf(byte[] array, byte[] pattern, int offset) {
    int success = 0;
    for (int i = offset; i < array.length; i++) {
      if (array[i] == pattern[success]) {
        success++;
      } else {
        success = 0;
      }
      if (pattern.length == success) {
        return i - pattern.length + 1;
      }
    }
    return -1;
  }

  /*
   * This class just provides an OutputStream interface over writing to the
   * data buffer. Since we can't throw SQLExceptions here, we catch them
   * and rethrow as IOExceptions.
   */

  private class ByteaBlobOutputStream extends OutputStream {

    // keep track of where the stream is writing to in the buffer
    private int writePos;

    ByteaBlobOutputStream(long pos) {
      writePos = (int) pos;
    }

    @Override
    public void write(int b) throws IOException {
      byte[] nb = new byte[1];
      nb[0] = (byte) b;
      write(nb);
    }

    @Override
    public void write(byte[] b) throws IOException {
      try {
        writePos += PgBlobBytea.this.setBytes(writePos, b, 0 , b.length);
      } catch (SQLException e) {
        throw new IOException(e.getMessage());
      }
    }

    @Override
    public void write​(byte[] b, int off, int len) throws IOException {
      try {
        writePos += PgBlobBytea.this.setBytes(writePos, b, off, len);
      } catch (SQLException e) {
        throw new IOException(e.getMessage());
      }
    }
  }
}
