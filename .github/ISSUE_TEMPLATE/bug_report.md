---
name: Bug report
about: Create a report to help us improve
title: ''
labels: ''
assignees: ''

---

Please read https://stackoverflow.com/help/minimal-reproducible-example 

**Describe the issue**
A clear and concise description of what the issue is.

**Driver Version?** 

**Java Version?**

**OS Version?**

**PostgreSQL Version?**

**To Reproduce**
Steps to reproduce the behaviour:

**Expected behaviour**
A clear and concise description of what you expected to happen.
And what actually happens

**Logs**
If possible PostgreSQL logs surrounding the occurrence of the issue
Additionally logs from the driver can be obtained adding

Using the following template code make sure the bug can be replicated in the driver alone.
```
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class TestNullsFirst {
    public static void main(String []args) throws Exception {


        String url = "jdbc:postgresql://localhost:5432/test";

        Properties props = new Properties();
        props.setProperty("user", "test");
        props.setProperty("password", "test");
        try ( Connection conn = DriverManager.getConnection(url, props) ){
            try ( Statement statement = conn.createStatement() ) {
                try (ResultSet rs = statement.executeQuery( "select lastname from users order by lastname asc nulls first") ){
                    if (rs.next())
                        System.out.println( "Get String: " + rs.getString(1));
                }
            }
        }
    }
}
```
