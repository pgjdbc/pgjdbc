/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/PGStream.java,v 1.15 2005/04/20 00:10:58 oliver Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.io.Writer;
import java.net.Socket;
import java.sql.*;

import org.postgresql.util.GT;

/**
 * Wrapper around the raw connection to the server that implements some basic
 * primitives (reading/writing formatted data, doing string encoding, etc).
 *<p>
 * In general, instances of PGStream are not threadsafe; the caller must ensure
 * that only one thread at a time is accessing a particular PGStream instance.
 */
public class PGStream
{
    private final String host;
    private final int port;

    private Socket connection;
    private InputStream pg_input;
    private OutputStream pg_output;
    private byte[] streamBuffer;

    private Encoding encoding;
    private Writer encodingWriter;

    /**
     * Constructor:  Connect to the PostgreSQL back end and return
     * a stream connection.
     *
     * @param host the hostname to connect to
     * @param port the port number that the postmaster is sitting on
     * @exception IOException if an IOException occurs below it.
     */
    public PGStream(String host, int port) throws IOException
    {
        this.host = host;
        this.port = port;

        changeSocket(new Socket(host, port));
        setEncoding(Encoding.getJVMEncoding("US-ASCII"));
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
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
        pg_input = new BufferedInputStream(connection.getInputStream(), 8192);
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
        pg_output.write((byte)val);
    }

    /**
     * Sends a 4-byte integer to the back end
     *
     * @param val the integer to be sent
     * @exception IOException if an I/O error occurs
     */
    public void SendInteger4(int val) throws IOException
    {
        SendChar((val >> 24)&255);
        SendChar((val >> 16)&255);
        SendChar((val >> 8)&255);
        SendChar(val&255);
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

        SendChar((val >> 8)&255);
        SendChar(val&255);
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
        int i;

        pg_output.write(buf, off, ((buf.length - off) < siz ? (buf.length - off) : siz));
        if ((buf.length - off) < siz)
        {
            for (i = buf.length - off ; i < siz ; ++i)
            {
                pg_output.write(0);
            }
        }
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
     * Receives an integer from the backend
     *
     * @param siz length of the integer in bytes
     * @return the integer received from the backend
     * @exception IOException if an I/O error occurs
     */
    public int ReceiveIntegerR(int siz) throws IOException
    {
        int n = 0;

        for (int i = 0 ; i < siz ; i++)
        {
            int b = pg_input.read();

            if (b < 0)
                throw new EOFException();
            n = b | (n << 8);
        }

        switch (siz) {
            case 1:
                return (int)((byte)n);
            case 2:
                return (int)((short)n);
            default:
                return n;
        }

    }

    private byte[] byte_buf = new byte[8*1024];

    /**
     * Receives a fixed-size string from the backend.
     *
     * @param len the length of the string to receive, in bytes.
     * @return the decoded string
     */
    public String ReceiveString(int len) throws IOException {
        if (len > byte_buf.length)
            byte_buf = new byte[len];

        Receive(byte_buf, 0, len);
        return encoding.decode(byte_buf, 0, len);
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
        int i = 0;
        byte[] rst = byte_buf;
        int buflen = rst.length;

        while (true)
        {
            int c = pg_input.read();

            if (c < 0)
                throw new EOFException();

            if (c == 0)
                break;

            if (i == buflen)
            {
                // Grow the buffer.
                buflen *= 2;     // 100% bigger
                if (buflen <= 0) // Watch for overflow
                    throw new IOException("Impossibly long string");

                byte[] newrst = new byte[buflen];
                System.arraycopy(rst, 0, newrst, 0, i);
                rst = newrst;
            }

            rst[i++] = (byte)c;
        }

        return encoding.decode(rst, 0, i);
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
    public byte[][] ReceiveTupleV3() throws IOException
    {
        //TODO: use l_msgSize
        int l_msgSize = ReceiveIntegerR(4);
        int i;
        int l_nf = ReceiveIntegerR(2);
        byte[][] answer = new byte[l_nf][];

        for (i = 0 ; i < l_nf ; ++i)
        {
            int l_size = ReceiveIntegerR(4);
            if (l_size != -1)
                answer[i] = Receive(l_size);
        }

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
    public byte[][] ReceiveTupleV2(int nf, boolean bin) throws IOException
    {
        int i, bim = (nf + 7) / 8;
        byte[] bitmask = Receive(bim);
        byte[][] answer = new byte[nf][];

        int whichbit = 0x80;
        int whichbyte = 0;

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
                int len = ReceiveIntegerR(4);
                if (!bin)
                    len -= 4;
                if (len < 0)
                    len = 0;
                answer[i] = Receive(len);
            }
        }
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
