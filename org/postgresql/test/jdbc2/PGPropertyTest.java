package org.postgresql.test.jdbc2;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.FileInputStream;
import java.sql.DriverPropertyInfo;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import junit.framework.TestCase;

import org.apache.xml.resolver.tools.CatalogResolver;
import org.junit.Assert;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.test.TestUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


public class PGPropertyTest extends TestCase
{

    public static final String DOCUMENTATION_FILE = "doc/pgjdbc.xml";

    /**
     * Test that we can get and set all default values and all choices (if any)
     */
    public void testGetSetAllProperties()
    {
        Properties properties = new Properties();
        for(PGProperty property : PGProperty.values())
        {
            String value = property.get(properties);
            Assert.assertEquals(property.getDefaultValue(), value);

            property.set(properties, value);
            Assert.assertEquals(value, property.get(properties));

            if (property.getChoices() != null && property.getChoices().length > 0)
            {
                for (String choice : property.getChoices())
                {
                    property.set(properties, choice);
                    Assert.assertEquals(choice, property.get(properties));
                }
            }
        }
    }

    /**
     * Test that the enum constant is common with the underlying property name
     */
    public void testEnumConstantNaming()
    {
        for(PGProperty property : PGProperty.values())
        {
            String enumName = property.name().replaceAll("_", "");
            Assert.assertEquals("Naming of the enum constant [" + property.name() + "] should follow the naming of its underlying property [" + property.getName() + "] in PGProperty",
                                property.getName().toLowerCase(), enumName.toLowerCase());
        }
    }

    public void testDriverGetPropertyInfo()
    {
        Driver driver = new Driver();
        DriverPropertyInfo[] infos = driver.getPropertyInfo("jdbc:postgresql://localhost/test?user=fred&password=secret&ssl=true", // this is the example we give in docs
                                                            new Properties());
        for(DriverPropertyInfo info : infos)
        {
            if ("user".equals(info.name))
            {
                Assert.assertEquals("fred", info.value);
            }
            else if ("password".equals(info.name))
            {
                Assert.assertEquals("secret", info.value);
            }
            else if ("ssl".equals(info.name))
            {
                Assert.assertEquals("true", info.value);
            }
        }
    }

    /**
     * Test if the datasource has getter and setter for all properties
     */
    public void testDataSourceProperties()
      throws Exception
    {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        BeanInfo info = Introspector.getBeanInfo(dataSource.getClass());

        // index PropertyDescriptors by name
        Map<String, PropertyDescriptor> propertyDescriptors = new TreeMap<String, PropertyDescriptor>(String.CASE_INSENSITIVE_ORDER);
        for (PropertyDescriptor propertyDescriptor: info.getPropertyDescriptors())
        {
            propertyDescriptors.put(propertyDescriptor.getName(), propertyDescriptor);
        }

        // test for the existence of all read methods (getXXX/isXXX) and write methods (setXXX) for all known properties
        for(PGProperty property : PGProperty.values())
        {
            if (!property.getName().startsWith("PG"))
            {
                Assert.assertTrue("Missing getter/setter for property [" + property.getName() + "] in [" + BaseDataSource.class + "]",
                                  propertyDescriptors.containsKey(property.getName()));

                Assert.assertNotNull("Not getter for property [" + property.getName() + "] in [" + BaseDataSource.class + "]",
                                     propertyDescriptors.get(property.getName()).getReadMethod());

                Assert.assertNotNull("Not setter for property [" + property.getName() + "] in [" + BaseDataSource.class + "]",
                                     propertyDescriptors.get(property.getName()).getWriteMethod());
            }
        }

        // test readability/writability of default value
        for(PGProperty property : PGProperty.values())
        {
            if (!property.getName().startsWith("PG"))
            {
                Object propertyValue = propertyDescriptors.get(property.getName()).getReadMethod().invoke(dataSource);
                propertyDescriptors.get(property.getName()).getWriteMethod().invoke(dataSource, propertyValue);
            }
        }
    }

