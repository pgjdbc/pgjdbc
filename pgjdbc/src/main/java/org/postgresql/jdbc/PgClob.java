/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.core.BaseConnection;
import org.postgresql.largeobject.LargeObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.sql.Clob;
import java.sql.SQLException;

public class PgClob extends AbstractBlobClob implements Clob {

  public PgClob(BaseConnection conn, long oid) throws SQLException {
    super(conn, oid);
  }

  @Override
  public Reader getCharacterStream(long pos, long length) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "getCharacterStream(long, long)");
    }
  }

  @Override
  public int setString(long pos, String str) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "setString(long,str)");
    }
  }

  @Override
  public int setString(long pos, String str, int offset, int len) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "setString(long,String,int,int)");
    }
  }

  @Override
  public OutputStream setAsciiStream(long pos) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "setAsciiStream(long)");
    }
  }

  @Override
  public Writer setCharacterStream(long pos) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "setCharacterStream(long)");
    }
  }

  @Override
  public InputStream getAsciiStream() throws SQLException {
    return getBinaryStream();
  }

  @Override
  public Reader getCharacterStream() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      Charset connectionCharset = Charset.forName(conn.getEncoding().name());
      return new InputStreamReader(getBinaryStream(), connectionCharset);
    }
  }

  @Override
  public String getSubString(long i, int j) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      assertPosition(i, j);
      LargeObject lo = getLo(false);
      lo.seek((int) i - 1);
      return new String(lo.read(j));
    }
  }

  /**
   * For now, this is not implemented.
   */
  @Override
  public long position(String pattern, long start) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "position(String,long)");
    }
  }

  /**
   * This should be simply passing the byte value of the pattern Blob.
   */
  @Override
  public long position(Clob pattern, long start) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      checkFreed();
      throw Driver.notImplemented(this.getClass(), "position(Clob,start)");
    }
  }
}
