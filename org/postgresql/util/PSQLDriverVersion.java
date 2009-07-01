/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2008, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/util/PSQLDriverVersion.java,v 1.25.2.1 2008/11/16 13:45:43 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.util;

import org.postgresql.Driver;

/**
 * This class holds the current build number and a utility program to print
 * it and the file it came from.  The primary purpose of this is to keep
 * from filling the cvs history of Driver.java.in with commits simply
 * changing the build number.  The utility program can also be helpful for
 * people to determine what version they really have and resolve problems
 * with old and unknown versions located somewhere in the classpath.
 */
public class PSQLDriverVersion {

    public final static int buildNumber = 605;

    public static void main(String args[]) {
        java.net.URL url = Driver.class.getResource("/org/postgresql/Driver.class");
        System.out.println(Driver.getVersion());
        System.out.println("Found in: " + url);
    }

}
