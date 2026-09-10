/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.ReaderInputStream;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * @author Thomas Kellerer
 */
public class PgStringClob
    extends AbstractBasicLob
    implements java.sql.Clob {

  private String content;

  public PgStringClob(String data) {

    this.content = data;
  }

  @Override
  public long length()
      throws SQLException {

    return content.length();
  }

  @Override
  public String getSubString(long pos, int length)
      throws SQLException {

    assertPosition(pos);

    return content.substring((int) (pos - 1), (int) pos + length - 1);
  }

  @Override
  public Reader getCharacterStream()
      throws SQLException {

    return new StringReader(content);
  }

  @Override
  public InputStream getAsciiStream()
      throws SQLException {

    return new ReaderInputStream(getCharacterStream());
  }

  @Override
  public long position(String searchstr, long start)
      throws SQLException {

    assertPosition(start);

    return content.indexOf(searchstr, (int) start - 1) + 1;
  }

  @Override
  public long position(Clob searchstr, long start)
      throws SQLException {

    assertPosition(start);

    String toSearch = searchstr.getSubString(1, (int) searchstr.length());
    return position(toSearch, start);
  }

  @Override
  public int setString(long pos, String str)
      throws SQLException {

    assertPosition(pos);

    if (str == null) {
      return 0;
    }

    StringBuilder buffer = new StringBuilder(content.length());
    buffer.append(content.substring(0, (int) (pos - 1)));
    buffer.append(str);
    if (str.length() + (pos - 1) < content.length()) {
      buffer.append(content.substring(str.length() + (int) (pos - 1)));
    }
    content = buffer.toString();
    return str.length();
  }

  @Override
  public int setString(long pos, String str, int offset, int len)
      throws SQLException {

    assertPosition(pos);

    if (str == null) {
      return 0;
    }
    return setString(pos, str.substring(offset, offset + len));
  }

  @Override
  public OutputStream setAsciiStream(long pos)
      throws SQLException {

    throw org.postgresql.Driver.notImplemented(this.getClass(), "setAsciiStream(long)");
  }

  @Override
  public Writer setCharacterStream(long pos)
      throws SQLException {

    throw org.postgresql.Driver.notImplemented(this.getClass(), "setCharacterStream(long)");
  }

  @Override
  public void truncate(long len)
      throws SQLException {

    if (len > content.length()) {
      // nothing to do
      return;
    }
    content = content.substring(0, (int) len);
  }

  @Override
  public void free()
      throws SQLException {
    // nothing to do
  }

  @Override
  public Reader getCharacterStream(long pos, long length)
      throws SQLException {

    return new StringReader(content.substring((int) (pos - 1), (int) length));
  }

  @Override
  public String toString() {
    return content;
  }

}
