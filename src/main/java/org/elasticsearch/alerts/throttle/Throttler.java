/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.throttle;

import org.elasticsearch.alerts.ExecutionContext;
import org.elasticsearch.alerts.condition.Condition;
import org.elasticsearch.common.ParseField;

/**
 *
 */
public interface Throttler {

    public static final Throttler NO_THROTTLE = new Throttler() {
        @Override
        public Result throttle(ExecutionContext ctx, Condition.Result result) {
            return Result.NO;
        }
    };

    Result throttle(ExecutionContext ctx, Condition.Result result);

    static class Result {

        public static ParseField THROTTLE_FIELD = new ParseField("throttle");
        public static ParseField REASON_FIELD = new ParseField("reason");

        public static final Result NO = new Result(false, null);
        
        private final boolean throttle;
        private final String reason;

        private Result(boolean throttle, String reason) {
            this.throttle = throttle;
            this.reason = reason;
        }

        public static Result throttle(String reason) {
            return new Result(true, reason);
        }

        public boolean throttle() {
            return throttle;
        }

        public String reason() {
            return reason;
        }

    }
}
