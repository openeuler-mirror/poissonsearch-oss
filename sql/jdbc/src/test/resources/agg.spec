//
// Group-By
//

groupByOnText
SELECT gender g FROM "emp.emp" GROUP BY gender;
groupByOnTextWithWhereClause
SELECT gender g FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender;
groupByOnTextWithWhereAndLimit
SELECT gender g FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender LIMIT 1;
groupByOnTextOnAlias
SELECT gender g FROM "emp.emp" WHERE emp_no < 10020 GROUP BY g;
groupByOnTextOnAliasOrderDesc
SELECT gender g FROM "emp.emp" WHERE emp_no < 10020 GROUP BY g ORDER BY g DESC;

groupByOnDate
SELECT birth_date b FROM "emp.emp" GROUP BY birth_date ORDER BY birth_date DESC;
groupByOnDateWithWhereClause
SELECT birth_date b FROM "emp.emp" WHERE emp_no < 10020 GROUP BY birth_date ORDER BY birth_date DESC;
groupByOnDateWithWhereAndLimit
SELECT birth_date b FROM "emp.emp" WHERE emp_no < 10020 GROUP BY birth_date ORDER BY birth_date DESC LIMIT 1;
groupByOnDateOnAlias
SELECT birth_date b FROM "emp.emp" WHERE emp_no < 10020 GROUP BY b ORDER BY birth_date DESC;

groupByOnNumber
SELECT emp_no e FROM "emp.emp" GROUP BY emp_no ORDER BY emp_no DESC;
groupByOnNumberWithWhereClause
SELECT emp_no e FROM "emp.emp" WHERE emp_no < 10020 GROUP BY emp_no ORDER BY emp_no DESC;
groupByOnNumberWithWhereAndLimit
SELECT emp_no e FROM "emp.emp" WHERE emp_no < 10020 GROUP BY emp_no ORDER BY emp_no DESC LIMIT 1;
groupByOnNumberOnAlias
SELECT emp_no e FROM "emp.emp" WHERE emp_no < 10020 GROUP BY e ORDER BY emp_no DESC;

//
// Aggregate Functions
//

// COUNT
aggCountImplicit
SELECT COUNT(*) c FROM "emp.emp";
aggCountImplicitWithCast
SELECT CAST(COUNT(*) AS INT) c FROM "emp.emp";
aggCountImplicitWithConstant
SELECT COUNT(1) FROM "emp.emp";
aggCountImplicitWithConstantAndFilter
SELECT COUNT(1) FROM "emp.emp" WHERE emp_no < 10010;
aggCountAliasAndWhereClause
SELECT gender g, COUNT(*) c FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender;
aggCountAliasAndWhereClauseAndLimit
SELECT gender g, COUNT(*) c FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender LIMIT 1;
aggCountAliasWithCastAndFilter
SELECT gender g, CAST(COUNT(*) AS INT) c FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender;
aggCountWithAlias
SELECT gender g, COUNT(*) c FROM "emp.emp" GROUP BY g;

// Conditional COUNT
aggCountAndHaving
SELECT gender g, COUNT(*) c FROM "emp.emp" GROUP BY g HAVING COUNT(*) > 10;
aggCountOnColumnAndHaving
SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING COUNT(gender) > 10;
// NOT supported yet since Having introduces a new agg
//aggCountOnColumnAndWildcardAndHaving
//SELECT gender g, COUNT(*) c FROM "emp.emp" GROUP BY g HAVING COUNT(gender) > 10;
aggCountAndHavingOnAlias
SELECT gender g, COUNT(*) c FROM "emp.emp" GROUP BY g HAVING c > 10;
aggCountOnColumnAndHavingOnAlias
SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING c > 10;
aggCountOnColumnAndMultipleHaving
SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING c > 10 AND c < 70;
aggCountOnColumnAndMultipleHavingWithLimit
SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING c > 10 AND c < 70 LIMIT 1;
aggCountOnColumnAndHavingOnAliasAndFunction
SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING c > 10 AND COUNT(gender) < 70;
// NOT supported yet since Having introduces a new agg
//aggCountOnColumnAndHavingOnAliasAndFunctionWildcard -> COUNT(*/1) vs COUNT(gender)
//SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING c > 10 AND COUNT(*) < 70;
//aggCountOnColumnAndHavingOnAliasAndFunctionConstant
//SELECT gender g, COUNT(gender) c FROM "emp.emp" GROUP BY g HAVING c > 10 AND COUNT(1) < 70;


// MIN
aggMinImplicit
SELECT MIN(emp_no) m FROM "emp.emp";
aggMinImplicitWithCast
SELECT CAST(MIN(emp_no) AS SMALLINT) m FROM "emp.emp";
aggMin
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY gender;
aggMinWithCast
SELECT CAST(MIN(emp_no) AS SMALLINT) m FROM "emp.emp" GROUP BY gender;
aggMinAndCount
SELECT MIN(emp_no) m, COUNT(1) c FROM "emp.emp" GROUP BY gender;
aggMinAndCountWithFilter
SELECT MIN(emp_no) m, COUNT(1) c FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender ;
aggMinAndCountWithFilterAndLimit
SELECT MIN(emp_no) m, COUNT(1) c FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender LIMIT 1;
aggMinWithCastAndFilter
SELECT gender g, CAST(MIN(emp_no) AS SMALLINT) m, COUNT(1) c FROM "emp.emp" WHERE emp_no < 10020 GROUP BY gender;
aggMinWithAlias
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY g;

