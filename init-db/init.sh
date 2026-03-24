#!/bin/bash
set -e

# Function to create a database and grant privileges
create_user_and_database() {
    local database=$1
    echo "  Creating database '$database'..."
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
        CREATE DATABASE $database;
        -- Use double quotes for the role name because "user" is a reserved keyword
        GRANT ALL PRIVILEGES ON DATABASE $database TO "$POSTGRES_USER";

        -- Connect to the new database to grant schema permissions
        \c $database
        GRANT ALL ON SCHEMA public TO "$POSTGRES_USER";
EOSQL
}

# Check if multiple databases are requested via environment variable
if [ -n "$POSTGRES_MULTIPLE_DATABASES" ]; then
    echo "Multiple database creation requested: $POSTGRES_MULTIPLE_DATABASES"
    for db in $(echo $POSTGRES_MULTIPLE_DATABASES | tr ',' ' '); do
        create_user_and_database $db
    done
    echo "All databases created successfully."
fi