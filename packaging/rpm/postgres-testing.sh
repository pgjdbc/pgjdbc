#! /bin/sh

# Do some "integration" testing against running PostgreSQL server.

# This file is to be sourced.

: ${PGTESTS_DATADIR=`pwd`/datadir}
: ${PGTESTS_ADMIN=`id -u -n`}
: ${PGTESTS_ADMINDB=$PGTESTS_ADMIN}
: ${PGTESTS_ADMINPASS=$PGTESTS_ADMIN}
: ${PGTESTS_PORT=54321}
: ${PGTESTS_SOCKETDIR=/tmp}
: ${PGTESTS_USERS=test:test}
: ${PGTESTS_DATABASES=test:test}

# Stop the old cluster and/or remove it's data.
: ${PGTESTS_STARTCLEANUP=:}

# Cleanup once we exit the script.
: ${PGTESTS_CLEANUP=:}

# Cleanup once we exit the script.
: ${PGTESTS_CLEANUP=:}

export PGPORT=$PGTESTS_PORT
export PGHOST=$PGTESTS_SOCKETDIR

warning ()
{
    echo >&2 " ! $*"
}


__trap_cb ()
{
    IFS=' '
    for __func in $__TRAP_ACTIONS
    do
        $__func
    done
}
trap __trap_cb EXIT


__pgtests_initdb ()
{
    initdb "$PGTESTS_DATADIR" -U "$PGTESTS_ADMIN" \
        --auth-local=peer --auth-host=md5 \
        ${PGTESTS_LOCALE+--locale="$PGTESTS_LOCALE"}
}


__pgtests_start ()
{
    pg_ctl -D "$PGTESTS_DATADIR" -l "$PGTESTS_DATADIR"/start.log start -o "-k $PGTESTS_SOCKETDIR -p $PGTESTS_PORT" -w
}


__pgtests_create_admins_db ()
{
    createdb -h "$PGTESTS_SOCKETDIR" "$PGTESTS_ADMINDB" --owner "$PGTESTS_ADMIN" -p "$PGTESTS_PORT"
}


__pgtests_passwd()
{
    psql -d postgres --set=user="$1" --set=pass="$2" -tA \
        <<<"ALTER USER :\"user\" WITH ENCRYPTED PASSWORD :'pass';"
}

pgtests_start ()
{
    unset __TRAP_ACTIONS

    if $PGTESTS_STARTCLEANUP; then
        # We don't plan to be serious here.  This pgtests_* effort is just to
        # ease _testing_ against running postgresql server without too much
        # writing.
        if test -f "$PGTESTS_DATADIR"/postmaster.pid; then
            # Give it a try.
            warning "Seems like server works, trying to stop."
            pg_ctl stop -D "$PGTESTS_DATADIR" -w
        fi

        # Cleanup testing directory
        if test -e "$PGTESTS_DATADIR"; then
            warning "Removing old data directory."
            rm -r "$PGTESTS_DATADIR"
        fi
    fi

    __pgtests_initdb && __TRAP_ACTIONS="pgtests_cleanup $__TRAP_ACTIONS"
    __pgtests_start  && __TRAP_ACTIONS="pgtests_stop $__TRAP_ACTIONS"
    __pgtests_create_admins_db

    __pgtests_passwd "$PGTESTS_ADMIN" "$PGTESTS_ADMINPASS"


    for _pgt_user in $PGTESTS_USERS
    do
        save_IFS=$IFS
        IFS=:
        _user=
        _pass=
        for _part in $_pgt_user
        do
            if test -z "$_user"; then
                _user=$_part
            else
                _pass=$_part
            fi
        done

        createuser "$_user"
        __pgtests_passwd "$_user" "$_pass"
        IFS=$save_IFS
    done


    for _pgt_db in $PGTESTS_DATABASES
    do
        save_IFS=$IFS
        IFS=:
        _db=
        _user=
        for _part in $_pgt_db
        do
            if test -z "$_user"; then
                _user=$_part
            else
                _db=$_part
            fi
        done

        createdb "$_db" --owner "$_part"

        IFS=$save_IFS
    done
}


__clean_trap_action ()
{
    __new_actions=
    for __action in $__TRAP_ACTIONS
    do
        if test "$__action" = "$1"; then
            :
        else
            __new_actions="$__action $__new_actions"
        fi
    done

    __TRAP_ACTIONS=$__new_actions
}


pgtests_cleanup ()
{
    if $PGTESTS_CLEANUP && $PGTESTS_AUTOSTOP; then
        rm -r "$PGTESTS_DATADIR"
    fi
    __clean_trap_action pgtests_cleanup
}


pgtests_stop ()
{
    if $PGTESTS_AUTOSTOP; then
        pg_ctl stop -D "$PGTESTS_DATADIR" -w
    fi
    __clean_trap_action pgtests_stop
}
