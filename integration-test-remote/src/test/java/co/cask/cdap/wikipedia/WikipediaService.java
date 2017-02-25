/*
 * Copyright © 2015 Cask Data, Inc.
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

package co.cask.cdap.wikipedia;

import co.cask.cdap.api.annotation.UseDataSet;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.dataset.lib.CloseableIterator;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.api.dataset.lib.KeyValueTable;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.dataset.table.Scanner;
import co.cask.cdap.api.dataset.table.Table;
import co.cask.cdap.api.service.AbstractService;
import co.cask.cdap.api.service.http.AbstractHttpServiceHandler;
import co.cask.cdap.api.service.http.HttpServiceHandler;
import co.cask.cdap.api.service.http.HttpServiceRequest;
import co.cask.cdap.api.service.http.HttpServiceResponder;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;

/**
 * Service to retrieve results of analyses of Wikipedia data.
 */
public class WikipediaService extends AbstractService {
  public static final String NAME = WikipediaService.class.getSimpleName();

  @Override
  protected void configure() {
    setName(NAME);
    setDescription("A service that allows users to query Wikipedia Data Analysis results.");
    addHandler(new WikipediaHandler());
  }

  /**
   * {@link HttpServiceHandler} that contains endpoints for serving results of analyses of Wikipedia data.
   */
  @Path("/v1/functions")
  public static final class WikipediaHandler extends AbstractHttpServiceHandler {

    @SuppressWarnings("unused")
    @UseDataSet(WikipediaPipelineApp.SPARK_CLUSTERING_OUTPUT_DATASET)
    private Table clusteringTable;

    @SuppressWarnings("unused")
    @UseDataSet(WikipediaPipelineApp.MAPREDUCE_TOPN_OUTPUT)
    private KeyValueTable topNKVTable;

    /**
     * The {@link SparkWikipediaClustering} program generates a list of topics for the input data. This API returns the
     * list of topics that were generated by the {@link SparkWikipediaClustering} program.
     */
    @GET
    @Path("/lda/topics")
    public void getTopics(HttpServiceRequest request, HttpServiceResponder responder) {
      List<Integer> topics = new ArrayList<>();
      Scanner scanner = clusteringTable.scan(null, null);
      Row row;
      while ((row = scanner.next()) != null) {
        topics.add(Bytes.toInt(row.getRow()));
      }
      responder.sendJson(topics);
    }

    /**
     * Returns the details of a particular topic. Each topic contains a list of terms and and their weight in the
     * specified topic.
     *
     * @param topic the topic to return details for
     */
    @GET
    @Path("/lda/topics/{topic}")
    public void getTopic(HttpServiceRequest request, HttpServiceResponder responder,
                         @PathParam("topic") Integer topic) {
      Row row = clusteringTable.get(Bytes.toBytes(topic));
      if (row.isEmpty()) {
        responder.sendError(404, String.format("Topic %s was not found.", topic));
        return;
      }
      List<Term> terms = new ArrayList<>();
      Map<byte[], byte[]> columns = row.getColumns();
      for (Map.Entry<byte[], byte[]> next : columns.entrySet()) {
        terms.add(new Term(Bytes.toString(next.getKey()), Bytes.toDouble(next.getValue())));
      }

      responder.sendJson(terms);
    }

    /**
     * Returns the list of words emitted by the {@link TopNMapReduce} program.
     */
    @GET
    @Path("/topn/words")
    public void getTopNWords(HttpServiceRequest request, HttpServiceResponder responder) {
      List<JsonObject> words = new ArrayList<>();
      CloseableIterator<KeyValue<byte[], byte[]>> scanner = topNKVTable.scan(null, null);
      while (scanner.hasNext()) {
        KeyValue<byte[], byte[]> next = scanner.next();
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty(Bytes.toString(next.getKey()), Bytes.toInt(next.getValue()));
        words.add(jsonObject);
      }

      responder.sendJson(words);
    }
  }
}