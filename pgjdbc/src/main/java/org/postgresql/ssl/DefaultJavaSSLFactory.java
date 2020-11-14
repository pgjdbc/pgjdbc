/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import java.util.Properties;

import javax.net.ssl.SSLSocketFactory;

/**
 * Socket factory that uses Java's default truststore to validate server certificate.
 * Note: it always validates server certificate, so it might result to downgrade to non-encrypted
 * connection when default truststore lacks certificates to validate server.
 */
public class DefaultJavaSSLFactory extends WrappedFactory {
  public DefaultJavaSSLFactory(Properties info) {
    factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
  }
}
