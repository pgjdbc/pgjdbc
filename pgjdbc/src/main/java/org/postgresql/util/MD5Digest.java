/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5-based utility function to obfuscate passwords before network transmission.
 *
 * @author Jeremy Wohl
 */
public class MD5Digest {
  private MD5Digest() {
  }

  /**
   * Encodes user/password/salt information in the following way: MD5(MD5(password + user) + salt).
   *
   * @param user The connecting user.
   * @param password The connecting user's password.
   * @param salt A four-salt sent by the server.
   * @return A 35-byte array, comprising the string "md5" and an MD5 digest.
   */
  public static byte[] encode(byte[] user, byte[] password, byte[] salt) {
    MessageDigest md;
    byte[] tempDigest;
    byte[] passDigest;
    byte[] hexDigest = new byte[35];

    try {
      md = MessageDigest.getInstance("MD5");

      md.update(password);
      md.update(user);
      tempDigest = md.digest();

      bytesToHex(tempDigest, hexDigest, 0);
      md.update(hexDigest, 0, 32);
      md.update(salt);
      passDigest = md.digest();

      bytesToHex(passDigest, hexDigest, 3);
      hexDigest[0] = (byte) 'm';
      hexDigest[1] = (byte) 'd';
      hexDigest[2] = (byte) '5';
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Unable to encode password with MD5", e);
    }

    return hexDigest;
  }

  /*
   * Turn 16-byte stream into a human-readable 32-byte hex string
   */
  private static void bytesToHex(byte[] bytes, byte[] hex, int offset) {
    final char[] lookup =
        {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};

    int i;
    int c;
    int j;
    int pos = offset;

    for (i = 0; i < 16; i++) {
      c = bytes[i] & 0xFF;
      j = c >> 4;
      hex[pos++] = (byte) lookup[j];
      j = (c & 0xF);
      hex[pos++] = (byte) lookup[j];
    }
  }
}
