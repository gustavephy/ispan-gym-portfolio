/*
 * Read-only SQL Server diagnostics for the wpower_gym development database.
 * This script does not INSERT, UPDATE, DELETE, ALTER, or CREATE anything.
 * Run it in SSMS using the same server/login configured in DB_URL.
 */

SET NOCOUNT ON;

/* 1. Which server, instance, database, login, and network endpoint am I using? */
SELECT
    CAST(SERVERPROPERTY('MachineName') AS NVARCHAR(128)) AS machine_name,
    CAST(SERVERPROPERTY('ServerName') AS NVARCHAR(128)) AS server_name,
    CAST(SERVERPROPERTY('InstanceName') AS NVARCHAR(128)) AS instance_name,
    CAST(SERVERPROPERTY('Edition') AS NVARCHAR(128)) AS edition,
    CAST(SERVERPROPERTY('ProductVersion') AS NVARCHAR(128)) AS product_version,
    DB_NAME() AS current_database,
    ORIGINAL_LOGIN() AS original_login,
    SUSER_SNAME() AS server_login,
    USER_NAME() AS database_user,
    CONNECTIONPROPERTY('net_transport') AS net_transport,
    CONNECTIONPROPERTY('auth_scheme') AS auth_scheme,
    CONNECTIONPROPERTY('local_net_address') AS server_ip,
    CONNECTIONPROPERTY('local_tcp_port') AS server_tcp_port,
    CONNECTIONPROPERTY('client_net_address') AS client_ip;

/* 2. Database status and settings. */
SELECT
    name,
    state_desc,
    user_access_desc,
    recovery_model_desc,
    compatibility_level,
    collation_name,
    create_date
FROM sys.databases
WHERE name = DB_NAME();

/* 3. Physical MDF/NDF/LDF locations and current sizes. */
SELECT
    name AS logical_file_name,
    type_desc,
    physical_name,
    CAST(size * 8.0 / 1024 AS DECIMAL(18,2)) AS size_mb,
    CASE max_size
        WHEN -1 THEN NULL
        ELSE CAST(max_size * 8.0 / 1024 AS DECIMAL(18,2))
    END AS max_size_mb,
    growth,
    is_percent_growth
FROM sys.database_files
ORDER BY type_desc, file_id;

/* 4. Confirm the expected core tables exist in the current database. */
WITH expected_tables(table_name) AS (
    SELECT name
    FROM (VALUES
        ('users'),
        ('products'),
        ('cart_items'),
        ('orders'),
        ('order_items')
    ) AS required(name)
)
SELECT
    e.table_name,
    CASE WHEN t.object_id IS NULL THEN 'MISSING' ELSE 'OK' END AS table_status
FROM expected_tables e
LEFT JOIN sys.tables t
    ON t.name = e.table_name
   AND SCHEMA_NAME(t.schema_id) = 'dbo'
ORDER BY e.table_name;

/* 5. List columns and database types for the current module. */
SELECT
    SCHEMA_NAME(t.schema_id) AS schema_name,
    t.name AS table_name,
    c.column_id,
    c.name AS column_name,
    ty.name AS data_type,
    CASE
        WHEN ty.name IN ('nvarchar', 'nchar') AND c.max_length > 0 THEN c.max_length / 2
        ELSE c.max_length
    END AS max_length,
    c.precision,
    c.scale,
    c.is_nullable,
    c.is_identity,
    c.is_computed
FROM sys.tables t
JOIN sys.columns c ON c.object_id = t.object_id
JOIN sys.types ty ON ty.user_type_id = c.user_type_id
WHERE t.name IN ('users', 'products', 'cart_items', 'orders', 'order_items')
ORDER BY t.name, c.column_id;

/* 6. Approximate row counts without scanning entire tables. */
SELECT
    SCHEMA_NAME(t.schema_id) AS schema_name,
    t.name AS table_name,
    SUM(p.rows) AS row_count
FROM sys.tables t
JOIN sys.partitions p ON p.object_id = t.object_id
WHERE p.index_id IN (0, 1)
  AND t.name IN ('users', 'products', 'cart_items', 'orders', 'order_items')
GROUP BY t.schema_id, t.name
ORDER BY t.name;

/* 7. Show safe, non-secret test-data identifiers for Postman. */
SELECT id, email, role, status
FROM dbo.users
ORDER BY id;

SELECT
    id,
    type,
    name,
    price,
    stock,
    sessions_count,
    validity_days,
    status
FROM dbo.products
ORDER BY id;

SELECT
    id AS cart_item_id,
    user_id,
    product_id,
    quantity,
    created_at,
    updated_at
FROM dbo.cart_items
ORDER BY user_id, id;

