/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * <p>This class helps with URL encoding and decoding. UTF-8 encoding is used by default to make
 * encoding consistent across the driver, and encoding might be changed via {@code
 * postgresql.url.encoding} property</p>
 *
 * <p>Note: this should not be used outside of PostgreSQL source, this is not a public API of the
 * driver.</p>
 */
public final class URLCoder {
  private static final String ENCODING_FOR_URL =
      System.getProperty("postgresql.url.encoding", "UTF-8");

  /**
   * Decodes {@code x-www-form-urlencoded} string into Java string.
   *
   * @param encoded encoded value
   * @return decoded value
   * @see URLDecoder#decode(String, String)
   */
  public static String decode(String encoded) {
    try {
      return URLDecoder.decode(encoded, ENCODING_FOR_URL);
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(
          "Unable to decode URL entry via " + ENCODING_FOR_URL + ". This should not happen", e);
    }
  }

  /**
   * Encodes Java string into {@code x-www-form-urlencoded} format
   *
   * @param plain input value
   * @return encoded value
   * @see URLEncoder#encode(String, String)
   */
  public static String encode(String plain) {
    try {
      return URLEncoder.encode(plain, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(
          "Unable to encode URL entry via " + ENCODING_FOR_URL + ". This should not happen", e);
    }
  }
}
