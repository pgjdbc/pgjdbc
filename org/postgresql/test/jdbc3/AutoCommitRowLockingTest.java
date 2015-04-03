package org.postgresql.test.jdbc3;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import junit.framework.TestCase;

import org.postgresql.test.TestUtil;

/**
 * Test locking (SELECT FOR UPDATE) in AutoCommit on/off mode
 */
public class AutoCommitRowLockingTest extends TestCase {

	private Connection	_conn_1;
	private Connection	_conn_2;
	private Connection	_conn_3;
	private Connection	_conn_4;
	private Connection	_conn_5;

	/**
	 * New locking test
	 * 
	 * @param name
	 */
	public AutoCommitRowLockingTest(String name) {
		super(name);
	}

	/**
	 * Test behavior with active parameter and locking in AutoCommit off mode
	 * 
	 * @throws SQLException
	 */
	private final void activeAutoCommitOff() throws SQLException {
		_conn_1.setAutoCommit(false);
		_conn_2.setAutoCommit(false);
		_conn_3.setAutoCommit(false);
		_conn_4.setAutoCommit(false);
		_conn_5.setAutoCommit(false);
		// -- The behavior must be the same as the default -> we are in transaction --
		testTransactionnal(false);
	}

	/**
	 * Test behavior with active parameter and locking in AutoCommit on mode
	 * 
	 * @throws SQLException
	 */
	private final void activeAutoCommitOn() throws SQLException {
		_conn_1.setAutoCommit(true);
		_conn_2.setAutoCommit(true);
		_conn_3.setAutoCommit(true);
		_conn_4.setAutoCommit(true);
		_conn_5.setAutoCommit(true);
		// -- The behavior must be the same as in autoCommit off mode -> we are in transaction --
		testTransactionnal(true);
	}

	/**
	 * Test default behavior with locking in AutoCommit off mode
	 * 
	 * @throws SQLException
	 */
	private final void defaultAutoCommitOff() throws SQLException {
		_conn_1.setAutoCommit(false);
		_conn_2.setAutoCommit(false);
		_conn_3.setAutoCommit(false);
		_conn_4.setAutoCommit(false);
		_conn_5.setAutoCommit(false);
		// -- AutoCommit off -> transaction --
		testTransactionnal(false);
	}

