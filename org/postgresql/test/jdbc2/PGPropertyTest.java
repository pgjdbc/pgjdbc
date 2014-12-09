package org.postgresql.test.jdbc2;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.sql.DriverPropertyInfo;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import junit.framework.TestCase;

import org.junit.Assert;
import org.postgresql.Driver;
import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.ds.common.BaseDataSource;

public class PGPropertyTest extends TestCase
{

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

}
