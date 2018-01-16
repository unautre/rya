/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.streams.api.interactor.defaults;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.apache.rya.streams.api.exception.RyaStreamsException;
import org.apache.rya.streams.api.interactor.AddQuery;
import org.apache.rya.streams.api.queries.QueryRepository;
import org.junit.Test;

/**
 * Unit tests the methods of {@link DefaultAddQuery}.
 */
public class DefaultAddQueryTest {

    @Test
    public void addQuery_validSparql() throws Exception {
        // Valid SPARQL.
        final String sparql = "SELECT * WHERE { ?person <urn:worksAt> ?business }";

        // Setup the interactor.
        final QueryRepository repo = mock(QueryRepository.class);
        final AddQuery addQuery = new DefaultAddQuery(repo);

        // Add the query.
        addQuery.addQuery(sparql, true);

        // Verify the call was forwarded to the repository.
        verify(repo, times(1)).add(eq(sparql), eq(true));
    }

    @Test(expected = RyaStreamsException.class)
    public void addQuery_invalidSparql() throws Exception {
        // Inalid SPARQL.
        final String sparql = "This is not sparql.";

        // Setup the interactor.
        final QueryRepository repo = mock(QueryRepository.class);
        final AddQuery addQuery = new DefaultAddQuery(repo);

        // Add the query.
        addQuery.addQuery(sparql, true);
    }
}