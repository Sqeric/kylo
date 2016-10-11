package com.thinkbiganalytics.nifi.rest.client;

import com.thinkbiganalytics.nifi.rest.support.NifiConstants;
import com.thinkbiganalytics.nifi.rest.support.NifiProcessUtil;

import org.apache.nifi.web.api.dto.ProcessGroupDTO;

import java.util.Optional;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * Provides a standard implementation of {@link NiFiProcessGroupsRestClient} that can be extended for different NiFi versions.
 */
public abstract class AbstractNiFiProcessGroupsRestClient implements NiFiProcessGroupsRestClient {

    @Nonnull
    @Override
    public Optional<ProcessGroupDTO> delete(@Nonnull final ProcessGroupDTO processGroup) {
        return deleteWithRetries(processGroup, 0);
    }

    @Nonnull
    @Override
    public Optional<ProcessGroupDTO> findByName(@Nonnull final String parentGroupId, @Nonnull final String groupName, final boolean recursive, final boolean verbose) {
        final Set<ProcessGroupDTO> children = findAll(parentGroupId);
        final ProcessGroupDTO group = NifiProcessUtil.findFirstProcessGroupByName(children, groupName);
        if (group != null && verbose) {
            return findById(group.getId(), recursive, true);
        } else {
            return Optional.ofNullable(group);
        }
    }

    @Nonnull
    @Override
    public ProcessGroupDTO findRoot() {
        return findById("root", true, true).orElseThrow(IllegalStateException::new);
    }

    @Nonnull
    protected Optional<ProcessGroupDTO> deleteWithRetries(@Nonnull final ProcessGroupDTO processGroup, final int retries) {
        assert retries == 0;  // TODO
        return doDelete(processGroup);
    }

    @Nonnull
    protected abstract Optional<ProcessGroupDTO> doDelete(@Nonnull final ProcessGroupDTO processGroup);
}
