/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * @author Thomas Kellerer
 */
public class PgByteaBlob
    extends AbstractBasicLob
    implements java.sql.Blob {

  private byte[] content;

  public PgByteaBlob(byte[] content) {
    this.content = content;
  }

  @Override
  public long length()
      throws SQLException {
    return content.length;
  }

  @Override
  public byte[] getBytes(long pos, int length)
      throws SQLException {

    assertPosition(pos);

    return Arrays.copyOfRange(content, (int) (pos - 1), (int) length);
  }

  public byte[] getBytes()
      throws SQLException {

    return Arrays.copyOf(content, content.length);
  }

  @Override
  public InputStream getBinaryStream()
      throws SQLException {

    return new ByteArrayInputStream(content);
  }

  @Override
  public long position(byte[] pattern, long start)
      throws SQLException {

    assertPosition(start);

    for (int i = 0; i < content.length; i++) {
      int pi = 0;
      while (content[i] == pattern[pi] && pi < pattern.length) {
        pi++;
      }
      if (pi == pattern.length) {
        return i + 1;
      }
    }
    return -1;
  }

  @Override
  public long position(Blob pattern, long start)
      throws SQLException {

    byte[] toSearch = pattern.getBytes(1, (int) pattern.length());

    return position(toSearch, start);
  }

  @Override
  public int setBytes(long pos, byte[] bytes)
      throws SQLException {

    assertPosition(pos);

    return setBytes(pos, bytes, 0, bytes.length);
  }

  @Override
  public int setBytes(long pos, byte[] bytes, int offset, int len)
      throws SQLException {

    if (bytes == null) {
      return 0;
    }

    assertPosition(pos);

    pos = pos - 1;

    if (pos + len > content.length) {
      content = Arrays.copyOf(content, (int) pos + len);
    }

    System.arraycopy(bytes, offset, content, (int) pos, len);

    return len;
  }

  @Override
  public OutputStream setBinaryStream(long pos)
      throws SQLException {

    throw org.postgresql.Driver.notImplemented(this.getClass(), "setBinaryStream(long)");
  }

  @Override
  public void truncate(long len)
      throws SQLException {

    content = Arrays.copyOfRange(content, 0, (int) len);
  }

  @Override
  public void free()
      throws SQLException {

    // nothing to do
  }

  @Override
  public InputStream getBinaryStream(long pos, long length)
      throws SQLException {

    return new ByteArrayInputStream(content, (int) (pos - 1), (int) length);
  }

}
