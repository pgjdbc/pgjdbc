/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ds.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.PGEnvironment;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PreferQueryMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.org.webcompere.systemstubs.properties.SystemProperties;
import uk.org.webcompere.systemstubs.resource.Resources;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;

import javax.naming.NamingException;
import javax.naming.Reference;

class BaseDataSourceTest {

  @Test
  void beanPropertiesShouldProvideConsistentReadAndWrite()
      throws IntrospectionException, InvocationTargetException, IllegalAccessException,
             NamingException, IOException, ClassNotFoundException {
    // Get the BeanInfo for BaseDataSource and its properties
    BeanInfo info = Introspector.getBeanInfo(
        BaseDataSource.class,
        Introspector.IGNORE_ALL_BEANINFO
    );
    PropertyDescriptor[] properties = info.getPropertyDescriptors();

    // Sample values to use in tests
    HashMap<Class<?>, Object[]> sampleValues = new HashMap<>();
    sampleValues.put(String.class, new Object[]{"test", "example"});
    sampleValues.put(Integer.TYPE, new Object[]{1, 42});
    sampleValues.put(Boolean.TYPE, new Object[]{true, false});
    sampleValues.put(AutoSave.class, new Object[]{AutoSave.ALWAYS, AutoSave.NEVER});
    sampleValues.put(
        PreferQueryMode.class,
        new Object[]{PreferQueryMode.SIMPLE, PreferQueryMode.EXTENDED}
    );

    // Exclude these properties as they require special values.
    HashSet<String> excludedProperties = new HashSet<>();

    // URL requires a special value
    excludedProperties.add("URL");
    excludedProperties.add("url");

    // Log writer is not used
    excludedProperties.add("logWriter");

    // Arrays require a special equality test
    excludedProperties.add("portNumbers");
    excludedProperties.add("serverNames");

    // Loop over all the properties
    for (PropertyDescriptor pd : properties) {
      Method readMethod = pd.getReadMethod();
      Method writeMethod = pd.getWriteMethod();

      // Skip properties requiring special tests.
      if (readMethod == null || writeMethod == null || excludedProperties.contains(pd.getName())) {
        continue;
      }

      // set the value
      Object[] values = sampleValues.get(pd.getPropertyType());
      for (Object value : values) {
        // Use setter and getter to test value is actually set.
        BaseDataSource dataSource = new PGSimpleDataSource();
        writeMethod.invoke(dataSource, value);
        assertEquals(
            value,
            readMethod.invoke(dataSource),
            "Property '" + pd.getName() + "' should return the value set: " + value
        );

        // Is property maintained in Reference?
        Reference reference = dataSource.getReference();
        BaseDataSource newDataSource = new PGSimpleDataSource();
        newDataSource.setFromReference(reference);
        assertEquals(
            value,
            readMethod.invoke(dataSource),
            "Property '" + pd.getName() + "' should return the value set: " + value
        );


        // Is property maintained by initializeFrom?
        newDataSource = new PGSimpleDataSource();
        newDataSource.initializeFrom(dataSource);
        assertEquals(
            value,
            readMethod.invoke(newDataSource),
            "Property '" + pd.getName() + "' should return the value set after initializeFrom: " + value
        );

        // user and password are not maintained in the URL
        if (pd.getName().equals("user") || pd.getName().equals("password")) {
          continue;
        }

        // Is property maintained in URL?
        String url = dataSource.getUrl();
        newDataSource = new PGSimpleDataSource();
        newDataSource.setUrl(url);
        assertEquals(
            value,
            readMethod.invoke(newDataSource),
            "Property '" + pd.getName() + "' should return the value set: " + value
        );
      }
    }
  }

  @Test
  void canSetAndGetHostNamesAndPorts() {
    BaseDataSource dataSource = new PGSimpleDataSource();
    String[] hostNames = {"localhost", "server1", "server2"};
    dataSource.setServerNames(hostNames);

    int[] portNumbers = {5432, 5433, 5434};
    dataSource.setPortNumbers(portNumbers);

    assertArrayEquals(hostNames, dataSource.getServerNames());
    assertArrayEquals(portNumbers, dataSource.getPortNumbers());

    String url = dataSource.getURL();
    BaseDataSource other = new PGSimpleDataSource();
    other.setURL(url);

    assertArrayEquals(hostNames, dataSource.getServerNames());
    assertArrayEquals(portNumbers, dataSource.getPortNumbers());
  }

  @Test
  void doesNotUseLogWriter() {
    BaseDataSource dataSource = new PGSimpleDataSource();
    assertNull(dataSource.getLogWriter());

    dataSource.setLogWriter(new PrintWriter(System.out));
    assertNull(dataSource.getLogWriter());
  }

