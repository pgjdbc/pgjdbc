/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core.v2;

import legacy.org.postgresql.core.ParameterList;
import legacy.org.postgresql.core.Parser;
import legacy.org.postgresql.core.ProtocolConnection;
import legacy.org.postgresql.core.Query;

import java.util.Vector;

/**
 * Query implementation for all queries via the V2 protocol.
 */
class V2Query implements Query {
    V2Query(String query, boolean withParameters, ProtocolConnection pconn) {

        useEStringSyntax = pconn.getServerVersion() != null
                && pconn.getServerVersion().compareTo("8.1") > 0;
        boolean stdStrings = pconn.getStandardConformingStrings();

        if (!withParameters)
        {
            fragments = new String[] { query };
            return ;
        }

        // Parse query and find parameter placeholders.

        Vector v = new Vector();
        int lastParmEnd = 0;

        char []aChars = query.toCharArray();

        for (int i = 0; i < aChars.length; ++i)
        {
            switch (aChars[i])
            {
            case '\'': // single-quotes
                i = Parser.parseSingleQuotes(aChars, i, stdStrings);
                break;

            case '"': // double-quotes
                i = Parser.parseDoubleQuotes(aChars, i);
                break;

            case '-': // possibly -- style comment
                i = Parser.parseLineComment(aChars, i);
                break;

            case '/': // possibly /* */ style comment
                i = Parser.parseBlockComment(aChars, i);
                break;
            
            case '$': // possibly dollar quote start
                i = Parser.parseDollarQuotes(aChars, i);
                break;

            case '?':
                v.addElement(query.substring (lastParmEnd, i));
                lastParmEnd = i + 1;
                break;

            default:
                break;
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

        return new SimpleParameterList(fragments.length - 1, useEStringSyntax);
    }

    public String toString(ParameterList parameters) {
        StringBuffer sbuf = new StringBuffer(fragments[0]);
        for (int i = 1; i < fragments.length; ++i)
        {
            if (parameters == null)
                sbuf.append("?");
            else
                sbuf.append(parameters.toString(i));
            sbuf.append(fragments[i]);
        }
        return sbuf.toString();
    }

    public void close() {
    }

    String[] getFragments() {
        return fragments;
    }

    private static final ParameterList NO_PARAMETERS = new SimpleParameterList(0, false);

    private final String[] fragments;      // Query fragments, length == # of parameters + 1
    
    private final boolean useEStringSyntax; // whether escaped string syntax should be used
}

