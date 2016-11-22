/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.support;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.cache.Cache;
import org.elasticsearch.common.cache.CacheBuilder;
import org.elasticsearch.common.cache.CacheLoader;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.xpack.security.authc.AuthenticationToken;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.user.User;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public abstract class CachingUsernamePasswordRealm extends UsernamePasswordRealm implements CachingRealm {

    public static final String CACHE_HASH_ALGO_SETTING = "cache.hash_algo";
    public static final String CACHE_TTL_SETTING = "cache.ttl";
    public static final String CACHE_MAX_USERS_SETTING = "cache.max_users";

    private static final TimeValue DEFAULT_TTL = TimeValue.timeValueMinutes(20);
    private static final int DEFAULT_MAX_USERS = 100000; //100k users

    private final Cache<String, UserWithHash> cache;
    final Hasher hasher;

    protected CachingUsernamePasswordRealm(String type, RealmConfig config) {
        super(type, config);
        hasher = Hasher.resolve(config.settings().get(CACHE_HASH_ALGO_SETTING, null), Hasher.SSHA256);
        TimeValue ttl = config.settings().getAsTime(CACHE_TTL_SETTING, DEFAULT_TTL);
        if (ttl.getNanos() > 0) {
            cache = CacheBuilder.<String, UserWithHash>builder()
                    .setExpireAfterAccess(ttl)
                    .setMaximumWeight(config.settings().getAsInt(CACHE_MAX_USERS_SETTING, DEFAULT_MAX_USERS))
                    .build();
        } else {
            cache = null;
        }
    }

    public final void expire(String username) {
        if (cache != null) {
            logger.trace("invalidating cache for user [{}] in realm [{}]", username, name());
            cache.invalidate(username);
        }
    }

    public final void expireAll() {
        if (cache != null) {
            logger.trace("invalidating cache for all users in realm [{}]", name());
            cache.invalidateAll();
        }
    }

    /**
     * If the user exists in the cache (keyed by the principle name), then the password is validated
     * against a hash also stored in the cache.  Otherwise the subclass authenticates the user via
     * doAuthenticate
     *
     * @param authToken The authentication token
     * @return an authenticated user with roles
     */
    @Override
    public final User authenticate(AuthenticationToken authToken) {
        UsernamePasswordToken token = (UsernamePasswordToken)authToken;
        if (cache == null) {
            return doAuthenticate(token);
        }

        try {
            UserWithHash userWithHash = cache.get(token.principal());
            if (userWithHash == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("user not found in cache, proceeding with normal authentication");
                }
                User user = doAuthenticate(token);
                if (user == null) {
                    return null;
                }
                userWithHash = new UserWithHash(user, token.credentials(), hasher);
                // it doesn't matter if we already computed it elsewhere
                cache.put(token.principal(), userWithHash);
                if (logger.isDebugEnabled()) {
                    logger.debug("authenticated user [{}], with roles [{}]", token.principal(), user.roles());
                }
                return user;
            }

            final boolean hadHash = userWithHash.hasHash();
            if (hadHash) {
                if (userWithHash.verify(token.credentials())) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("authenticated user [{}], with roles [{}]", token.principal(), userWithHash.user.roles());
                    }
                    return userWithHash.user;
                }
            }
            //this handles when a user's password has changed or the user was looked up for run as and not authenticated
            cache.invalidate(token.principal());
            User user = doAuthenticate(token);
            if (user == null) {
                return null;
            }
            userWithHash = new UserWithHash(user, token.credentials(), hasher);
            // it doesn't matter if we already computed it elsewhere
            cache.put(token.principal(), userWithHash);
            if (logger.isDebugEnabled()) {
                if (hadHash) {
                    logger.debug("cached user's password changed. authenticated user [{}], with roles [{}]", token.principal(),
                            userWithHash.user.roles());
                } else {
                    logger.debug("cached user came from a lookup and could not be used for authentication. authenticated user [{}]" +
                            " with roles [{}]", token.principal(), userWithHash.user.roles());
                }
            }
            return userWithHash.user;

        } catch (Exception ee) {
            if (ee instanceof ElasticsearchSecurityException) {
                // this should bubble out
                throw ee;
            }

            if (logger.isTraceEnabled()) {
                logger.trace(
                        (Supplier<?>) () -> new ParameterizedMessage(
                                "realm [{}] could not authenticate [{}]", type(), token.principal()), ee);
            } else if (logger.isDebugEnabled()) {
                logger.debug("realm [{}] could not authenticate [{}]", type(), token.principal());
            }
            return null;
        }
    }


    @Override
    public final User lookupUser(final String username) {
        if (!userLookupSupported()) {
            return null;
        }

        CacheLoader<String, UserWithHash> callback = key -> {
            if (logger.isDebugEnabled()) {
                logger.debug("user [{}] not found in cache, proceeding with normal lookup", username);
            }
            User user = doLookupUser(username);
            if (user == null) {
                return null;
            }
            return new UserWithHash(user, null, null);
        };

        try {
            UserWithHash userWithHash = cache.computeIfAbsent(username, callback);
            assert userWithHash != null : "the cache contract requires that a value returned from computeIfAbsent be non-null or an " +
                    "ExecutionException should be thrown";
            return userWithHash.user;
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof ElasticsearchSecurityException) {
                // this should bubble out
                throw (ElasticsearchSecurityException) ee.getCause();
            }

            if (logger.isTraceEnabled()) {
                logger.trace((Supplier<?>) () -> new ParameterizedMessage("realm [{}] could not lookup [{}]", name(), username), ee);
            } else if (logger.isDebugEnabled()) {
                logger.debug("realm [{}] could not lookup [{}]", name(), username);
            }
            return null;
        }
    }

    @Override
    public Map<String, Object> usageStats() {
        Map<String, Object> stats = super.usageStats();
        stats.put("size", cache.count());
        return stats;
    }

    protected abstract User doAuthenticate(UsernamePasswordToken token);

    @Override
    public void lookupUser(String username, ActionListener<User> listener) {
        if (!userLookupSupported()) {
            listener.onResponse(null);
        } else {
            UserWithHash withHash = cache.get(username);
            if (withHash == null) {
                doLookupUser(username, ActionListener.wrap((user) -> {
                    try {
                        if (user != null) {
                            UserWithHash userWithHash = new UserWithHash(user, null, null);
                            cache.computeIfAbsent(username, (n) -> userWithHash);
                        }
                        listener.onResponse(user);
                    } catch (ExecutionException e) {
                        listener.onFailure(e);
                    }
                }, listener::onFailure));
            } else {
                listener.onResponse(withHash.user);
            }
        }
    }

    protected abstract User doLookupUser(String username);

    protected void doLookupUser(String username, ActionListener<User> listener) {
        listener.onResponse(doLookupUser(username));
    }

    private static class UserWithHash {
        User user;
        char[] hash;
        Hasher hasher;

        public UserWithHash(User user, SecuredString password, Hasher hasher) {
            this.user = user;
            this.hash = password == null ? null : hasher.hash(password);
            this.hasher = hasher;
        }

        public boolean verify(SecuredString password) {
            return hash != null && hasher.verify(password, hash);
        }

        public boolean hasHash() {
            return hash != null;
        }
    }
}
