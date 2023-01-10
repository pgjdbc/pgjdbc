/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc4;

import java.sql.*;

import junit.framework.TestCase;

import legacy.org.postgresql.TestUtil;

import java.io.StringReader;
import java.io.StringWriter;
import java.io.IOException;

import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.transform.stax.StAXSource;
import org.w3c.dom.Node;

import javax.xml.transform.Transformer;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerException;

import javax.xml.transform.Result;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stax.StAXResult;


public class XmlTest extends TestCase {

    private Connection _conn;
    private final Transformer _xslTransformer;
    private final Transformer _identityTransformer;
    private final static String _xsl = "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\"><xsl:output method=\"text\" indent=\"no\" /><xsl:template match=\"/a\"><xsl:for-each select=\"/a/b\">B<xsl:value-of select=\".\" /></xsl:for-each></xsl:template></xsl:stylesheet>";
    private final static String _xmlDocument = "<a><b>1</b><b>2</b></a>";
    private final static String _xmlFragment = "<a>f</a><b>g</b>";



    public XmlTest(String name) throws Exception {
        super(name);
        TransformerFactory factory = TransformerFactory.newInstance();
        _xslTransformer = factory.newTransformer(new StreamSource(new StringReader(_xsl)));
        _xslTransformer.setErrorListener(new Ignorer());
        _identityTransformer = factory.newTransformer();
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE TEMP TABLE xmltest(id int primary key, val xml)");
        stmt.execute("INSERT INTO xmltest VALUES (1, '" + _xmlDocument + "')");
        stmt.execute("INSERT INTO xmltest VALUES (2, '" + _xmlFragment + "')");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP TABLE xmltest");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    private ResultSet getRS() throws SQLException {
        Statement stmt = _conn.createStatement();
        return stmt.executeQuery("SELECT val FROM xmltest");
    }

    public void testUpdateRS() throws SQLException {
        Statement stmt = _conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
        ResultSet rs = stmt.executeQuery("SELECT id, val FROM xmltest");
        assertTrue(rs.next());
        SQLXML xml = rs.getSQLXML(2);
        rs.updateSQLXML(2, xml);
        rs.updateRow();
    }

    public void testDOMParse() throws SQLException {
        ResultSet rs = getRS();

        assertTrue(rs.next());
        SQLXML xml = rs.getSQLXML(1);
        DOMSource source = xml.getSource(DOMSource.class);
        Node doc = source.getNode();
        Node root = doc.getFirstChild();
        assertEquals("a", root.getNodeName());
        Node first = root.getFirstChild();
        assertEquals("b", first.getNodeName());
        assertEquals("1", first.getTextContent());
        Node last = root.getLastChild();
        assertEquals("b", last.getNodeName());
        assertEquals("2", last.getTextContent());

        assertTrue(rs.next());
        try {
            xml = rs.getSQLXML(1);
            source = xml.getSource(DOMSource.class);
            fail("Can't retrieve a fragment.");
        } catch (SQLException sqle) {
        }
    }

    private void transform(Source source) throws Exception {
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        _xslTransformer.transform(source, result);
        assertEquals("B1B2", writer.toString());
    }

    private <T extends Source> void testRead(Class<T> sourceClass) throws Exception
    {
        ResultSet rs = getRS();

        assertTrue(rs.next());
        SQLXML xml = rs.getSQLXML(1);
        Source source = xml.getSource(sourceClass);
        transform(source);

        assertTrue(rs.next());
        xml = rs.getSQLXML(1);
        try {
            source = xml.getSource(sourceClass);
            transform(source);
            fail("Can't transform a fragment.");
        } catch (Exception sqle) { }
    }

    public void testDOMRead() throws Exception
    {
        testRead(DOMSource.class);
    }

    public void testSAXRead() throws Exception
    {
        testRead(SAXSource.class);
    }

    public void testStAXRead() throws Exception
    {
        testRead(StAXSource.class);
    }

    public void testStreamRead() throws Exception
    {
        testRead(StreamSource.class);
    }

