/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.session;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.sql.analysis.analyzer.Analyzer;
import org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer;
import org.elasticsearch.xpack.sql.analysis.analyzer.PreAnalyzer.PreAnalysis;
import org.elasticsearch.xpack.sql.analysis.index.GetIndexResult;
import org.elasticsearch.xpack.sql.analysis.index.IndexResolver;
import org.elasticsearch.xpack.sql.analysis.index.MappingException;
import org.elasticsearch.xpack.sql.expression.function.FunctionRegistry;
import org.elasticsearch.xpack.sql.optimizer.Optimizer;
import org.elasticsearch.xpack.sql.parser.SqlParser;
import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.sql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.sql.planner.Planner;
import org.elasticsearch.xpack.sql.rule.RuleExecutor;

import java.util.function.Function;

import static org.elasticsearch.action.ActionListener.wrap;

public class SqlSession {

    private final Client client;

    private final FunctionRegistry functionRegistry;
    private final IndexResolver indexResolver;
    private final PreAnalyzer preAnalyzer;
    private final Optimizer optimizer;
    private final Planner planner;

    // TODO rename to `configuration`
    private Configuration settings;

    public SqlSession(SqlSession other) {
        this(other.settings, other.client, other.functionRegistry, other.indexResolver,
                other.preAnalyzer, other.optimizer,other.planner);
    }

    public SqlSession(Configuration settings, Client client, FunctionRegistry functionRegistry,
            IndexResolver indexResolver,
            PreAnalyzer preAnalyzer,
            Optimizer optimizer,
            Planner planner) {
        this.client = client;
        this.functionRegistry = functionRegistry;

        this.indexResolver = indexResolver;
        this.preAnalyzer = preAnalyzer;
        this.optimizer = optimizer;
        this.planner = planner;

        this.settings = settings;
    }

    public FunctionRegistry functionRegistry() {
        return functionRegistry;
    }

    public Client client() {
        return client;
    }

    public Planner planner() {
        return planner;
    }

    public IndexResolver indexResolver() {
        return indexResolver;
    }

    public Optimizer optimizer() {
        return optimizer;
    }

    private LogicalPlan doParse(String sql) {
        return new SqlParser(settings.timeZone()).createStatement(sql);
    }

    public void analyzedPlan(LogicalPlan parsed, boolean verify, ActionListener<LogicalPlan> listener) {
        if (parsed.analyzed()) {
            listener.onResponse(parsed);
            return;
        }

        preAnalyze(parsed, c -> {
            Analyzer analyzer = new Analyzer(functionRegistry, c, settings.timeZone());
            LogicalPlan p = analyzer.analyze(parsed);
            return verify ? analyzer.verify(p) : p;
        }, listener);
    }

    public void debugAnalyzedPlan(LogicalPlan parsed, ActionListener<RuleExecutor<LogicalPlan>.ExecutionInfo> listener) {
        if (parsed.analyzed()) {
            listener.onResponse(null);
            return;
        }

        preAnalyze(parsed, getIndexResult -> {
            Analyzer analyzer = new Analyzer(functionRegistry, getIndexResult, settings.timeZone());
            return analyzer.debugAnalyze(parsed);
        }, listener);
    }

    private <T> void preAnalyze(LogicalPlan parsed, Function<GetIndexResult, T> action, ActionListener<T> listener) {
        PreAnalysis preAnalysis = preAnalyzer.preAnalyze(parsed);
        // TODO we plan to support joins in the future when possible, but for now we'll just fail early if we see one
        if (preAnalysis.indices.size() > 1) {
            // Note: JOINs are not supported but we detect them when
            listener.onFailure(new MappingException("Queries with multiple indices are not supported"));
        } else if (preAnalysis.indices.size() == 1) {
            indexResolver.asIndex(preAnalysis.indices.get(0),
                    wrap(indexResult -> listener.onResponse(action.apply(indexResult)), listener::onFailure));
        } else {
            try {
                //TODO when can this ever happen? shouldn't it be an exception instead?
                listener.onResponse(action.apply(GetIndexResult.invalid("_na_")));
            } catch (Exception ex) {
                listener.onFailure(ex);
            }
        }
    }

    public void optimizedPlan(LogicalPlan verified, ActionListener<LogicalPlan> listener) {
        analyzedPlan(verified, true, wrap(v -> listener.onResponse(optimizer.optimize(v)), listener::onFailure));
    }

    public void physicalPlan(LogicalPlan optimized, boolean verify, ActionListener<PhysicalPlan> listener) {
        optimizedPlan(optimized, wrap(o -> listener.onResponse(planner.plan(o, verify)), listener::onFailure));
    }

    public void sql(String sql, ActionListener<SchemaRowSet> listener) {
        sqlExecutable(sql, wrap(e -> e.execute(this, listener), listener::onFailure));
    }

    public void sqlExecutable(String sql, ActionListener<PhysicalPlan> listener) {
        try {
            physicalPlan(doParse(sql), true, listener);
        } catch (Exception ex) {
            listener.onFailure(ex);
        }
    }

    public Configuration settings() {
        return settings;
    }
}