  @ParameterizedTest
  @CsvSource({
      "adaptiveFetch,false",
      "adaptiveFetch,true",
      "adaptiveFetchMaximum,10",
      "adaptiveFetchMaximum,100",
      "adaptiveFetchMinimum,3",
      "adaptiveFetchMinimum,5",
      "allowEncodingChanges,false",
      "allowEncodingChanges,true",
      "ApplicationName,My Driver 1",
      "ApplicationName,My Driver 2",
      "assumeMinServerVersion,3.0",
      "assumeMinServerVersion,8.0",
      "authenticationPluginClassName,plugin.MuAuthPlugin",
      "authenticationPluginClassName,plugin.MyPlugin",
      "autosave,never",
      "autosave,always",
      "binaryTransfer,true",
      "binaryTransfer,false",
      "binaryTransferDisable,INT",
      "binaryTransferDisable,TEXT",
      "binaryTransferEnable,INT",
      "binaryTransferEnable,TEXT",
      "cancelSignalTimeout,10",
      "cancelSignalTimeout,60",
      "channelBinding,prefer",
      "channelBinding,require",
      "cleanupSavepoints,true",
      "cleanupSavepoints,false",
      "connectTimeout,10",
      "connectTimeout,120",
      "currentSchema,schema1",
      "currentSchema,schema2",
      "databaseMetadataCacheFields,65536",
      "databaseMetadataCacheFields,100000",
      "databaseMetadataCacheFieldsMiB,5",
      "databaseMetadataCacheFieldsMiB,50",
      "defaultRowFetchSize,0",
      "defaultRowFetchSize,10",
      "disableColumnSanitiser,false",
      "disableColumnSanitiser,true",
      "escapeSyntaxCallMode,select",
      "escapeSyntaxCallMode,callIfNoReturn",
      "groupStartupParameters,false",
      "groupStartupParameters,true",
      "gssEncMode,allow",
      "gssEncMode,disable",
      "gsslib,auto",
      "gsslib,sspi",
      "gssResponseTimeout,5000",
      "gssResponseTimeout,50000",
      "gssUseDefaultCreds,false",
      "gssUseDefaultCreds,true",
      "hideUnprivilegedObjects,false",
      "hideUnprivilegedObjects,true",
      "hostRecheckSeconds,10",
      "hostRecheckSeconds,100",
      "jaasApplicationName,pgjdbc",
      "jaasApplicationName,PGJBDC",
      "jaasLogin,true",
      "jaasLogin,false",
      "kerberosServerName,k1",
      "kerberosServerName,k2",
      "loadBalanceHosts,false",
      "loadBalanceHosts,true",
      "localSocketAddress,myhost:5000",
      "localSocketAddress,localhost:6000",
      "loggerFile,output.log",
      "loggerFile,pgjdbc.log",
      "loggerLevel,OFF",
      "loggerLevel,DEBUG",
      "loginTimeout,0",
      "loginTimeout,50",
      "logServerErrorDetail,true",
      "logServerErrorDetail,false",
      "logUnclosedConnections,true",
      "logUnclosedConnections,false",
      "maxResultBuffer,1000",
      "maxResultBuffer,10000",
      "maxSendBufferSize,8192",
      "maxSendBufferSize,81920",
      "options,abc",
      "options,def",
      "password,my-secret-password",
      "password,Super^Princess^888",
      "PGDBNAME,mydb",
      "PGDBNAME,appdb",
      "PGHOST,localhost",
      "PGHOST,server",
      "PGPORT,5432",
      "PGPORT,55432",
      "preferQueryMode,extended",
      "preferQueryMode,extendedForPrepared",
      "preparedStatementCacheQueries,256",
      "preparedStatementCacheQueries,512",
      "preparedStatementCacheSizeMiB,5",
      "preparedStatementCacheSizeMiB,10",
      "prepareThreshold,5",
      "prepareThreshold,10",
      "protocolVersion,3",
      "protocolVersion,2",
      "quoteReturningIdentifiers,true",
      "quoteReturningIdentifiers,false",
      "readOnly,false",
      "readOnly,true",
      "readOnlyMode,transaction",
      "readOnlyMode,always",
      "receiveBufferSize,-1",
      "receiveBufferSize,1024",
      "replication,true",
      "replication,database",
      "reWriteBatchedInserts,false",
      "reWriteBatchedInserts,true",
      "sendBufferSize,-1",
      "sendBufferSize,8192",
      "service,test-service1",
      "service,mydb1",
      "socketFactory,net.socketFactory",
      "socketFactory,net.specialSocketFactory",
      "socketFactoryArg,abc",
      "socketFactoryArg,def",
      "socketTimeout,0",
      "socketTimeout,60",
      "ssl,",
      "ssl,true",
      "sslcert,/ssl/cert1.pem",
      "sslcert,/ssl/cert2.pem",
      "sslfactory,org.postgresql.ssl.LibPQFactory",
      "sslfactory,alt.ssl.LibPQFactory",
      "sslfactoryarg,arg1",
      "sslfactoryarg,arg2",
      "sslhostnameverifier,null",
      "sslhostnameverifier,my.custom.HostnameVerifier",
      "sslkey,/ssl/key1.pem",
      "sslkey,/ssl/key2.pem",
      "sslmode,prefer",
      "sslmode,require",
      "sslNegotiation,postgres",
      "sslNegotiation,direct",
      "sslpassword,password1",
      "sslpassword,password2",
      "sslpasswordcallback,my.callback.Class",
      "sslpasswordcallback,my.other.callback.Class",
      "sslResponseTimeout,5000",
      "sslResponseTimeout,6000",
      "sslrootcert,/ssl/rootcert1.pem",
      "sslrootcert,/ssl/rootcert2.pem",
      "sspiServiceClass,POSTGRES",
      "sspiServiceClass,OTHER",
      "stringtype,varchar",
      "stringtype,unspecified",
      "targetServerType,any",
      "targetServerType,secondary",
      "tcpKeepAlive,true",
      "tcpKeepAlive,false",
      "tcpNoDelay,true",
      "tcpNoDelay,false",
      "unknownLength,2147483647",
      "unknownLength,1000000000",
      "user,myuser",
      "user,anotheruser",
      "useSpnego,false",
      "useSpnego,true",
      "xmlFactoryFactory,xml.factory.Factory1",
      "xmlFactoryFactory,xml.factory.Factory2"
  })
  void settableShouldBeReadable(String propertyName, String testValue) throws Exception {
    PGProperty property = PGProperty.forName(propertyName);
    assertNotNull(property);

    // Test with the PGProperty.
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setProperty(property, testValue);
    assertEquals(testValue, dataSource.getProperty(property));
    assertEquals(testValue, dataSource.getProperty(propertyName));

    // Test with the PGProperty's name
    dataSource = new PGSimpleDataSource();
    dataSource.setProperty(propertyName, testValue);
    assertEquals(testValue, dataSource.getProperty(property));
    assertEquals(testValue, dataSource.getProperty(propertyName));

    // Is property maintained in Reference?
    Reference reference = dataSource.getReference();
    BaseDataSource newDataSource = new PGSimpleDataSource();
    newDataSource.setFromReference(reference);
    assertEquals(testValue, newDataSource.getProperty(property));

    // Is property maintained by initializeFrom?
    newDataSource = new PGSimpleDataSource();
    newDataSource.initializeFrom(dataSource);
    assertEquals(testValue, newDataSource.getProperty(property));

    // user and password are not maintained in the URL
    // service defines a set of default properties so changes the URL rather than being set in it
    if (
        propertyName.equals("user")
            || propertyName.equals("password")
            || propertyName.equals("service")
    ) {
      return;
    }

    String url = dataSource.getUrl();
    newDataSource = new PGSimpleDataSource();
    newDataSource.setUrl(url);
    assertEquals(testValue, newDataSource.getProperty(property));
  }

