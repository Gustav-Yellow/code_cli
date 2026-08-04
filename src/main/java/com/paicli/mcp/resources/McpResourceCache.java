package com.paicli.mcp.resources;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP resource 缓存。
 *
 * 按 server 存储 resource 列表，支持 server 级和 URI 级的过期追踪。
 * 收到 resources/list_changed / resources/updated 通知时标记过期，
 * 下次 readResource / listResources 时重新拉取。
 */
public class McpResourceCache {
    private final Map<String, List<McpResourceDescriptor>> byServer = new ConcurrentHashMap<>();
    private final Set<String> staleServers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> staleUrisByServer = new ConcurrentHashMap<>();

    public void put(String serverName, List<McpResourceDescriptor> resources) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        byServer.put(serverName, resources == null ? List.of() : List.copyOf(resources));
        staleServers.remove(serverName);
        staleUrisByServer.remove(serverName);
    }

    public List<McpResourceDescriptor> get(String serverName) {
        if (serverName == null || isServerStale(serverName)) {
            return List.of();
        }
        return byServer.getOrDefault(serverName, List.of());
    }

    /** 返回所有未过期的 resource，按 server → uri 排序 */
    public List<McpResourceDescriptor> all() {
        List<McpResourceDescriptor> resources = new ArrayList<>();
        byServer.keySet().stream()
                .filter(server -> !isServerStale(server))
                .sorted()
                .forEach(server -> resources.addAll(byServer.getOrDefault(server, List.of())));
        resources.sort(Comparator
                .comparing(McpResourceDescriptor::serverName)
                .thenComparing(McpResourceDescriptor::uri));
        return resources;
    }

    public void invalidateServer(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            staleServers.add(serverName);
        }
    }

    public void invalidateResource(String serverName, String uri) {
        if (serverName == null || serverName.isBlank() || uri == null || uri.isBlank()) {
            return;
        }
        staleUrisByServer
                .computeIfAbsent(serverName, ignored -> ConcurrentHashMap.newKeySet())
                .add(uri);
    }

    public boolean isServerStale(String serverName) {
        return serverName != null && staleServers.contains(serverName);
    }

    public boolean isResourceStale(String serverName, String uri) {
        if (serverName == null || uri == null) {
            return false;
        }
        return staleUrisByServer.getOrDefault(serverName, Set.of()).contains(uri);
    }

    public void markResourceFresh(String serverName, String uri) {
        Set<String> staleUris = staleUrisByServer.get(serverName);
        if (staleUris != null) {
            staleUris.remove(uri);
        }
    }
}
