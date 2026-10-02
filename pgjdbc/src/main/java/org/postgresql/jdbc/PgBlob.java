/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.BaseConnection;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.InputStream;
import java.sql.Blob;
import java.sql.SQLException;

public class PgBlob extends AbstractBlobClob implements Blob {

  public PgBlob(BaseConnection conn, long oid) throws SQLException {
    super(conn, oid);
  }

  /**
   * {@inheritDoc}
   *
   * <p>{@code pos} may exceed {@link Integer#MAX_VALUE} here, unlike elsewhere in this class. A
   * position that large needs the 64-bit large object API, which PostgreSQL 9.3 added. A
   * {@code pos} below 1 or a negative {@code length} is refused before anything reaches the
   * server.</p>
   */
  @Override
  public InputStream getBinaryStream(long pos, long length)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      // Not assertPosition, which caps the offset at Integer.MAX_VALUE, while this method seeks
      // in 64 bits
      if (pos < 1) {
        throw new PSQLException(GT.tr("LOB positioning offsets start at 1."),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
      if (length < 0) {
        throw new PSQLException(GT.tr("LOB stream length must not be negative: {0}", length),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
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
