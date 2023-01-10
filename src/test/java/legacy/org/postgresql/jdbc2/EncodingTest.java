/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package legacy.org.postgresql.jdbc2;

import junit.framework.*;
import legacy.org.postgresql.core.Encoding;

import java.io.*;
import java.util.Locale;

/*
 * Tests for the Encoding class.
 *
 */


public class EncodingTest extends TestCase
{

    public EncodingTest(String name)
    {
        super(name);
    }

    public void testCreation() throws Exception
    {
        Encoding encoding;
        encoding = Encoding.getDatabaseEncoding("UTF8");
        assertEquals("UTF", encoding.name().substring(0, 3).toUpperCase(Locale.US));
        encoding = Encoding.getDatabaseEncoding("SQL_ASCII");
        assertTrue(encoding.name().toUpperCase(Locale.US).indexOf("ASCII") != -1);
        assertEquals("When encoding is unknown the default encoding should be used",
                     Encoding.defaultEncoding(),
                     Encoding.getDatabaseEncoding("UNKNOWN"));
    }

    public void testTransformations() throws Exception
    {
        Encoding encoding = Encoding.getDatabaseEncoding("UTF8");
        assertEquals("ab", encoding.decode(new byte[] { 97, 98 }));

        assertEquals(2, encoding.encode("ab").length);
        assertEquals(97, encoding.encode("a")[0]);
        assertEquals(98, encoding.encode("b")[0]);

        encoding = Encoding.defaultEncoding();
        assertEquals("a".getBytes()[0], encoding.encode("a")[0]);
        assertEquals(new String(new byte[] { 97 }),
                     encoding.decode(new byte[] { 97 }));
    }

    public void testReader() throws Exception
    {
        Encoding encoding = Encoding.getDatabaseEncoding("SQL_ASCII");
        InputStream stream = new ByteArrayInputStream(new byte[] { 97, 98 });
        Reader reader = encoding.getDecodingReader(stream);
        assertEquals(97, reader.read());
        assertEquals(98, reader.read());
        assertEquals( -1, reader.read());
    }
}
