/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/*
 * Implementation of Clob interface backed by Postgres varchar/text fields.
 *
 * See PgClob.java for implementation using Postgres Large Objects.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;

public class PgClobText implements java.sql.Clob {

  private String data;

  public PgClobText() {
    this.data = "";
  }

  public PgClobText(String s) {
    this.data = s;
  }

  // This method frees the Clob object and releases the resources the
  // resources that it holds.
  @Override
  public void free() {
    this.data = null;
  }

  // Retrieves the CLOB value designated by this Clob object as an ascii stream.
  @Override
  public InputStream	getAsciiStream() {
    if (this.data != null) {
      return new ByteArrayInputStream(this.data.getBytes());
    }
    return null;
  }

  // Retrieves the CLOB value designated by this Clob object as a
  // java.io.Reader object (or as a stream of characters).
  @Override
  public Reader getCharacterStream() {
    if (this.data != null) {
      return new StringReader(this.data);
    }
    return null;
  }

  //Returns a Reader object that contains a partial Clob value, starting
  // with the character specified by pos, which is length characters in length.
  @Override
  public Reader getCharacterStream(long pos, long length) {
    pos--;
    if (this.data != null) {
      return new StringReader(this.data.substring((int)pos, (int)(pos + length)));
    }
    return null;
  }

  // Retrieves a copy of the specified substring in the CLOB value designated
  // by this Clob object.
  @Override
  public String getSubString(long pos, int length)  throws SQLException {
    if (this.data != null) {
      if (pos < 1) {
        throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                                PSQLState.INVALID_PARAMETER_VALUE);
      }
      if (length < 0) {
        throw new PSQLException(GT.tr("invalid length parameter {0}", length),
                                PSQLState.INVALID_PARAMETER_VALUE);
      }
      long dlen = this.data.length();
      pos--; // CLOBS start at 1, Strings start at 0 - go figure
      if (pos + (long) length > dlen) {
        // don't  run off the end of the string, we return UP TO length chars
        // according to the spec
        length = (int) (dlen - pos);
      }
      return this.data.substring((int)pos, (int) (pos + length));
    }
    return null;
  }

  // Retrieves the number of characters in the CLOB value designated by this
  // Clob object.
  @Override
  public long length() {
    return this.data.length();
  }

  // Retrieves the character position at which the specified Clob object
  // searchstr appears in this Clob object.
  // implement by calling the string variant
  @Override
  public long position(Clob searchstr, long start) throws SQLException {
    return position(searchstr.getSubString(1L, (int) searchstr.length()), start);
  }

  // Retrieves the character position at which the specified substring
  // searchstr appears in the SQL CLOB value represented by this Clob object.
  @Override
  public long position(String searchstr, long start) throws SQLException {
    if (this.data != null) {
      int pos = this.data.indexOf(searchstr,(int) (start - 1));
      return (pos == -1) ? -1 : (pos + 1);
    }
    return -1;
  }

  // Retrieves a stream to be used to write Ascii characters to the CLOB
  // value that this Clob object represents, starting at position pos.
  @Override
  public OutputStream setAsciiStream(long pos) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "setAsciiStream(long)");
  }

  // Retrieves a stream to be used to write a stream of Unicode characters to
  // the CLOB value that this Clob object represents, at position pos.
  @Override
  public Writer setCharacterStream(long pos) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "setCharacterStream(long)");
  }

  // Writes the given Java String to the CLOB value that this Clob object
  // designates at the position pos.
  @Override
  public int setString(long pos, String str) throws SQLException {
    if (pos < 1) {
      throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (this.data == null) {
      throw new PSQLException(GT.tr("setString called on null Clob"),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    StringBuilder buf = new StringBuilder(this.data);
    int len = str.length();
    try {
      buf.replace((int)pos - 1, (int) (pos + len - 1), str);
      this.data = buf.toString();
    } catch (StringIndexOutOfBoundsException e) {
      throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    return len;
  }

  // Writes len characters of str, starting at character offset, to the CLOB
  // value that this Clob represents.
  @Override
  public int setString(long pos, String str, int offset, int len) throws SQLException {
    if (pos < 1) {
      throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (this.data == null) {
      throw new PSQLException(GT.tr("setString called on null Clob"),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    StringBuilder buf = new StringBuilder(this.data);
    try {
      String repl = str.substring(offset, offset + len);
      buf.replace((int)pos - 1, (int) (pos + repl.length() - 1), repl);
      this.data = buf.toString();
    } catch (StringIndexOutOfBoundsException e) {
      throw new PSQLException(GT.tr("invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    return len;
  }

  // Truncates the CLOB value that this Clob designates to have a length of
  // len characters.
  @Override
  public void truncate(long len) throws SQLException {
    if (this.data == null) {
      throw new PSQLException(GT.tr("truncate on null Clob}"),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (len <= data.length()) {
      this.data = this.data.substring(0, (int) len);
    } else {
      throw new PSQLException(GT.tr("truncate length too long for string of length {0}", data.length()),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public String toString() {
    return this.data;
  }

}
