/*-------------------------------------------------------------------------
 *
 * V2Query.java
 *	  Query implementation for all queries via the V2 protocol.
 *
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * Copyright (c) 2004, Open Cloud Limited.
 *
 * IDENTIFICATION
 *	  $PostgreSQL$
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.core.v2;

import java.util.Vector;
import org.postgresql.core.*;

/**
 * Query implementation for all queries via the V2 protocol.
 */
class V2Query implements Query {
	V2Query(String query, boolean withParameters) {
		if (!withParameters) {			
			fragments = new String[] { query };
			return;
		}

		// Parse query and find parameter placeholders.

		Vector v = new Vector();
		boolean inQuotes = false;
		int lastParmEnd = 0;

		for (int i = 0; i < query.length(); ++i)
		{
			int c = query.charAt(i);

			if (c == '\'')
				inQuotes = !inQuotes;
			if (c == '?' && !inQuotes)
			{
				v.addElement(query.substring (lastParmEnd, i));
				lastParmEnd = i + 1;
			}
		}

		v.addElement(query.substring (lastParmEnd, query.length()));
		
		fragments = new String[v.size()];
		for (int i = 0 ; i < fragments.length; ++i)
			fragments[i] = (String)v.elementAt(i);
	}

	public ParameterList createParameterList() {
		if (fragments.length == 1)
			return NO_PARAMETERS;

		return new SimpleParameterList(fragments.length - 1);
	}

	public String toString(ParameterList parameters) {
		StringBuffer sbuf = new StringBuffer(fragments[0]);
		for (int i = 1; i < fragments.length; ++i) {
			if (parameters == null)
				sbuf.append("?");
			else
				sbuf.append(parameters.toString(i));
			sbuf.append(fragments[i]);
		}
		return sbuf.toString();
	}

	public void close() {}

	String[] getFragments() { return fragments; }

	private static final ParameterList NO_PARAMETERS = new SimpleParameterList(0);

	private final String[] fragments;      // Query fragments, length == # of parameters + 1
}

