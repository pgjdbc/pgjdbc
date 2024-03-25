/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.ssl.PKCS12KeyManager;
import org.postgresql.ssl.SubjectKeyManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.ssl.PKCS12KeyTest.TestCallbackHandler;

import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;

class SubjectKeyManagerTest {

  @Test
  void TestChooseClientAlias() throws Exception {
    PKCS12KeyManager pkcs12KeyManager = new PKCS12KeyManager(TestUtil.getSslTestCertPath("goodclient.p12"), new TestCallbackHandler("sslpwd"));
    X500Principal testPrincipal = new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");
    X500Principal[] issuers = new X500Principal[]{testPrincipal};

    String validKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    assertNotNull(validKeyType);

    X500Principal testSubject = new X500Principal("CN=test, O=PgJdbc tests, ST=CA, C=US");
    SubjectKeyManager subjectKeyManager = new SubjectKeyManager(pkcs12KeyManager, testSubject);

    String filteredKeyType = subjectKeyManager.chooseClientAlias(new String[]{"rsa"}, issuers, null);
    assertNotNull(filteredKeyType);

    X500Principal badSubject = new X500Principal("CN=bad, O=PgJdbc tests, ST=CA, C=US");
    SubjectKeyManager badSubjectKeyManager = new SubjectKeyManager(pkcs12KeyManager, badSubject);

    String badKeyType = badSubjectKeyManager.chooseClientAlias(new String[]{"rsa"}, issuers, null);
    assertNull(badKeyType);
  }
}
