/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Before;
import org.junit.Test;

import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.util.Properties;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;

public class PgSQLXMLTest extends BaseTest4 {

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "xmltab", "x xml");
  }

  @Test
  public void setCharacterStream() throws Exception {
    String exmplar = "<x>value</x>";
    SQLXML pgSQLXML = con.createSQLXML();
    Writer writer = pgSQLXML.setCharacterStream();
    writer.write(exmplar);
    PreparedStatement preparedStatement = con.prepareStatement("insert into xmltab values (?)");
    preparedStatement.setSQLXML(1, pgSQLXML);
    preparedStatement.execute();

    Statement statement = con.createStatement();
    ResultSet rs = statement.executeQuery("select * from xmltab");
    assertTrue(rs.next());
    SQLXML result = rs.getSQLXML(1);
    assertNotNull(result);
    assertEquals(exmplar, result.getString());
  }

  private static final String LICENSE_URL =
      PgSQLXMLTest.class.getClassLoader().getResource("META-INF/LICENSE").toString();
  private static final String XXE_EXAMPLE =
      "<!DOCTYPE foo [<!ELEMENT foo ANY >\n"
      + "<!ENTITY xxe SYSTEM \"" + LICENSE_URL + "\">]>"
      + "<foo>&xxe;</foo>";

  @Test
  public void testLegacyXxe() throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.XML_FACTORY_FACTORY.getName(), "LEGACY_INSECURE");
    try (Connection conn = TestUtil.openDB(props)) {
      BaseConnection baseConn = conn.unwrap(BaseConnection.class);
      PgSQLXML xml = new PgSQLXML(baseConn, XXE_EXAMPLE);
      xml.getSource(null);
    }
  }

  private static String sourceToString(Source source) throws TransformerException {
    StringWriter sw = new StringWriter();
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.transform(source, new StreamResult(sw));
    return sw.toString();
  }

  private <T extends Source> void testGetSourceXxe(Class<T> clazz) {
    SQLException ex = assertThrows(SQLException.class, () -> {
      PgSQLXML xml = new PgSQLXML(null, XXE_EXAMPLE);
      xml.getSource(clazz);
    });
    String message = ex.getCause().getMessage();
    assertTrue(
        "Expected to get a <<DOCTYPE disallowed>> SAXParseException. Actual message is " + message,
        message.startsWith("DOCTYPE is disallowed"));
  }

  @Test
  public void testGetSourceXxeNull() throws Exception {
    testGetSourceXxe(null);
  }

  @Test
  public void testGetSourceXxeDOMSource() throws Exception {
    testGetSourceXxe(DOMSource.class);
  }

  @Test
  public void testGetSourceXxeSAXSource() throws Exception {
    PgSQLXML xml = new PgSQLXML(null, XXE_EXAMPLE);
    SAXSource source = xml.getSource(SAXSource.class);
    TransformerException ex = assertThrows(TransformerException.class, () -> {
      sourceToString(source);
    });
    String message = ex.getCause().getMessage();
    assertTrue(
        "Expected to get a <<DOCTYPE disallowed>> TransformerException. Actual message is " + message,
        message.startsWith("DOCTYPE is disallowed"));
  }

  @Test
  public void testGetSourceXxeStAXSource() throws Exception {
    PgSQLXML xml = new PgSQLXML(null, XXE_EXAMPLE);
    StAXSource source = xml.getSource(StAXSource.class);
    XMLStreamReader reader = source.getXMLStreamReader();
    // STAX will not throw XXE error until we actually read the element
    assertThrows(XMLStreamException.class, () -> {
      while (reader.hasNext()) {
        reader.next();
      }
    });
  }
}
