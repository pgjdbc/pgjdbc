/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/*
 * Implementation of Blob interface backed by Postgres bytea fields.
 *
 * See PgBlob.java for implementation using Postgres Large Objects.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

public class PgBlobBytea implements java.sql.Blob {

  private byte[] data;

  public static final int MAX_BYTES = 1073741824; // 1GiB max size of a bytea

  public PgBlobBytea() {
    this.data = new byte[0];
  }

  public PgBlobBytea(byte[] in) throws SQLException {
    if (in.length > MAX_BYTES) {
      throw new PSQLException(GT.tr("input data too long for bytea type Blob {0}", in.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    this.data = in;
  }

  // This method frees the Blob object and releases the resources that it holds.
  @Override
  public void	free() 	 {
    this.data = new byte[0];
  }

  // Retrieves the BLOB value designated by this Blob instance as a stream.
  @Override
  public InputStream getBinaryStream() {
    if (this.data != null) {
      return new ByteArrayInputStream(this.data);
    }
    return new ByteArrayInputStream(new byte[0]);
  }

  // Returns an InputStream object that contains a partial Blob value,
  // starting with the byte specified by pos, which is length bytes in length.
  @Override
  public InputStream getBinaryStream​(long pos, long length) throws SQLException {
    if (this.data == null) {
      return new ByteArrayInputStream(new byte[0]);
    }
    if (pos < 1) {
      throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (length < 0 || pos + length - 1 > data.length) {
      throw new PSQLException(GT.tr("invalid length parameter {0}", length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    byte [] part = new byte[(int)length];
    System.arraycopy(this.data, (int) pos - 1, part, 0, (int) length);
    return new ByteArrayInputStream(part);
  }

  // Retrieves all or part of the BLOB value that this Blob object represents,
  // as an array of bytes.
  @Override
  public byte[]	getBytes​(long pos, int length) throws SQLException {
    if (this.data == null) {
      throw new PSQLException(GT.tr("called getBytes on null Blob"),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (pos < 1) {
      throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (length < 0) {
      throw new PSQLException(GT.tr("invalid length parameter {0}", length),
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

  // Returns the number of bytes in the BLOB value designated by this
  // Blob object.
  @Override
  public long length() {
    return this.data == null ? 0 : this.data.length;
  }

  // Retrieves the byte position at which the specified byte array pattern
  // begins within the BLOB value that this Blob object represents.
  @Override
  public long	position​(byte[] pattern, long start) throws SQLException {
    if (start < 1 || start > data.length) {
      throw new PSQLException(GT.tr("invalid start parameter {0}", start),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    int res = indexOf(this.data, pattern, (int) start - 1);
    if (res >= 0) {
      res = res + 1;
    }
    return res;
  }

  // Retrieves the byte position in the BLOB value designated by this Blob
  // object at which pattern begins.
  @Override
  public long position​(Blob pattern, long start) throws SQLException {
    return position(pattern.getBytes(1, (int) pattern.length()), start);
  }

  // Retrieves a stream that can be used to write to the BLOB value that this
  // Blob object represents.
  @Override
  public OutputStream setBinaryStream​(long pos) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "setBinaryStream(long)");
  }

  // Writes the given array of bytes to the BLOB value that this Blob object
  // represents, starting at position pos, and returns the number of bytes
  // written.
  @Override
  public int setBytes​(long pos, byte[] bytes) throws SQLException {
    return setBytes(pos, bytes, 0, bytes.length);
  }

  // Writes all or part of the given byte array to the BLOB value that this
  // Blob object represents and returns the number of bytes written.
  @Override
  public int setBytes​(long pos, byte[] bytes, int offset, int len) throws SQLException {
    if (pos < 1 || pos > data.length + 1) {
      throw new PSQLException(GT.tr("invalid pos parameter {0} for length {1}", pos, data.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (offset < 0) {
      throw new PSQLException(GT.tr("invalid offset parameter {0}", offset),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (offset + len > bytes.length) {
      throw new PSQLException(GT.tr("invalid offset/len parameters {0}/{1} for length {2}", offset, len, bytes.length),
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

  // Truncates the BLOB value that this Blob object represents to be len
  // bytes in length.
  @Override
  public void	truncate​(long len) throws SQLException {
    if (len <= data.length) {
      this.data = Arrays.copyOf(this.data, (int) len);
    } else {
      throw new PSQLException(GT.tr("truncate length too long for string of length {0}", data.length),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  // shamelessly stolen from http://www.java2s.com/Code/CSharp/File-Stream/Searchabytearrayforasubbytearray.htm
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
}
