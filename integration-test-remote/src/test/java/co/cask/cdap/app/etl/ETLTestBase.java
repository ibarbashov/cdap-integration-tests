/*
 * Copyright © 2015-2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.app.etl;

import co.cask.cdap.api.artifact.ArtifactScope;
import co.cask.cdap.api.artifact.ArtifactSummary;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.client.ApplicationClient;
import co.cask.cdap.client.ArtifactClient;
import co.cask.cdap.client.DatasetClient;
import co.cask.cdap.client.config.ConnectionConfig;
import co.cask.cdap.common.ArtifactNotFoundException;
import co.cask.cdap.common.UnauthenticatedException;
import co.cask.cdap.common.utils.Tasks;
import co.cask.cdap.etl.api.batch.BatchAggregator;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.cdap.etl.proto.v2.DataStreamsConfig;
import co.cask.cdap.etl.proto.v2.ETLBatchConfig;
import co.cask.cdap.proto.ConfigEntry;
import co.cask.cdap.proto.artifact.AppRequest;
import co.cask.cdap.proto.artifact.PluginSummary;
import co.cask.cdap.proto.id.ArtifactId;
import co.cask.cdap.test.AudiTestBase;
import co.cask.cdap.test.DataSetManager;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.cdap.common.http.HttpMethod;
import io.cdap.common.http.HttpResponse;
import org.junit.Assert;
import org.junit.Before;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * An abstract class for writing etl integration tests. Tests for etl should extend this class.
 */
public abstract class ETLTestBase extends AudiTestBase {
  protected static final String SOURCE_DATASET = "sourceDataset";
  protected static final Schema DATASET_SCHEMA = Schema.recordOf(
    "event",
    Schema.Field.of("ts", Schema.of(Schema.Type.LONG)),
    Schema.Field.of("ticker", Schema.of(Schema.Type.STRING)),
    Schema.Field.of("num", Schema.of(Schema.Type.INT)),
    Schema.Field.of("price", Schema.of(Schema.Type.DOUBLE)));

  protected ApplicationClient appClient;
  protected DatasetClient datasetClient;
  protected ArtifactClient artifactClient;
  protected String version;

  @Before
  public void setup() throws InterruptedException, ExecutionException, TimeoutException {
    appClient = getApplicationClient();
    datasetClient = getDatasetClient();
    artifactClient = new ArtifactClient(getClientConfig(), getRestClient());

    version = getVersion();
    final ArtifactId datapipelineId = TEST_NAMESPACE.artifact("cdap-data-pipeline", version);
    final ArtifactId datastreamsId = TEST_NAMESPACE.artifact("cdap-data-streams", version);

    // wait until we see extensions for cdap-data-pipeline and cdap-data-streams
    Tasks.waitFor(true, () -> {
      try {
        // cdap-data-pipeline and cdap-data-streams are parent artifacts
        List<PluginSummary> plugins =
          artifactClient.getPluginSummaries(datapipelineId, BatchAggregator.PLUGIN_TYPE, ArtifactScope.SYSTEM);
        if (plugins.stream().noneMatch(pluginSummary -> "GroupByAggregate".equals(pluginSummary.getName()))) {
          return false;
        }

        plugins = artifactClient.getPluginSummaries(datapipelineId, BatchSink.PLUGIN_TYPE, ArtifactScope.SYSTEM);
        if (plugins.stream().noneMatch(pluginSummary -> "File".equals(pluginSummary.getName()))) {
          return false;
        }

        plugins = artifactClient.getPluginSummaries(datastreamsId, BatchAggregator.PLUGIN_TYPE, ArtifactScope.SYSTEM);
        if (plugins.stream().noneMatch(pluginSummary -> "GroupByAggregate".equals(pluginSummary.getName()))) {
          return false;
        }

        return true;
      } catch (ArtifactNotFoundException e) {
        // happens if cdap-data-pipeline or cdap-data-streams were not added yet
        return false;
      }
    }, 5, TimeUnit.MINUTES, 3, TimeUnit.SECONDS);
  }

