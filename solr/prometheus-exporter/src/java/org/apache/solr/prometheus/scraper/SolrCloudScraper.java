/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.prometheus.scraper;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.Pair;
import org.apache.solr.prometheus.collector.MetricSamples;
import org.apache.solr.prometheus.exporter.MetricsQuery;
import org.apache.solr.prometheus.exporter.SolrClientFactory;

public class SolrCloudScraper extends SolrScraper {

  private final CloudSolrClient solrClient;
  private final SolrClientFactory solrClientFactory;

  private Cache<String, Http2SolrClient> hostClientCache = Caffeine.newBuilder().build();

  public SolrCloudScraper(
      CloudSolrClient solrClient,
      ExecutorService executor,
      SolrClientFactory solrClientFactory,
      String clusterId) {
    super(executor, clusterId);
    this.solrClient = solrClient;
    this.solrClientFactory = solrClientFactory;
  }

  @Override
  public Map<String, MetricSamples> pingAllCores(MetricsQuery query) throws IOException {
    Map<String, Http2SolrClient> httpSolrClients = createHttpSolrClients();

    List<Replica> replicas =
        solrClient
            .getClusterState()
            .collectionStream()
            .map(DocCollection::getReplicas)
            .flatMap(List::stream)
            .collect(Collectors.toList());

    List<String> coreNames =
        replicas.stream().map(Replica::getCoreName).collect(Collectors.toList());

    Map<String, Http2SolrClient> coreToClient =
        replicas.stream()
            .map(
                replica ->
                    new Pair<>(replica.getCoreName(), httpSolrClients.get(replica.getBaseUrl())))
            .collect(Collectors.toMap(Pair::first, Pair::second));

    return sendRequestsInParallel(
        coreNames,
        core -> {
          try {
            return request(coreToClient.get(core), query.withCore(core));
          } catch (IOException exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  private Map<String, Http2SolrClient> createHttpSolrClients() throws IOException {
    return getBaseUrls().stream()
        .map(url -> hostClientCache.get(url, solrClientFactory::createStandaloneSolrClient))
        .collect(Collectors.toMap(Http2SolrClient::getBaseURL, Function.identity()));
  }

  @Override
  public Map<String, MetricSamples> pingAllCollections(MetricsQuery query) throws IOException {
    return sendRequestsInParallel(
        getCollections(),
        (collection) -> {
          try {
            return request(solrClient, query.withCollection(collection));
          } catch (IOException exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  @Override
  public Map<String, MetricSamples> metricsForAllHosts(MetricsQuery query) throws IOException {
    Map<String, Http2SolrClient> httpSolrClients = createHttpSolrClients();

    return sendRequestsInParallel(
        httpSolrClients.keySet(),
        (baseUrl) -> {
          try {
            return request(httpSolrClients.get(baseUrl), query);
          } catch (IOException exception) {
            throw new RuntimeException(exception);
          }
        });
  }

  @Override
  public MetricSamples search(MetricsQuery query) throws IOException {
    return request(solrClient, query);
  }

  @Override
  public MetricSamples collections(MetricsQuery metricsQuery) throws IOException {
    return request(solrClient, metricsQuery);
  }

  private Set<String> getBaseUrls() throws IOException {
    return solrClient
        .getClusterState()
        .collectionStream()
        .map(DocCollection::getReplicas)
        .flatMap(List::stream)
        .map(Replica::getBaseUrl)
        .collect(Collectors.toSet());
  }

  private Collection<String> getCollections() throws IOException {
    return solrClient.getClusterState().getCollectionNames();
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(solrClient);
    hostClientCache.asMap().values().forEach(IOUtils::closeQuietly);
  }
}
