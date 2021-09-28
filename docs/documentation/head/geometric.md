---
layout: default_docs
title: Geometric Data Types
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: /documentation/head/media
previoustitle: Chapter 9. PostgreSQL™ Extensions to the JDBC API
previous: ext.html
nexttitle: Large Objects
next: largeobjects.html
---

PostgreSQL™ has a set of data types that can store geometric features into a
table. These include single points, lines, and polygons.  We support these types
in Java with the org.postgresql.geometric package. Please consult the Javadoc
mentioned in [Chapter 13, *Further Reading*](reading.html) for details of
available classes and features.

<a name="geometric-circle-example"></a>
**Example 9.1. Using the CIRCLE datatype JDBC**

```java
import java.sql.*;

import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGcircle;

public class GeometricTest {
    public static void main(String args[]) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/test";
        try (Connection conn = DriverManager.getConnection(url, "test", "")) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TEMP TABLE geomtest(mycirc circle)");
            }
            insertCircle(conn);
            retrieveCircle(conn);
        }
    }

    private static void insertCircle(Connection conn) throws SQLException {
        PGpoint center = new PGpoint(1, 2.5);
        double radius = 4;
        PGcircle circle = new PGcircle(center, radius);
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO geomtest(mycirc) VALUES (?)")) {
            ps.setObject(1, circle);
            ps.executeUpdate();
        }
    }

    private static void retrieveCircle(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("SELECT mycirc, area(mycirc) FROM geomtest")) {
                while (rs.next()) {
                    PGcircle circle = (PGcircle)rs.getObject(1);
                    double area = rs.getDouble(2);

                    System.out.println("Center (X, Y) = (" + circle.center.x + ", " + circle.center.y + ")");
                    System.out.println("Radius = " + circle.radius);
                    System.out.println("Area = " + area);
                }
            }
        }
    }
}
```
