package org.example.controller;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/tools")
public class AgentToolController {

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)
    private QueryLogsTools queryLogsTools;

    @PostMapping("/datetime/current")
    public String getCurrentDateTime() {
        return dateTimeTools.getCurrentDateTime();
    }

    @PostMapping("/internal-docs/query")
    public String queryInternalDocs(@RequestBody Map<String, String> request) {
        String query = request.get("query");
        return internalDocsTools.queryInternalDocs(query);
    }

    @PostMapping("/metrics/alerts")
    public String queryPrometheusAlerts() {
        return queryMetricsTools.queryPrometheusAlerts();
    }

    @PostMapping("/logs/topics")
    public String getAvailableLogTopics() {
        if (queryLogsTools == null) {
            return "{\"success\":false,\"message\":\"Log tools not available\"}";
        }
        return queryLogsTools.getAvailableLogTopics();
    }

    @PostMapping("/logs/query")
    public String queryLogs(@RequestBody Map<String, Object> request) {
        if (queryLogsTools == null) {
            return "{\"success\":false,\"message\":\"Log tools not available\"}";
        }
        String region = (String) request.get("region");
        String logTopic = (String) request.get("logTopic");
        String query = (String) request.get("query");
        Integer limit = request.containsKey("limit") ? (Integer) request.get("limit") : null;
        return queryLogsTools.queryLogs(region, logTopic, query, limit);
    }
}