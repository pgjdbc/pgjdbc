/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.LimitedReader;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;

class PgClob extends AbstractBlobClob implements Clob, NClob {

  PgClob(org.postgresql.core.BaseConnection conn) throws java.sql.SQLException {
    super(conn);
  }

  PgClob(org.postgresql.core.BaseConnection conn, long oid) throws java.sql.SQLException {
    super(conn, oid);
  }

  private int getBufferSize(long length) {
    return (int) Math.min(length * 1.2, 64000);
  }

  @Override
  public synchronized int setString(long pos, String str) throws SQLException {
    // TODO: pos>0 check
    return setString(pos, str, 0, str.length());
  }

  private long measureBytes(long numCharacters) throws SQLException {
    if (numCharacters == 0) {
      return 0;
    }
    // TODO: offload to backend somehow?
    Reader characterStream = getCharacterStream(1, numCharacters);
    int bufferSize = (int) (numCharacters < 8192 ? numCharacters * 1.2 : numCharacters);
    char[] tmp = new char[bufferSize];
    int read;
    long len = 0;
    try {
      while ((read = characterStream.read(tmp)) != -1) {
        len = len + read;
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("Unable to skip {0} characters, large object oid is {1}",
          numCharacters, getOid()),
          PSQLState.UNEXPECTED_ERROR, e);
    }
    return len;
  }


  @Override
  public synchronized int setString(long pos, String str, int offset, int len) throws SQLException {
    // TODO: add pos>0 check and error message
    Writer writer = setCharacterStream(pos);
    try {
      writer.write(str, offset, len);
      return len;
    } catch (IOException e) {
      throw new SQLException();
    } finally {
      try {
        writer.close();
      } catch (IOException e) {
        e.printStackTrace();
      }

    }
  }

  @Override
  public synchronized java.io.OutputStream setAsciiStream(long pos) throws SQLException {
    return setBinaryStream(pos);
  }

  @Override
  public synchronized java.io.Writer setCharacterStream(long pos) throws SQLException {
    Charset connectionCharset = Charset.forName(conn.getEncoding().name());
    return new OutputStreamWriter(setBinaryStream(measureBytes(pos - 1) + 1), connectionCharset);
  }

  @Override
  public synchronized InputStream getAsciiStream() throws SQLException {
    return getBinaryStream();
  }

  @Override
  public synchronized Reader getCharacterStream() throws SQLException {
    Charset connectionCharset = Charset.forName(conn.getEncoding().name());
    return new InputStreamReader(getBinaryStream(), connectionCharset);
  }

  @Override
  public synchronized Reader getCharacterStream(long pos, long length) throws SQLException {
    Reader characterStream = getCharacterStream();
    // TODO: add pos>0 check and error message
    try {
      // TODO: offload to backend somehow?
      characterStream.skip(pos - 1);
    } catch (IOException e) {
      throw new PSQLException(GT.tr("Unable to skip {0} bytes, large object oid is {1}",
          pos - 1, getOid()),
          PSQLState.COMMUNICATION_ERROR, e);
    }
    return new LimitedReader(characterStream, getBufferSize(length), length);
  }

  @Override
  public synchronized String getSubString(long pos, int length) throws SQLException {
    StringWriter sw = new StringWriter();
    Reader reader = null;
    try {
      reader = getCharacterStream(pos, length);
      char[] buffer = new char[Math.min(8192, length)];
      int len;
      while ((len = reader.read(buffer)) != -1) {
        sw.write(buffer, 0, len);
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("Error while reading large object oid {0} at character {1}",
          getOid(), sw.getBuffer().length()),
          PSQLState.COMMUNICATION_ERROR, e);
    } finally {
      try {
        reader.close();
      } catch (IOException e) {
        throw new PSQLException(GT.tr("Unable to release large object oid {0} reader",
            getOid()),
            PSQLState.UNEXPECTED_ERROR, e);
      }
    }
    return sw.toString();
  }

  /**
   * For now, this is not implemented.
   */
  @Override
  public synchronized long position(String pattern, long start) throws SQLException {
    checkFreed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "position(String,long)");
  }

  /**
   * This should be simply passing the byte value of the pattern Blob.
   */
  @Override
  public synchronized long position(Clob pattern, long start) throws SQLException {
    checkFreed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "position(Clob,start)");
  }
}
