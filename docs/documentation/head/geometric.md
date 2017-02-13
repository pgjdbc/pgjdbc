---
layout: default_docs
title: Geometric Data Types
header: Chapter 9. PostgreSQL™ Extensions to the JDBC API
resource: media
previoustitle: Chapter 9. PostgreSQL™ Extensions to the JDBC API
previous: ext.html
nexttitle: Large Objects
next: largeobjects.html
---

PostgreSQL™ has a set of data types that can store geometric features into a
table. These include single points, lines, and polygons.  We support these types
in Java with the org.postgresql.geometric package. Please consult the Javadoc
for the details of available classes and features metioned in [Chapter 12, *Further Reading*](reading.html).

<a name="geometric-circle-example"></a>
**Example 9.1. Using the CIRCLE datatype JDBC**

import java.sql.*;

import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGcircle;

public class GeometricTest {

	public static void main(String args[]) throws Exception {
		Class.forName("org.postgresql.Driver");
		String url = "jdbc:postgresql://localhost:5432/test";

		Connection conn = DriverManager.getConnection(url,"test","");

		Statement stmt = conn.createStatement();
		stmt.execute("CREATE TEMP TABLE geomtest(mycirc circle)");
		stmt.close();

		insertCircle(conn);
		retrieveCircle(conn);
		conn.close();
	}

	private static void insertCircle(Connection conn) throws SQLException {

		PGpoint center = new PGpoint(1, 2.5);
		double radius = 4;
		PGcircle circle = new PGcircle(center, radius);

		PreparedStatement ps = conn.prepareStatement("INSERT INTO geomtest(mycirc) VALUES (?)");
		ps.setObject(1, circle);
		ps.executeUpdate();
		ps.close();
	}
	
	private static void retrieveCircle(Connection conn) throws SQLException {
		Statement stmt = conn.createStatement();
		ResultSet rs = stmt.executeQuery("SELECT mycirc, area(mycirc) FROM geomtest");
		rs.next();
		PGcircle circle = (PGcircle)rs.getObject(1);
		double area = rs.getDouble(2);

		PGpoint center = circle.center;
		double radius = circle.radius;

		System.out.println("Center (X, Y) = (" + center.x + ", " + center.y + ")");
		System.out.println("Radius = " + radius);
		System.out.println("Area = " + area);
	}
}