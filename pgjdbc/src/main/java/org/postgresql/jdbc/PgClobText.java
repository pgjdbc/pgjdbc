/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

/*
 * Implementation of Clob interface backed by Postgres varchar/text fields.
 *
 * See PgClob.java for implementation using Postgres Large Objects.
 */

package org.postgresql.jdbc;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.sql.Clob;
import java.sql.SQLException;

public class PgClobText implements java.sql.Clob {

  private @Nullable String data;
  private boolean freed = false;

  public PgClobText() {
    this.data = null;
  }

  public PgClobText(@Nullable String s) {
    this.data = s;
  }

  @Override
  public void free() {
    if (! this.freed) {
      this.data = null;
    }
    this.freed = true;
  }

  // it is forbidden to access a freed Clob
  private void checkIfFreed() throws SQLException {
    if (this.freed) {
      throw new SQLException("Operation forbidden on freed Clob");
    }
  }

  @Override
  public InputStream	getAsciiStream()  throws SQLException {
    checkIfFreed();
    if (this.data != null) {
      return new ByteArrayInputStream(this.data.getBytes());
    }
    return new ByteArrayInputStream(new byte[0]);
  }

  @Override
  public Reader getCharacterStream()  throws SQLException {
    checkIfFreed();
    if (this.data != null) {
      return new StringReader(this.data);
    }
    return new StringReader("");
  }

  @Override
  public Reader getCharacterStream(long pos, long length)  throws SQLException {
    checkIfFreed();
    pos--;
    if (this.data != null) {
      return new StringReader(this.data.substring((int)pos, (int)(pos + length)));
    }
    return new StringReader("");
  }

  @Override
  public String getSubString(long pos, int length)  throws SQLException {
    checkIfFreed();
    if (this.data != null) {
      if (pos < 1) {
        throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                                PSQLState.INVALID_PARAMETER_VALUE);
      }
      if (length < 0) {
        throw new PSQLException(GT.tr("Invalid length parameter {0}", length),
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
    return castNonNull(this.data);
  }

  @Override
  public long length()  throws SQLException {
    checkIfFreed();
    if (this.data != null) {
      return this.data.length();
    }
    return 0;
  }

  @Override
  public long position(Clob searchstr, long start) throws SQLException {
    checkIfFreed();
    return position(searchstr.getSubString(1L, (int) searchstr.length()), start);
  }

  @Override
  public long position(String searchstr, long start) throws SQLException {
    checkIfFreed();
    if (this.data != null) {
      int pos = this.data.indexOf(searchstr,(int) (start - 1));
      return (pos == -1) ? -1 : (pos + 1);
    }
    return -1;
  }

  @Override
  public OutputStream setAsciiStream(long pos) throws SQLException {
    checkIfFreed();
    return new TextClobOutputStream(pos);
  }

  @Override
  public Writer setCharacterStream(long pos) throws SQLException {
    checkIfFreed();
    return new TextClobOutputStreamWriter(new TextClobOutputStream(pos));
  }

  @Override
  public int setString(long pos, String str) throws SQLException {
    checkIfFreed();
    if (pos < 1) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (this.data == null) {
      if (pos == 1) {
        this.data = "";
      } else {
        throw new PSQLException(GT.tr("Called setString on null Clob"),
                              PSQLState.INVALID_PARAMETER_VALUE);
      }
    }
    StringBuilder buf = new StringBuilder(this.data);
    int len = str.length();
    try {
      buf.replace((int)pos - 1, (int) (pos + len - 1), str);
      this.data = buf.toString();
    } catch (StringIndexOutOfBoundsException e) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    return len;
  }

  @Override
  public int setString(long pos, String str, int offset, int len) throws SQLException {
    checkIfFreed();
    if (pos < 1) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (this.data == null) {
      if (pos == 1) {
        this.data = "";
      } else {
        throw new PSQLException(GT.tr("Called setString on null Clob"),
                              PSQLState.INVALID_PARAMETER_VALUE);
      }
    }
    StringBuilder buf = new StringBuilder(this.data);
    try {
      String repl = str.substring(offset, offset + len);
      buf.replace((int)pos - 1, (int) (pos + repl.length() - 1), repl);
      this.data = buf.toString();
    } catch (StringIndexOutOfBoundsException e) {
      throw new PSQLException(GT.tr("Invalid pos parameter {0}", pos),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    return len;
  }

  @Override
  public void truncate(long len) throws SQLException {
    checkIfFreed();
    if (this.data == null) {
      throw new PSQLException(GT.tr("Called truncate on null Clob}"),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
    if (len <= data.length()) {
      this.data = this.data.substring(0, (int) len);
    } else {
      throw new PSQLException(GT.tr("Called truncate with length too long for string of length {0}", data.length()),
                              PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public String toString() {
    return castNonNull(this.data);
  }

  /*
   * This class just provides an OutputStream interface over writing to the
   * data buffer. Since we can't throw SQLExceptions here, we catch them
   * and rethrow as IOExceptions.
   */

  private class TextClobOutputStream extends OutputStream {

    // keep track of where the stream is writing to in the buffer
    private int writePos;

    TextClobOutputStream(long pos) {
      writePos = (int) pos;
    }

    @Override
    public void write(int b) throws IOException {
      char c = (char) b;
      byte[] nb = String.valueOf(c).getBytes();
      write(nb);
    }

    @Override
    public void write(byte[] b) throws IOException {
      String sb = new String(b);
      try {
        writePos += PgClobText.this.setString(writePos, sb, 0 , sb.length());
      } catch (SQLException e) {
        throw new IOException(e.getMessage());
      }
    }

    @Override
    public void write​(byte[] b, int off, int len) throws IOException {
      String sb = new String(b);
      try {
        writePos += PgClobText.this.setString(writePos, sb, off, len);
      } catch (SQLException e) {
        throw new IOException(e.getMessage());
      }
    }
  }

  /*
   * subclass of OutputStreamWriter that calls flush after every write
   */
  private class TextClobOutputStreamWriter extends OutputStreamWriter {

    TextClobOutputStreamWriter(OutputStream os) {
      super(os);
    }

    @Override
    public void write​(char[] cbuf, int off, int len) throws IOException {
      super.write(cbuf, off, len);
      this.flush();
    }

    @Override
    public void write​(int c) throws IOException {
      super.write(c);
      flush();
    }

    @Override
    public void write​(String str, int off, int len) throws IOException {
      super.write(str, off, len);
      flush();
    }
  }
}
