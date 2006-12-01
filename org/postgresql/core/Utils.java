/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*        $PostgreSQL: pgjdbc/org/postgresql/core/Utils.java,v 1.4 2005/01/11 08:25:43 jurka Exp $
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core;

import java.sql.SQLException;

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
     * Encode a string as UTF-8.
     *
     * @param str the string to encode
     * @return the UTF-8 representation of <code>str</code>
     */
    public static byte[] encodeUTF8(String str) {
        // It turns out that under 1.4.2, at least, calling getBytes() is
        // faster than rolling our own converter (it uses direct buffers and, I suspect,
        // a native helper -- and the cost of the encoding lookup is mitigated by a
        // ThreadLocal that caches the last lookup). So we just do things the simple way here.
        try
        {
            return str.getBytes("UTF-8");
        }
        catch (java.io.UnsupportedEncodingException e)
        {
            // Javadoc says that UTF-8 *must* be supported by all JVMs, so we don't try to be clever here.
            throw new RuntimeException("Unexpected exception: UTF-8 charset not supported: " + e);
        }
    }
    
    /**
     * Escape the given string <tt>value</tt> and append it to the string buffer
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
    public static StringBuffer appendEscapedString(StringBuffer sbuf, String value,
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
}
