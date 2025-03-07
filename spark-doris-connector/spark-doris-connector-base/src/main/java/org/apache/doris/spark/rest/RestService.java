// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.spark.rest;

import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_BENODES;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_FENODES;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_REQUEST_AUTH_PASSWORD;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_REQUEST_AUTH_USER;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLET_SIZE;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLET_SIZE_DEFAULT;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLET_SIZE_MIN;
import static org.apache.doris.spark.cfg.ConfigurationOptions.DORIS_TABLE_IDENTIFIER;
import static org.apache.doris.spark.util.ErrorMessages.ILLEGAL_ARGUMENT_MESSAGE;
import static org.apache.doris.spark.util.ErrorMessages.PARSE_NUMBER_FAILED_MESSAGE;
import static org.apache.doris.spark.util.ErrorMessages.SHOULD_NOT_HAPPEN_MESSAGE;

import org.apache.doris.spark.cfg.ConfigurationOptions;
import org.apache.doris.spark.cfg.Settings;
import org.apache.doris.spark.cfg.SparkSettings;
import org.apache.doris.spark.exception.DorisException;
import org.apache.doris.spark.exception.IllegalArgumentException;
import org.apache.doris.spark.exception.ShouldNeverHappenException;
import org.apache.doris.spark.rest.models.Backend;
import org.apache.doris.spark.rest.models.BackendRow;
import org.apache.doris.spark.rest.models.BackendV2;
import org.apache.doris.spark.rest.models.QueryPlan;
import org.apache.doris.spark.rest.models.Schema;
import org.apache.doris.spark.rest.models.Tablet;
import org.apache.doris.spark.sql.SchemaUtils;
import org.apache.doris.spark.sql.Utils;
import org.apache.doris.spark.util.HttpUtil;
import org.apache.doris.spark.util.URLs;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Service for communicate with Doris FE.
 */
@Deprecated
public class RestService implements Serializable {
    public final static int REST_RESPONSE_STATUS_OK = 200;
    private static final String API_PREFIX = "/api";
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();


    /**
     * parse table identifier to array.
     * @param tableIdentifier table identifier string
     * @param logger {@link Logger}
     * @return first element is db name, second element is table name
     * @throws IllegalArgumentException table identifier is illegal
     */
    @VisibleForTesting
    static String[] parseIdentifier(String tableIdentifier, Logger logger) throws IllegalArgumentException {
        logger.trace("Parse identifier '{}'.", tableIdentifier);
        if (StringUtils.isEmpty(tableIdentifier)) {
            logger.error(ILLEGAL_ARGUMENT_MESSAGE, "table.identifier", tableIdentifier);
            throw new IllegalArgumentException("table.identifier", tableIdentifier);
        }
        String[] identifier = tableIdentifier.split("\\.");
        if (identifier.length != 2) {
            logger.error(ILLEGAL_ARGUMENT_MESSAGE, "table.identifier", tableIdentifier);
            throw new IllegalArgumentException("table.identifier", tableIdentifier);
        }
        return identifier;
    }

    /**
     * choice a Doris FE node to request.
     * @param feNodes Doris FE node list, separate be comma
     * @param logger slf4j logger
     * @return the chosen one Doris FE node
     * @throws IllegalArgumentException fe nodes is illegal
     */
    @VisibleForTesting
    public static String randomEndpoint(String feNodes, Logger logger) throws IllegalArgumentException {
        logger.trace("Parse fenodes '{}'.", feNodes);
        if (StringUtils.isEmpty(feNodes)) {
            logger.error(ILLEGAL_ARGUMENT_MESSAGE, "fenodes", feNodes);
            throw new IllegalArgumentException("fenodes", feNodes);
        }
        List<String> nodes = Arrays.asList(feNodes.split(","));
        Collections.shuffle(nodes);
        return nodes.get(0).trim();
    }

    /**
     * get a valid URI to connect Doris FE.
     * @param cfg configuration of request
     * @param logger {@link Logger}
     * @return uri string
     * @throws IllegalArgumentException throw when configuration is illegal
     */
    @VisibleForTesting
    static String getUriStr(Settings cfg, Logger logger) throws IllegalArgumentException {
        String[] identifier = parseIdentifier(cfg.getProperty(DORIS_TABLE_IDENTIFIER), logger);
        return "http://" +
                randomEndpoint(cfg.getProperty(DORIS_FENODES), logger) + API_PREFIX +
                "/" + identifier[0] +
                "/" + identifier[1] +
                "/";
    }

