/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2004, PostgreSQL Global Development Group
 *
 * IDENTIFICATION
 *	  $PostgreSQL: pgjdbc/org/postgresql/core/Encoding.java,v 1.17 2004/09/20 08:36:48 jurka Exp $
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.io.IOException;
import java.util.Hashtable;

/**
 * Representation of a particular character encoding.
 */
public class Encoding
{

	private static final Encoding DEFAULT_ENCODING = new Encoding(null);

	/*
	 * Preferred JVM encodings for backend encodings.
	 */
	private static final Hashtable encodings = new Hashtable();

	static {
		//Note: this list should match the set of supported server
		// encodings found in backend/util/mb/encnames.c
		encodings.put("SQL_ASCII", new String[] { "ASCII", "us-ascii" });
		encodings.put("UNICODE", new String[] { "UTF-8", "UTF8" });
		encodings.put("LATIN1", new String[] { "ISO8859_1" });
		encodings.put("LATIN2", new String[] { "ISO8859_2" });
		encodings.put("LATIN3", new String[] { "ISO8859_3" });
		encodings.put("LATIN4", new String[] { "ISO8859_4" });
		encodings.put("ISO_8859_5", new String[] { "ISO8859_5" });
		encodings.put("ISO_8859_6", new String[] { "ISO8859_6" });
		encodings.put("ISO_8859_7", new String[] { "ISO8859_7" });
		encodings.put("ISO_8859_8", new String[] { "ISO8859_8" });
		encodings.put("LATIN5", new String[] { "ISO8859_9" });
		encodings.put("LATIN7", new String[] { "ISO8859_13" });
		encodings.put("LATIN9", new String[] { "ISO8859_15_FDIS" });
		encodings.put("EUC_JP", new String[] { "EUC_JP" });
		encodings.put("EUC_CN", new String[] { "EUC_CN" });
		encodings.put("EUC_KR", new String[] { "EUC_KR" });
		encodings.put("JOHAB", new String[] { "Johab" });
		encodings.put("EUC_TW", new String[] { "EUC_TW" });
		encodings.put("SJIS", new String[] { "MS932", "SJIS" });
		encodings.put("BIG5", new String[] { "Big5", "MS950", "Cp950" });
		encodings.put("GBK", new String[] { "GBK", "MS936" });
		encodings.put("UHC", new String[] { "MS949", "Cp949", "Cp949C" });
		encodings.put("TCVN", new String[] { "Cp1258" });
		encodings.put("WIN1256", new String[] { "Cp1256" });
		encodings.put("WIN1250", new String[] { "Cp1250" });
		encodings.put("WIN874", new String[] { "MS874", "Cp874" });
		encodings.put("WIN", new String[] { "Cp1251" });
		encodings.put("ALT", new String[] { "Cp866" });
		// We prefer KOI8-U, since it is a superset of KOI8-R.
		encodings.put("KOI8", new String[] { "KOI8_U", "KOI8_R" });
		// If the database isn't encoding-aware then we can't have
		// any preferred encodings.
		encodings.put("UNKNOWN", new String[0]);
		// The following encodings do not have a java equivalent
		encodings.put("MULE_INTERNAL", new String[0]);
		encodings.put("LATIN6", new String[0]);
		encodings.put("LATIN8", new String[0]);
		encodings.put("LATIN10", new String[0]);
	}

	private final String encoding;
	private final boolean utf8;

	private Encoding(String encoding)
	{
		this.encoding = encoding;
		this.utf8 = (encoding != null && (encoding.equals("UTF-8") || encoding.equals("UTF8")));
	}

	/**
	 * Construct an Encoding for a given JVM encoding.
	 * 
	 * @param jvmEncoding the name of the JVM encoding
	 * @return an Encoding instance for the specified encoding,
	 *   or an Encoding instance for the default JVM encoding if the
	 *   specified encoding is unavailable.
	 */
	public static Encoding getJVMEncoding(String jvmEncoding) {
		if (isAvailable(jvmEncoding))
			return new Encoding(jvmEncoding);
		else
			return defaultEncoding();
	}

	/**
	 * Construct an Encoding for a given database encoding.
	 * 
	 * @param databaseEncoding the name of the database encoding
	 * @return an Encoding instance for the specified encoding,
	 *   or an Encoding instance for the default JVM encoding if the
	 *   specified encoding is unavailable.
	 */
	public static Encoding getDatabaseEncoding(String databaseEncoding)
	{
		// If the backend encoding is known and there is a suitable
		// encoding in the JVM we use that. Otherwise we fall back
		// to the default encoding of the JVM.

		if (encodings.containsKey(databaseEncoding))
		{
			String[] candidates = (String[]) encodings.get(databaseEncoding);
			for (int i = 0; i < candidates.length; i++)
			{
				if (isAvailable(candidates[i]))
				{
					return new Encoding(candidates[i]);
				}
			}
		}

		// Try the encoding name directly -- maybe the charset has been
		// provided by the user.
		if (isAvailable(databaseEncoding))
			return new Encoding(databaseEncoding);

		// Fall back to default JVM encoding.
		return defaultEncoding();
	}

