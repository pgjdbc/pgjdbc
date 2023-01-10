/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.util;

import java.io.InputStream;

/**
 * Wrapper around a length-limited InputStream.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public class StreamWrapper {
    public StreamWrapper(byte[] data, int offset, int length) {
        this.stream = null;
        this.rawData = data;
        this.offset = offset;
        this.length = length;
    }

    public StreamWrapper(InputStream stream, int length) {
        this.stream = stream;
        this.rawData = null;
        this.offset = 0;
        this.length = length;
    }

    public InputStream getStream() {
        if (stream != null)
            return stream;

        return new java.io.ByteArrayInputStream(rawData, offset, length);
    }

    public int getLength() {
        return length;
    }

    public int getOffset() {
        return offset;
    }

    public byte[] getBytes() {
        return rawData;
    }

    public String toString() {
        return "<stream of " + length + " bytes>";
    }

    private final InputStream stream;
    private final byte[] rawData;
    private final int offset;
    private final int length;
}
