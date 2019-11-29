This Docker Compose script helps to start a PostgreSQL instance for tests

Typical usage:

    docker-compose up # starts the database

    ...
    Ctrl+C

    docker-compose up -d # launch the container in background

    docker-compose rm # removes the container (e.g. to recreate the db)