    @Deprecated
    @VisibleForTesting
    static String getUriStr(String feNode,Settings cfg, Logger logger) throws IllegalArgumentException {
        String[] identifier = parseIdentifier(cfg.getProperty(DORIS_TABLE_IDENTIFIER), logger);
        return "http://" +
                feNode + API_PREFIX +
                "/" + identifier[0] +
                "/" + identifier[1] +
                "/";
    }


    /**
     * discover Doris table schema from Doris FE.
     * @param cfg configuration of request
     * @param logger slf4j logger
     * @return Doris table schema
     * @throws DorisException throw when discover failed
     */
    public static Schema getSchema(Settings cfg, Logger logger)
            throws DorisException {
        logger.trace("Finding schema.");
        String[] identifier = parseIdentifier(cfg.getProperty(DORIS_TABLE_IDENTIFIER), logger);
        String response = queryAllFrontends((SparkSettings) cfg, (frontend, enableHttps) ->
                new HttpGet(URLs.tableSchema(frontend, identifier[0], identifier[1], enableHttps)), logger);
        return parseSchema(response, logger);
    }

    /**
     * translate Doris FE response to inner {@link Schema} struct.
     * @param response Doris FE response
     * @param logger {@link Logger}
     * @return inner {@link Schema} struct
     * @throws DorisException throw when translate failed
     */
    @VisibleForTesting
    public static Schema parseSchema(String response, Logger logger) throws DorisException {
        logger.trace("Parse response '{}' to schema.", response);
        Schema schema;
        try {
            schema = MAPPER.readValue(response, Schema.class);
        } catch (JsonParseException e) {
            String errMsg = "Doris FE's response is not a json. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (JsonMappingException e) {
            String errMsg = "Doris FE's response cannot map to schema. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (IOException e) {
            String errMsg = "Parse Doris FE's response to json failed. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        }

        if (schema == null) {
            logger.error(SHOULD_NOT_HAPPEN_MESSAGE);
            throw new ShouldNeverHappenException();
        }

        if (schema.getStatus() != REST_RESPONSE_STATUS_OK) {
            String errMsg = "Doris FE's response is not OK, status is " + schema.getStatus();
            logger.error(errMsg);
            throw new DorisException(errMsg);
        }
        logger.debug("Parsing schema result is '{}'.", schema);
        return schema;
    }

    /**
     * find Doris RDD partitions from Doris FE.
     * @param cfg configuration of request
     * @param logger {@link Logger}
     * @return an list of Doris RDD partitions
     * @throws DorisException throw when find partition failed
     */
    public static List<PartitionDefinition> findPartitions(Settings cfg, Logger logger) throws DorisException {
        String[] tableIdentifiers =
                parseIdentifier(cfg.getProperty(ConfigurationOptions.DORIS_TABLE_IDENTIFIER), logger);
        String readFields = cfg.getProperty(ConfigurationOptions.DORIS_READ_FIELD, "*");
        if (!"*".equals(readFields)) {
            String[] readFieldArr = readFields.split(",");
            readFields = StringUtils.join(readFieldArr, ",");
        }
        String sql = "select " + readFields + " from `" + tableIdentifiers[0] + "`.`" + tableIdentifiers[1] + "`";
        if (!StringUtils.isEmpty(cfg.getProperty(ConfigurationOptions.DORIS_FILTER_QUERY))) {
            sql += " where " + cfg.getProperty(ConfigurationOptions.DORIS_FILTER_QUERY);
        }
        logger.debug("Query SQL Sending to Doris FE is: '{}'.", sql);

        String finalSql = sql;
        String response = queryAllFrontends((SparkSettings) cfg, (frontend, enableHttps) -> {
            HttpPost httpPost =
                    new HttpPost(URLs.queryPlan(frontend, tableIdentifiers[0], tableIdentifiers[1], enableHttps));
            String entity = "{\"sql\": \"" + finalSql + "\"}";
            logger.debug("Post body Sending to Doris FE is: '{}'.", entity);
            StringEntity stringEntity = new StringEntity(entity, StandardCharsets.UTF_8);
            stringEntity.setContentEncoding("UTF-8");
            stringEntity.setContentType("application/json");
            httpPost.setEntity(stringEntity);
            return httpPost;
        }, logger);
        logger.debug("Find partition response is '{}'.", response);
        QueryPlan queryPlan = getQueryPlan(response, logger);
        Map<String, List<Long>> be2Tablets = selectBeForTablet(queryPlan, logger);
        return tabletsMapToPartition(
                cfg,
                be2Tablets,
                queryPlan.getOpaqued_query_plan(),
                tableIdentifiers[0],
                tableIdentifiers[1],
                logger);

    }

    /**
     * translate Doris FE response string to inner {@link QueryPlan} struct.
     * @param response Doris FE response string
     * @param logger {@link Logger}
     * @return inner {@link QueryPlan} struct
     * @throws DorisException throw when translate failed.
     */
    @VisibleForTesting
    static QueryPlan getQueryPlan(String response, Logger logger) throws DorisException {
        QueryPlan queryPlan;
        try {
            queryPlan = MAPPER.readValue(response, QueryPlan.class);
        } catch (JsonParseException e) {
            String errMsg = "Doris FE's response is not a json. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (JsonMappingException e) {
            String errMsg = "Doris FE's response cannot map to schema. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (IOException e) {
            String errMsg = "Parse Doris FE's response to json failed. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        }

        if (queryPlan == null) {
            logger.error(SHOULD_NOT_HAPPEN_MESSAGE);
            throw new ShouldNeverHappenException();
        }

        if (queryPlan.getStatus() != REST_RESPONSE_STATUS_OK) {
            String errMsg = "Doris FE's response is not OK, status is " + queryPlan.getStatus();
            logger.error(errMsg);
            throw new DorisException(errMsg);
        }
        logger.debug("Parsing partition result is '{}'.", queryPlan);
        return queryPlan;
    }

    /**
     * select which Doris BE to get tablet data.
     * @param queryPlan {@link QueryPlan} translated from Doris FE response
     * @param logger {@link Logger}
     * @return BE to tablets {@link Map}
     * @throws DorisException throw when select failed.
     */
    @VisibleForTesting
    static  Map<String, List<Long>> selectBeForTablet(QueryPlan queryPlan, Logger logger) throws DorisException {
        Map<String, List<Long>> be2Tablets = new HashMap<>();
        for (Map.Entry<String, Tablet> part : queryPlan.getPartitions().entrySet()) {
            logger.debug("Parse tablet info: '{}'.", part);
            long tabletId;
            try {
                tabletId = Long.parseLong(part.getKey());
            } catch (NumberFormatException e) {
                String errMsg = "Parse tablet id '" + part.getKey() + "' to long failed.";
                logger.error(errMsg, e);
                throw new DorisException(errMsg, e);
            }
            String target = null;
            int tabletCount = Integer.MAX_VALUE;
            for (String candidate : part.getValue().getRoutings()) {
                logger.trace("Evaluate Doris BE '{}' to tablet '{}'.", candidate, tabletId);
                if (!be2Tablets.containsKey(candidate)) {
                    logger.debug("Choice a new Doris BE '{}' for tablet '{}'.", candidate, tabletId);
                    List<Long> tablets = new ArrayList<>();
                    be2Tablets.put(candidate, tablets);
                    target = candidate;
                    break;
                } else {
                    if (be2Tablets.get(candidate).size() < tabletCount) {
                        target = candidate;
                        tabletCount = be2Tablets.get(candidate).size();
                        logger.debug("Current candidate Doris BE to tablet '{}' is '{}' with tablet count {}.",
                                tabletId, target, tabletCount);
                    }
                }
            }
            if (target == null) {
                String errMsg = "Cannot choice Doris BE for tablet " + tabletId;
                logger.error(errMsg);
                throw new DorisException(errMsg);
            }

            logger.debug("Choice Doris BE '{}' for tablet '{}'.", target, tabletId);
            be2Tablets.get(target).add(tabletId);
        }
        return be2Tablets;
    }

    /**
     * tablet count limit for one Doris RDD partition
     * @param cfg configuration of request
     * @param logger {@link Logger}
     * @return tablet count limit
     */
    @VisibleForTesting
    static int tabletCountLimitForOnePartition(Settings cfg, Logger logger) {
        int tabletsSize = DORIS_TABLET_SIZE_DEFAULT;
        if (cfg.getProperty(DORIS_TABLET_SIZE) != null) {
            try {
                tabletsSize = Integer.parseInt(cfg.getProperty(DORIS_TABLET_SIZE));
            } catch (NumberFormatException e) {
                logger.warn(PARSE_NUMBER_FAILED_MESSAGE, DORIS_TABLET_SIZE, cfg.getProperty(DORIS_TABLET_SIZE));
            }
        }
        if (tabletsSize < DORIS_TABLET_SIZE_MIN) {
            logger.warn("{} is less than {}, set to default value {}.",
                    DORIS_TABLET_SIZE, DORIS_TABLET_SIZE_MIN, DORIS_TABLET_SIZE_MIN);
            tabletsSize = DORIS_TABLET_SIZE_MIN;
        }
        logger.debug("Tablet size is set to {}.", tabletsSize);
        return tabletsSize;
    }

    /**
     * choice a Doris BE node to request.
     * @param logger slf4j logger
     * @return the chosen one Doris BE node
     * @throws IllegalArgumentException BE nodes is illegal
     * Deprecated, use randomBackendV2 instead
     */
    @Deprecated
    @VisibleForTesting
    public static String randomBackend(SparkSettings sparkSettings , Logger logger) throws DorisException {
        return getBackend(sparkSettings, logger);
    }

    @Deprecated
    @VisibleForTesting
    public static String beBackend(SparkSettings sparkSettings , Logger logger) throws DorisException {
        return getBackend(sparkSettings, logger);
    }

    private static String getBackend(SparkSettings sparkSettings, Logger logger) throws DorisException {
        List<BackendV2.BackendRowV2> backends = getBackendRows(sparkSettings, logger);
        Collections.shuffle(backends);
        BackendV2.BackendRowV2 backend = backends.get(0);
        return backend.getIp() + ":" + backend.getHttpPort();
    }

    /**
     * translate Doris FE response to inner {@link BackendRow} struct.
     * @param response Doris FE response
     * @param logger {@link Logger}
     * @return inner {@link List<BackendRow>} struct
     * @throws DorisException,IOException throw when translate failed
     * */
    @Deprecated
    @VisibleForTesting
    static List<BackendRow> parseBackend(String response, Logger logger) throws DorisException, IOException {
        Backend backend;
        try {
            backend = MAPPER.readValue(response, Backend.class);
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            String errMsg = "Doris BE's response is not a json. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            String errMsg = "Doris BE's response cannot map to schema. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (IOException e) {
            String errMsg = "Parse Doris BE's response to json failed. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        }

        if (backend == null) {
            logger.error(SHOULD_NOT_HAPPEN_MESSAGE);
            throw new ShouldNeverHappenException();
        }
        List<BackendRow> backendRows = backend.getRows().stream().filter(BackendRow::getAlive).collect(Collectors.toList());
        logger.debug("Parsing schema result is '{}'.", backendRows);
        return backendRows;
    }

    /**
     * get Doris BE nodes.
     * @param logger slf4j logger
     * @return the Doris BE node
     * @throws IllegalArgumentException BE nodes is illegal
     */
    public static List<BackendV2.BackendRowV2> getBeNodes(SparkSettings sparkSettings, Logger logger) throws DorisException {
        List<String> backends = allBeEndpoints(sparkSettings.getProperty(DORIS_BENODES),logger);
        List<BackendV2.BackendRowV2> backendRowV2s = new ArrayList<BackendV2.BackendRowV2>();
        if (backends == null || backends.isEmpty()) {
            logger.error(ILLEGAL_ARGUMENT_MESSAGE, "benodes", backends);
            throw new IllegalArgumentException("benodes", String.valueOf(backends));
        }
        /*
         * By default, the BE port you enter is is_alive=true
         */
        for (String s : backends) {
            String ip = s.substring(0, s.indexOf(":"));
            try {
                int port = Integer.parseInt(s.substring(s.indexOf(":") + 1));
                /*
                 * By default, the BE port you enter is is_alive=true
                 */
                BackendV2.BackendRowV2 backend = BackendV2.BackendRowV2.of(ip, port, true);
                backendRowV2s.add(backend);
            } catch (NumberFormatException e) {
                logger.error("Doris BE is port error, please check configuration");
                throw new RuntimeException(e);
            }
        }
        return backendRowV2s;
    }

    /**
     * get Doris BE node list.
     * @param logger slf4j logger
     * @return the Doris BE node list
     * @throws IllegalArgumentException BE nodes is illegal
     */
    @VisibleForTesting
    public static List<BackendV2.BackendRowV2> getBackendRows(SparkSettings sparkSettings,  Logger logger) throws DorisException {
        if (StringUtils.isNoneBlank(sparkSettings.getProperty(DORIS_BENODES))) {
            return getBeNodes(sparkSettings, logger);
        } else { // If the specified BE does not exist, the FE mode is used
            String response = queryAllFrontends(sparkSettings, (frontend, enableHttps) ->
                    new HttpGet(URLs.aliveBackend(frontend, enableHttps)), logger);
            logger.info("Backend Info:{}", response);
            List<BackendV2.BackendRowV2> backends = parseBackendV2(response, logger);
            logger.trace("Parse benodes '{}'.", backends);
            if (backends == null || backends.isEmpty()) {
                logger.error(ILLEGAL_ARGUMENT_MESSAGE, "benodes", backends);
                throw new IllegalArgumentException("benodes", String.valueOf(backends));
            }
            return backends;
        }
    }

    static List<BackendV2.BackendRowV2> parseBackendV2(String response, Logger logger) throws DorisException {
        BackendV2 backend;
        try {
            backend = MAPPER.readValue(response, BackendV2.class);
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            String errMsg = "Doris BE's response is not a json. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (com.fasterxml.jackson.databind.JsonMappingException e) {
            String errMsg = "Doris BE's response cannot map to schema. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        } catch (IOException e) {
            String errMsg = "Parse Doris BE's response to json failed. res: " + response;
            logger.error(errMsg, e);
            throw new DorisException(errMsg, e);
        }

        if (backend == null) {
            logger.error(SHOULD_NOT_HAPPEN_MESSAGE);
            throw new ShouldNeverHappenException();
        }
        List<BackendV2.BackendRowV2> backendRows = backend.getBackends();
        logger.debug("Parsing schema result is '{}'.", backendRows);
        return backendRows;
    }

    /**
     * translate BE tablets map to Doris RDD partition.
     * @param cfg configuration of request
     * @param be2Tablets BE to tablets {@link Map}
     * @param opaquedQueryPlan Doris BE execute plan getting from Doris FE
     * @param database database name of Doris table
     * @param table table name of Doris table
     * @param logger {@link Logger}
     * @return Doris RDD partition {@link List}
     * @throws IllegalArgumentException throw when translate failed
     */
    @VisibleForTesting
    static List<PartitionDefinition> tabletsMapToPartition(Settings cfg, Map<String, List<Long>> be2Tablets,
                                                           String opaquedQueryPlan, String database, String table, Logger logger)
            throws IllegalArgumentException {
        int tabletsSize = tabletCountLimitForOnePartition(cfg, logger);
        List<PartitionDefinition> partitions = new ArrayList<>();
        for (Map.Entry<String, List<Long>> beInfo : be2Tablets.entrySet()) {
            logger.debug("Generate partition with beInfo: '{}'.", beInfo);
            HashSet<Long> tabletSet = new HashSet<>(beInfo.getValue());
            beInfo.getValue().clear();
            beInfo.getValue().addAll(tabletSet);
            int first = 0;
            while (first < beInfo.getValue().size()) {
                Set<Long> partitionTablets = new HashSet<>(beInfo.getValue().subList(
                        first, Math.min(beInfo.getValue().size(), first + tabletsSize)));
                first = first + tabletsSize;
                PartitionDefinition partitionDefinition =
                        new PartitionDefinition(database, table, cfg,
                                beInfo.getKey(), partitionTablets, opaquedQueryPlan);
                logger.debug("Generate one PartitionDefinition '{}'.", partitionDefinition);
                partitions.add(partitionDefinition);
            }
        }
        return partitions;
    }

    /**
     * choice a Doris FE node to request.
     *
     * @param feNodes Doris FE node list, separate be comma
     * @param logger  slf4j logger
     * @return the array of Doris FE nodes
     * @throws IllegalArgumentException fe nodes is illegal
     */
    @VisibleForTesting
    static List<String> allEndpoints(String feNodes, Logger logger) throws IllegalArgumentException {
        logger.trace("Parse fenodes '{}'.", feNodes);
        if (StringUtils.isEmpty(feNodes)) {
            logger.error(ILLEGAL_ARGUMENT_MESSAGE, "fenodes", feNodes);
            throw new IllegalArgumentException("fenodes", feNodes);
        }
        List<String> nodes = Arrays.stream(feNodes.split(",")).map(String::trim).collect(Collectors.toList());
        Collections.shuffle(nodes);
        return nodes;
    }

    /**
     * choice a Doris BE node to request.
     *
     * @param beNodes Doris BE node list, separate be comma
     * @param logger  slf4j logger
     * @return the array of Doris FE nodes
     * @throws IllegalArgumentException fe nodes is illegal
     */
    @VisibleForTesting
    static List<String> allBeEndpoints(String beNodes, Logger logger) throws IllegalArgumentException {
        logger.trace("Parse benodes '{}'.", beNodes);
        if (StringUtils.isEmpty(beNodes)) {
            logger.error(ILLEGAL_ARGUMENT_MESSAGE, "benodes", beNodes);
            throw new IllegalArgumentException("benodes", beNodes);
        }
        List<String> nodes = Arrays.stream(beNodes.split(",")).map(String::trim).collect(Collectors.toList());
        Collections.shuffle(nodes);
        return nodes;
    }

    /**
     * query all frontend
     *
     * @param settings doris config
     * @param func request provider
     * @param logger logger
     * @return http response string
     * @throws DorisException
     */
    private static String queryAllFrontends(SparkSettings settings, BiFunction<String, Boolean, HttpUriRequest> func,
                                          Logger logger) throws DorisException {
        List<String> frontends = allEndpoints(settings.getProperty(DORIS_FENODES), logger);
        boolean enableHttps = settings.getBooleanProperty(ConfigurationOptions.DORIS_ENABLE_HTTPS,
                ConfigurationOptions.DORIS_ENABLE_HTTPS_DEFAULT);
        CloseableHttpClient client = HttpUtil.getHttpClient(settings);
        for (String frontend : frontends) {
            try {
                HttpUriRequest request = func.apply(frontend, enableHttps);
                String user = settings.getProperty(DORIS_REQUEST_AUTH_USER, "");
                String password = settings.getProperty(DORIS_REQUEST_AUTH_PASSWORD, "");
                logger.info("Send request to Doris FE '{}' with user '{}'.", request.getURI(), user);
                request.setHeader(HttpHeaders.AUTHORIZATION, "Basic "
                        + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
                CloseableHttpResponse response = client.execute(request);
                StatusLine statusLine = response.getStatusLine();
                if (statusLine.getStatusCode() == HttpStatus.SC_OK) {
                    String resStr = EntityUtils.toString(response.getEntity());
                    Map<String, Object> resMap = MAPPER.readValue(resStr,
                            new TypeReference<Map<String, Object>>() {
                            });
                    if (resMap.containsKey("msg") && resMap.containsKey("data")) {
                        return MAPPER.writeValueAsString(resMap.get("data"));
                    }
                    return resStr;
                }
                logger.warn("Request for {} get a bad status, code: {}, msg: {}", request.getURI().toString(),
                        statusLine.getStatusCode(), statusLine.getReasonPhrase());
            } catch (IOException e) {
                logger.error("Doris FE node {} is unavailable, Request the next Doris FE node. Err: {}", frontend, e.getMessage());
            }
        }
        String errMsg = "No Doris FE is available, please check configuration";
        logger.error(errMsg);
        throw new DorisException(errMsg);
    }

}
