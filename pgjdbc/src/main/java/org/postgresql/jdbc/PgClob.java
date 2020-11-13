/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.largeobject.LargeObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.sql.Clob;
import java.sql.SQLException;

public class PgClob extends AbstractBlobClob implements java.sql.Clob {

  public PgClob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException {
    super(conn, oid);
  }

  public Reader getCharacterStream(long pos, long length) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "getCharacterStream(long, long)");
    } finally {
      lock.unlock();
    }
  }

  public int setString(long pos, String str) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "setString(long,str)");
    } finally {
      lock.unlock();
    }
  }

  public int setString(long pos, String str, int offset, int len) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "setString(long,String,int,int)");
    } finally {
      lock.unlock();
    }
  }

  public java.io.OutputStream setAsciiStream(long pos) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "setAsciiStream(long)");
    } finally {
      lock.unlock();
    }
  }

  public java.io.Writer setCharacterStream(long pos) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "setCharacteStream(long)");
    } finally {
      lock.unlock();
    }
  }

  public InputStream getAsciiStream() throws SQLException {
    lock.lock();
    try {
      return getBinaryStream();
    } finally {
      lock.unlock();
    }
  }

  public Reader getCharacterStream() throws SQLException {
    lock.lock();
    try {
      Charset connectionCharset = Charset.forName(conn.getEncoding().name());
      return new InputStreamReader(getBinaryStream(), connectionCharset);
    } finally {
      lock.unlock();
    }
  }

  public String getSubString(long i, int j) throws SQLException {
    lock.lock();
    try {
      assertPosition(i, j);
      LargeObject lo = getLo(false);
      lo.seek((int) i - 1);
      return new String(lo.read(j));
    } finally {
      lock.unlock();
    }
  }

  /**
   * For now, this is not implemented.
   */
  public long position(String pattern, long start) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "position(String,long)");
    } finally {
      lock.unlock();
    }
  }

  /**
   * This should be simply passing the byte value of the pattern Blob.
   */
  public long position(Clob pattern, long start) throws SQLException {
    lock.lock();
    try {
      checkFreed();
      throw org.postgresql.Driver.notImplemented(this.getClass(), "position(Clob,start)");
    } finally {
      lock.unlock();
    }
  }
}
