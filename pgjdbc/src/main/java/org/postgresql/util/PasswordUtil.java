/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.core.Utils;

import com.ongres.scram.common.ScramFunctions;
import com.ongres.scram.common.ScramMechanisms;
import com.ongres.scram.common.bouncycastle.base64.Base64;
import com.ongres.scram.common.stringprep.StringPreparations;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

public class PasswordUtil {
  private static final int DEFAULT_ITERATIONS = 4096;
  private static final int DEFAULT_SALT_LENGTH = 16;

  private static class SecureRandomHolder {
    static final SecureRandom INSTANCE = new SecureRandom();
  }

  private static SecureRandom getSecureRandom() {
    return SecureRandomHolder.INSTANCE;
  }

  /**
   * Generate the encoded text representation of the given password for
   * SCRAM-SHA-256 authentication. The return value of this method is the literal
   * text that may be used when creating or modifying a user with the given
   * password without the surrounding single quotes.
   *
   * @param password   The plain text of the user's password. The implementation will zero out
   *                   the array after use
   * @param iterations The number of iterations of the hashing algorithm to
   *                   perform
   * @param salt       The random salt value
   * @return The text representation of the password encrypted for SCRAM-SHA-256
   *         authentication
   */
  public static String encodeScramSha256(char[] password, int iterations, byte[] salt) {
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(salt, "salt");
    if (iterations <= 0) {
      throw new IllegalArgumentException("iterations must be greater than zero");
    }
    if (salt.length == 0) {
      throw new IllegalArgumentException("salt length must be greater than zero");
    }
    try {
      String passwordText = String.valueOf(password);
      byte[] saltedPassword = ScramFunctions.saltedPassword(ScramMechanisms.SCRAM_SHA_256,
          StringPreparations.SASL_PREPARATION, passwordText, salt, iterations);
      byte[] clientKey = ScramFunctions.clientKey(ScramMechanisms.SCRAM_SHA_256, saltedPassword);
      byte[] storedKey = ScramFunctions.storedKey(ScramMechanisms.SCRAM_SHA_256, clientKey);
      byte[] serverKey = ScramFunctions.serverKey(ScramMechanisms.SCRAM_SHA_256, saltedPassword);

      return "SCRAM-SHA-256"
        + "$" + iterations
        + ":" + Base64.toBase64String(salt)
        + "$" + Base64.toBase64String(storedKey)
        + ":" + Base64.toBase64String(serverKey);
    } finally {
      Arrays.fill(password, (char) 0);
    }
  }

  /**
   * Encode the given password for SCRAM-SHA-256 authentication using the default
   * iteration count and a random salt.
   *
   * @param password The plain text of the user's password. The implementation will zero out the
   *                 array after use
   * @return The text representation of the password encrypted for SCRAM-SHA-256
   *         authentication
   */
  public static String encodeScramSha256(char[] password) {
    Objects.requireNonNull(password, "password");
    try {
      SecureRandom rng = getSecureRandom();
      byte[] salt = rng.generateSeed(DEFAULT_SALT_LENGTH);
      return encodeScramSha256(password, DEFAULT_ITERATIONS, salt);
    } finally {
      Arrays.fill(password, (char) 0);
    }
  }

  /**
   * Encode the given password for use with md5 authentication. The PostgreSQL
   * server uses the username as the per-user salt so that must also be provided.
   * The return value of this method is the literal text that may be used when
   * creating or modifying a user with the given password without the surrounding
   * single quotes.
   *
   * @param user     The username of the database user
   * @param password The plain text of the user's password. The implementation will zero out the
   *                 array after use
   * @return The text representation of the password encrypted for md5
   *         authentication.
   * @deprecated prefer {@link org.postgresql.PGConnection#alterUserPassword(String, char[], String)}
   *             or {@link #encodeScramSha256(char[])} for better security.
   */
  @Deprecated
  @SuppressWarnings("DeprecatedIsStillUsed")
  public static String encodeMd5(String user, char[] password) {
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(password, "password");
    ByteBuffer passwordBytes = null;
    try {
      passwordBytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
      byte[] userBytes = user.getBytes(StandardCharsets.UTF_8);
      final MessageDigest md = MessageDigest.getInstance("MD5");

      md.update(passwordBytes);
      md.update(userBytes);
      byte[] digest = md.digest(); // 16-byte MD5

      final byte[] encodedPassword = new byte[35]; // 3 + 2 x 16
      encodedPassword[0] = (byte) 'm';
      encodedPassword[1] = (byte) 'd';
      encodedPassword[2] = (byte) '5';
      MD5Digest.bytesToHex(digest, encodedPassword, 3);

      return new String(encodedPassword, StandardCharsets.UTF_8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Unable to encode password with MD5", e);
    } finally {
      Arrays.fill(password, (char) 0);
      if (passwordBytes != null) {
        if (passwordBytes.hasArray()) {
          Arrays.fill(passwordBytes.array(), (byte) 0);
        } else {
          int limit = passwordBytes.limit();
          for (int i = 0; i < limit; i++) {
            passwordBytes.put(i, (byte) 0);
          }
        }
      }
    }
  }

  /**
   * Encode the given password for the specified encryption type.
   * The word "encryption" is used here to match the verbiage in the PostgreSQL
   * server, i.e. the "password_encryption" setting. In reality, a cryptographic
   * digest / HMAC operation is being performed.
   * The database user is only required for the md5 encryption type.
   *
   * @param user           The username of the database user
   * @param password       The plain text of the user's password. The implementation will zero
   *                       out the array after use
   * @param encryptionType The encryption type for which to encode the user's
   *                       password. This should match the database's supported
   *                       methods and value of the password_encryption setting.
   * @return The encoded password
   * @throws SQLException If an error occurs encoding the password
   */
  public static String encodePassword(String user, char[] password, String encryptionType)
      throws SQLException {
    Objects.requireNonNull(password, "password");
    Objects.requireNonNull(encryptionType, "encryptionType");
    switch (encryptionType) {
      case "on":
      case "off":
      case "md5":
        return encodeMd5(user, password);
      case "scram-sha-256":
        return encodeScramSha256(password);
    }
    // If we get here then it's an unhandled encryption type so we must wipe the array ourselves
    Arrays.fill(password, (char) 0);
    throw new PSQLException("Unable to determine encryption type: " + encryptionType, PSQLState.SYSTEM_ERROR);
  }

  /**
   * Generate the SQL statement to alter a user's password using the given
   * encryption.
   * All other encryption settings for the password will use the driver's
   * defaults.
   *
   * @param user           The username of the database user
   * @param password       The plain text of the user's password. The implementation will zero
   *                       out the array after use
   * @param encryptionType The encryption type of the password
   * @return An SQL statement that may be executed to change the user's password
   * @throws SQLException If an error occurs encoding the password
   */
  public static String genAlterUserPasswordSQL(String user, char[] password, String encryptionType)
      throws SQLException {
    try {
      String encodedPassword = encodePassword(user, password, encryptionType);
      StringBuilder sb = new StringBuilder();
      sb.append("ALTER USER ");
      Utils.escapeIdentifier(sb, user);
      sb.append(" PASSWORD '");
      // The choice of true / false for standard conforming strings does not matter
      // here as the value being escaped is generated by us and known to be hex
      // characters for all of the implemented password encryption methods.
      Utils.escapeLiteral(sb, encodedPassword, true);
      sb.append("'");
      return sb.toString();
    } finally {
      Arrays.fill(password, (char) 0);
    }
  }
}
