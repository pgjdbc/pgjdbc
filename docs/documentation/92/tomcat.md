---
layout: default_docs
title: Tomcat setup
header: Chapter 11. Connection Pools and Data Sources
resource: media
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

<pre><code>
&lt;Resource name="jdbc/postgres" scope="Shareable" type="javax.sql.DataSource"/&gt;
&lt;ResourceParams name="jdbc/postgres"&gt;
	&lt;parameter&gt;
		&lt;name&gt;validationQuery&lt;/name&gt;
		&lt;value&gt;select version();&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;url&lt;/name&gt;
		&lt;value&gt;jdbc:postgresql://localhost/davec&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;password&lt;/name&gt;
		&lt;value&gt;davec&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;maxActive&lt;/name&gt;
		&lt;value&gt;4&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;maxWait&lt;/name&gt;
		&lt;value&gt;5000&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;driverClassName&lt;/name&gt;
		&lt;value&gt;org.postgresql.Driver&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;username&lt;/name&gt;
		&lt;value&gt;davec&lt;/value&gt;
	&lt;/parameter&gt;
	&lt;parameter&gt;
		&lt;name&gt;maxIdle&lt;/name&gt;
		&lt;value&gt;2&lt;/value&gt;
	&lt;/parameter&gt;
&lt;/ResourceParams&gt;	
</code></pre>

Setup for Tomcat 5, you can use the above method, except that it goes inside the
&lt;DefaultContext&gt; tag inside the &lt;Host&gt; tag. eg. &lt;Host&gt; ... &lt;DefaultContext&gt; ...

Alternatively there is a conf/Catalina/hostname/context.xml file. For example
http://localhost:8080/servlet-example has a directory $CATALINA_HOME/conf/Catalina/localhost/servlet-example.xml file. 
Inside this file place the above xml inside the &lt;Context&gt; tag

Then you can use the following code to access the connection.

<pre><code>
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
</code></pre>