	/**
	 * Test default behavior with locking in AutoCommit on mode
	 * 
	 * @throws SQLException
	 */
	private final void defaultAutoCommitOn() throws SQLException {

		_conn_1.setAutoCommit(true);
		_conn_2.setAutoCommit(true);
		_conn_3.setAutoCommit(true);
		_conn_4.setAutoCommit(true);
		_conn_5.setAutoCommit(true);

		Statement stmt_1;
		Statement stmt_2;
		Statement stmt_3;
		Statement stmt_4;
		Statement stmt_5;
		ResultSet rs_1;
		ResultSet rs_2;
		ResultSet rs_3;
		ResultSet rs_4;
		ResultSet rs_5;

		// FOR KEY SHARE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR SHARE -> no problem --
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();

			// -- try lock the same row with FOR NO KEY UPDATE -> no problem --
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
			while (rs_3.next()) {
				assertEquals(1, rs_3.getInt(1));
			}
			rs_3.close();

			// -- try lock the same row with FOR UPDATE -> no problem --
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
			while (rs_4.next()) {
				assertEquals(1, rs_4.getInt(1));
			}
			rs_4.close();

			// -- try lock the same row with FOR KEY SHARE -> no problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_5.next()) {
				assertEquals(1, rs_5.getInt(1));
			}
			rs_5.close();
		}
		rs_1.close();

		// FOR SHARE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR KEY SHARE -> no problem --
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();

			// -- try lock the same row with FOR NO KEY UPDATE -> no problem --
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
			while (rs_3.next()) {
				assertEquals(1, rs_3.getInt(1));
			}
			rs_3.close();

			// -- try lock the same row with FOR UPDATE -> no problem --
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
			while (rs_4.next()) {
				assertEquals(1, rs_4.getInt(1));
			}
			rs_4.close();

			// -- try lock the same row with FOR SHARE -> no problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
			while (rs_5.next()) {
				assertEquals(1, rs_5.getInt(1));
			}
			rs_5.close();
		}
		rs_1.close();

		// FOR NO KEY UPDATE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR KEY SHARE -> no problem--
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();

			// -- try lock the same row with FOR SHARE -> no problem--
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
			while (rs_3.next()) {
				assertEquals(1, rs_3.getInt(1));
			}
			rs_3.close();

			// -- try lock the same row with FOR UPDATE -> no problem --
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
			while (rs_4.next()) {
				assertEquals(1, rs_4.getInt(1));
			}
			rs_4.close();

			// -- try lock the same row with FOR NO KEY UPDATE -> no problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
			while (rs_5.next()) {
				assertEquals(1, rs_5.getInt(1));
			}
			rs_5.close();
		}
		rs_1.close();

		// FOR UPDATE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR KEY SHARE -> no problem--
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();

			// -- try lock the same row with FOR SHARE -> no problem--
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
			while (rs_3.next()) {
				assertEquals(1, rs_3.getInt(1));
			}
			rs_3.close();

			// -- try lock the same row with FOR NO KEY UPDATE -> no problem--
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
			while (rs_4.next()) {
				assertEquals(1, rs_4.getInt(1));
			}
			rs_4.close();

			// -- try lock the same row with FOR UPDATE -> no problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
			while (rs_5.next()) {
				assertEquals(1, rs_5.getInt(1));
			}
			rs_5.close();
		}
		rs_1.close();

		// -- try lock the same row with FOR KEY SHARE after resultset closed -> no problem--
		stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
		while (rs_2.next()) {
			assertEquals(1, rs_2.getInt(1));
		}
		rs_2.close();
	}

	/*
	 * (non-Javadoc)
	 * @see junit.framework.TestCase#setUp()
	 */
	protected void setUp() throws Exception {
		// -- Prepare table for test --
		_conn_1 = TestUtil.openDB();
		Statement stmt = _conn_1.createStatement();
		try {
			stmt.execute("DROP TABLE article CASCADE");
		} catch (Exception e) {
			// nothing
		}
		stmt.execute("CREATE TABLE article(no_product integer NOT NULL, price integer, CONSTRAINT pkey PRIMARY KEY (no_product))");
		stmt.execute("INSERT INTO article VALUES (1,10)");
		stmt.execute("INSERT INTO article VALUES (2,20)");
		stmt.execute("INSERT INTO article VALUES (3,30)");
		stmt.execute("INSERT INTO article VALUES (4,40)");
		stmt.close();
		TestUtil.closeDB(_conn_1);
	}

	/*
	 * (non-Javadoc)
	 * @see junit.framework.TestCase#tearDown()
	 */
	protected void tearDown() throws SQLException {
		Statement stmt = _conn_1.createStatement();
		stmt.execute("DROP TABLE article CASCADE");
		stmt.close();
		TestUtil.closeDB(_conn_1);
		TestUtil.closeDB(_conn_2);
		TestUtil.closeDB(_conn_3);
		TestUtil.closeDB(_conn_4);
		TestUtil.closeDB(_conn_5);
	}

	/**
	 * Test AutoCommit and row locking
	 * 
	 * @throws Exception
	 */
	public void testAutoCommit() throws Exception {

		// -- Test default --
		_conn_1 = TestUtil.openDB();
		_conn_2 = TestUtil.openDB();
		_conn_3 = TestUtil.openDB();
		_conn_4 = TestUtil.openDB();
		_conn_5 = TestUtil.openDB();
		// -- Test default with AutoCommit on --
		defaultAutoCommitOn();
		// -- Test default with AutoCommit off --
		defaultAutoCommitOff();
		// -- Close all connections --
		TestUtil.closeDB(_conn_1);
		TestUtil.closeDB(_conn_2);
		TestUtil.closeDB(_conn_3);
		TestUtil.closeDB(_conn_4);
		TestUtil.closeDB(_conn_5);

		// -- Test parameter active --
		Properties props = new Properties();
		props.setProperty("allowAutoCommitRowLocking", "true");
		_conn_1 = TestUtil.openDB(props);
		_conn_2 = TestUtil.openDB(props);
		_conn_3 = TestUtil.openDB(props);
		_conn_4 = TestUtil.openDB(props);
		_conn_5 = TestUtil.openDB(props);
		// -- Test parameter active with AutoCommit on --
		activeAutoCommitOn();
		// -- Test parameter active with AutoCommit off --
		activeAutoCommitOff();
	}

	/**
	 * Test in transactional mode
	 * 
	 * @param _autocommit true if AutoCommit on
	 * @throws SQLException
	 */
	private final void testTransactionnal(boolean _autocommit) throws SQLException {

		Statement stmt_1;
		Statement stmt_2;
		Statement stmt_3;
		Statement stmt_4;
		Statement stmt_5;
		ResultSet rs_1;
		ResultSet rs_2;
		ResultSet rs_3;
		ResultSet rs_4;
		ResultSet rs_5;

		// FOR KEY SHARE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR SHARE -> no problem --
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();
			if (!_autocommit) {
				_conn_2.commit();
			}

			// -- try lock the same row with FOR NO KEY UPDATE -> no problem --
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
			while (rs_3.next()) {
				assertEquals(1, rs_3.getInt(1));
			}
			rs_3.close();
			if (!_autocommit) {
				_conn_3.commit();
			}

			// -- try lock the same row with FOR UPDATE -> problem --
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
				fail("FOR KEY SHARE/FOR UPDATE : Expected exception thrown");
				rs_4.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_4.rollback();
				}
			}

			// -- try lock the same row with FOR KEY SHARE -> no problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_5.next()) {
				assertEquals(1, rs_5.getInt(1));
			}
			rs_5.close();
			if (!_autocommit) {
				_conn_5.commit();
			}
		}
		rs_1.close();
		if (!_autocommit) {
			_conn_1.commit();
		}

		// FOR SHARE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR KEY SHARE -> no problem --
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();
			if (!_autocommit) {
				_conn_2.commit();
			}

			// -- try lock the same row with FOR NO KEY UPDATE -> problem --
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
				fail("FOR SHARE/FOR NO KEY UPDATE : Expected exception thrown");
				rs_3.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_3.rollback();
				}
			}

			// -- try lock the same row with FOR UPDATE -> problem --
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
				fail("FOR SHARE/FOR UPDATE : Expected exception thrown");
				rs_4.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_4.rollback();
				}
			}

			// -- try lock the same row with FOR SHARE -> no problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
			while (rs_5.next()) {
				assertEquals(1, rs_5.getInt(1));
			}
			rs_5.close();
			if (!_autocommit) {
				_conn_5.commit();
			}
		}
		rs_1.close();
		if (!_autocommit) {
			_conn_1.commit();
		}

		// FOR NO KEY UPDATE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR KEY SHARE -> no problem--
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
			while (rs_2.next()) {
				assertEquals(1, rs_2.getInt(1));
			}
			rs_2.close();
			if (!_autocommit) {
				_conn_2.commit();
			}

			// -- try lock the same row with FOR SHARE -> problem--
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
				fail(" FOR NO KEY UPDATE/FOR SHARE : Expected exception thrown");
				rs_3.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_3.rollback();
				}
			}

			// -- try lock the same row with FOR UPDATE -> problem --
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
				fail("FOR NO KEY UPDATE/FOR UPDATE : Expected exception thrown");
				rs_4.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_4.rollback();
				}
			}

			// -- try lock the same row with FOR NO KEY UPDATE -> problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
				fail("FOR NO KEY UPDATE/FOR NO KEY UPDATE : Expected exception thrown");
				rs_5.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_5.rollback();
				}
			}
		}
		rs_1.close();
		if (!_autocommit) {
			_conn_1.commit();
		}

		// FOR UPDATE
		stmt_1 = _conn_1.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_1 = stmt_1.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");

		while (rs_1.next()) {
			assertEquals(1, rs_1.getInt(1));

			// -- try lock the same row with FOR KEY SHARE -> problem--
			stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
				fail("FOR UPDATE/FOR KEY SHARE : Expected exception thrown");
				rs_2.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_2.rollback();
				}
			}

			// -- try lock the same row with FOR SHARE -> problem--
			stmt_3 = _conn_3.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_3 = stmt_3.executeQuery("SELECT * FROM article where no_product=1 FOR SHARE NOWAIT");
				fail("FOR UPDATE/FOR SHARE : Expected exception thrown");
				rs_3.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_3.rollback();
				}
			}

			// -- try lock the same row with FOR NO KEY UPDATE -> problem--
			stmt_4 = _conn_4.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_4 = stmt_4.executeQuery("SELECT * FROM article where no_product=1 FOR NO KEY UPDATE NOWAIT");
				fail("FOR UPDATE/FOR NO KEY UPDATE : Expected exception thrown");
				rs_4.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_4.rollback();
				}
			}

			// -- try lock the same row with FOR UPDATE -> problem --
			stmt_5 = _conn_5.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
				ResultSet.CLOSE_CURSORS_AT_COMMIT);
			try {
				rs_5 = stmt_5.executeQuery("SELECT * FROM article where no_product=1 FOR UPDATE NOWAIT");
				fail("FOR UPDATE/FOR UPDATE : Expected exception thrown");
				rs_5.close();
			} catch (Exception e) {
				if (!_autocommit) {
					_conn_5.rollback();
				}
			}
		}
		rs_1.close();
		if (!_autocommit) {
			_conn_1.commit();
		}

		// -- try lock the same row with FOR KEY SHARE after resultset closed -> no problem--
		stmt_2 = _conn_2.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
			ResultSet.CLOSE_CURSORS_AT_COMMIT);
		rs_2 = stmt_2.executeQuery("SELECT * FROM article where no_product=1 FOR KEY SHARE NOWAIT");
		while (rs_2.next()) {
			assertEquals(1, rs_2.getInt(1));
		}
		rs_2.close();
		if (!_autocommit) {
			_conn_2.commit();
		}
	}

}
