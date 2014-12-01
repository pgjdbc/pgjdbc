/*
 * -------------------------------------------------------------------------
 *
 *  Copyright (c) 2003-2014, PostgreSQL Global Development Group
 *
 *
 * -------------------------------------------------------------------------
 */

package org.postgresql.util;

import java.util.Timer;

/**
 * @author Lukas Krejci
 */
public final class SharedTimer
{
    private static Timer timer;
    private static int loanCount;

    // no instances of this class
    private SharedTimer() {

    }

    public static synchronized Loan loan()
    {
        if (timer == null)
        {
            timer = new Timer("Postgresql JDBC shared statement cancellation timer", true);
        }

        loanCount++;

        return new Loan();
    }

    /**
     * @see java.util.Timer#purge()
     */
    public static int purge()
    {
        return timer == null ? 0 : timer.purge();
    }

    /**
     * Just for testing purposes.
     */
    public static synchronized int getLoanCount()
    {
        return loanCount;
    }

    private static synchronized void release()
    {
        loanCount--;

        // clean out cancelled tasks
        timer.purge();

        if (loanCount == 0)
        {
            timer.cancel();
            timer = null;
        }
    }

    /**
     * A loan of a shared timer.
     */
    public static final class Loan
    {
        private boolean returned;

        private Loan() {

        }

        /**
         * @return the loaned timer. Don't keep a reference to the timer itself because it might be cancelled if this
         *         loan object is {@link #release()}ed.
         */
        public Timer timer()
        {
            return returned ? null : timer;
        }

        /**
         * Releases the loan making it no longer usable.
         */
        public void release()
        {
            if (!returned)
            {
                returned = true;
                SharedTimer.release();
            }
        }
    }
}
