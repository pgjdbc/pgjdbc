package org.postgresql.jdbc3;

import java.sql.SQLException;
import java.sql.Savepoint;
import org.postgresql.util.PSQLException;

public class PSQLSavepoint implements Savepoint {

	private boolean _isValid;
	private boolean _isNamed;
	private int _id;
	private String _name;

	public PSQLSavepoint(int id) {
		_isValid = true;
		_isNamed = false;
		_id = id;
	}
	
	public PSQLSavepoint(String name) {
		_isValid = true;
		_isNamed = true;
		_name = name;
	}

	public int getSavepointId() throws SQLException {
		if (!_isValid)
			throw new PSQLException("postgresql.savepoint.invalid");

		if (_isNamed)
			throw new PSQLException("postgresql.savepoint.notunnamed");

		return _id;
	}

	public String getSavepointName() throws SQLException {
		if (!_isValid)
			throw new PSQLException("postgresql.savepoint.invalid");

		if (!_isNamed)
			throw new PSQLException("postgresql.savepoint.notnamed");

		return _name;
	}

	public void invalidate() {
		_isValid = false;
	}

	public String getPGName() throws SQLException {
		if (!_isValid)
			throw new PSQLException("postgresql.savepoint.invalid");

		if (_isNamed) {
			// We need to quote and escape the name in case it
			// contains spaces/quotes/backslashes.
			//
			StringBuffer sb = new StringBuffer(_name.length() + 2);
			sb.append("\"");
			for (int i=0; i < _name.length(); i++) {
				char c = _name.charAt(i);
				if (c == '\\' || c == '"')
					sb.append(c);
				sb.append(c);
			}
			sb.append("\"");
			return sb.toString();
		}

		return "JDBC_SAVEPOINT_" + _id;
	}

}
