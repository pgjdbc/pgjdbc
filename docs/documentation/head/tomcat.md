---
layout: default_docs
title: Tomcat setup
header: Chapter 11. Connection Pools and Data Sources
resource: /documentation/head/media
previoustitle: Applications DataSource
previous: ds-ds.html
nexttitle: Data Sources and JNDI
next: jndi.html
---

### Note

The postgresql.jar file must be placed in $CATALINA_HOME/common/lib in both
Tomcat 4 and 5.

The absolute easiest way to set this up in either tomcat instance is to use the
admin web application that comes with Tomcat, simply add the datasource to the
context you want to use it in.

Setup for Tomcat 4 place the following inside the &lt;Context&gt; tag inside
conf/server.xml

```xml
<Resource name="jdbc/postgres" scope="Shareable" type="javax.sql.DataSource"/>
<ResourceParams name="jdbc/postgres">
	<parameter>
		<name>validationQuery</name>
		<value>select version();</value>
	</parameter>
	<parameter>
		<name>url</name>
		<value>jdbc:postgresql://localhost/davec</value>
	</parameter>
	<parameter>
		<name>password</name>
		<value>davec</value>
	</parameter>
	<parameter>
		<name>maxActive</name>
		<value>4</value>
	</parameter>
	<parameter>
		<name>maxWait</name>
		<value>5000</value>
	</parameter>
	<parameter>
		<name>driverClassName</name>
		<value>org.postgresql.Driver</value>
	</parameter>
	<parameter>
		<name>username</name>
		<value>davec</value>
	</parameter>
	<parameter>
		<name>maxIdle</name>
		<value>2</value>
	</parameter>
</ResourceParams>	
```

Setup for Tomcat 5, you can use the above method, except that it goes inside the
&lt;DefaultContext&gt; tag inside the &lt;Host&gt; tag. eg. &lt;Host&gt; ... &lt;DefaultContext&gt; ...

Alternatively there is a conf/Catalina/hostname/context.xml file. For example
http://localhost:8080/servlet-example has a directory $CATALINA_HOME/conf/Catalina/localhost/servlet-example.xml file. 
Inside this file place the above xml inside the &lt;Context&gt; tag

Then you can use the following code to access the connection.

```java
import javax.naming.*;
import javax.sql.*;
import java.sql.*;
public class DBTest 
{

	String foo = "Not Connected";
	int bar = -1;
    
	public void init() 
	{
		try
		{
			Context ctx = new InitialContext();
			if(ctx == null )
				throw new Exception("Boom - No Context");
	
			// /jdbc/postgres is the name of the resource above 
			DataSource ds = (DataSource)ctx.lookup("java:comp/env/jdbc/postgres");
	    
			if (ds != null) 
			{
				Connection conn = ds.getConnection();
	    
				if(conn != null) 
				{
					foo = "Got Connection "+conn.toString();
					Statement stmt = conn.createStatement();
					ResultSet rst = stmt.executeQuery("select id, foo, bar from testdata");
					
					if(rst.next())
					{
						foo=rst.getString(2);
						bar=rst.getInt(3);
					}
					conn.close();
				}
			}
		}
		catch(Exception e) 
		{
			e.printStackTrace();
		}
	}

	public String getFoo() { return foo; }

	public int getBar() { return bar;}
}
```