    /**
     * Test that all properties in {@link PGProperty} are documented in {@code doc/pgjdbc.xml}
     */
    public void testDocumentation()
      throws Exception
    {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        documentBuilder.setEntityResolver(new CatalogResolver());
        Document document = documentBuilder.parse(new FileInputStream(DOCUMENTATION_FILE));

        XPathFactory xpathFactory = XPathFactory.newInstance();
        XPath xpath = xpathFactory.newXPath();

        Node connectionParameters = (Node) xpath.evaluate("//*[@id='connection-parameters']", document, XPathConstants.NODE);

        Assert.assertNotNull("Could not find connection parameters section  of [" + DOCUMENTATION_FILE + "]", connectionParameters);

        for(PGProperty property : PGProperty.values())
        {
            if (!property.getName().startsWith("PG"))
            {
                NodeList nodeList = (NodeList) xpath.evaluate(".//variablelist/varlistentry/term/varname[text()='"+ property.getName() +"']", connectionParameters, XPathConstants.NODESET);
                assertNotNull("Connection parameter [" + property.getName() + "] is not documented in [" + DOCUMENTATION_FILE + "]", nodeList);
                assertFalse("Connection parameter [" + property.getName() + "] is not documented in [" + DOCUMENTATION_FILE + "]", nodeList.getLength() == 0);
                assertFalse("Connection parameter [" + property.getName() + "] is documented twice in [" + DOCUMENTATION_FILE + "]", nodeList.getLength() > 1);
            }
        }
    }

    /**
     * Test that all connection parameters referenced in the documentation are in {@link PGProperty}
     */
    public void testUselessDocumentation()
      throws Exception
    {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        documentBuilder.setEntityResolver(new CatalogResolver());
        Document document = documentBuilder.parse(new FileInputStream(DOCUMENTATION_FILE));

        XPathFactory xpathFactory = XPathFactory.newInstance();
        XPath xpath = xpathFactory.newXPath();

        Node connectionParameters = (Node) xpath.evaluate("//*[@id='connection-parameters']", document, XPathConstants.NODE);

        Assert.assertNotNull("Could not find connection parameters section of [" + DOCUMENTATION_FILE + "]", connectionParameters);

        NodeList nodeList = (NodeList) xpath.evaluate(".//variablelist/varlistentry/term/varname", connectionParameters, XPathConstants.NODESET);
        for (int i = 0 ; i < nodeList.getLength() ; i++)
        {
            Node node = nodeList.item(i);
            String propertyName = node.getTextContent();
            PGProperty enumProperty = PGProperty.forName(propertyName);
            Assert.assertNotNull("Connection parameter [" + propertyName + "] documented in [" + DOCUMENTATION_FILE + "] does not exist in the code (see PGProperty enum)", enumProperty);
        }
    }

    /**
     * Test that {@link PGProperty#isPresent(Properties)} returns a correct result in all cases
     */
    public void testIsPresentWithParseURLResult() throws Exception
    {
        Properties givenProperties = new Properties();
        givenProperties.setProperty("user", TestUtil.getUser());
        givenProperties.setProperty("password", TestUtil.getPassword());

        Properties parsedProperties = Driver.parseURL(TestUtil.getURL(), givenProperties);
        Assert.assertFalse("SSL property should not be present", PGProperty.SSL.isPresent(parsedProperties));

        givenProperties.setProperty("ssl", "true");
        parsedProperties = Driver.parseURL(TestUtil.getURL(), givenProperties);
        Assert.assertTrue("SSL property should be present", PGProperty.SSL.isPresent(parsedProperties));

        givenProperties.setProperty("ssl", "anotherValue");
        parsedProperties = Driver.parseURL(TestUtil.getURL(), givenProperties);
        Assert.assertTrue("SSL property should be present", PGProperty.SSL.isPresent(parsedProperties));

        parsedProperties = Driver.parseURL(TestUtil.getURL() + "&ssl=true" , null);
        Assert.assertTrue("SSL property should be present", PGProperty.SSL.isPresent(parsedProperties));
    }
}
