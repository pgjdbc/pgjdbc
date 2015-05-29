package org.postgresql.jdbc2;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * This class closes the statements that were not closed by the application.
 * The idea of using a separate class for handling finalization is to avoid usage
 * of finalizer in the base connection class since finalizers retain garbage longer
 * than regular objects and finalization is not scalable yet in all the JMVs.
 */
/* package */ class StatementFinalizer
{
    /**
     * The updater is used to implement compare and swap on the {@link #statement} field.
     * We could use {@link java.util.concurrent.atomic.AtomicReference}, however it would
     * increase heap size retained by the finalizer.
     */
    private AtomicReferenceFieldUpdater<StatementFinalizer, Statement> STATEMENT_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(StatementFinalizer.class, Statement.class, "statement");

    private volatile Statement statement;

    /**
     * @param statement not null resource that should be close when Statement leak
     */
    public StatementFinalizer(Statement statement)
    {
        this.statement = statement;
    }

    protected void finalize() throws Throwable
    {
        try
        {
            Statement statement = shouldClose();
            if(statement != null && !statement.isClosed())
            {
                statement.close();
            }
        }
        finally
        {
            super.finalize();
        }
    }

    /**
     * Returns {@code true} non-null {@link Statement} if {@link #shouldClose()} was never yet called.
     *
     * <p>Note: in general finalizer thread might compete with application thread
     * trying to close the connection, so we need at least some race condition prevention
     * logic here. The idea behind {@link #shouldClose()} is to make sure only first caller would get
     * non-null reference.
     * @return non-null {@link Statement} if {@link #shouldClose()} was never yet called.
     */
    public Statement shouldClose()
    {
        return STATEMENT_UPDATER.getAndSet(this, null);
    }

    /**
     * Cleanups the finalizer in order to minimize garbage used by the finalizer when
     * the {@link Statement} is closed in a regular way.
     */
    public void cleanup()
    {
        statement = null;
    }
}
