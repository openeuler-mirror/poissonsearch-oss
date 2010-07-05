/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.blobstore;

import org.elasticsearch.common.collect.ImmutableMap;

import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
public interface BlobContainer {

    interface BlobNameFilter {
        /**
         * Return <tt>false</tt> if the blob should be filtered.
         */
        boolean accept(String blobName);
    }

    interface ReadBlobListener {

        void onPartial(byte[] data, int offset, int size) throws IOException;

        void onCompleted();

        void onFailure(Throwable t);
    }

    BlobPath path();

    boolean blobExists(String blobName);

    void readBlob(String blobName, ReadBlobListener listener);

    byte[] readBlobFully(String blobName) throws IOException;

    boolean deleteBlob(String blobName) throws IOException;

    void deleteBlobsByPrefix(String blobNamePrefix) throws IOException;

    void deleteBlobsByFilter(BlobNameFilter filter) throws IOException;

    ImmutableMap<String, BlobMetaData> listBlobs() throws IOException;

    ImmutableMap<String, BlobMetaData> listBlobsByPrefix(String blobNamePrefix) throws IOException;
}
