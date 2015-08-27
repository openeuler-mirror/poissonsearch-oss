/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.audit;

import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.inject.multibindings.Multibinder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.ShieldLifecycleService;
import org.elasticsearch.shield.audit.index.IndexAuditTrail;
import org.elasticsearch.shield.audit.index.IndexAuditUserHolder;
import org.elasticsearch.shield.audit.logfile.LoggingAuditTrail;
import org.elasticsearch.shield.support.AbstractShieldModule;

import java.util.Set;

/**
 *
 */
public class AuditTrailModule extends AbstractShieldModule.Node {

    private final boolean enabled;

    private IndexAuditUserHolder indexAuditUser;

    public AuditTrailModule(Settings settings) {
        super(settings);
        enabled = auditingEnabled(settings);
    }

    @Override
    protected void configureNode() {
        if (!enabled) {
            bind(AuditTrail.class).toInstance(AuditTrail.NOOP);
            return;
        }
        indexAuditUser = new IndexAuditUserHolder();
        String[] outputs = settings.getAsArray("shield.audit.outputs", new String[] { LoggingAuditTrail.NAME });
        if (outputs.length == 0) {
            bind(AuditTrail.class).toInstance(AuditTrail.NOOP);
            return;
        }
        bind(AuditTrail.class).to(AuditTrailService.class).asEagerSingleton();
        Multibinder<AuditTrail> binder = Multibinder.newSetBinder(binder(), AuditTrail.class);

        Set<String> uniqueOutputs = Sets.newHashSet(outputs);
        for (String output : uniqueOutputs) {
            switch (output) {
                case LoggingAuditTrail.NAME:
                    binder.addBinding().to(LoggingAuditTrail.class);
                    bind(LoggingAuditTrail.class).asEagerSingleton();
                    break;
                case IndexAuditTrail.NAME:
                    // TODO should bind the lifecycle service in ShieldModule if we use it other places...
                    bind(ShieldLifecycleService.class).asEagerSingleton();
                    bind(IndexAuditUserHolder.class).toInstance(indexAuditUser);
                    binder.addBinding().to(IndexAuditTrail.class);
                    bind(IndexAuditTrail.class).asEagerSingleton();
                    break;
                default:
                    throw new ElasticsearchException("unknown audit trail output [" + output + "]");
            }
        }
    }

    public static boolean auditingEnabled(Settings settings) {
        return settings.getAsBoolean("shield.audit.enabled", false);
    }

    public static boolean indexAuditLoggingEnabled(Settings settings) {
        if (auditingEnabled(settings)) {
            String[] outputs = settings.getAsArray("shield.audit.outputs");
            for (String output : outputs) {
                if (output.equals(IndexAuditTrail.NAME)) {
                    return true;
                }
            }
        }
        return false;
    }
}
