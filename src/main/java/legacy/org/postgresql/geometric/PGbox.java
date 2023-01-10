/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.geometric;

import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PGobject;
import legacy.org.postgresql.util.PGtokenizer;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

import java.io.Serializable;
import java.sql.SQLException;

/**
 *     This represents the box datatype within org.postgresql.
 */
public class PGbox extends PGobject implements Serializable, Cloneable
{
    /**
     * These are the two points.
     */
    public PGpoint point[] = new PGpoint[2];

    /**
     * @param x1 first x coordinate
     * @param y1 first y coordinate
     * @param x2 second x coordinate
     * @param y2 second y coordinate
     */
    public PGbox(double x1, double y1, double x2, double y2)
    {
        this();
        this.point[0] = new PGpoint(x1, y1);
        this.point[1] = new PGpoint(x2, y2);
    }

    /**
     * @param p1 first point
     * @param p2 second point
     */
    public PGbox(PGpoint p1, PGpoint p2)
    {
        this();
        this.point[0] = p1;
        this.point[1] = p2;
    }

    /**
     * @param s Box definition in PostgreSQL syntax
     * @exception SQLException if definition is invalid
     */
    public PGbox(String s) throws SQLException
    {
        this();
        setValue(s);
    }

    /**
     * Required constructor
     */
    public PGbox()
    {
        setType("box");
    }

    /**
     * This method sets the value of this object. It should be overidden,
     * but still called by subclasses.
     *
     * @param value a string representation of the value of the object
     * @exception SQLException thrown if value is invalid for this type
     */
    public void setValue(String value) throws SQLException
    {
        PGtokenizer t = new PGtokenizer(value, ',');
        if (t.getSize() != 2)
            throw new PSQLException(GT.tr("Conversion to type {0} failed: {1}.", new Object[]{type,value}), PSQLState.DATA_TYPE_MISMATCH);

        point[0] = new PGpoint(t.getToken(0));
        point[1] = new PGpoint(t.getToken(1));
    }

    /**
     * @param obj Object to compare with
     * @return true if the two boxes are identical
     */
    public boolean equals(Object obj)
    {
        if (obj instanceof PGbox)
        {
            PGbox p = (PGbox)obj;

            // Same points.
            if (p.point[0].equals(point[0]) && p.point[1].equals(point[1]))
                return true;

            // Points swapped.
            if (p.point[0].equals(point[1]) && p.point[1].equals(point[0]))
                return true;

            // Using the opposite two points of the box:
            //  (x1,y1),(x2,y2)  ->   (x1,y2),(x2,y1)
            if (p.point[0].x == point[0].x && p.point[0].y == point[1].y &&
                    p.point[1].x == point[1].x && p.point[1].y == point[0].y)
                return true;

            // Using the opposite two points of the box, and the points are swapped
            //  (x1,y1),(x2,y2)  ->   (x2,y1),(x1,y2)
            if (p.point[0].x == point[1].x && p.point[0].y == point[0].y &&
                    p.point[1].x == point[0].x && p.point[1].y == point[1].y)
                return true;
        }

        return false;
    }

    public int hashCode()
    {
        // This relies on the behaviour of point's hashcode being an exclusive-OR of
        // its X and Y components; we end up with an exclusive-OR of the two X and
        // two Y components, which is equal whenever equals() would return true
        // since xor is commutative.
        return point[0].hashCode() ^ point[1].hashCode();
    }

    public Object clone() throws CloneNotSupportedException
    {
        PGbox newPGbox = (PGbox) super.clone();
        if( newPGbox.point != null )
        {
            newPGbox.point = (PGpoint[]) newPGbox.point.clone();
            for( int i = 0; i < newPGbox.point.length; ++i )
                if( newPGbox.point[i] != null )
                    newPGbox.point[i] = (PGpoint) newPGbox.point[i].clone();
        }
        return newPGbox;
    }

    /**
     * @return the PGbox in the syntax expected by org.postgresql
     */
    public String getValue()
    {
        return point[0].toString() + "," + point[1].toString();
    }
}