  protected AppRequest<DataStreamsConfig> getStreamingAppRequest(DataStreamsConfig config) {
    return new AppRequest<>(new ArtifactSummary("cdap-data-streams", version, ArtifactScope.SYSTEM), config);
  }

  @Nullable
  protected AppRequest getWranglerAppRequest(List<ArtifactSummary> list) {
    //arbitrary AppRequest
    AppRequest request = null;
    for (ArtifactSummary summary : list) {
      if (summary.getName().contains("wrangler-service")) {
        request = new AppRequest<>(summary);
      }
    }
    return request;
  }

  // make the above two methods use this method instead
  protected AppRequest<ETLBatchConfig> getBatchAppRequestV2(co.cask.cdap.etl.proto.v2.ETLBatchConfig config) {
    return new AppRequest<>(new ArtifactSummary("cdap-data-pipeline", version, ArtifactScope.SYSTEM), config);
  }

  private String getVersion() {
    if (version == null) {
      try {
        version = getMetaClient().getVersion().getVersion();
      } catch (Exception e) {
        Throwables.propagate(e);
      }
    }
    return version;
  }

  protected void installPluginFromMarket(String packageName, String pluginName, String version)
    throws IOException, UnauthenticatedException {
    Map<String, ConfigEntry> cdapConfig = getMetaClient().getCDAPConfig();

    String caskMarketURL = cdapConfig.get("market.base.url").getValue();
    URL pluginJsonURL = new URL(String.format("%s/packages/%s/%s/%s-%s.json",
                                              caskMarketURL, packageName, version, pluginName, version));
    HttpResponse response = getRestClient().execute(HttpMethod.GET, pluginJsonURL, getClientConfig().getAccessToken());
    Assert.assertEquals(200, response.getResponseCode());

    // get the artifact 'parents' from the plugin json
    JsonObject pluginJson = new JsonParser().parse(response.getResponseBodyAsString()).getAsJsonObject();
    JsonArray parents = pluginJson.get("parents").getAsJsonArray();
    List<String> parentStrings = new ArrayList<>();
    for (JsonElement parent : parents) {
      parentStrings.add(parent.getAsString());
    }

    // leverage a UI endpoint to upload the plugins from market
    String source = URLEncoder.encode(
      String.format("packages/%s/%s/%s-%s.jar",
                    packageName, version, pluginName, version), "UTF-8");
    String target = URLEncoder.encode(
      String.format("v3/namespaces/%s/artifacts/%s", TEST_NAMESPACE.getNamespace(), pluginName), "UTF-8");

    ConnectionConfig connConfig = getClientConfig().getConnectionConfig();
    String uiPort = connConfig.isSSLEnabled() ?
      cdapConfig.get("dashboard.ssl.bind.port").getValue() : cdapConfig.get("dashboard.bind.port").getValue();
    String url =
      String.format("%s://%s:%s/forwardMarketToCdap?source=%s&target=%s",
                    connConfig.isSSLEnabled() ? "https" : "http",
                    connConfig.getHostname(), // just assume that UI is colocated with Router
                    uiPort,
                    source, target);

    Map<String, String> headers =
      ImmutableMap.of("Artifact-Extends", Joiner.on("/").join(parentStrings),
                      "Artifact-Version", version);
    response = getRestClient().execute(HttpMethod.GET, new URL(url), headers, getClientConfig().getAccessToken());
    Assert.assertEquals(200, response.getResponseCode());
  }

  protected void ingestData() throws Exception {
    // write input data
    DataSetManager<Table> datasetManager = getTableDataset(SOURCE_DATASET);
    Table table = datasetManager.get();
    // AAPL|10|500.32 with dummy timestamp
    putValues(table, 1, 234, "AAPL", 10, 500.32);
    datasetManager.flush();
  }

  private void putValues(Table table, int index, long ts, String ticker, int num, double price) {
    Put put = new Put(Bytes.toBytes(index));
    put.add("ts", ts);
    put.add("ticker", ticker);
    put.add("num", num);
    put.add("price", price);
    table.put(put);
  }
}