// Conditional MIN
aggMinWithHaving
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY g HAVING MIN(emp_no) > 10;
aggMinWithHavingOnAlias
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10;
aggMinWithMultipleHaving
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10 AND m < 99999;
aggMinWithMultipleHavingWithLimit
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10 AND m < 99999 LIMIT 1;
aggMinWithMultipleHavingOnAliasAndFunction
SELECT gender g, MIN(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10 AND MIN(emp_no) < 99999;

// MAX
aggMaxImplicit
SELECT MAX(emp_no) c FROM "emp.emp";
aggMaxImplicitWithCast
SELECT CAST(MAX(emp_no) AS SMALLINT) c FROM "emp.emp";
aggMax
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY gender ;
aggMaxWithCast
SELECT gender g, CAST(MAX(emp_no) AS SMALLINT) m FROM "emp.emp" GROUP BY gender ;
aggMaxAndCount
SELECT MAX(emp_no) m, COUNT(1) c FROM "emp.emp" GROUP BY gender;
aggMaxAndCountWithFilter
SELECT gender g, MAX(emp_no) m, COUNT(1) c FROM "emp.emp" WHERE emp_no > 10000 GROUP BY gender;
aggMaxAndCountWithFilterAndLimit
SELECT gender g, MAX(emp_no) m, COUNT(1) c FROM "emp.emp" WHERE emp_no > 10000 GROUP BY gender LIMIT 1;
aggMaxWithAlias
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY g;

// Conditional MAX
aggMaxWithHaving
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY g HAVING MAX(emp_no) > 10;
aggMaxWithHavingOnAlias
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10;
aggMaxWithMultipleHaving
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10 AND m < 99999;
aggMaxWithMultipleHavingWithLimit
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10 AND m < 99999 LIMIT 1;
aggMaxWithMultipleHavingOnAliasAndFunction
SELECT gender g, MAX(emp_no) m FROM "emp.emp" GROUP BY g HAVING m > 10 AND MAX(emp_no) < 99999;

// SUM
aggSumImplicitWithCast
SELECT CAST(SUM(emp_no) AS BIGINT) s FROM "emp.emp";
aggSumWithCast
SELECT gender g, CAST(SUM(emp_no) AS BIGINT) s FROM "emp.emp" GROUP BY gender;
aggSumWithCastAndCount
SELECT gender g, CAST(SUM(emp_no) AS BIGINT) s, COUNT(1) c FROM "emp.emp" GROUP BY gender;
aggSumWithCastAndCountWithFilter
SELECT gender g, CAST(SUM(emp_no) AS BIGINT) s, COUNT(1) c FROM "emp.emp" WHERE emp_no > 10000 GROUP BY gender;
aggSumWithCastAndCountWithFilterAndLimit
SELECT gender g, CAST(SUM(emp_no) AS BIGINT) s, COUNT(1) c FROM "emp.emp" WHERE emp_no > 10000 GROUP BY gender LIMIT 1;
aggSumWithAlias
SELECT gender g, CAST(SUM(emp_no) AS BIGINT) s FROM "emp.emp" GROUP BY g;

// Conditional SUM
aggSumWithHaving
SELECT gender g, CAST(SUM(emp_no) AS INT) s FROM "emp.emp" GROUP BY g HAVING SUM(emp_no) > 10;
aggSumWithHavingOnAlias
SELECT gender g, CAST(SUM(emp_no) AS INT) s FROM "emp.emp" GROUP BY g HAVING s > 10;
aggSumWithMultipleHaving
SELECT gender g, CAST(SUM(emp_no) AS INT) s FROM "emp.emp" GROUP BY g HAVING s > 10 AND s < 10000000;
aggSumWithMultipleHavingWithLimit
SELECT gender g, CAST(SUM(emp_no) AS INT) s FROM "emp.emp" GROUP BY g HAVING s > 10 AND s < 10000000 LIMIT 1;
aggSumWithMultipleHavingOnAliasAndFunction
SELECT gender g, CAST(SUM(emp_no) AS INT) s FROM "emp.emp" GROUP BY g HAVING s > 10 AND SUM(emp_no) > 10000000;

// AVG
aggAvgImplicitWithCast
SELECT CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp";
aggAvgWithCastToFloat
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY gender;
// casting to an exact type - varchar, bigint, etc... will likely fail due to rounding error
aggAvgWithCastToDouble
SELECT gender g, CAST(AVG(emp_no) AS DOUBLE) a FROM "emp.emp" GROUP BY gender;
aggAvgWithCastAndCount
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a, COUNT(1) c FROM "emp.emp" GROUP BY gender;
aggAvgWithCastAndCountWithFilter
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a, COUNT(1) c FROM "emp.emp" WHERE emp_no > 10000 GROUP BY gender ;
aggAvgWithCastAndCountWithFilterAndLimit
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a, COUNT(1) c FROM "emp.emp" WHERE emp_no > 10000 GROUP BY gender LIMIT 1;
aggAvgWithAlias
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY g;

// Conditional AVG
aggAvgWithHaving
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY g HAVING AVG(emp_no) > 10;
aggAvgWithHavingOnAlias
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY g HAVING a > 10;
aggAvgWithMultipleHaving
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY g HAVING a > 10 AND a < 10000000;
aggAvgWithMultipleHavingWithLimit
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY g HAVING a > 10 AND a < 10000000 LIMIT 1;
aggAvgWithMultipleHavingOnAliasAndFunction
SELECT gender g, CAST(AVG(emp_no) AS FLOAT) a FROM "emp.emp" GROUP BY g HAVING a > 10 AND AVG(emp_no) > 10000000;
