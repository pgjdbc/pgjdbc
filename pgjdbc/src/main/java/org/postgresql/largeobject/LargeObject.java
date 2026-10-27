/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import org.postgresql.core.BaseConnection;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.fastpath.FastpathArg;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * This class provides the basic methods required to run the interface, plus a pair of methods that
 * provide InputStream and OutputStream classes for this object.
 *
 * <p>Normally, client code would use the getAsciiStream, getBinaryStream, or getUnicodeStream methods
 * in ResultSet, or setAsciiStream, setBinaryStream, or setUnicodeStream methods in
 * PreparedStatement to access Large Objects.</p>
 *
 * <p>However, sometimes lower level access to Large Objects are required, that are not supported by
 * the JDBC specification.</p>
 *
 * <p>Refer to org.postgresql.largeobject.LargeObjectManager on how to gain access to a Large Object,
 * or how to create one.</p>
 *
 * @see org.postgresql.largeobject.LargeObjectManager
 * @see java.sql.ResultSet#getAsciiStream
 * @see java.sql.ResultSet#getBinaryStream
 * @see java.sql.ResultSet#getUnicodeStream
 * @see java.sql.PreparedStatement#setAsciiStream
 * @see java.sql.PreparedStatement#setBinaryStream
 * @see java.sql.PreparedStatement#setUnicodeStream
 */