  @Test
  void settingServiceShouldNotOverrideProperties() throws Exception {
    // Is property maintained in URL?
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      BaseDataSource oldDataSource = new PGSimpleDataSource();
      oldDataSource.setProperty(PGProperty.PG_HOST, "global-somehost3");
      oldDataSource.setProperty(PGProperty.PG_PORT, "5555");
      oldDataSource.setReadOnly(false);

      // Set a service.
      oldDataSource.setProperty(PGProperty.SERVICE, "dataSourceTestService1");

      // Setting the URL sets many properties to their defaults. This changes the URL.
      String url = oldDataSource.getUrl();
      assertEquals("jdbc:postgresql://global-somehost3:5555/?loginTimeout=60&readOnly=false", url);
    });
  }

  @Test
  void settingServiceShouldSetProperties() throws Exception {
    // Is property maintained in URL?
    URL urlFileProps = getClass().getResource("/pg_service/pgservicefileProps.conf");
    Resources.with(
        new SystemProperties(PGEnvironment.ORG_POSTGRESQL_PGSERVICEFILE.getName(), urlFileProps.getFile())
    ).execute(() -> {
      BaseDataSource oldDataSource = new PGSimpleDataSource();

      // Set a service.
      oldDataSource.setProperty(PGProperty.SERVICE, "dataSourceTestService1");

      // Setting the URL sets many properties to their defaults. This changes the URL.
      String url = oldDataSource.getUrl();
      assertEquals("jdbc:postgresql://global-somehost2:5433/?loginTimeout=60&readOnly=true",url);
      oldDataSource.setUrl(url);

      // Get the URL again, it will now include the defaults and service properties.
      url = oldDataSource.getUrl();

      BaseDataSource newDataSource = new PGSimpleDataSource();
      newDataSource.setUrl(url);
      String url2 = newDataSource.getUrl();

      // Should be the same?
      assertEquals(url, url2);
    });
  }
}
