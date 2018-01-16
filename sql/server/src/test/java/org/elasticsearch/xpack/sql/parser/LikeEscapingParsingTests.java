/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.parser;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.regex.Like;
import org.elasticsearch.xpack.sql.expression.regex.LikePattern;
import org.joda.time.DateTimeZone;

import java.util.Locale;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class LikeEscapingParsingTests extends ESTestCase {

    private final SqlParser parser = new SqlParser(DateTimeZone.UTC);

    private String error(String pattern) {
        ParsingException ex = expectThrows(ParsingException.class,
                () -> parser.createExpression(String.format(Locale.ROOT, "exp LIKE %s", pattern)));

        return ex.getMessage();
    }

    private LikePattern like(String pattern) {
        Expression exp = parser.createExpression(String.format(Locale.ROOT, "exp LIKE %s", pattern));
        assertThat(exp, instanceOf(Like.class));
        Like l = (Like) exp;
        return (LikePattern) l.right();
    }

    public void testNoEscaping() {
        LikePattern like = like("'string'");
        assertThat(like.pattern(), is("string"));
        assertThat(like.asJavaRegex(), is("^string$"));
        assertThat(like.asLuceneWildcard(), is("string"));
    }

    public void testEscapingLastChar() {
        assertThat(error("'string|' ESCAPE '|'"),
                is("line 1:11: Pattern [string|] is invalid as escape char [|] at position 6 does not escape anything"));
    }

    public void testEscapingWrongChar() {
        assertThat(error("'|string' ESCAPE '|'"),
                is("line 1:11: Pattern [|string] is invalid as escape char [|] at position 0 can only escape wildcard chars; found [s]"));
    }

    public void testInvalidChar() {
        assertThat(error("'%string' ESCAPE '%'"),
                is("line 1:28: Char [%] cannot be used for escaping"));
    }
    
    public void testCannotUseStar() {
        assertThat(error("'|*string' ESCAPE '|'"),
                is("line 1:11: Invalid char [*] found in pattern [|*string] at position 1; use [%] or [_] instead"));
    }
}