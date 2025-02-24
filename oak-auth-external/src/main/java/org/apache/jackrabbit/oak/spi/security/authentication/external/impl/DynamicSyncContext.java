/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.spi.security.authentication.external.impl;

import com.google.common.collect.Iterables;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalGroup;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentity;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityException;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityProvider;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalIdentityRef;
import org.apache.jackrabbit.oak.spi.security.authentication.external.ExternalUser;
import org.apache.jackrabbit.oak.spi.security.authentication.external.PrincipalNameResolver;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncException;
import org.apache.jackrabbit.oak.spi.security.authentication.external.SyncResult;
import org.apache.jackrabbit.oak.spi.security.authentication.external.basic.DefaultSyncConfig;
import org.apache.jackrabbit.oak.spi.security.authentication.external.basic.DefaultSyncContext;
import org.apache.jackrabbit.oak.spi.security.authentication.external.basic.DefaultSyncResultImpl;
import org.apache.jackrabbit.oak.spi.security.authentication.external.basic.DefaultSyncedIdentity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Extension of the {@code DefaultSyncContext} that doesn't synchronize group
 * membership of new external users into the user management of the repository.
 * Instead it will only synchronize the principal names up to the configured depths.
 * In combination with the a dedicated {@code PrincipalConfiguration} this allows
 * to benefit from the repository's authorization model (which is solely
 * based on principals) i.e. full compatibility with the default approach without
 * the complication of synchronizing user management information into the repository,
 * when user management is effectively take care of by the third party system.
 *
 * With the {@link org.apache.jackrabbit.oak.spi.security.authentication.external.impl.DefaultSyncHandler}
 * this feature can be turned on using
 * {@link org.apache.jackrabbit.oak.spi.security.authentication.external.basic.DefaultSyncConfig.User#setDynamicMembership(boolean)}
 *
 * Note: users and groups that have been synchronized before the dynamic membership
 * feature has been enabled will continue to be synchronized in the default way
 * and this context doesn't take effect.
 *
 * @since Oak 1.5.3
 */
public class DynamicSyncContext extends DefaultSyncContext {

    private static final Logger log = LoggerFactory.getLogger(DynamicSyncContext.class);

    public DynamicSyncContext(@NotNull DefaultSyncConfig config,
                              @NotNull ExternalIdentityProvider idp,
                              @NotNull UserManager userManager,
                              @NotNull ValueFactory valueFactory) {
        super(config, idp, userManager, valueFactory);
    }
    
    public boolean convertToDynamicMembership(@NotNull Authorizable authorizable) throws RepositoryException {
        if (authorizable.isGroup() || !groupsSyncedBefore(authorizable)) {
            return false;
        }
        
        Collection<String> principalNames = clearGroupMembership(authorizable);
        authorizable.setProperty(ExternalIdentityConstants.REP_EXTERNAL_PRINCIPAL_NAMES, createValues(principalNames));
        return true;
    }

    //--------------------------------------------------------< SyncContext >---
    @NotNull
    @Override
    public SyncResult sync(@NotNull ExternalIdentity identity) throws SyncException {
        if (identity instanceof ExternalUser) {
            return super.sync(identity);
        } else if (identity instanceof ExternalGroup) {
            ExternalIdentityRef ref = identity.getExternalId();
            if (!isSameIDP(ref)) {
                // create result in accordance with sync(String) where status is FOREIGN
                return new DefaultSyncResultImpl(new DefaultSyncedIdentity(identity.getId(), ref, true, -1), SyncResult.Status.FOREIGN);
            }
            return sync((ExternalGroup) identity, ref);
        } else {
            throw new IllegalArgumentException("identity must be user or group but was: " + identity);
        }
    }
    
    @NotNull
    private SyncResult sync(@NotNull ExternalGroup identity, @NotNull ExternalIdentityRef ref) throws SyncException {
        try {
            Group group = getAuthorizable(identity, Group.class);
            if (group != null) {
                // this group has been synchronized before -> continue updating for consistency.
                return syncGroup(identity, group);
            } else if (hasDynamicGroups()) {
                // group does not exist and dynamic-groups option is enabled -> sync the group
                log.debug("ExternalGroup {}: synchronizing as dynamic group {}.", ref.getString(), identity.getId());
                group = createGroup(identity);
                DefaultSyncResultImpl res = syncGroup(identity, group);
                res.setStatus(SyncResult.Status.ADD);
                return res;
            } else {
                // external group has never been synchronized before and dynamic membership is enabled:
                // don't sync external groups into the repository internal user management
                // but limit synchronized information to group-principals stored
                // separately with each external user such that the subject gets
                // properly populated upon login
                log.debug("ExternalGroup {}: Not synchronized as Group into the repository.", ref.getString());
                return new DefaultSyncResultImpl(new DefaultSyncedIdentity(identity.getId(), ref, true, -1), SyncResult.Status.NOP);
            }
        } catch (RepositoryException e) {
            throw new SyncException(e);
        }
    }

    //-------------------------------------------------< DefaultSyncContext >---
    @Override
    protected void syncMembership(@NotNull ExternalIdentity external, @NotNull Authorizable auth, long depth) throws RepositoryException {
        if (auth.isGroup()) {
            return;
        }

        boolean groupsSyncedBefore = groupsSyncedBefore(auth);
        if (groupsSyncedBefore && !enforceDynamicSync()) {
            // user has been synchronized before dynamic membership has been turned on. continue regular sync unless 
            // either dynamic membership is enforced or dynamic-group option is enabled.
            super.syncMembership(external, auth, depth);
        } else {
            try {
                Iterable<ExternalIdentityRef> declaredGroupRefs = external.getDeclaredGroups();
                // store dynamic membership with the user
                setExternalPrincipalNames(auth, declaredGroupRefs, depth);
                
                // if dynamic-group option is enabled -> sync groups without member-information
                // in case group-membership has been synched before -> clear it
                if (hasDynamicGroups() && depth > 0) {
                    createDynamicGroups(declaredGroupRefs, depth);
                }
                
                // clean up any other membership
                if (groupsSyncedBefore) {
                    clearGroupMembership(auth);
                }
            } catch (ExternalIdentityException e) {
                log.error("Failed to synchronize membership information for external identity {}", external.getId(), e);
            }
        }
    }

    @Override
    protected void applyMembership(@NotNull Authorizable member, @NotNull Set<String> groups) throws RepositoryException {
        log.debug("Dynamic membership sync enabled => omit setting auto-membership for {} ", member.getID());
    }

    /**
     * Retrieve membership of the given external user (up to the configured depth) and add (or replace) the 
     * rep:externalPrincipalNames property with the accurate collection of principal names.
     * 
     * @param authorizable The target synced user
     * @param declareGroupRefs The declared group references for the external user
     * @param depth The configured depth to resolve nested groups.
     * @throws ExternalIdentityException If group principal names cannot be calculated
     * @throws RepositoryException If another error occurs
     */
    private void setExternalPrincipalNames(@NotNull Authorizable authorizable, Iterable<ExternalIdentityRef> declareGroupRefs, long depth) throws ExternalIdentityException, RepositoryException {
        Value[] vs;
        if (depth <= 0) {
            vs = new Value[0];
        } else {
            Set<String> principalsNames = new HashSet<>();
            collectPrincipalNames(principalsNames, declareGroupRefs, depth);
            vs = createValues(principalsNames);
        }
        authorizable.setProperty(ExternalIdentityConstants.REP_EXTERNAL_PRINCIPAL_NAMES, vs);
    }
    
    /**
     * Recursively collect the principal names of the given declared group
     * references up to the given depth.
     *
     * Note, that this method will filter out references that don't belong to the same IDP (see OAK-8665).
     *
     * @param principalNames The set used to collect the names of the group principals.
     * @param declaredGroupIdRefs The declared group references for a user or a group.
     * @param depth Configured membership nesting; the recursion will be stopped once depths is < 1.
     * @throws ExternalIdentityException If an error occurs while resolving the the external group references.
     */
    private void collectPrincipalNames(@NotNull Set<String> principalNames, @NotNull Iterable<ExternalIdentityRef> declaredGroupIdRefs, long depth) throws ExternalIdentityException {
        boolean shortcut = (depth <= 1 && idp instanceof PrincipalNameResolver);
        for (ExternalIdentityRef ref : Iterables.filter(declaredGroupIdRefs, this::isSameIDP)) {
            if (shortcut) {
                principalNames.add(((PrincipalNameResolver) idp).fromExternalIdentityRef(ref));
            } else {
                // get group from the IDP
                ExternalIdentity extId = idp.getIdentity(ref);
                if (extId instanceof ExternalGroup) {
                    principalNames.add(extId.getPrincipalName());
                    // recursively apply further membership until the configured depth is reached
                    if (depth > 1) {
                        collectPrincipalNames(principalNames, extId.getDeclaredGroups(), depth - 1);
                    }
                } else {
                    log.debug("Not an external group ({}) => ignore.", extId);
                }
            }
        }
    }
    
    private void createDynamicGroups(@NotNull Iterable<ExternalIdentityRef> declaredGroupIdRefs, 
                                     long depth) throws RepositoryException, ExternalIdentityException {
        for (ExternalIdentityRef groupRef : declaredGroupIdRefs) {
            ExternalGroup externalGroup = getExternalGroupFromRef(groupRef);
            if (externalGroup != null) {
                Group gr = userManager.getAuthorizable(externalGroup.getId(), Group.class);
                if (gr == null) {
                    gr = createGroup(externalGroup);
                }
                syncGroup(externalGroup, gr);
                if (depth > 1) {
                    createDynamicGroups(externalGroup.getDeclaredGroups(),depth-1);
                }
            }
        }
    }
    
    @NotNull
    private Collection<String> clearGroupMembership(@NotNull Authorizable authorizable) throws RepositoryException {
        Set<String> groupPrincipalNames = new HashSet<>();
        Set<Group> toRemove = new HashSet<>();

        // loop over declared and inherited groups as it has been synchronzied before to clean up any previously 
        // defined membership to external groups and automembership.
        // principal-names are collected solely for migration trigger through JXM
        clearGroupMembership(authorizable, groupPrincipalNames, toRemove);
        
        // finally remove external groups that are no longer needed
        for (Group group : toRemove) {
            group.remove();
        }
        return groupPrincipalNames;
    }
    
    private void clearGroupMembership(@NotNull Authorizable authorizable, @NotNull Set<String> groupPrincipalNames, @NotNull Set<Group> toRemove) throws RepositoryException {
        Iterator<Group> grpIter = authorizable.declaredMemberOf();
        Set<String> autoMembership = ((authorizable.isGroup()) ? config.group() : config.user()).getAutoMembership(authorizable);
        while (grpIter.hasNext()) {
            Group grp = grpIter.next();
            if (isSameIDP(grp)) {
                // collected same-idp group principals for the rep:externalPrincipalNames property 
                groupPrincipalNames.add(grp.getPrincipal().getName());
                grp.removeMember(authorizable);
                clearGroupMembership(grp, groupPrincipalNames, toRemove);
                if (clearGroup(grp)) {
                    toRemove.add(grp);
                }
            } else if (autoMembership.contains(grp.getID())) {
                // clear auto-membership
                grp.removeMember(authorizable);
                clearGroupMembership(grp, groupPrincipalNames, toRemove);
            } else {
                // some other membership that has not been added by the sync process
                log.debug("TODO");
            }
        }
    }
    
    private boolean hasDynamicGroups() {
        return config.group().getDynamicGroups();
    }
    
    private boolean enforceDynamicSync() {
        return config.user().getEnforceDynamicMembership() || hasDynamicGroups();
    }
    
    private boolean clearGroup(@NotNull Group group) throws RepositoryException {
        if (hasDynamicGroups()) {
            return false;
        } else {
            return !group.getDeclaredMembers().hasNext();
        }
    }
    
    private static boolean groupsSyncedBefore(@NotNull Authorizable authorizable) throws RepositoryException {
        return authorizable.hasProperty(REP_LAST_SYNCED) && !authorizable.hasProperty(ExternalIdentityConstants.REP_EXTERNAL_PRINCIPAL_NAMES);
    }
}
