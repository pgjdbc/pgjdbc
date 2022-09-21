package p1;
import java.sql.*;

public interface NewStatement extends Statement
{
    public String executeQueryStringJson(String sql) throws SQLException;
}