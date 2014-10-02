/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.action.get;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.metadata.RepositoryMetaData;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.ImmutableSettings;

import java.io.IOException;
import java.util.Iterator;

public class GetLicenseResponse extends ActionResponse implements Iterable<RepositoryMetaData> {

    //TODO: use LicenseMetaData instead
    private ImmutableList<RepositoryMetaData> repositories = ImmutableList.of();


    GetLicenseResponse() {
    }

    GetLicenseResponse(ImmutableList<RepositoryMetaData> repositories) {
        this.repositories = repositories;
    }

    /**
     * List of repositories to return
     *
     * @return list or repositories
     */
    public ImmutableList<RepositoryMetaData> repositories() {
        return repositories;
    }


    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        int size = in.readVInt();
        ImmutableList.Builder<RepositoryMetaData> repositoryListBuilder = ImmutableList.builder();
        for (int j = 0; j < size; j++) {
            repositoryListBuilder.add(new RepositoryMetaData(
                            in.readString(),
                            in.readString(),
                            ImmutableSettings.readSettingsFromStream(in))
            );
        }
        repositories = repositoryListBuilder.build();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(repositories.size());
        for (RepositoryMetaData repository : repositories) {
            out.writeString(repository.name());
            out.writeString(repository.type());
            ImmutableSettings.writeSettingsToStream(repository.settings(), out);
        }
    }

    /**
     * Iterator over the repositories data
     *
     * @return iterator over the repositories data
     */
    @Override
    public Iterator<RepositoryMetaData> iterator() {
        return repositories.iterator();
    }
}