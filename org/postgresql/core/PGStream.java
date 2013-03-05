/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;

import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLException;

/**
 * Wrapper around the raw connection to the server that implements some basic
 * primitives (reading/writing formatted data, doing string encoding, etc).
 *<p>
 * In general, instances of PGStream are not threadsafe; the caller must ensure
 * that only one thread at a time is accessing a particular PGStream instance.
 */
public class PGStream
{
    private final HostSpec hostSpec;

    private final byte[] _int4buf;
    private final byte[] _int2buf;

    private Socket connection;
    private VisibleBufferedInputStream pg_input;
    private OutputStream pg_output;
    private byte[] streamBuffer;

    private Encoding encoding;
    private Writer encodingWriter;

    /**
     * Constructor:  Connect to the PostgreSQL back end and return
     * a stream connection.
     *
     * @param hostSpec the host and port to connect to
     * @exception IOException if an IOException occurs below it.
     */
    public PGStream(HostSpec hostSpec) throws IOException
    {
        this.hostSpec = hostSpec;

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(hostSpec.getHost(), hostSpec.getPort()));
        changeSocket(socket);
        setEncoding(Encoding.getJVMEncoding("US-ASCII"));

        _int2buf = new byte[2];
        _int4buf = new byte[4];
    }

    public HostSpec getHostSpec() {
        return hostSpec;
    }

    public Socket getSocket() {
        return connection;
    }

    /**
     * Check for pending backend messages without blocking.
     * Might return false when there actually are messages
     * waiting, depending on the characteristics of the
     * underlying socket. This is used to detect asynchronous
     * notifies from the backend, when available.
     *
     * @return true if there is a pending backend message
     */
    public boolean hasMessagePending() throws IOException {
        return pg_input.available() > 0 || connection.getInputStream().available() > 0;
    }

    /**
     * Switch this stream to using a new socket. Any existing socket
     * is <em>not</em> closed; it's assumed that we are changing to
     * a new socket that delegates to the original socket (e.g. SSL).
     *
     * @param socket the new socket to change to
     * @throws IOException if something goes wrong
     */
    public void changeSocket(Socket socket) throws IOException {
        this.connection = socket;

        // Submitted by Jason Venner <jason@idiom.com>. Disable Nagle
        // as we are selective about flushing output only when we
        // really need to.
        connection.setTcpNoDelay(true);

        // Buffer sizes submitted by Sverre H Huseby <sverrehu@online.no>
        pg_input = new VisibleBufferedInputStream(connection.getInputStream(), 8192);
        pg_output = new BufferedOutputStream(connection.getOutputStream(), 8192);

        if (encoding != null)
            setEncoding(encoding);
    }

    public Encoding getEncoding() {
        return encoding;
    }

    /**
     * Change the encoding used by this connection.
     *
     * @param encoding the new encoding to use
     * @throws IOException if something goes wrong
     */
    public void setEncoding(Encoding encoding) throws IOException {
        // Close down any old writer.
        if (encodingWriter != null)
            encodingWriter.close();

        this.encoding = encoding;

        // Intercept flush() downcalls from the writer; our caller
        // will call PGStream.flush() as needed.
        OutputStream interceptor = new FilterOutputStream(pg_output) {
                                       public void flush() throws IOException {
                                       }
                                       public void close() throws IOException {
                                           super.flush();
                                       }
                                   };

        encodingWriter = encoding.getEncodingWriter(interceptor);
    }

    /**
     * Get a Writer instance that encodes directly onto the underlying stream.
     *<p>
     * The returned Writer should not be closed, as it's a shared object.
     * Writer.flush needs to be called when switching between use of the Writer and
     * use of the PGStream write methods, but it won't actually flush output
     * all the way out -- call {@link #flush} to actually ensure all output
     * has been pushed to the server.
     *
     * @return the shared Writer instance
     * @throws IOException if something goes wrong.
     */
    public Writer getEncodingWriter() throws IOException {
        if (encodingWriter == null)
            throw new IOException("No encoding has been set on this connection");
        return encodingWriter;
    }

    /**
     * Sends a single character to the back end
     *
     * @param val the character to be sent
     * @exception IOException if an I/O error occurs
     */
    public void SendChar(int val) throws IOException
    {
        pg_output.write(val);
    }

    /**
     * Sends a 4-byte integer to the back end
     *
     * @param val the integer to be sent
     * @exception IOException if an I/O error occurs
     */
    public void SendInteger4(int val) throws IOException
    {
        _int4buf[0] = (byte)(val >>> 24);
        _int4buf[1] = (byte)(val >>> 16);
        _int4buf[2] = (byte)(val >>> 8);
        _int4buf[3] = (byte)(val);
        pg_output.write(_int4buf);
    }

    /**
     * Sends a 2-byte integer (short) to the back end
     *
     * @param val the integer to be sent
     * @exception IOException if an I/O error occurs or <code>val</code> cannot be encoded in 2 bytes
     */
    public void SendInteger2(int val) throws IOException
    {
        if (val < Short.MIN_VALUE || val > Short.MAX_VALUE)
            throw new IOException("Tried to send an out-of-range integer as a 2-byte value: " + val);

        _int2buf[0] = (byte)(val >>> 8);
        _int2buf[1] = (byte)val;
        pg_output.write(_int2buf);
    }

    /**
     * Send an array of bytes to the backend
     *
     * @param buf The array of bytes to be sent
     * @exception IOException if an I/O error occurs
     */
    public void Send(byte buf[]) throws IOException
    {
        pg_output.write(buf);
    }

    /**
     * Send a fixed-size array of bytes to the backend. If buf.length < siz,
     * pad with zeros. If buf.lengh > siz, truncate the array.
     *
     * @param buf the array of bytes to be sent
     * @param siz the number of bytes to be sent
     * @exception IOException if an I/O error occurs
     */
    public void Send(byte buf[], int siz) throws IOException
    {
        Send(buf, 0, siz);
    }

    /**
     * Send a fixed-size array of bytes to the backend. If length < siz,
     * pad with zeros. If length > siz, truncate the array.
     *
     * @param buf the array of bytes to be sent
     * @param off offset in the array to start sending from
     * @param siz the number of bytes to be sent
     * @exception IOException if an I/O error occurs
     */
    public void Send(byte buf[], int off, int siz) throws IOException
    {
	int bufamt = buf.length - off;
        pg_output.write(buf, off, bufamt < siz ? bufamt : siz);
        for (int i = bufamt ; i < siz ; ++i)
        {
            pg_output.write(0);
        }
    }

    /**
     * Receives a single character from the backend, without
     * advancing the current protocol stream position.
     *
     * @return the character received
     * @exception IOException if an I/O Error occurs
     */
    public int PeekChar() throws IOException
    {
        int c = pg_input.peek();
        if (c < 0)
            throw new EOFException();
        return c;
    }

    /**
     * Receives a single character from the backend
     *
     * @return the character received
     * @exception IOException if an I/O Error occurs
     */
    public int ReceiveChar() throws IOException
    {
        int c = pg_input.read();
        if (c < 0)
            throw new EOFException();
        return c;
    }

    /**
     * Receives a four byte integer from the backend
     *
     * @return the integer received from the backend
     * @exception IOException if an I/O error occurs
     */
    public int ReceiveInteger4() throws IOException
    {
        if (pg_input.read(_int4buf) != 4)
            throw new EOFException();

        return (_int4buf[0] & 0xFF) << 24 | (_int4buf[1] & 0xFF) << 16 | (_int4buf[2] & 0xFF) << 8 | _int4buf[3] & 0xFF;
    }

    /**
     * Receives a two byte integer from the backend
     *
     * @return the integer received from the backend
     * @exception IOException if an I/O error occurs
     */
    public int ReceiveInteger2() throws IOException
    {
        if (pg_input.read(_int2buf) != 2)
            throw new EOFException();

        return (_int2buf[0] & 0xFF) << 8 | _int2buf[1] & 0xFF;
    }

    /**
     * Receives a fixed-size string from the backend.
     *
     * @param len the length of the string to receive, in bytes.
     * @return the decoded string
     */
    public String ReceiveString(int len) throws IOException {
        if (!pg_input.ensureBytes(len)) {
            throw new EOFException();
        }

        String res = encoding.decode(pg_input.getBuffer(), pg_input.getIndex(),
                                     len);
        pg_input.skip(len);
        return res;
    }

    /**
     * Receives a null-terminated string from the backend. If we don't see a
     * null, then we assume something has gone wrong.
     *
     * @return string from back end
     * @exception IOException if an I/O error occurs, or end of file
     */
    public String ReceiveString() throws IOException
    {
        int len = pg_input.scanCStringLength();
        String res = encoding.decode(pg_input.getBuffer(), pg_input.getIndex(),
                                     len - 1);
        pg_input.skip(len);
        return res;
    }

    /**
     * Read a tuple from the back end. A tuple is a two dimensional
     * array of bytes. This variant reads the V3 protocol's tuple
     * representation.
     *
     * @return null if the current response has no more tuples, otherwise
     * an array of bytearrays
     * @exception IOException if a data I/O error occurs
     */
    public byte[][] ReceiveTupleV3() throws IOException, OutOfMemoryError
    {
        //TODO: use l_msgSize
        int l_msgSize = ReceiveInteger4();
        int i;
        int l_nf = ReceiveInteger2();
        byte[][] answer = new byte[l_nf][];

        OutOfMemoryError oom = null;
        for (i = 0 ; i < l_nf ; ++i)
        {
            int l_size = ReceiveInteger4();
            if (l_size != -1) {
                try {
                    answer[i] = new byte[l_size];
                    Receive(answer[i], 0, l_size);
                } catch(OutOfMemoryError oome) {
                    oom = oome;
                    Skip(l_size);
                }
            }
        }

        if (oom != null)
            throw oom;

        return answer;
    }

    /**
     * Read a tuple from the back end. A tuple is a two dimensional
     * array of bytes. This variant reads the V2 protocol's tuple
     * representation.
     *
     * @param nf the number of fields expected
     * @param bin true if the tuple is a binary tuple
     * @return null if the current response has no more tuples, otherwise
     * an array of bytearrays
     * @exception IOException if a data I/O error occurs
     */
    public byte[][] ReceiveTupleV2(int nf, boolean bin) throws IOException, OutOfMemoryError
    {
        int i, bim = (nf + 7) / 8;
        byte[] bitmask = Receive(bim);
        byte[][] answer = new byte[nf][];

        int whichbit = 0x80;
        int whichbyte = 0;

        OutOfMemoryError oom = null;
        for (i = 0 ; i < nf ; ++i)
        {
            boolean isNull = ((bitmask[whichbyte] & whichbit) == 0);
            whichbit >>= 1;
            if (whichbit == 0)
            {
                ++whichbyte;
                whichbit = 0x80;
            }
            if (!isNull)
            {
                int len = ReceiveInteger4();
                if (!bin)
                    len -= 4;
                if (len < 0)
                    len = 0;
                try {
                    answer[i] = new byte[len];
                    Receive(answer[i], 0, len);
                } catch(OutOfMemoryError oome) {
                    oom = oome;
                    Skip(len);
                }
            }
        }

        if (oom != null)
            throw oom;

        return answer;
    }

    /**
     * Reads in a given number of bytes from the backend
     *
     * @param siz number of bytes to read
     * @return array of bytes received
     * @exception IOException if a data I/O error occurs
     */
    public byte[] Receive(int siz) throws IOException
    {
        byte[] answer = new byte[siz];
        Receive(answer, 0, siz);
        return answer;
    }

    /**
     * Reads in a given number of bytes from the backend
     *
     * @param buf buffer to store result
     * @param off offset in buffer
     * @param siz number of bytes to read
     * @exception IOException if a data I/O error occurs
     */
    public void Receive(byte[] buf, int off, int siz) throws IOException
    {
        int s = 0;

        while (s < siz)
        {
            int w = pg_input.read(buf, off + s, siz - s);
            if (w < 0)
                throw new EOFException();
            s += w;
        }
    }

    public void Skip(int size) throws IOException {
        long s = 0;
        while (s < size) {
            s += pg_input.skip(size - s);
        }
    }


    /**
     * Copy data from an input stream to the connection.
     *
     * @param inStream the stream to read data from
     * @param remaining the number of bytes to copy
     */
    public void SendStream(InputStream inStream, int remaining) throws IOException {
        int expectedLength = remaining;
        if (streamBuffer == null)
            streamBuffer = new byte[8192];

        while (remaining > 0)
        {
            int count = (remaining > streamBuffer.length ? streamBuffer.length : remaining);
            int readCount;

            try
            {
                readCount = inStream.read(streamBuffer, 0, count);
                if (readCount < 0)
                    throw new EOFException(GT.tr("Premature end of input stream, expected {0} bytes, but only read {1}.", new Object[]{new Integer(expectedLength), new Integer(expectedLength - remaining)}));
            }
            catch (IOException ioe)
            {
                while (remaining > 0)
                {
                    Send(streamBuffer, count);
                    remaining -= count;
                    count = (remaining > streamBuffer.length ? streamBuffer.length : remaining);
                }
                throw new PGBindException(ioe);
            }

            Send(streamBuffer, readCount);
            remaining -= readCount;
        }
    }



    /**
     * Flush any pending output to the backend.
     * @exception IOException if an I/O error occurs
     */
    public void flush() throws IOException
    {
        if (encodingWriter != null)
            encodingWriter.flush();
        pg_output.flush();
    }

    /**
     * Consume an expected EOF from the backend
     * @exception SQLException if we get something other than an EOF
     */
    public void ReceiveEOF() throws SQLException, IOException
    {
        int c = pg_input.read();
        if (c < 0)
            return;
        throw new PSQLException(GT.tr("Expected an EOF from server, got: {0}", new Integer(c)), PSQLState.COMMUNICATION_ERROR);
    }

    /**
     * Closes the connection
     *
     * @exception IOException if an I/O Error occurs
     */
    public void close() throws IOException
    {
        if (encodingWriter != null)
            encodingWriter.close();

        pg_output.close();
        pg_input.close();
        connection.close();
    }
}
