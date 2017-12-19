/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.parser;

import org.elasticsearch.xpack.sql.parser.SqlBaseParser.SingleStatementContext;
import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.joda.time.DateTimeZone;

class AstBuilder extends CommandBuilder {
    AstBuilder(DateTimeZone timeZone) {
        super(timeZone);
    }

    @Override
    public LogicalPlan visitSingleStatement(SingleStatementContext ctx) {
        return plan(ctx.statement());
    }
}
