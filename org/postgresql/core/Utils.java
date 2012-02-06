/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core;

import java.sql.SQLException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * Collection of utilities used by the protocol-level code.
 */
public class Utils {
    /**
     * Turn a bytearray into a printable form, representing
     * each byte in hex.
     *
     * @param data the bytearray to stringize
     * @return a hex-encoded printable representation of <code>data</code>
     */
    public static String toHexString(byte[] data) {
        StringBuffer sb = new StringBuffer(data.length * 2);
        for (int i = 0; i < data.length; ++i)
        {
            sb.append(Integer.toHexString((data[i] >> 4) & 15));
            sb.append(Integer.toHexString(data[i] & 15));
        }
        return sb.toString();
    }

    /**
     * Keep a local copy of the UTF-8 Charset so we can avoid
     * synchronization overhead from looking up the Charset by
     * name as String.getBytes(String) requires.
     */
    private final static Charset utf8Charset = Charset.forName("UTF-8");

    /**
     * Encode a string as UTF-8.
     *
     * @param str the string to encode
     * @return the UTF-8 representation of <code>str</code>
     */
    public static byte[] encodeUTF8(String str) {
        // Previously we just used str.getBytes("UTF-8"), but when
        // the JVM is using more than one encoding the lookup cost
        // makes that a loser to the below (even in the single thread case).
        // When multiple threads are doing Charset lookups, they all get
        // blocked and must wait, severely dropping throughput.
        //
        ByteBuffer buf = utf8Charset.encode(CharBuffer.wrap(str));
        byte b[] = new byte[buf.limit()];
        buf.get(b, 0, buf.limit());
        return b;
    }

    /**
     * Escape the given literal <tt>value</tt> and append it to the string buffer
     * <tt>sbuf</tt>. If <tt>sbuf</tt> is <tt>null</tt>, a new StringBuffer will be
     * returned. The argument <tt>standardConformingStrings</tt> defines whether the
     * backend expects standard-conforming string literals or allows backslash
     * escape sequences.
     * 
     * @param sbuf the string buffer to append to; or <tt>null</tt>
     * @param value the string value
     * @param standardConformingStrings
     * @return the sbuf argument; or a new string buffer for sbuf == null
     * @throws SQLException if the string contains a <tt>\0</tt> character
     */
    public static StringBuffer appendEscapedLiteral(StringBuffer sbuf, String value,
                                                   boolean standardConformingStrings)
                                                   throws SQLException {
        if (sbuf == null)
            sbuf = new StringBuffer(value.length() * 11 / 10); // Add 10% for escaping.
        
        if (standardConformingStrings)
        {
            // With standard_conforming_strings on, escape only single-quotes.
            for (int i = 0; i < value.length(); ++i)
            {
                char ch = value.charAt(i);
                if (ch == '\0')
                    throw new PSQLException(GT.tr("Zero bytes may not occur in string parameters."), PSQLState.INVALID_PARAMETER_VALUE);
                if (ch == '\'')
                    sbuf.append('\'');
                sbuf.append(ch);
            }
        }
        else
        {
            // With standard_conforming_string off, escape backslashes and
            // single-quotes, but still escape single-quotes by doubling, to
            // avoid a security hazard if the reported value of
            // standard_conforming_strings is incorrect, or an error if
            // backslash_quote is off.
            for (int i = 0; i < value.length(); ++i)
            {
                char ch = value.charAt(i);
                if (ch == '\0')
                    throw new PSQLException(GT.tr("Zero bytes may not occur in string parameters."), PSQLState.INVALID_PARAMETER_VALUE);
                if (ch == '\\' || ch == '\'')
                    sbuf.append(ch);
                sbuf.append(ch);
            }
        }
        
        return sbuf;
    }

    /**
     * Escape the given identifier <tt>value</tt> and append it to the string
     * buffer * <tt>sbuf</tt>. If <tt>sbuf</tt> is <tt>null</tt>, a new
     * StringBuffer will be returned.  This method is different from
     * appendEscapedLiteral in that it includes the quoting required for the
     * identifier while appendEscapedLiteral does not.
     * 
     * @param sbuf the string buffer to append to; or <tt>null</tt>
     * @param value the string value
     * @return the sbuf argument; or a new string buffer for sbuf == null
     * @throws SQLException if the string contains a <tt>\0</tt> character
     */
    public static StringBuffer appendEscapedIdentifier(StringBuffer sbuf, String value)
                                                   throws SQLException {
        if (sbuf == null)
            sbuf = new StringBuffer(2 + value.length() * 11 / 10); // Add 10% for escaping.

        sbuf.append('"');

        for (int i = 0; i < value.length(); ++i)
        {
            char ch = value.charAt(i);
            if (ch == '\0')
                throw new PSQLException(GT.tr("Zero bytes may not occur in identifiers."), PSQLState.INVALID_PARAMETER_VALUE);
            if (ch == '"')
                sbuf.append(ch);
            sbuf.append(ch);
        }

        sbuf.append('"');

        return sbuf;
    }
}
