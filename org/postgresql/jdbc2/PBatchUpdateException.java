package org.postgresql.jdbc2;

import org.postgresql.util.MessageTranslator;

/*
 * This class extends java.sql.BatchUpdateException, and provides our
 * internationalisation handling.
 */
class PBatchUpdateException extends java.sql.BatchUpdateException
{

	public PBatchUpdateException(
		String error, Object arg1, Object arg2, int[] updateCounts )
	{

		super(translate(error, new Object[] { arg1, arg2 }), updateCounts);
	}

	private static String translate(String error, Object[] args)
	{
		return MessageTranslator.translate(error, args);
	}
}