	/**
	 * Get the name of the (JVM) encoding used.
	 *
	 * @return the JVM encoding name used by this instance.
	 */
	public String name()
	{
		return encoding;
	}

	/**
	 * Encode a string to an array of bytes.
	 *
	 * @param s the string to encode
	 * @return a bytearray containing the encoded string
	 * @throws IOException if something goes wrong
	 */
	public byte[] encode(String s) throws IOException
	{
		if (s == null)
			return null;

		if (encoding == null)
			return s.getBytes();

		return s.getBytes(encoding);
	}

	/**
	 * Decode an array of bytes into a string.
	 * 
	 * @param encodedString a bytearray containing the encoded string  the string to encod
	 * @param offset the offset in <code>encodedString</code> of the first byte of the encoded representation
	 * @param length the length, in bytes, of the encoded representation
	 * @return the decoded string
	 * @throws IOException if something goes wrong
	 */
	public String decode(byte[] encodedString, int offset, int length) throws IOException
	{
		if (encoding == null)
			return new String(encodedString, offset, length);

		if (utf8)
			return decodeUTF8(encodedString, offset, length);

		return new String(encodedString, offset, length, encoding);
	}

	/**
	 * Decode an array of bytes into a string.
	 *
	 * @param encodedString a bytearray containing the encoded string  the string to encod
	 * @return the decoded string
	 * @throws IOException if something goes wrong
	 */
	public String decode(byte[] encodedString) throws IOException
	{
		return decode(encodedString, 0, encodedString.length);
	}

	/**
	 * Get a Reader that decodes the given InputStream using this encoding.
	 *
	 * @param in the underlying stream to decode from
	 * @return a non-null Reader implementation.
	 * @throws IOException if something goes wrong
	 */
	public Reader getDecodingReader(InputStream in) throws IOException
	{
		if (encoding == null)
			return new InputStreamReader(in);

		return new InputStreamReader(in, encoding);
	}

	/**
	 * Get a Writer that encodes to the given OutputStream using this encoding.
	 *
	 * @param out the underlying stream to encode to
	 * @return a non-null Writer implementation.
	 * @throws IOException if something goes wrong
	 */
	public Writer getEncodingWriter(OutputStream out) throws IOException
	{
		if (encoding == null)
			return new OutputStreamWriter(out);

		return new OutputStreamWriter(out, encoding);
	}

	/**
	 * Get an Encoding using the default encoding for the JVM.
	 * @return an Encoding instance
	 */
	public static Encoding defaultEncoding()
	{
		return DEFAULT_ENCODING;
	}

	/**
	 * Test if an encoding is available in the JVM.
	 *
	 * @param encodingName the JVM encoding name to test
	 * @return true iff the encoding is supported
	 */
	private static boolean isAvailable(String encodingName)
	{
		try
		{
			"DUMMY".getBytes(encodingName);
			return true;
		}
		catch (java.io.UnsupportedEncodingException e)
		{
			return false;
		}
	}

	private char[] decoderArray = new char[1024];

 	/**
 	 * Custom byte[] -> String conversion routine for UTF-8 only.
	 * This is about 30% faster than using the String(byte[],int,int,String)
	 * ctor, at least under JDK 1.4.2.
	 *
	 * @param data the array containing UTF8-encoded data
	 * @param offset the offset of the first byte in <code>data</code> to decode from
	 * @param length the number of bytes to decode
	 * @return a decoded string
	 * @throws IOException if something goes wrong
 	 */
	private synchronized String decodeUTF8(byte[] data, int offset, int length) throws IOException {
		char[] cdata = decoderArray;
		if (cdata.length < length)
			cdata = decoderArray = new char[length];

		int in = offset;
		int out = 0;
		int end = length + offset;

		try {
			while (in < end) {
				int ch = data[in++] & 0xff;
				if (ch < 0x80) {
					// Length 1: \u00000 .. \u0007f
				} else if (ch < 0xe0) { 
					// Length 2: \u00080 .. \u007ff
					ch = ((ch & 0x1f) << 6);
					ch = ch | (data[in++] & 0x3f);
				} else {
					// Length 3: \u00800 .. \u0ffff
					ch = ((ch & 0x0f) << 12);
					ch = ch | ((data[in++] & 0x3f) << 6);
					ch = ch | (data[in++] & 0x3f);
				}
				cdata[out++] = (char)ch;
			}
		} catch (ArrayIndexOutOfBoundsException a) {
			throw new IOException("UTF-8 string representation was truncated");
		}

		// Check if we ran past the end without seeing an exception.
		if (in > end)
			throw new IOException("UTF-8 string representation was truncated");

		return new String(cdata, 0, out);
	}

	public String toString() {
		return (encoding == null ? "<default JVM encoding>" : encoding);
	}
}
