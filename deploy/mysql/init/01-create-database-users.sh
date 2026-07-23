#!/bin/sh
# Runs only while the MySQL image initializes an empty data volume. The application identity gets
# DML only; Flyway connects as MYSQL_MIGRATION_USER in the migration Job or local Compose startup.
#
# Keep this file executable: the MySQL image invokes .sh files directly from
# /docker-entrypoint-initdb.d, including when the directory is bind-mounted by Docker Desktop.
set -eu

mysql --protocol=socket --user=root --password="${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE USER IF NOT EXISTS '${MYSQL_MIGRATION_USER}'@'%' IDENTIFIED BY '${MYSQL_MIGRATION_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_MIGRATION_USER}'@'%';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '${MYSQL_USER}'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
SQL
