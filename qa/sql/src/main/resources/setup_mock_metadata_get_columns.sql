CREATE TABLE mock (
  TABLE_SCHEM VARCHAR,
  TABLE_NAME VARCHAR,
  COLUMN_NAME VARCHAR,
  DATA_TYPE INTEGER,
  TYPE_NAME VARCHAR,
  COLUMN_SIZE INTEGER,
  BUFFER_LENGTH NULL,
  DECIMAL_DIGITS INTEGER,
  NUM_PREC_RADIX INTEGER,
  NULLABLE INTEGER,
  REMARKS VARCHAR,
  COLUMN_DEF VARCHAR,
  SQL_DATA_TYPE INTEGER,
  SQL_DATETIME_SUB INTEGER,
  CHAR_OCTET_LENGTH INTEGER,
  ORDINAL_POSITION INTEGER,
  IS_NULLABLE VARCHAR,
  SCOPE_CATALOG VARCHAR,
  SCOPE_SCHEMA VARCHAR,
  SCOPE_TABLE VARCHAR,
  SOURCE_DATA_TYPE SMALLINT,
  IS_AUTOINCREMENT VARCHAR,
  IS_GENERATEDCOLUMN VARCHAR
) AS
SELECT '', 'test1', 'name', 12, 'VARCHAR', 2147483647, null, null,
  10, -- NOCOMMIT 10 seem wrong to hard code for stuff like strings
  2, -- columnNullableUnknown NOCOMMIT I think it'd be more correct to return columnNullable
  null, null, null, null, null, 1, '', null, null, null, null, '', ''
FROM DUAL
UNION ALL
SELECT '', 'test2', 'date', 93, 'TIMESTAMP', 19, null, null,
  10,
  2, -- columnNullableUnknown NOCOMMIT I think it'd be more correct to return columnNullable
  null, null, null, null, null, 1, '', null, null, null, null, '', ''
FROM DUAL
UNION ALL
SELECT '', 'test2', 'number', -5, 'BIGINT', 19, null, null,
  10,
  2, -- columnNullableUnknown NOCOMMIT I think it'd be more correct to return columnNullable
  null, null, null, null, null, 2, '', null, null, null, null, '', ''
FROM DUAL
;
