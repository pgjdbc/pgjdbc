/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/ServerErrorMessage.java,v 1.6 2005/01/11 08:25:49 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

import org.postgresql.Driver;
import java.util.Hashtable;

public class ServerErrorMessage
{

    private static final Character SEVERITY = new Character('S');
    private static final Character MESSAGE = new Character('M');
    private static final Character DETAIL = new Character('D');
    private static final Character HINT = new Character('H');
    private static final Character POSITION = new Character('P');
    private static final Character WHERE = new Character('W');
    private static final Character FILE = new Character('F');
    private static final Character LINE = new Character('L');
    private static final Character ROUTINE = new Character('R');
    private static final Character SQLSTATE = new Character('C');

    private Hashtable m_mesgParts;

    public ServerErrorMessage(String p_serverError)
    {
        char[] l_chars = p_serverError.toCharArray();
        int l_pos = 0;
        int l_length = l_chars.length;
        m_mesgParts = new Hashtable();
        while (l_pos < l_length)
        {
            char l_mesgType = l_chars[l_pos];
            if (l_mesgType != '\0')
            {
                l_pos++;
                int l_startString = l_pos;
                while (l_chars[l_pos] != '\0' && l_pos < l_length)
                {
                    l_pos++;
                }
                String l_mesgPart = new String(l_chars, l_startString, l_pos - l_startString);
                m_mesgParts.put(new Character(l_mesgType), l_mesgPart);
            }
            l_pos++;
        }
    }

    public String getSQLState()
    {
        return (String)m_mesgParts.get(SQLSTATE);
    }

    public String getMessage()
    {
        return (String)m_mesgParts.get(MESSAGE);
    }

    public String toString()
    {
        //Now construct the message from what the server sent
        //The general format is:
        //SEVERITY: Message \n
        //  Detail: \n
        //  Hint: \n
        //  Position: \n
        //  Where: \n
        //  Location: File:Line:Routine \n
        //  SQLState: \n
        //
        //Normally only the message and detail is included.
        //If INFO level logging is enabled then detail, hint, position and where are
        //included.  If DEBUG level logging is enabled then all information
        //is included.

        StringBuffer l_totalMessage = new StringBuffer();
        String l_message = (String)m_mesgParts.get(SEVERITY);
        if (l_message != null)
            l_totalMessage.append(l_message).append(": ");
        l_message = (String)m_mesgParts.get(MESSAGE);
        if (l_message != null)
            l_totalMessage.append(l_message);
        if (Driver.logInfo)
        {
            l_message = (String)m_mesgParts.get(DETAIL);
            if (l_message != null)
                l_totalMessage.append("\n  ").append(GT.tr("Detail: {0}", l_message));
            l_message = (String)m_mesgParts.get(HINT);
            if (l_message != null)
                l_totalMessage.append("\n  ").append(GT.tr("Hint: {0}", l_message));
            l_message = (String)m_mesgParts.get(POSITION);
            if (l_message != null)
                l_totalMessage.append("\n  ").append(GT.tr("Position: {0}", l_message));
            l_message = (String)m_mesgParts.get(WHERE);
            if (l_message != null)
                l_totalMessage.append("\n  ").append(GT.tr("Where: {0}", l_message));
        }
        if (Driver.logDebug)
        {
            String l_file = (String)m_mesgParts.get(FILE);
            String l_line = (String)m_mesgParts.get(LINE);
            String l_routine = (String)m_mesgParts.get(ROUTINE);
            if (l_file != null || l_line != null || l_routine != null)
                l_totalMessage.append("\n  ").append(GT.tr("Location: File: {0}, Routine: {1}, Line: {2}", new Object[] {l_file, l_routine, l_line}));
            l_message = (String)m_mesgParts.get(SQLSTATE);
            if (l_message != null)
                l_totalMessage.append("\n  ").append(GT.tr("Server SQLState: {0}", l_message));
        }

        return l_totalMessage.toString();
    }
}
