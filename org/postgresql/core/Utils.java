/*-------------------------------------------------------------------------
 *
 * Utils.java
 *        Collection of utilities used by the protocol-level code.
 *
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *        $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core;

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
		StringBuffer sb = new StringBuffer(data.length*2);
		for (int i = 0; i < data.length; ++i) {
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
		try {
			return str.getBytes("UTF-8");
		} catch (java.io.UnsupportedEncodingException e) {
			// Javadoc says that UTF-8 *must* be supported by all JVMs, so we don't try to be clever here.
			throw new RuntimeException("Unexpected exception: UTF-8 charset not supported: " + e);
		}
	}
}
