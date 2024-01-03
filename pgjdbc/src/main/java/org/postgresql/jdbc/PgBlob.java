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