    private <T extends Result> void testWrite(Class<T> resultClass) throws Exception
    {
        Statement stmt = _conn.createStatement();
        stmt.execute("DELETE FROM xmltest");
        stmt.close();

        PreparedStatement ps = _conn.prepareStatement("INSERT INTO xmltest VALUES (?,?)");
        SQLXML xml = _conn.createSQLXML();
        Result result = xml.setResult(resultClass);

        Source source = new StreamSource(new StringReader(_xmlDocument));
        _identityTransformer.transform(source, result);

	ps.setInt(1, 1);
        ps.setSQLXML(2, xml);
        ps.executeUpdate();
        ps.close();

        ResultSet rs = getRS();
        assertTrue(rs.next());

        // DOMResults tack on the additional <?xml ...?> header.
        //
        String header = "";
        if (DOMResult.class.equals(resultClass)) {
            header = "<?xml version=\"1.0\" standalone=\"no\"?>";
        }

        assertEquals(header + _xmlDocument, rs.getString(1));
        xml = rs.getSQLXML(1);
        assertEquals(header + _xmlDocument, xml.getString());

        assertTrue(!rs.next());
    }

    public void testDomWrite() throws Exception
    {
        testWrite(DOMResult.class);
    }

    public void testStAXWrite() throws Exception
    {
        testWrite(StAXResult.class);
    }

    public void testStreamWrite() throws Exception
    {
        testWrite(StreamResult.class);
    }

    public void testSAXWrite() throws Exception
    {
        testWrite(SAXResult.class);
    }

    public void testFree() throws SQLException
    {
        ResultSet rs = getRS();
        assertTrue(rs.next());
        SQLXML xml = rs.getSQLXML(1);
        xml.free();
        xml.free();
        try {
            xml.getString();
            fail("Not freed.");
        } catch (SQLException sqle) { }
    }

    public void testGetObject() throws SQLException
    {
        ResultSet rs = getRS();
        assertTrue(rs.next());
        SQLXML xml = (SQLXML)rs.getObject(1);
    }

    public void testSetNull() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.execute("DELETE FROM xmltest");
        stmt.close();

        PreparedStatement ps = _conn.prepareStatement("INSERT INTO xmltest VALUES (?,?)");
	ps.setInt(1, 1);
        ps.setNull(2, Types.SQLXML);
        ps.executeUpdate();
	ps.setInt(1, 2);
        ps.setObject(2, null, Types.SQLXML);
        ps.executeUpdate();
        SQLXML xml = _conn.createSQLXML();
        xml.setString(null);
	ps.setInt(1, 3);
        ps.setObject(2, xml);
        ps.executeUpdate();
        ps.close();

        ResultSet rs = getRS();
        assertTrue(rs.next());
        assertNull(rs.getObject(1));
        assertTrue(rs.next());
        assertNull(rs.getSQLXML(1));
        assertTrue(rs.next());
        assertNull(rs.getSQLXML("val"));
        assertTrue(!rs.next());
    }

    public void testEmpty() throws SQLException, IOException {
        SQLXML xml = _conn.createSQLXML();

        try {
            xml.getString();
            fail("Cannot retrieve data from an uninitialized object.");
        } catch (SQLException sqle) { }

        try {
            xml.getSource(null);
            fail("Cannot retrieve data from an uninitialized object.");
        } catch (SQLException sqle) { }
    }

    public void testDoubleSet() throws SQLException
    {
        SQLXML xml = _conn.createSQLXML();

        xml.setString("");

        try {
            xml.setString("");
            fail("Can't set a value after its been initialized.");
        } catch (SQLException sqle) { }

        ResultSet rs = getRS();
        assertTrue(rs.next());
        xml = rs.getSQLXML(1);
        try {
            xml.setString("");
            fail("Can't set a value after its been initialized.");
        } catch (SQLException sqle) { }
    }

    // Don't print warning and errors to System.err, it just
    // clutters the display.
    static class Ignorer implements ErrorListener {
        public void error(TransformerException t) { }
        public void fatalError(TransformerException t) { }
        public void warning(TransformerException t) { }
    }

}

