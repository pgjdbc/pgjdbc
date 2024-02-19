/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class ParameterInjectionTest {
    @Test
    public void negateParameter() throws Exception {
        try (Connection conn = TestUtil.openDB()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT -?");

            stmt.setInt(1, 1);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getMetaData().getColumnCount(), "number of result columns must match");
                int value = rs.getInt(1);
                assertEquals(-1, value, "Input value 1");
            }

            stmt.setInt(1, -1);
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getMetaData().getColumnCount(), "number of result columns must match");
                int value = rs.getInt(1);
                assertEquals(1, value, "Input value -1");
            }
        }
    }

    @Test
    public void negateParameterWithContinuation() throws Exception {
        try (Connection conn = TestUtil.openDB()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT -?, ?");

            stmt.setInt(1, 1);
            stmt.setString(2, "\nWHERE false --");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "ResultSet should contain a row");
                assertEquals(2, rs.getMetaData().getColumnCount(), "rs.getMetaData().getColumnCount(");
                int value = rs.getInt(1);
                assertEquals(-1, value);
            }

            stmt.setInt(1, -1);
            stmt.setString(2, "\nWHERE false --");
            try (ResultSet rs = stmt.executeQuery()) {
                assertTrue(rs.next(), "ResultSet should contain a row");
                assertEquals(2, rs.getMetaData().getColumnCount(), "rs.getMetaData().getColumnCount(");
                int value = rs.getInt(1);
                assertEquals(1, value);
            }
        }
    }
}
