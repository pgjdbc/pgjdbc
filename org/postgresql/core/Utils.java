/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core;

import java.sql.SQLException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.text.NumberFormat;
import java.text.ParsePosition;

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
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte aData : data) {
            sb.append(Integer.toHexString((aData >> 4) & 15));
            sb.append(Integer.toHexString(aData & 15));
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
     * @deprecated use {@link #escapeLiteral(StringBuilder, String, boolean)} instead
     */
    public static StringBuffer appendEscapedLiteral(StringBuffer sbuf, String value, boolean standardConformingStrings)
        throws SQLException
    {
        if (sbuf == null)
        {
            sbuf = new StringBuffer(value.length() * 11 / 10); // Add 10% for escaping.
        }
        doAppendEscapedLiteral(sbuf, value, standardConformingStrings);
        return sbuf;
    }

    /**
     * Escape the given literal <tt>value</tt> and append it to the string builder
     * <tt>sbuf</tt>. If <tt>sbuf</tt> is <tt>null</tt>, a new StringBuilder will be
     * returned. The argument <tt>standardConformingStrings</tt> defines whether the
     * backend expects standard-conforming string literals or allows backslash
     * escape sequences.
     * 
     * @param sbuf the string builder to append to; or <tt>null</tt>
     * @param value the string value
     * @param standardConformingStrings
     * @return the sbuf argument; or a new string builder for sbuf == null
     * @throws SQLException if the string contains a <tt>\0</tt> character
     */
    public static StringBuilder escapeLiteral(StringBuilder sbuf, String value, boolean standardConformingStrings)
        throws SQLException
    {
        if (sbuf == null)
        {
            sbuf = new StringBuilder(value.length() * 11 / 10); // Add 10% for escaping.
        }
        doAppendEscapedLiteral(sbuf, value, standardConformingStrings);
        return sbuf;
    }

    /**
     * Common part for {@link #appendEscapedLiteral(StringBuffer, String, boolean)} and {@link #escapeLiteral(StringBuilder, String, boolean)}
     * @param sbuf Either StringBuffer or StringBuilder as we do not expect any IOException to be thrown
     * @param value
     * @param standardConformingStrings
     * @throws SQLException
     */
    private static void doAppendEscapedLiteral(Appendable sbuf, String value, boolean standardConformingStrings)
        throws SQLException
    {
        try
        {
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
        }
        catch (IOException e)
        {
            throw new PSQLException(GT.tr("No IOException expected from StringBuffer or StringBuilder"), PSQLState.UNEXPECTED_ERROR, e);
        }
    }

    /**
     * Escape the given identifier <tt>value</tt> and append it to the string buffer
     * <tt>sbuf</tt>. If <tt>sbuf</tt> is <tt>null</tt>, a new
     * StringBuffer will be returned.  This method is different from
     * appendEscapedLiteral in that it includes the quoting required for the
     * identifier while appendEscapedLiteral does not.
     * 
     * @param sbuf the string buffer to append to; or <tt>null</tt>
     * @param value the string value
     * @return the sbuf argument; or a new string buffer for sbuf == null
     * @throws SQLException if the string contains a <tt>\0</tt> character
     * @deprecated use {@link #escapeIdentifier(StringBuilder, String)} instead
     */
    public static StringBuffer appendEscapedIdentifier(StringBuffer sbuf, String value)
        throws SQLException
    {
        if (sbuf == null)
        {
            sbuf = new StringBuffer(2 + value.length() * 11 / 10); // Add 10% for escaping.
        }
        doAppendEscapedIdentifier(sbuf, value);
        return sbuf;
    }

    /**
     * Escape the given identifier <tt>value</tt> and append it to the string builder
     * <tt>sbuf</tt>. If <tt>sbuf</tt> is <tt>null</tt>, a new
     * StringBuilder will be returned.  This method is different from
     * appendEscapedLiteral in that it includes the quoting required for the
     * identifier while {@link #escapeLiteral(StringBuilder, String, boolean)} does not.
     * 
     * @param sbuf the string builder to append to; or <tt>null</tt>
     * @param value the string value
     * @return the sbuf argument; or a new string builder for sbuf == null
     * @throws SQLException if the string contains a <tt>\0</tt> character
     */
    public static StringBuilder escapeIdentifier(StringBuilder sbuf, String value)
        throws SQLException
    {
        if (sbuf == null)
        {
            sbuf = new StringBuilder(2 + value.length() * 11 / 10); // Add 10% for escaping.
        }
        doAppendEscapedIdentifier(sbuf, value);
        return sbuf;
    }

    /**
     * Common part for appendEscapedIdentifier
     * @param sbuf Either StringBuffer or StringBuilder as we do not expect any IOException to be thrown.
     * @param value
     * @throws SQLException
     */
    private static void doAppendEscapedIdentifier(Appendable sbuf, String value)
        throws SQLException
    {
        try
        {
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
        }
        catch (IOException e)
        {
            throw new PSQLException(GT.tr("No IOException expected from StringBuffer or StringBuilder"), PSQLState.UNEXPECTED_ERROR, e);
        }
    }

    /**
     * Attempt to parse the server version string into an XXYYZZ form version number.
     *
     * Returns 0 if the version could not be parsed.
     *
     * Returns minor version 0 if the minor version could not be determined, e.g. devel
     * or beta releases.
     *
     * If a single major part like 90400 is passed, it's assumed to be a pre-parsed
	 * version and returned verbatim. (Anything equal to or greater than 10000
	 * is presumed to be this form).
     *
	 * The yy or zz version parts may be larger than 99. A
	 * NumberFormatException is thrown if a version part is out of range.
     */
    public static int parseServerVersionStr(String serverVersion)
		throws NumberFormatException
 	{
        int vers;
        NumberFormat numformat = NumberFormat.getIntegerInstance();
		numformat.setGroupingUsed(false);
        ParsePosition parsepos = new ParsePosition(0);
        Long parsed;

        if (serverVersion == null)
            return 0;

        /* Get first major version part */
        parsed = (Long) numformat.parseObject(serverVersion, parsepos);
        if (parsed == null) {
            return 0;
        }
        if (parsed.intValue() >= 10000)
        {
            /*
             * PostgreSQL version 1000? I don't think so. We're seeing a version like
             * 90401; return it verbatim, but only if there's nothing else in the version.
             * If there is, treat it as a parse error.
             */
			if (parsepos.getIndex() == serverVersion.length())
				return parsed.intValue();
			else
				throw new NumberFormatException("First major-version part equal to or greater than 10000 in invalid version string: " + serverVersion);
        }

        vers = parsed.intValue() * 10000;

		/* Did we run out of string? */
		if (parsepos.getIndex() == serverVersion.length())
			return 0;

        /* Skip the . */
        if (serverVersion.charAt(parsepos.getIndex()) == '.')
            parsepos.setIndex(parsepos.getIndex() + 1);
        else
            /* Unexpected version format */
            return 0;

        /*
         * Get second major version part. If this isn't purely an integer,
         * accept the integer part and return with a minor version of zero,
         * so we cope with 8.1devel, etc.
         */
        parsed = (Long) numformat.parseObject(serverVersion, parsepos);
        if (parsed == null) {
            /*
             * Failed to parse second part of minor version at all. Half
             * a major version is useless, return 0.
             */
            return 0;
        }
		if (parsed.intValue() > 99)
			throw new NumberFormatException("Unsupported second part of major version > 99 in invalid version string: " + serverVersion);
        vers = vers + parsed.intValue() * 100;

		/* Did we run out of string? Return just the major. */
		if (parsepos.getIndex() == serverVersion.length())
			return vers;

        /* Skip the . */
        if (serverVersion.charAt(parsepos.getIndex()) == '.')
            parsepos.setIndex(parsepos.getIndex() + 1);
        else
            /* Doesn't look like an x.y.z version, return what we have */
            return vers;

        /* Try to parse any remainder as a minor version */
        parsed = (Long) numformat.parseObject(serverVersion, parsepos);
        if (parsed != null) {
			if (parsed.intValue() > 99)
				throw new NumberFormatException("Unsupported minor version value > 99 in invalid version string: " + serverVersion);
            vers = vers + parsed.intValue();
        }

        return vers;
    }
}