@SuppressWarnings("deprecation") // support for deprecated Fastpath API
public class LargeObject
    implements AutoCloseable {

  /**
   * Indicates a seek from the beginning of a file.
   */
  public static final int SEEK_SET = 0;

  /**
   * Indicates a seek from the current position.
   */
  public static final int SEEK_CUR = 1;

  /**
   * Indicates a seek from the end of a file.
   */
  public static final int SEEK_END = 2;

  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  /**
   * Largest number of bytes one {@code lowrite} call sends, 64 KiB below 1 GiB ({@value} bytes).
   * PostgreSQL 14 and later close the connection on a function call message longer than
   * {@code PQ_LARGE_MESSAGE_LIMIT}, which is 1 GiB minus two bytes, and earlier versions fail the
   * call with {@code out of memory}. The 64 KiB leave room for the rest of the message.
   */
  static final int MAX_LOWRITE_LENGTH = (1 << 30) - 64 * 1024;

  /**
   * Size of the buffer, 8 MiB ({@value} bytes), that copies the output of a
   * {@link ByteStreamWriter} longer than one {@code lowrite} can send.
   */
  static final int WRITER_PIECE_SIZE = 8 * 1024 * 1024;

  private final Fastpath fp; // Fastpath API to use
  private final long oid; // OID of this object
  private final int mode; // read/write mode of this object
  private final int fd; // the descriptor of the open large object
  private final int maxLowriteLength; // MAX_LOWRITE_LENGTH, or a smaller value set by a test

  private @Nullable BlobOutputStream os; // The current output stream

  private boolean closed; // true when we are closed

  private @Nullable BaseConnection conn; // Only initialized when open a LOB with CommitOnClose
  private final boolean commitOnClose; // Only initialized when open a LOB with CommitOnClose

  /**
   * Checks if this LargeObject is closed and throws an exception if it is.
   *
   * @throws SQLException if this LargeObject has been closed
   */
  private void checkClosed() throws SQLException {
    if (closed) {
      throw new PSQLException(GT.tr("This large object has been closed."),
          PSQLState.OBJECT_NOT_IN_STATE);
    }
  }

  /**
   * This opens a large object.
   *
   * <p>If the object does not exist, then an SQLException is thrown.</p>
   *
   * @param fp FastPath API for the connection to use
   * @param oid of the Large Object to open
   * @param mode Mode of opening the large object
   * @param conn the connection to the database used to access this LOB
   * @param commitOnClose commit the transaction when this LOB will be closed (defined in
   *        LargeObjectManager)
   * @throws SQLException if a database-access error occurs.
   * @see org.postgresql.largeobject.LargeObjectManager
   */
  protected LargeObject(Fastpath fp, long oid, int mode,
      @Nullable BaseConnection conn, boolean commitOnClose)
      throws SQLException {
    this(fp, oid, mode, conn, commitOnClose, MAX_LOWRITE_LENGTH);
  }

  /**
   * Opens a large object whose writes send at most {@code maxLowriteLength} bytes per
   * {@code lowrite}, so a test can exercise the splitting with a small array.
   *
   * @param maxLowriteLength largest number of bytes one {@code lowrite} call sends, from 1 to
   *        {@link #MAX_LOWRITE_LENGTH}
   * @throws IllegalArgumentException if {@code maxLowriteLength} is outside that range
   */
  LargeObject(Fastpath fp, long oid, int mode,
      @Nullable BaseConnection conn, boolean commitOnClose, int maxLowriteLength)
      throws SQLException {
    if (maxLowriteLength <= 0 || maxLowriteLength > MAX_LOWRITE_LENGTH) {
      throw new IllegalArgumentException("maxLowriteLength must be in [1, " + MAX_LOWRITE_LENGTH
          + "], got " + maxLowriteLength);
    }
    this.maxLowriteLength = maxLowriteLength;
    this.fp = fp;
    this.oid = oid;
    this.mode = mode;
    if (commitOnClose) {
      this.commitOnClose = true;
      this.conn = conn;
    } else {
      this.commitOnClose = false;
    }

    FastpathArg[] args = new FastpathArg[2];
    args[0] = Fastpath.createOIDArg(oid);
    args[1] = new FastpathArg(mode);
    this.fd = fp.getInteger("lo_open", args);
  }

  /**
   * This opens a large object.
   *
   * <p>If the object does not exist, then an SQLException is thrown.</p>
   *
   * @param fp FastPath API for the connection to use
   * @param oid of the Large Object to open
   * @param mode Mode of opening the large object (defined in LargeObjectManager)
   * @throws SQLException if a database-access error occurs.
   * @see org.postgresql.largeobject.LargeObjectManager
   */
  protected LargeObject(Fastpath fp, long oid, int mode) throws SQLException {
    this(fp, oid, mode, null, false);
  }

  public LargeObject copy() throws SQLException {
    checkClosed();
    return new LargeObject(fp, oid, mode);
  }

  /**
   * @return the OID of this LargeObject
   * @deprecated As of 8.3, replaced by {@link #getLongOID()}
   */
  @Deprecated
  public int getOID() {
    return (int) oid;
  }

  /**
   * @return the OID of this LargeObject
   */
  public long getLongOID() {
    return oid;
  }

  /**
   * This method closes the object. You must not call methods in this object after this is called.
   *
   * @throws SQLException if a database-access error occurs.
   */
  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    // Flush while still open: BlobOutputStream.flush() calls back into LargeObject.write(),
    // which would fail checkClosed() if the object were already marked closed.
    SQLException error = null;
    if (os != null) {
      try {
        // we can't call os.close() otherwise we go into an infinite loop!
        os.flush();
      } catch (IOException ioe) {
        error = new PSQLException(GT.tr("Exception flushing output stream"),
            PSQLState.DATA_ERROR, ioe);
      } finally {
        os = null;
      }
    }

    closed = true;

    // Always release the server-side descriptor, but do not let lo_close mask a flush failure.
    try {
      FastpathArg[] args = new FastpathArg[1];
      args[0] = new FastpathArg(fd);
      fp.fastpath("lo_close", args);
    } catch (SQLException e) {
      if (error != null) {
        error.addSuppressed(e);
      } else {
        error = e;
      }
    }

    // Commit only when flush and lo_close both succeeded; never commit a half-written object.
    BaseConnection conn = this.conn;
    if (error == null && this.commitOnClose && conn != null) {
      conn.commit();
    }

    if (error != null) {
      throw error;
    }
  }

  /**
   * Reads some data from the object, and return as a byte[] array.
   *
   * @param len number of bytes to read
   * @return byte[] array containing data read
   * @throws SQLException if a database-access error occurs.
   */
  public byte[] read(int len) throws SQLException {
    checkClosed();
    // This is the original method, where the entire block (len bytes)
    // is retrieved in one go.
    FastpathArg[] args = new FastpathArg[2];
    args[0] = new FastpathArg(fd);
    args[1] = new FastpathArg(len);
    byte[] bytes = fp.getData("loread", args);
    if (bytes == null) {
      return EMPTY_BYTE_ARRAY;
    }
    return bytes;
  }

  /**
   * Reads some data from the object into an existing array.
   *
   * @param buf destination array
   * @param off offset within array
   * @param len number of bytes to read
   * @return the number of bytes actually read
   * @throws SQLException if a database-access error occurs.
   */
  public int read(byte[] buf, int off, int len) throws SQLException {
    checkClosed();
    byte[] b = read(len);
    if (b.length == 0) {
      return 0;
    }
    len = Math.min(len, b.length);
    System.arraycopy(b, 0, buf, off, len);
    return len;
  }

  /**
   * Writes an array to the object.
   *
   * <p>An array longer than {@value #MAX_LOWRITE_LENGTH} bytes goes out in several {@code lowrite}
   * calls, because the server refuses a longer one. If one of them fails, the bytes the earlier
   * calls sent stay written.</p>
   *
   * @param buf array to write
   * @throws SQLException if a database-access error occurs.
   */
  public void write(byte[] buf) throws SQLException {
    checkClosed();
    writeInPieces(buf, 0, buf.length);
  }

  /**
   * Writes some data from an array to the object.
   *
   * <p>More than {@value #MAX_LOWRITE_LENGTH} bytes go out in several {@code lowrite} calls,
   * because the server refuses a longer one. If one of them fails, the bytes the earlier calls sent
   * stay written.</p>
   *
   * @param buf destination array
   * @param off offset within array
   * @param len number of bytes to write
   * @throws SQLException if a database-access error occurs.
   */
  public void write(byte[] buf, int off, int len) throws SQLException {
    checkClosed();
    writeInPieces(buf, off, len);
  }

  private void writeInPieces(byte[] buf, int off, int len) throws SQLException {
    // Every lowrite advances the descriptor's position, so the pieces land back to back.
    while (len > maxLowriteLength) {
      lowrite(new FastpathArg(buf, off, maxLowriteLength));
      off += maxLowriteLength;
      len -= maxLowriteLength;
    }
    lowrite(new FastpathArg(buf, off, len));
  }

  /**
   * Writes some data from a given writer to the object.
   *
   * <p>A writer whose {@link ByteStreamWriter#getLength() length} is at most
   * {@value #MAX_LOWRITE_LENGTH} bytes streams into one {@code lowrite} call. A longer writer goes
   * out in several calls, because the server refuses a longer call: its output is copied into a
   * buffer of up to {@value #WRITER_PIECE_SIZE} bytes, and each full buffer is sent as one call.</p>
   *
   * <p>A writer that writes fewer bytes than its length is padded with zero bytes. A writer that
   * writes more gets an {@link IOException} from its target stream.</p>
   *
   * @param writer the source of the data to write
   * @throws SQLException if a database-access error occurs. When the writer throws an
   *         {@link IOException} while streaming into one call, the driver closes the connection.
   *         When it throws while its output goes out in several calls, the connection stays open,
   *         the exception has SQLState {@code 22000} and the {@code IOException} as its cause, and
   *         the bytes the earlier calls sent stay written, as they do when one of those calls
   *         fails.
   */
  public void write(ByteStreamWriter writer) throws SQLException {
    checkClosed();
    int length = writer.getLength();
    if (length <= maxLowriteLength) {
      lowrite(FastpathArg.of(writer));
      return;
    }
    LowriteOutputStream out =
        new LowriteOutputStream(length, Math.min(WRITER_PIECE_SIZE, maxLowriteLength));
    try {
      writer.writeTo(() -> out);
      out.finish();
    } catch (IOException e) {
      SQLException lowriteFailure = out.lowriteFailure;
      if (lowriteFailure != null) {
        throw lowriteFailure;
      }
      throw new PSQLException(
          GT.tr("Can not write data to large object {0}, requested write length: {1}",
              oid, length),
          PSQLState.DATA_ERROR, e);
    }
  }

  private void lowrite(FastpathArg data) throws SQLException {
    FastpathArg[] args = new FastpathArg[2];
    args[0] = new FastpathArg(fd);
    args[1] = data;
    fp.fastpath("lowrite", args);
  }

  /**
   * Sends the bytes written to it as {@code lowrite} calls of {@code piece.length} bytes, and the
   * remainder as a shorter one.
   */
  private final class LowriteOutputStream extends OutputStream {
    private final int length;
    private final byte[] piece;
    private int pieceLength;
    private int remaining;
    /**
     * The failure of a {@code lowrite} call. Once set, every write throws, because the object no
     * longer receives the bytes in order.
     */
    @Nullable SQLException lowriteFailure;

    LowriteOutputStream(int length, int pieceSize) {
      this.length = length;
      this.piece = new byte[pieceSize];
      this.remaining = length;
    }

    @Override
    public void write(int b) throws IOException {
      reserve(1);
      piece[pieceLength++] = (byte) b;
      sendIfFull();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (off < 0 || len < 0 || len > b.length - off) {
        throw new IndexOutOfBoundsException();
      }
      reserve(len);
      while (len > 0) {
        int n = Math.min(len, piece.length - pieceLength);
        System.arraycopy(b, off, piece, pieceLength, n);
        pieceLength += n;
        off += n;
        len -= n;
        sendIfFull();
      }
    }

    /**
     * Pads the output with zero bytes up to {@code length}, as {@code PGStream.send} pads a
     * {@link ByteStreamWriter} that streams into one message, and sends what is left.
     */
    void finish() throws IOException {
      checkNoLowriteFailure();
      while (remaining > 0) {
        int n = Math.min(remaining, piece.length - pieceLength);
        Arrays.fill(piece, pieceLength, pieceLength + n, (byte) 0);
        pieceLength += n;
        remaining -= n;
        sendIfFull();
      }
      if (pieceLength > 0) {
        send();
      }
    }

    private void checkNoLowriteFailure() throws IOException {
      if (lowriteFailure != null) {
        throw new IOException("A previous lowrite failed", lowriteFailure);
      }
    }

    private void reserve(int len) throws IOException {
      checkNoLowriteFailure();
      if (len > remaining) {
        throw new IOException("Attempt to write more than the specified " + length + " bytes");
      }
      remaining -= len;
    }

    private void sendIfFull() throws IOException {
      if (pieceLength == piece.length) {
        send();
      }
    }

    private void send() throws IOException {
      try {
        lowrite(new FastpathArg(piece, 0, pieceLength));
      } catch (SQLException e) {
        lowriteFailure = e;
        throw new IOException(e);
      }
      pieceLength = 0;
    }
  }

  /**
   * Sets the current position within the object.
   *
   * <p>This is similar to the fseek() call in the standard C library. It allows you to have random
   * access to the large object.</p>
   *
   * @param pos position within object
   * @param ref Either SEEK_SET, SEEK_CUR or SEEK_END
   * @throws SQLException if a database-access error occurs.
   */
  public void seek(int pos, int ref) throws SQLException {
    checkClosed();
    FastpathArg[] args = new FastpathArg[3];
    args[0] = new FastpathArg(fd);
    args[1] = new FastpathArg(pos);
    args[2] = new FastpathArg(ref);
    fp.fastpath("lo_lseek", args);
  }

  /**
   * Sets the current position within the object using 64-bit value (9.3+).
   *
   * @param pos position within object
   * @param ref Either SEEK_SET, SEEK_CUR or SEEK_END
   * @throws SQLException if a database-access error occurs.
   */
  public void seek64(long pos, int ref) throws SQLException {
    checkClosed();
    FastpathArg[] args = new FastpathArg[3];
    args[0] = new FastpathArg(fd);
    args[1] = new FastpathArg(pos);
    args[2] = new FastpathArg(ref);
    fp.fastpath("lo_lseek64", args);
  }

  /**
   * Sets the current position within the object.
   *
   * <p>This is similar to the fseek() call in the standard C library. It allows you to have random
   * access to the large object.</p>
   *
   * @param pos position within object from beginning
   * @throws SQLException if a database-access error occurs.
   */
  public void seek(int pos) throws SQLException {
    checkClosed();
    seek(pos, SEEK_SET);
  }

  /**
   * @return the current position within the object
   * @throws SQLException if a database-access error occurs.
   */
  public int tell() throws SQLException {
    checkClosed();
    FastpathArg[] args = new FastpathArg[1];
    args[0] = new FastpathArg(fd);
    return fp.getInteger("lo_tell", args);
  }

  /**
   * @return the current position within the object
   * @throws SQLException if a database-access error occurs.
   */
  public long tell64() throws SQLException {
    checkClosed();
    FastpathArg[] args = new FastpathArg[1];
    args[0] = new FastpathArg(fd);
    return fp.getLong("lo_tell64", args);
  }

  /**
   * Reports whether the server supports the 64-bit large object functions {@code lo_tell64} and
   * {@code lo_lseek64}, added in PostgreSQL 9.3. Callers can use this to choose the 64-bit
   * functions by version rather than calling them and recovering from the failure.
   *
   * @return {@code true} if the server is 9.3 or newer
   */
  boolean supports64BitOffsets() {
    return fp.getServerVersionNum() >= 90300;
  }

  /**
   * This method is inefficient, as the only way to find out the size of the object is to seek to
   * the end, record the current position, then return to the original position.
   *
   * <p>A better method will be found in the future.</p>
   *
   * @return the size of the large object
   * @throws SQLException if a database-access error occurs.
   */
  public int size() throws SQLException {
    checkClosed();
    int cp = tell();
    seek(0, SEEK_END);
    int sz = tell();
    seek(cp, SEEK_SET);
    return sz;
  }

  /**
   * See #size() for information about efficiency.
   *
   * @return the size of the large object
   * @throws SQLException if a database-access error occurs.
   */
  public long size64() throws SQLException {
    checkClosed();
    long cp = tell64();
    seek64(0, SEEK_END);
    long sz = tell64();
    seek64(cp, SEEK_SET);
    return sz;
  }

  /**
   * Truncates the large object to the given length in bytes. If the number of bytes is larger than
   * the current large object length, the large object will be filled with zero bytes. This method
   * does not modify the current file offset.
   *
   * @param len given length in bytes
   * @throws SQLException if something goes wrong
   */
  public void truncate(int len) throws SQLException {
    checkClosed();
    FastpathArg[] args = new FastpathArg[2];
    args[0] = new FastpathArg(fd);
    args[1] = new FastpathArg(len);
    fp.getInteger("lo_truncate", args);
  }

  /**
   * Truncates the large object to the given length in bytes. If the number of bytes is larger than
   * the current large object length, the large object will be filled with zero bytes. This method
   * does not modify the current file offset.
   *
   * @param len given length in bytes
   * @throws SQLException if something goes wrong
   */
  public void truncate64(long len) throws SQLException {
    checkClosed();
    FastpathArg[] args = new FastpathArg[2];
    args[0] = new FastpathArg(fd);
    args[1] = new FastpathArg(len);
    fp.getInteger("lo_truncate64", args);
  }

  /**
   * Returns an {@link InputStream} from this object.
   *
   * <p>This {@link InputStream} can then be used in any method that requires an InputStream.</p>
   *
   * @return {@link InputStream} from this object
   * @throws SQLException if a database-access error occurs.
   */
  public InputStream getInputStream() throws SQLException {
    checkClosed();
    return new BlobInputStream(this);
  }

  /**
   * Returns an {@link InputStream} from this object, that will limit the amount of data that is
   * visible.
   *
   * @param limit maximum number of bytes the resulting stream will serve
   * @return {@link InputStream} from this object
   * @throws SQLException if a database-access error occurs.
   */
  public InputStream getInputStream(long limit) throws SQLException {
    checkClosed();
    return new BlobInputStream(this, BlobInputStream.DEFAULT_MAX_BUFFER_SIZE, limit);
  }

  /**
   * Returns an {@link InputStream} from this object, that will limit the amount of data that is
   * visible.
   * Added mostly for testing
   *
   * @param bufferSize buffer size for the stream
   * @param limit maximum number of bytes the resulting stream will serve
   * @return {@link InputStream} from this object
   * @throws SQLException if a database-access error occurs.
   */
  public InputStream getInputStream(int bufferSize, long limit) throws SQLException {
    checkClosed();
    return new BlobInputStream(this, bufferSize, limit);
  }

  /**
   * Returns an {@link OutputStream} to this object.
   *
   * <p>This OutputStream can then be used in any method that requires an OutputStream.</p>
   *
   * @return {@link OutputStream} from this object
   * @throws SQLException if a database-access error occurs.
   */
  public OutputStream getOutputStream() throws SQLException {
    checkClosed();
    if (os == null) {
      os = new BlobOutputStream(this);
    }
    return os;
  }
}
