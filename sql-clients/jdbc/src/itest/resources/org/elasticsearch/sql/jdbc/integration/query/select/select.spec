//
// Basic SELECT
//

wildcardWithOrder
SELECT * FROM "emp.emp" ORDER BY emp_no;
column
SELECT last_name FROM "emp.emp" ORDER BY emp_no; 
columnWithAlias
SELECT last_name AS l FROM "emp.emp" ORDER BY emp_no;
columnWithAliasNoAs
SELECT last_name l FROM "emp.emp" ORDER BY emp_no;
multipleColumnsNoAlias
SELECT first_name, last_name FROM "emp.emp" ORDER BY emp_no;
multipleColumnWithAliasWithAndWithoutAs
SELECT first_name f, last_name AS l FROM "emp.emp" ORDER BY emp_no;

//
// SELECT with LIMIT
//

wildcardWithLimit
SELECT * FROM "emp.emp" ORDER BY emp_no LIMIT 5;
wildcardWithOrderWithLimit
SELECT * FROM "emp.emp" ORDER BY emp_no LIMIT 5;
columnWithLimit
SELECT last_name FROM "emp.emp" ORDER BY emp_no LIMIT 5; 
columnWithAliasWithLimit
SELECT last_name AS l FROM "emp.emp" ORDER BY emp_no LIMIT 5;
columnWithAliasNoAsWithLimit
SELECT last_name l FROM "emp.emp" ORDER BY emp_no LIMIT 5;
multipleColumnsNoAliasWithLimit
SELECT first_name, last_name FROM "emp.emp" ORDER BY emp_no LIMIT 5;
multipleColumnWithAliasWithAndWithoutAsWithLimit
SELECT first_name f, last_name AS l FROM "emp.emp" ORDER BY emp_no LIMIT 5;


//
// SELECT with CAST
//
//castWithLiteralToInt
//SELECT CAST(1 AS INT);
castOnColumnNumberToVarchar
SELECT CAST(emp_no AS VARCHAR) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;
castOnColumnNumberToLong
SELECT CAST(emp_no AS BIGINT) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;
castOnColumnNumberToSmallint
SELECT CAST(emp_no AS SMALLINT) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;
castOnColumnNumberWithAliasToInt
SELECT CAST(emp_no AS INT) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;
castOnColumnNumberToReal
SELECT CAST(emp_no AS REAL) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;
castOnColumnNumberToDouble
SELECT CAST(emp_no AS DOUBLE) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;
castOnColumnNumberToBoolean
SELECT CAST(emp_no AS BOOL) AS emp_no_cast FROM "emp.emp" ORDER BY emp_no LIMIT 5;