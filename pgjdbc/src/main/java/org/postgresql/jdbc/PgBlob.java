/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.largeobject.LargeObject;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;

public class PgBlob extends AbstractBlobClob implements Blob {

  public PgBlob(BaseConnection conn, long oid) throws SQLException {
    super(conn, oid);
  }

  @Override
  public InputStream getBinaryStream(long pos, long length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      LargeObject subLO = getLo(false).copy();
      addSubLO(subLO);
      if (pos > Integer.MAX_VALUE) {
        subLO.seek64(pos - 1, LargeObject.SEEK_SET);
      } else {
        subLO.seek((int) pos - 1, LargeObject.SEEK_SET);
      }
      return subLO.getInputStream(length);
    }
  }

  @Override
  public int setBytes(long pos, byte[] bytes) throws SQLException {
    return setBytes(pos, bytes, 0, bytes.length);
  }

  /**
   * Writes bytes from an array into this blob, starting at the given position.
   *
   * <p>The two ways of getting the arguments wrong are reported differently. A position the large
   * object API cannot index arrives as an {@link SQLException}, while a range outside the array
   * the caller passed is a programming error and arrives as an
   * {@link IndexOutOfBoundsException}.</p>
   *
   * @param pos the position in this blob to start writing at, counted from 1
   * @param bytes the array to take the bytes from
   * @param offset the position in {@code bytes} to start from, counted from 0
   * @param len the number of bytes to write
   * @return the number of bytes written, which is {@code len}
   * @throws SQLException if {@code pos} is below 1 or above {@link Integer#MAX_VALUE}, which is as
   *         far as the large object API can index, or if the write fails
   * @throws NullPointerException if {@code bytes} is {@code null}
   * @throws IndexOutOfBoundsException if {@code offset} or {@code len} is negative, or if
   *         {@code len} is greater than {@code bytes.length - offset}
   */
  @Override
  public int setBytes(long pos, byte[] bytes, int offset, int len)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      assertPosition(pos);
      getLo(true).seek((int) (pos - 1));
      getLo(true).write(bytes, offset, len);
      return len;
    }
  }
}
