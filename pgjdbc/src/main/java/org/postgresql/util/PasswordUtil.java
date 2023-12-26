/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.jdbc.PgConnection;

import com.ongres.scram.common.ScramFunctions;
import com.ongres.scram.common.ScramMechanisms;
import com.ongres.scram.common.bouncycastle.base64.Base64;
import com.ongres.scram.common.stringprep.StringPreparations;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class PasswordUtil {

  public static final String SCRAM_ENCRYPTION = "scram-sha-256";
  public static final String MD5 = "md5";

  private static final int SEED_LENGTH = 16;
  private static final int ITERATIONS = 4096;

  public static String encodeScram(String password, int seedLength, int iterations) {
    SecureRandom rng = new SecureRandom();
    byte[] salt = rng.generateSeed(seedLength);
    byte [] saltedPassword = ScramFunctions.saltedPassword(ScramMechanisms.SCRAM_SHA_256, StringPreparations.SASL_PREPARATION, password,salt, iterations);
    byte [] clientKey = ScramFunctions.clientKey(ScramMechanisms.SCRAM_SHA_256, saltedPassword);
    byte [] storedKey = ScramFunctions.storedKey(ScramMechanisms.SCRAM_SHA_256, clientKey);
    byte [] serverKey = ScramFunctions.serverKey(ScramMechanisms.SCRAM_SHA_256,saltedPassword);

    StringBuffer sb =  new StringBuffer("SCRAM-SHA-256").append('$')
        .append(iterations).append(':')
        .append(Base64.toBase64String(salt)).append('$')
        .append(Base64.toBase64String(storedKey)).append(':')
        .append(Base64.toBase64String(serverKey));
    return sb.toString();
  }

  public static String encodeScram(String password) {
    return encodeScram( password, SEED_LENGTH, ITERATIONS);
  }

  public static void alterPassword(Connection con, String user, String password, @Nullable String encryptionType) throws SQLException {
    String encodedPassword;
    String encryption;

    if (encryptionType == null ) {
      encryption = getEncryption(con);
    } else if ( !MD5.equalsIgnoreCase(encryptionType) && !SCRAM_ENCRYPTION.equalsIgnoreCase(encryptionType)) {
      throw new IllegalArgumentException("Encryption type " + encryptionType + " not supported");
    } else {
      encryption = encryptionType;
    }
    if ( encryption.equalsIgnoreCase(SCRAM_ENCRYPTION)) {
      encodedPassword = encodeScram(password);
    } else if (encryption.equalsIgnoreCase(MD5)) {
      // libpq uses the user as the salt...
      encodedPassword = new String(MD5Digest.encryptPassword(password.getBytes(StandardCharsets.UTF_8), user.getBytes(StandardCharsets.UTF_8)));
    } else {
      throw new PSQLException("Unable to determine the encryption type ", PSQLState.SYSTEM_ERROR);
    }
    encodedPassword = ((PgConnection)con).escapeLiteral(encodedPassword);
    try (Statement statement = con.createStatement()) {
      statement.execute("ALTER USER " + user + " PASSWORD \'" + encodedPassword + '\'');
    }
  }

  public static String  getEncryption( Connection con) throws SQLException {
    try (Statement statement = con.createStatement()) {
      try (ResultSet rs = statement.executeQuery("show password_encryption ")) {
        if (rs.next()) {
          String passwordEncryption = castNonNull(rs.getString(1));

          switch (passwordEncryption) {
            case "on":
            case "off":
            case PasswordUtil.MD5:
              return PasswordUtil.MD5;
            case PasswordUtil.SCRAM_ENCRYPTION:
              return PasswordUtil.SCRAM_ENCRYPTION;
            default:
              throw new PSQLException("Unable to determine encryption type", PSQLState.SYSTEM_ERROR);
          }
        }
      }
    }
    throw new PSQLException("Unable to determine encryption type", PSQLState.SYSTEM_ERROR);
  }
}
