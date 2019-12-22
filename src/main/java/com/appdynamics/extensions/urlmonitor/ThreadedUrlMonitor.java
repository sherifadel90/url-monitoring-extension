/*
 * Copyright 2014. AppDynamics LLC and its affiliates.
 * All Rights Reserved.
 * This is unpublished proprietary source code of AppDynamics LLC and its affiliates.
 * The copyright notice above does not evidence any actual or intended publication of such source code.
 */

package com.appdynamics.extensions.urlmonitor;

import com.appdynamics.extensions.PathResolver;
import com.appdynamics.extensions.urlmonitor.SiteResult.ResultStatus;
import com.appdynamics.extensions.urlmonitor.auth.AuthSchemeFactory;
import com.appdynamics.extensions.urlmonitor.auth.AuthTypeEnum;
import com.appdynamics.extensions.urlmonitor.config.DefaultSiteConfig;
import com.appdynamics.extensions.urlmonitor.config.MatchPattern;
import com.appdynamics.extensions.urlmonitor.config.MonitorConfig;
import com.appdynamics.extensions.urlmonitor.config.ProxyConfig;
import com.appdynamics.extensions.urlmonitor.config.RequestConfig;
import com.appdynamics.extensions.urlmonitor.config.SiteConfig;
import com.appdynamics.extensions.yml.YmlReader;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.Request;
import com.ning.http.client.RequestBuilder;
import com.ning.http.client.Response;
import com.singularity.ee.agent.systemagent.api.AManagedMonitor;
import com.singularity.ee.agent.systemagent.api.MetricWriter;
import com.singularity.ee.agent.systemagent.api.TaskExecutionContext;
import com.singularity.ee.agent.systemagent.api.TaskOutput;
import com.singularity.ee.agent.systemagent.api.exception.TaskExecutionException;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThreadedUrlMonitor extends AManagedMonitor {
    private static final Logger log = Logger.getLogger(ThreadedUrlMonitor.class);
    private static final String DEFAULT_CONFIG_FILE = "config.yml";
    private static final String CONFIG_FILE_PARAM = "config-file";
    private static final String DEFAULT_METRIC_PATH = "Custom Metrics|URL Monitor";
    private String metricPath = DEFAULT_METRIC_PATH;
    protected MonitorConfig config;

    protected RequestConfig requestConfig = new RequestConfig();

    public ThreadedUrlMonitor() {
        System.out.println(logVersion());
    }


    private String getConfigFilename(String filename) {
        if (filename == null) {
            return "";
        }
        // for absolute paths
        if (new File(filename).exists()) {
            return filename;
        }
        // for relative paths
        File jarPath = PathResolver.resolveDirectory(AManagedMonitor.class);
        String configFileName = "";
        if (!Strings.isNullOrEmpty(filename)) {
            configFileName = jarPath + File.separator + filename;
        }
        return configFileName;
    }

    private String readPostRequestFile(SiteConfig site) {
        String requestBody = "";
        try {
            requestBody = Files.toString(new File(getConfigFilename(site.getRequestPayloadFile())), Charsets.UTF_8);
        } catch (FileNotFoundException e) {
            log.error("Post Request Payload file not found for url " + site.getUrl(), e);
        } catch (IOException e) {
            log.error("Exception while reading PostRequest Body file for url " + site.getUrl(), e);
        }
        return requestBody;
    }

    protected void setSiteDefaults() {
        DefaultSiteConfig defaults = config.getDefaultParams();

        for (final SiteConfig site : config.getSites()) {
            if (site.isTreatAuthFailedAsError() == null)
                site.setTreatAuthFailedAsError(defaults.isTreatAuthFailedAsError());
            if (site.getNumAttempts() == -1)
                site.setNumAttempts(defaults.getNumAttempts());
            if (Strings.isNullOrEmpty(site.getMethod()))
                site.setMethod(defaults.getMethod());
        }
    }

    protected final ConcurrentHashMap<SiteConfig, List<SiteResult>> buildResultMap() {
        final ConcurrentHashMap<SiteConfig, List<SiteResult>> results = new ConcurrentHashMap<SiteConfig, List<SiteResult>>();
        for (final SiteConfig site : config.getSites()) {
            results.put(site, Collections.synchronizedList(new ArrayList<SiteResult>()));
        }

        return results;
    }

    //    @Override
    public TaskOutput execute(Map<String, String> taskParams, TaskExecutionContext taskContext)
            throws TaskExecutionException {

        if (taskParams != null) {
            log.info(logVersion());
            String configFilename = DEFAULT_CONFIG_FILE;
            if (taskParams.containsKey(CONFIG_FILE_PARAM)) {
                configFilename = taskParams.get(CONFIG_FILE_PARAM);
            }

            File file = PathResolver.getFile(configFilename, MonitorConfig.class);
            config = YmlReader.readFromFile(file, MonitorConfig.class);
            if (config == null) {
                log.debug("Config created was null, returning without executing the monitor.");
                return null;
            }
            if (!Strings.isNullOrEmpty(config.getMetricPrefix())) {
                metricPath = StringUtils.stripEnd(config.getMetricPrefix(), "|");
                log.debug("Metric Path from config: " + metricPath);

            }

            final CountDownLatch latch = new CountDownLatch(config.getTotalAttemptCount());
            log.info(String.format("Sending %d HTTP requests asynchronously to %d sites",
                    latch.getCount(), config.getSites().length));

            setSiteDefaults();

            final ConcurrentHashMap<SiteConfig, List<SiteResult>> results = buildResultMap();
            final long overallStartTime = System.currentTimeMillis();
            final Map<String, Integer> groupStatus = new HashMap<String, Integer>();

            List<RequestConfig> requestConfigList = requestConfig.setClientForSite(config, config.getSites());

            try {
                for (final RequestConfig requestConfig : requestConfigList) {

                    final SiteConfig site = requestConfig.getSiteConfig();

                    for (int i = 0; i < site.getNumAttempts(); i++) {
                        RequestBuilder rb = new RequestBuilder()
                                .setMethod(site.getMethod())
                                .setUrl(site.getUrl())
                                .setFollowRedirects(site.isFollowRedirects())
                                .setRealm(AuthSchemeFactory.getAuth(AuthTypeEnum.valueOf(site.getAuthType()!=null ? site.getAuthType() : AuthTypeEnum.NONE.name()),site)
                                        .build());
                        if (!Strings.isNullOrEmpty(site.getRequestPayloadFile())) {
                            rb.setBody(readPostRequestFile(site));
                            if (!"post".equalsIgnoreCase(site.getMethod())) {
                                rb.setMethod("POST");
                            }
                        }
                        //proxy support
                        ProxyConfig proxyConfig = site.getProxyConfig();
                        if (proxyConfig != null) {
                            if (proxyConfig.getUsername() != null && proxyConfig.getPassword() != null) {
                                rb.setProxyServer(new ProxyServer(proxyConfig.getHost(), proxyConfig.getPort(), proxyConfig.getUsername(), proxyConfig.getPassword()));
                            } else {
                                rb.setProxyServer(new ProxyServer(proxyConfig.getHost(), proxyConfig.getPort()));
                            }
                        }

                        for (Map.Entry<String, String> header : site.getHeaders().entrySet()) {
                            rb.addHeader(header.getKey(), header.getValue());
                        }

                        log.info(String.format("Sending %s request %d of %d to %s at %s with redirect allowed as %s",
                                site.getMethod(), (i + 1),
                                site.getNumAttempts(), site.getName(), site.getUrl(), site.isFollowRedirects()));

                        final long startTime = System.currentTimeMillis();
                        final Request r = rb.build();

                        final SiteResult result = new SiteResult();
                        result.setStatus(ResultStatus.SUCCESS);

                        final ByteArrayOutputStream body = new ByteArrayOutputStream();

                        requestConfig.getClient().executeRequest(r, new AsyncCompletionHandler<Response>() {

                            private void finish(SiteResult result) {
                                results.get(site)
                                        .add(result);
                                latch.countDown();
                                printMetricsForRequestCompleted(results.get(site), site);
                                log.info(latch.getCount() + " requests remaining");
                            }

                            private void printMetricsForRequestCompleted(List<SiteResult> results, SiteConfig site) {
                                String myMetricPath = metricPath + "|" + site.getName();
                                int resultCount = results.size();

                                long totalFirstByteTime = 0;
                                long totalDownloadTime = 0;
                                long totalElapsedTime = 0;
                                int statusCode = 0;
                                long responseSize = 0;
                                long successPercentage = 0;
                                //int availability = 0;
                                HashMap<String, Integer> matches = null;
                                SiteResult.ResultStatus status = SiteResult.ResultStatus.UNKNOWN;
                                for (SiteResult result : results) {
                                    status = result.getStatus();
                                    statusCode = result.getResponseCode();
                                /*if(statusCode == 200) {
                                    availability = 1;
                                }*/
                                    responseSize = result.getResponseBytes();
                                    totalFirstByteTime += result.getFirstByteTime();
                                    totalDownloadTime += result.getDownloadTime();
                                    totalElapsedTime += result.getTotalTime();
                                    successPercentage = result.getSuccessPercentage();
                                    matches = result.getMatches();
                                }

                                long averageFirstByteTime = totalFirstByteTime / resultCount;
                                long averageDownloadTime = totalDownloadTime / resultCount;
                                long averageElapsedTime = totalElapsedTime / resultCount;

                                log.info(String.format("Results for site '%s': count=%d, total=%d ms, average=%d ms, respCode=%d, bytes=%d, status=%s",
                                        site.getName(), resultCount, totalFirstByteTime, averageFirstByteTime, statusCode, responseSize, status));


                                if (!Strings.isNullOrEmpty(site.getGroupName())) {
                                    myMetricPath = metricPath + "|" + site.getGroupName() + "|" + site.getName();

                                    if (statusCode == 200) {

                                        Integer count = groupStatus.get(site.getGroupName());

                                        if (count == null) {
                                            groupStatus.put(site.getGroupName(), 1);
                                        } else {
                                            groupStatus.put(site.getGroupName(), ++count);
                                        }
                                    }
                                }

                                printMetricWithValue(myMetricPath + "|Average Response Time (ms)", Long.toString(averageElapsedTime));
                                printMetricWithValue(myMetricPath + "|Download Time (ms)", Long.toString(averageDownloadTime));
                                printMetricWithValue(myMetricPath + "|First Byte Time (ms)", Long.toString(averageFirstByteTime));
                                printMetricWithValue(myMetricPath + "|Response Code", Integer.toString(statusCode));
                                printMetricWithValue(myMetricPath + "|Status", Long.toString(status.ordinal()));
                                printMetricWithValue(myMetricPath + "|Response Bytes", Long.toString(responseSize));
                                printMetricWithValue(myMetricPath + "|Success Percentage", Long.toString(successPercentage));
                                //printMetricWithValue(myMetricPath + "|Availability", Integer.toString(availability));


                                myMetricPath += "|Pattern Matches";
                                if (matches != null) {
                                    for (Map.Entry<String, Integer> match : matches.entrySet()) {
                                        getMetricWriter(myMetricPath + "|" + match.getKey() + "|Count",
                                                MetricWriter.METRIC_AGGREGATION_TYPE_SUM,
                                                MetricWriter.METRIC_TIME_ROLLUP_TYPE_SUM,
                                                MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE).printMetric(
                                                Long.toString(match.getValue()));
                                    }
                                }

                            }

                            @Override
                            public STATE onStatusReceived(HttpResponseStatus status) throws Exception {

                                result.setFirstByteTime(System.currentTimeMillis() - startTime);
                                result.setResponseCode(status.getStatusCode());
                                log.debug(String.format("[%s] First byte received in %d ms",
                                        site.getName(),
                                        result.getFirstByteTime()));

                                if (status.getStatusCode() == 200) {
                                    log.info(String.format("[%s] %s %s -> %d %s",
                                            site.getName(),
                                            site.getMethod(),
                                            site.getUrl(),
                                            status.getStatusCode(),
                                            status.getStatusText()));
                                            result.setSuccessPercentage(100);
                                    return STATE.CONTINUE;
                                } else if (status.getStatusCode() == 401 && !site.isTreatAuthFailedAsError()) {
                                    log.info(String.format("[%s] %s %s -> %d %s [but OK]",
                                            site.getName(),
                                            site.getMethod(),
                                            site.getUrl(),
                                            status.getStatusCode(),
                                            status.getStatusText()));
                                            result.setSuccessPercentage(0);
                                    return STATE.CONTINUE;
                                }

                                log.warn(String.format("[%s] %s %s -> %d %s",
                                        site.getName(),
                                        site.getMethod(),
                                        site.getUrl(),
                                        status.getStatusCode(),
                                        status.getStatusText()));
                                result.setStatus(ResultStatus.ERROR);
                                result.setSuccessPercentage(0);                               

                                return STATE.ABORT;
                            }

                            @Override
                            public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
                                for (Map.Entry<String, List<String>> entry : headers.getHeaders().entrySet()) {
                                    for (String value : entry.getValue()) {
                                        body.write(entry.getKey().getBytes());
                                        body.write(':');
                                        body.write(' ');
                                        body.write(value.getBytes());
                                        body.write('\n');
                                    }
                                }
                                body.write("\n\n".getBytes());

                                long headerTime = System.currentTimeMillis() - startTime;
                                log.debug(String.format("[%s] Headers received in %d ms",
                                        site.getName(), headerTime));

                                return STATE.CONTINUE;
                            }

                            @Override
                            public STATE onBodyPartReceived(HttpResponseBodyPart content) throws Exception {
                                content.writeTo(body);
                                return STATE.CONTINUE;
                            }

                            private int getStringMatchCount(String text, String pattern) {
                                return StringUtils.countMatches(text, pattern);
                            }

                            private int getRegexMatchCount(String text, String pattern) {

                                Pattern regexPattern = Pattern.compile(pattern);
                                Matcher regexMatcher = regexPattern.matcher(text);

                                int matchCount = 0;
                                while (regexMatcher.find()) {
                                    matchCount += 1;
                                }

                                return matchCount;
                            }

                            @Override
                            public Response onCompleted(Response response) throws Exception {

                                if (result.getStatus() == ResultStatus.SUCCESS) {

                                    result.setDownloadTime(System.currentTimeMillis() - (startTime + result.getFirstByteTime()));
                                    result.setTotalTime(System.currentTimeMillis() - startTime);

                                    String responseBody = body.toString();
                                    result.setResponseBytes(responseBody.length());
                                    log.info(String.format("[%s] Download time was %d ms for %d bytes",
                                            site.getName(),
                                            result.getDownloadTime(),
                                            result.getResponseBytes()));

                                    if (site.getMatchPatterns().size() > 0) {

                                        for (MatchPattern pattern : site.getMatchPatterns()) {
                                            MatchPattern.PatternType type = MatchPattern.PatternType.fromString(pattern.getType());
                                            log.debug(String.format("[%s] Checking for a %s match against '%s'",
                                                    site.getName(),
                                                    pattern.getType(),
                                                    pattern.getPattern()));

                                            int matchCount;
                                            switch (type) {
                                                case SUBSTRING:
                                                    matchCount = getStringMatchCount(responseBody,
                                                            pattern.getPattern());
                                                    break;
                                                case CASE_INSENSITIVE_SUBSTRING:
                                                    matchCount = getStringMatchCount(responseBody.toLowerCase(),
                                                            pattern.getPattern().toLowerCase());
                                                    break;
                                                case REGEX:
                                                    matchCount = getRegexMatchCount(responseBody,
                                                            pattern.getPattern());
                                                    break;
                                                case WORD:
                                                    matchCount = getRegexMatchCount(responseBody,
                                                            "(?i)\\b" + pattern.getPattern() + "\\b");
                                                    break;

                                                default:
                                                    throw new IllegalArgumentException("Unknown pattern type: " + pattern.getType());
                                            }

                                            log.info(String.format("[%s] Match count for %s pattern '%s' = %d ",
                                                    site.getName(),
                                                    pattern.getType(),
                                                    pattern.getPattern(),
                                                    matchCount));
                                            result.getMatches().put(pattern.getName(), matchCount);
                                        }
                                    }

                                }

                                log.info(String.format("[%s] Request completed in %d ms",
                                        site.getName(),
                                        result.getTotalTime()));
                                finish(result);


                                return response;
                            }

                            @Override
                            public void onThrowable(Throwable t) {
                                log.error(site.getUrl() + " -> FAILED: " + t.getMessage(), t);
                                finish(new SiteResult(ResultStatus.FAILED));
                            }
                        });
                    }
                }

                latch.await();

                final long overallElapsedTime = System.currentTimeMillis() - overallStartTime;

                getMetricWriter(metricPath + "|Requests Sent",
                        MetricWriter.METRIC_AGGREGATION_TYPE_SUM,
                        MetricWriter.METRIC_TIME_ROLLUP_TYPE_SUM,
                        MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE).printMetric(
                        Long.toString(config.getTotalAttemptCount()));
                getMetricWriter(metricPath + "|Elapsed Time (ms)",
                        MetricWriter.METRIC_AGGREGATION_TYPE_SUM,
                        MetricWriter.METRIC_TIME_ROLLUP_TYPE_SUM,
                        MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE).printMetric(
                        Long.toString(overallElapsedTime));
                getMetricWriter(metricPath + "|Monitored Sites Count",
                        MetricWriter.METRIC_AGGREGATION_TYPE_SUM,
                        MetricWriter.METRIC_TIME_ROLLUP_TYPE_SUM,
                        MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE).printMetric(
                        Long.toString(config.getSitesCount()));
                for (Map.Entry entry : groupStatus.entrySet()) {
                    String metricName = metricPath + "|" + entry.getKey() + "|Responsive Site Count";
                    Integer metricValue = groupStatus.get(entry.getKey());

                    printMetricWithValue(metricName, String.valueOf(
                            metricValue), MetricWriter.METRIC_AGGREGATION_TYPE_OBSERVATION,
                            MetricWriter.METRIC_TIME_ROLLUP_TYPE_CURRENT,
                            MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE);
                }

                log.info("All requests completed in " + overallElapsedTime + " ms");
            } catch (Exception ex) {
                log.error("Error in HTTP client: " + ex.getMessage(), ex);
                throw new TaskExecutionException(ex);
            } finally {
                requestConfig.closeClients(requestConfigList);
            }

            return new TaskOutput("Success");
        }
        throw new TaskExecutionException("Threaded URL Monitoring completed with failures.");
    }

    protected void printMetricWithValue(String metricName, String metricValue) {
        if (!Strings.isNullOrEmpty(metricValue)) {

            printMetricWithValue(metricName, metricValue, MetricWriter.METRIC_AGGREGATION_TYPE_AVERAGE,
                    MetricWriter.METRIC_TIME_ROLLUP_TYPE_AVERAGE,
                    MetricWriter.METRIC_CLUSTER_ROLLUP_TYPE_COLLECTIVE);
        } else {
            log.debug(String.format("Ignoring the metric %s as the value is null", metricName));
        }
    }

    protected void printMetricWithValue(String metricName, String metricValue, String aggregationType, String rollupType, String clusterRollupType) {
        if (!Strings.isNullOrEmpty(metricValue)) {

            log.debug(String.format("Printing metric %s with value %s", metricName, metricValue));

            MetricWriter metricWriter = getMetricWriter(metricName,
                    aggregationType,
                    rollupType,
                    clusterRollupType);
            metricWriter.printMetric(metricValue);
        }
    }

    private String logVersion() {
        String msg = "Using Monitor Version [" + getImplementationVersion() + "]";
        return msg;
    }

    private static String getImplementationVersion() {
        return ThreadedUrlMonitor.class.getPackage().getImplementationTitle();
    }

    public static void main(String[] args) throws TaskExecutionException {

        ConsoleAppender ca = new ConsoleAppender();
        ca.setWriter(new OutputStreamWriter(System.out));
        ca.setLayout(new PatternLayout("%-5p [%t]: %m%n"));
        ca.setThreshold(Level.DEBUG);

        log.getRootLogger().addAppender(ca);

        ThreadedUrlMonitor monitor = new ThreadedUrlMonitor();


        final Map<String, String> taskArgs = new HashMap<String, String>();
        taskArgs.put("config-file", "/Users/akshay.srivastava/AppDynamics/extensions/url-monitoring-extension/src/main/resources/conf/config.yml");


        monitor.execute(taskArgs, null);

    }

}
