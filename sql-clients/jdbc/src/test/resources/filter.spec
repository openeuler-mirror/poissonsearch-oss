//
// Filter 
// 

whereFieldQuality
SELECT last_name l FROM "emp.emp" WHERE emp_no = 10000 LIMIT 5;
whereFieldLessThan
SELECT last_name l FROM "emp.emp" WHERE emp_no < 10003 ORDER BY emp_no LIMIT 5;
whereFieldAndComparison
SELECT last_name l FROM "emp.emp" WHERE emp_no > 10000 AND emp_no < 10005 ORDER BY emp_no LIMIT 5;
whereFieldOrComparison
SELECT last_name l FROM "emp.emp" WHERE emp_no < 10003 OR emp_no = 10005 ORDER BY emp_no LIMIT 5;
whereFieldWithOrder
SELECT last_name l FROM "emp.emp" WHERE emp_no < 10003 ORDER BY emp_no;
whereFieldWithExactMatchOnString
SELECT last_name l FROM "emp.emp" WHERE emp_no < 10003 AND gender = 'M';
whereFieldWithLikeMatch
SELECT last_name l FROM "emp.emp" WHERE emp_no < 10003 AND last_name LIKE 'K%';
whereFieldOnMatchWithAndAndOr
SELECT last_name l FROM "emp.emp" WHERE emp_no < 10003 AND (gender = 'M' AND NOT FALSE OR last_name LIKE 'K%') ORDER BY emp_no;
