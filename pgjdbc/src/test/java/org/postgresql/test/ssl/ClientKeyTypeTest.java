/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.ssl.LazyKeyManager;
import org.postgresql.ssl.PEMKeyManager;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;
import java.util.stream.Stream;

import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * Checks which key types {@code chooseClientAlias} accepts for an EC P-384
 * client certificate, in each key manager that has its own copy of the
 * matching logic. Only the certificate file is read, so the tests do not need
 * a server or the private key.
 */
class ClientKeyTypeTest {

  private static final X500Principal[] ISSUERS = {
      new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US")
  };

  static Stream<Arguments> keyManagers() {
    Function<String, X509KeyManager> pem = certFile -> new PEMKeyManager(
        TestUtil.getSslTestCertPath("ecclient.key"), certFile, "EC");
    Function<String, X509KeyManager> lazy = certFile -> new LazyKeyManager(
        certFile, TestUtil.getSslTestCertPath("ecclient.key"),
        new PKCS12KeyManagerTest.TestCallbackHandler(null), false);
    return Stream.of(
        Arguments.of("PEMKeyManager", pem),
        Arguments.of("LazyKeyManager", lazy));
  }

  private static String cert(String name) {
    return TestUtil.getSslTestCertPath(name);
  }

  /**
   * BouncyCastle's JSSE provider asks for TLS 1.3 EC keys with the curve
   * after a slash. The curve is the provider's to check, so the key manager
   * offers the certificate for any {@code EC/...} key type.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("keyManagers")
  void acceptsAnEcKeyTypeWithACurveSuffix(String name,
      Function<String, X509KeyManager> keyManager) {
    X509KeyManager km = keyManager.apply(cert("ecclient.crt"));
    assertEquals("user",
        km.chooseClientAlias(new String[]{"EC/secp384r1"}, ISSUERS, null));
    assertEquals("user",
        km.chooseClientAlias(new String[]{"EC"}, ISSUERS, null));
    assertEquals("user",
        km.chooseClientAlias(new String[]{"ec/secp384r1"}, ISSUERS, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keyManagers")
  void refusesAKeyTypeForAnotherAlgorithm(String name,
      Function<String, X509KeyManager> keyManager) {
    X509KeyManager km = keyManager.apply(cert("ecclient.crt"));
    assertNull(km.chooseClientAlias(new String[]{"RSA"}, ISSUERS, null));
    assertNull(km.chooseClientAlias(new String[]{"RSASSA-PSS"}, ISSUERS, null));
    assertNull(km.chooseClientAlias(new String[]{"ECX/secp384r1"}, ISSUERS,
        null));
  }

  /**
   * {@code ecclient-chain.crt} holds the EC client certificate followed by the
   * RSA root that signed it. The key type comes from the client certificate,
   * so the key manager accepts {@code EC} and refuses {@code RSA}.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("keyManagers")
  void takesTheKeyTypeFromTheFirstCertificateInTheChain(String name,
      Function<String, X509KeyManager> keyManager) {
    X509KeyManager km = keyManager.apply(cert("ecclient-chain.crt"));
    assertEquals("user",
        km.chooseClientAlias(new String[]{"EC"}, ISSUERS, null));
    assertEquals("user",
        km.chooseClientAlias(new String[]{"EC/secp384r1"}, ISSUERS, null));
    assertNull(km.chooseClientAlias(new String[]{"RSA"}, ISSUERS, null));
  }
}
