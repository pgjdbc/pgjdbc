package org.postgresql.jdbc.ssl.test;

import java.util.Properties;
import java.sql.*;
import org.junit.Test;
import org.junit.Assert;
import org.junit.Test;
import org.postgresql.util.PSQLException;
import java.io.IOException;


public class SingleCertValidatingFactoryTest extends AbstractSslTest {
	/** Connect using no SSL. This is bad as passwords are sent over as
	 * plaintext.
	 */
	@Test
	public void connectNoSSL() throws SQLException {
		Properties info = new Properties();
		testConnect(info, false);
	}

	/**
	 * Connect using SSL without any server certificate validation. This is bad
	 * as this connection is vulnerable to a man in the middle attack.
	 */
	@Test
	public void connectSSLWithoutValidation() throws SQLException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.NonValidatingFactory");
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate but
	 * don't actually provide it. This connection attempt should *fail* as the
	 * client should reject the server.
	 */
	@Test(expected = PSQLException.class)
	public void connectSSLWithValidationNoCert() throws SQLException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate
	 * against the wrong pre shared certificate. This test uses a pre generated
	 * certificate that will *not* match the test PostgreSQL server (the
	 * certificate is for properssl.example.com).
	 * 
	 * This connection uses a custom SSLSocketFactory using a custom trust
	 * manager that validates the remote server's certificate against the pre
	 * shared certificate.
	 * 
	 * This test should throw an exception as the client should reject the
	 * server since the certificate does not match.
	 * 
	 * @throws SQLException
	 */
	@Test(expected = PSQLException.class)
	public void connectSSLWithValidationWrongCert() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", Utils.getClasspathFile("invalid-server.crt"));
		testConnect(info, true);
	}

	@Test(expected = PSQLException.class)
	public void classpathCertInvalid() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "classpath:foo/bar/baz");
		testConnect(info, true);
	}

	@Test(expected = PSQLException.class)
	public void fileCertInvalid() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "file:foo/bar/baz");
		testConnect(info, true);
	}

	@Test(expected = PSQLException.class)
	public void stringCertInvalid() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "foobar!");
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate
	 * against the proper pre shared certificate. The certificate is specified
	 * as a String. Note that the test read's the certificate from a classpath
	 * resource but it specifies it to the driver arg as a String. 
	 *
	 * This connection uses a custom SSLSocketFactory using a custom trust
	 * manager that validates the remote server's certificate against a pre
	 * shared certificate.
	 * 
	 * NOTE: If you're connecting to a remote server that uses a self signed
	 * certificate this is how a connection should be made.
	 * 
	 * @throws SQLException
	 */
	@Test
	public void connectSSLWithValidationProperCertString() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", Utils.getClasspathFile("server.crt"));
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate
	 * against the proper pre shared certificate. The certificate is specified
	 * as a classpath resource.
	 * 
	 * This connection uses a custom SSLSocketFactory using a custom trust
	 * manager that validates the remote server's certificate against a pre
	 * shared certificate.
	 * 
	 * NOTE: If you're connecting to a remote server that uses a self signed
	 * certificate this is how a connection should be made.
	 * 
	 * @throws SQLException
	 */
	@Test
	public void connectSSLWithValidationProperCertClasspathResource() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "classpath:server.crt");
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate
	 * against the proper pre shared certificate. The certificate is specified
	 * as a file on the local filesystem (relative path).
	 * 
	 * This connection uses a custom SSLSocketFactory using a custom trust
	 * manager that validates the remote server's certificate against a pre
	 * shared certificate.
	 * 
	 * NOTE: If you're connecting to a remote server that uses a self signed
	 * certificate this is how a connection should be made.
	 * 
	 * @throws SQLException
	 */
	@Test
	public void connectSSLWithValidationProperFile() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "file:src/test/resources/server.crt");
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate
	 * against the proper pre shared certificate. The certificate is specified
	 * as an environment variable.
	 * 
	 * This connection uses a custom SSLSocketFactory using a custom trust
	 * manager that validates the remote server's certificate against a pre
	 * shared certificate.
	 * 
	 * NOTE: If you're connecting to a remote server that uses a self signed
	 * certificate this is how a connection should be made.
	 * 
	 * @throws SQLException
	 */
	@Test
	public void connectSSLWithValidationProperEnv() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "env:SERVER_CERT");
		testConnect(info, true);
	}

	/**
	 * Connect using SSL and attempt to validate the server's certificate
	 * against the proper pre shared certificate. The certificate is specified
	 * as an environment variable.
	 * 
	 * This connection uses a custom SSLSocketFactory using a custom trust
	 * manager that validates the remote server's certificate against a pre
	 * shared certificate.
	 * 
	 * NOTE: If you're connecting to a remote server that uses a self signed
	 * certificate this is how a connection should be made.
	 * 
	 * @throws SQLException
	 */
	@Test
	public void connectSSLWithValidationProperSysProp() throws SQLException,
			IOException {
		Properties info = new Properties();
		info.setProperty("ssl", "true");
		info.setProperty("sslfactory",
				"org.postgresql.ssl.SingleCertValidatingFactory");
		info.setProperty("sslfactoryarg", "sys:postgresql.server.crt");
		testConnect(info, true);
	}
}