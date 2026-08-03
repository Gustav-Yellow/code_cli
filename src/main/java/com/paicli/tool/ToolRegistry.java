package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.LlmClient;
import com.paicli.rag.CodeRetriever;
import com.paicli.rag.SearchResultFormatter;
import com.paicli.rag.VectorStore;
import com.paicli.web.FetchResult;
import com.paicli.web.HtmlExtractor;
import com.paicli.web.NetworkPolicy;
import com.paicli.web.SearchProvider;
import com.paicli.web.SearchProviderFactory;
import com.paicli.web.SearchResult;
import com.paicli.web.WebFetcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 工具注册表 - 管理所有可用工具
 * Agent 要能干实事，得有一套工具。
 *
 * read_file：读取文件
 * write_file：写入文件
 * list_dir：列出目录
 * execute_command：执行 Shell 命令
 * create_project：创建项目结构
 */
public class ToolRegistry {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Tool> tools = new HashMap<>();

    private String projectPath = System.getProperty("user.dir");

    private static final int DEFAULT_COMMAND_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS = 90;
    private static final int MAX_PARALLEL_TOOLS = 4;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 8_000;
    private static final int DEFAULT_FETCH_MAX_CHARS = 8_000;
    private final long commandTimeoutSeconds;
    private final long toolBatchTimeoutSeconds;
    private SearchProvider searchProvider;
    private WebFetcher webFetcher;
    private HtmlExtractor htmlExtractor;
    private NetworkPolicy networkPolicy;

    public ToolRegistry() {
        this(DEFAULT_COMMAND_TIMEOUT_SECONDS, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS);
    }

    ToolRegistry(long commandTimeoutSeconds) {
        this(commandTimeoutSeconds, Math.max(commandTimeoutSeconds + 5, DEFAULT_TOOL_BATCH_TIMEOUT_SECONDS));
    }

    ToolRegistry(long commandTimeoutSeconds, long toolBatchTimeoutSeconds) {
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.toolBatchTimeoutSeconds = toolBatchTimeoutSeconds;
        // 注册内置工具
        registerFileTools();
        registerShellTools();
        registerCodeTools();
        registerRagTools();
        registerWebTools();
    }

    /**
     * 设置项目路径
     * @param projectPath 项目路径
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
    }

    /**
     * 获取代码检索的项目路径
     */
    public String getProjectPath() {
        return projectPath;
    }

    /**
     * 注册文件操作工具
     */
    private void registerFileTools() {
        // read_file 工具
        tools.put("read_file", new Tool(
                "read_file",
                "读取文件内容",
                createParameters(new Param("path", "string", "文件路径", true)),
                args -> {
                    String path = args.get("path");
                    try {
                        String content = Files.readString(Path.of(path));
                        return "文件内容:\n" + content;
                    } catch (Exception e) {
                        return "读取文件失败: " + e.getMessage();
                    }
                }
        ));

        // write_file 工具
        tools.put("write_file", new Tool(
                "write_file",
                "写入文件内容",
                createParameters(
                        new Param("path", "string", "文件路径", true),
                        new Param("content", "string", "文件内容", true)
                ),
                args -> {
                    String path = args.get("path");
                    String content = args.get("content");
                    try {
                        // 确保父目录存在
                        Path parent = Path.of(path).getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.writeString(Path.of(path), content);
                        return "文件已写入: " + path;
                    } catch (Exception e) {
                        return "写入文件失败: " + e.getMessage();
                    }
                }
        ));

        // list_dir 工具
        tools.put("list_dir", new Tool(
                "list_dir",
                "列出目录内容",
                createParameters(new Param("path", "string", "目录路径", true)),
                args -> {
                    String path = args.get("path");
                    try {
                        File dir = new File(path);
                        File[] files = dir.listFiles();
                        if (files == null) {
                            return "目录为空或不存在";
                        }
                        StringBuilder sb = new StringBuilder("目录内容:\n");
                        for (File f : files) {
                            sb.append(f.isDirectory() ? "[D] " : "[F] ")
                                    .append(f.getName())
                                    .append("\n");
                        }
                        return sb.toString();
                    } catch (Exception e) {
                        return "列出目录失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册Shell命令工具
     */
    private void registerShellTools() {
        tools.put("execute_command", new Tool(
                "execute_command",
                "在当前项目目录中执行短时 Shell 命令（默认 60 秒超时，不允许全盘扫描）",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> executeCommand(args.get("command"))
        ));
    }

    /**
     * 注册代码相关工具
     */
    private void registerCodeTools() {
        tools.put("create_project", new Tool(
                "create_project",
                "创建新项目结构",
                createParameters(
                        new Param("name", "string", "项目名称", true),
                        new Param("type", "string", "项目类型 (java/python/node)", true)
                ),
                args -> {
                    String name = args.get("name");
                    String type = args.get("type");
                    try {
                        Path projectPath = Paths.get(name);
                        Files.createDirectories(projectPath);

                        switch (type.toLowerCase()) {
                            case "java" -> {
                                Files.createDirectories(projectPath.resolve("src/main/java"));
                                Files.createDirectories(projectPath.resolve("src/main/resources"));
                                Files.writeString(projectPath.resolve("pom.xml"),
                                        String.format("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                "<project>\n" +
                                                "    <modelVersion>4.0.0</modelVersion>\n" +
                                                "    <groupId>com.example</groupId>\n" +
                                                "    <artifactId>%s</artifactId>\n" +
                                                "    <version>1.0</version>\n" +
                                                "</project>", name));
                            }
                            case "python" -> {
                                Files.createDirectories(projectPath.resolve(name));
                                Files.writeString(projectPath.resolve("main.py"), "# 主程序入口\n");
                                Files.writeString(projectPath.resolve("requirements.txt"), "# 依赖列表\n");
                            }
                            case "node" -> {
                                Files.writeString(projectPath.resolve("package.json"),
                                        String.format("{\"name\": \"%s\", \"version\": \"1.0.0\"}", name));
                            }
                        }
                        return "项目已创建: " + name + " (类型: " + type + ")";
                    } catch (Exception e) {
                        return "创建项目失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册代码检索工具
     */
    private void registerRagTools() {
        tools.put("search_code", new Tool(
                "search_code",
                "语义检索代码库，根据自然语言描述查找相关代码块",
                createParameters(
                        new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                        new Param("top_k", "integer", "返回结果数量（默认是 5）", false)
                ),
                args -> {
                    String query = args.get("query");
                    int topK = 5;
                    try {
                        if (args.containsKey("top_k")) {
                            topK = Integer.parseInt(args.get("top_k"));
                        }
                    } catch (NumberFormatException ignored) {
                    }

                    try (CodeRetriever retriever = new CodeRetriever(projectPath)) {
                        var stats = retriever.getStats();
                        if (stats.chunkCount() == 0) {
                            return "代码库尚未索引，请先使用 /index 命令索引当前项目。";
                        }

                        List<VectorStore.SearchResult> results = retriever.hybridSearch(query, topK);
                        if (results.isEmpty()) {
                            return "未找到与查询相关的代码。";
                        }

                        return SearchResultFormatter.formatForTool(query, results);
                    } catch (Exception e) {
                        return "代码检索失败: " + e.getMessage();
                    }
                }
        ));
    }

    /**
     * 注册联网工具：web_search（多 provider 抽象）+ web_fetch（HTTP + readability）
     */
    private void registerWebTools() {
        tools.put("web_search", new Tool(
                "web_search",
                "搜索互联网，获取实时信息（最新版本、官方文档、技术资讯等）",
                createParameters(
                        new Param("query", "string", "搜索关键词，例如'Java 21 新特性'、'Spring Boot 3.3 release notes'", true),
                        new Param("top_k", "integer", "返回结果数量（默认5）", false)
                ),
                args -> webSearch(args.get("query"), parseInt(args.get("top_k"), 5))
        ));

        tools.put("web_fetch", new Tool(
                "web_fetch",
                "抓取指定 URL，提取正文转 Markdown。" +
                        "适用静态 / SSR 页面（博客、文档、官网）；JS 渲染或防爬站会返回空正文，本期不重试。",
                createParameters(
                        new Param("url", "string", "完整 URL，需 http 或 https 协议", true),
                        new Param("max_chars", "integer", "返回 Markdown 最大字符数（默认 8000，超出截断）", false)
                ),
                args -> webFetch(args.get("url"), parseInt(args.get("max_chars"), DEFAULT_FETCH_MAX_CHARS))
        ));
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private synchronized SearchProvider searchProvider() {
        if (searchProvider == null) {
            searchProvider = SearchProviderFactory.create();
        }
        return searchProvider;
    }

    private synchronized WebFetcher webFetcher() {
        if (webFetcher == null) {
            webFetcher = new WebFetcher();
        }
        return webFetcher;
    }

    private synchronized HtmlExtractor htmlExtractor() {
        if (htmlExtractor == null) {
            htmlExtractor = new HtmlExtractor();
        }
        return htmlExtractor;
    }

    private synchronized NetworkPolicy networkPolicy() {
        if (networkPolicy == null) {
            networkPolicy = new NetworkPolicy();
        }
        return networkPolicy;
    }

    String webSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        SearchProvider provider = searchProvider();
        if (!provider.isReady()) {
            return "⚠️ " + provider.unavailableHint();
        }
        try {
            List<SearchResult> results = provider.search(query.trim(), topK);
            return formatSearchResults(provider.name(), query, results);
        } catch (Exception e) {
            return "搜索失败 (" + provider.name() + "): " + e.getMessage();
        }
    }

    private String formatSearchResults(String providerName, String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "🔍 [" + providerName + "] " + query + "\n\n未找到相关结果。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 [").append(providerName).append("] ").append(query).append("\n\n");
        for (SearchResult r : results) {
            sb.append(r.position()).append(". ").append(r.title()).append("\n");
            if (!r.snippet().isBlank()) {
                String snippet = r.snippet();
                if (snippet.length() > 200) {
                    snippet = snippet.substring(0, 200) + "...";
                }
                sb.append("   ").append(snippet).append("\n");
            }
            if (!r.url().isBlank()) {
                sb.append("   🔗 ").append(r.url());
                if (!r.source().isBlank()) {
                    sb.append("  (").append(r.source()).append(")");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    String webFetch(String url, int maxChars) {
        if (url == null || url.isBlank()) {
            return "URL 不能为空";
        }
        NetworkPolicy policy = networkPolicy();
        String denyReason = policy.checkUrl(url);
        if (denyReason != null) {
            return "❌ 网络访问被拒绝: " + denyReason;
        }
        String rateReason = policy.acquire();
        if (rateReason != null) {
            return "❌ " + rateReason;
        }

        try {
            WebFetcher.RawResponse raw = webFetcher().fetch(url.trim());
            HtmlExtractor.Extracted extracted = htmlExtractor().extract(raw.body(), raw.url());
            String markdown = extracted.markdown();
            int originalLength = markdown.length();
            boolean truncated = false;
            if (maxChars > 0 && markdown.length() > maxChars) {
                markdown = markdown.substring(0, maxChars);
                truncated = true;
            }
            FetchResult result = FetchResult.ok(raw.url(), extracted.title(), markdown, originalLength, truncated);
            return formatFetchResult(result);
        } catch (Exception e) {
            return "抓取失败: " + e.getMessage();
        }
    }

    private String formatFetchResult(FetchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌐 抓取: ").append(result.url()).append("\n");
        if (!result.title().isBlank()) {
            sb.append("📄 标题: ").append(result.title()).append("\n");
        }
        if (result.bodyEmpty()) {
            sb.append("\n⚠️ ").append(result.hint()).append("\n");
            return sb.toString();
        }
        sb.append("📏 正文 ").append(result.contentLength()).append(" 字符");
        if (result.truncated()) {
            sb.append("（已截断）");
        }
        sb.append("\n\n---\n\n");
        sb.append(result.markdown());
        return sb.toString();
    }

    /**
     * 创建工具参数的 JSON Schema 定义
     * <p>
     * 把可变数量的 {@link Param} 转换成符合 JSON Schema 规范的对象，作为工具的参数描述。
     * 生成的 Schema 最终会通过 {@link #getToolDefinitions()} 传给 LLM，让模型知道
     * "调用这个工具需要哪些参数、什么类型、是否必填"，从而生成符合格式的 arguments JSON。
     *
     * <h3>生成的 JSON Schema 结构：</h3>
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "<paramName>": {
     *       "type": "<paramType>",
     *       "description": "<paramDesc>"
     *     }
     *   },
     *   "required": ["<必填参数名1>", "..."]
     * }
     * }</pre>
     *
     * <h3>执行流程：</h3>
     * <ol>
     *   <li>创建顶层结构：{@code type=object}、空 {@code properties} 对象、空 {@code required} 数组</li>
     *   <li>遍历每个 Param，往 {@code properties} 下添加一项（type + description）</li>
     *   <li>若 Param.required=true，同时把参数名加入 {@code required} 数组</li>
     * </ol>
     *
     * @param params 零个或多个参数定义（参数名 / 类型 / 描述 / 是否必填）
     * @return JSON Schema 对象，存入 Tool.parameters 字段
     */
    private JsonNode createParameters(Param... params) {
        // 步骤1：创建顶层结构 {"type":"object","properties":{},"required":[]}
        ObjectNode parameters = mapper.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        // 步骤2：遍历每个 Param，填充 properties 并按需追加 required
        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }

        return parameters;
    }

    /**
     * 获取所有工具定义（用于LLM）
     *
     * 这个方法把内部 Tool（含 executor）转换成 LlmClient.Tool（不含 executor），
     * 用于塞进 LlmClient.chat() 的 tools 参数
     */
    public List<LlmClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.paicli.llm.LlmClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 执行工具调用
     * <p>
     * Agent 拿到 LLM 返回的工具调用后，通过本方法在本地真正执行工具。
     * 入参 {@code name} 和 {@code argumentsJson} 分别对应
     * {@code LlmClient.ToolCall.function().name()} 和
     * {@code LlmClient.ToolCall.function().arguments()}。
     *
     * <h3>执行步骤：</h3>
     * <ol>
     *   <li>查找工具：从 {@link #tools} Map 中按 name 取出 Tool，找不到返回 "未知工具"</li>
     *   <li>解析参数 JSON：用 Jackson 把 argumentsJson 字符串解析为 JsonNode</li>
     *   <li>转 Map：遍历 JSON 字段，每个值用 {@code asText()} 转字符串，存入 Map<String,String></li>
     *   <li>执行工具：调用 {@code tool.executor().execute(argMap)} 触发注册时的 lambda args 实现</li>
     *   <li>异常兜底：解析或执行失败返回 "工具执行失败: ..."</li>
     * </ol>
     *
     * <h3>参数传递限制：</h3>
     * 所有参数值都被 {@code asText()} 强转为字符串：
     * <ul>
     *   <li>字符串 / 数字 / 布尔值参数可正常工作（数字 42 → "42"）</li>
     *   <li>嵌套对象或数组参数会丢失结构（asText() 对对象/数组返回空字符串）</li>
     *   <li>当前 5 个工具的参数均为字符串类型，暂不受影响；扩展工具时需注意</li>
     * </ul>
     *
     * <h3>端到端调用示例：</h3>
     * <pre>{@code
     * // LLM 返回的工具调用
     * ToolCall tc = resp.toolCalls().get(0);
     * // tc.function().name()      → "write_file"
     * // tc.function().arguments() → "{\"path\":\"/tmp/a.txt\",\"content\":\"hi\"}"
     *
     * String result = registry.executeTool(tc.function().name(), tc.function().arguments());
     * // result → "文件已写入: /tmp/a.txt"
     *
     * // 包成 Message.tool(tc.id(), result) 追加到 messages，发起下一轮 chat()
     * }</pre>
     *
     * @param name          工具名，来自 LlmClient.ToolCall.function().name()
     * @param argumentsJson 参数 JSON 字符串，来自 LlmClient.ToolCall.function().arguments()
     * @return 工具执行结果字符串，会被原样回传给 LLM
     */
    public String executeTool(String name, String argumentsJson) {
        // 步骤1：查找工具
        Tool tool = tools.get(name);
        if (tool == null) {
            return "未知工具: " + name;
        }

        try {
            // 步骤2：解析 argumentsJson 为 JsonNode
            JsonNode args = mapper.readTree(argumentsJson);
            // 步骤3：转 Map<String,String>（注意 asText() 的限制：对象/数组会丢失结构）
            Map<String, String> argMap = new HashMap<>();
            args.fields().forEachRemaining(entry ->
                    argMap.put(entry.getKey(), entry.getValue().asText()));
            // 步骤4：调用工具的 executor（注册时传入的 lambda），返回执行结果
            return tool.executor().execute(argMap);
        } catch (Exception e) {
            // 步骤5：解析或执行失败时的兜底返回
            return "工具执行失败: " + e.getMessage();
        }
    }

    /**
     * 并行执行同一轮 LLM 返回的多个工具调用。
     *
     * 结果按传入顺序返回，调用方可以安全地按原 tool_call 顺序回灌消息历史。
     * 如果某个工具超过批次超时仍未返回，会取消任务并返回超时结果；已完成工具不受影响。
     */
    public List<ToolExecutionResult> executeTools(List<ToolInvocation> invocations) {
        if (invocations == null || invocations.isEmpty()) {
            return List.of();
        }
        if (invocations.size() == 1) {
            ToolInvocation invocation = invocations.get(0);
            long startedAt = System.nanoTime();
            String result = executeTool(invocation.name(), invocation.argumentsJson());
            return List.of(ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt)));
        }

        int parallelism = Math.min(invocations.size(), MAX_PARALLEL_TOOLS);
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, r -> {
            Thread thread = new Thread(r, "paicli-tool-executor");
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Callable<ToolExecutionResult>> tasks = invocations.stream()
                    .<Callable<ToolExecutionResult>>map(invocation -> () -> {
                        long startedAt = System.nanoTime();
                        String result = executeTool(invocation.name(), invocation.argumentsJson());
                        return ToolExecutionResult.completed(invocation, result, elapsedMillis(startedAt));
                    })
                    .toList();

            List<Future<ToolExecutionResult>> futures =
                    executor.invokeAll(tasks, toolBatchTimeoutSeconds, TimeUnit.SECONDS);

            List<ToolExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                ToolInvocation invocation = invocations.get(i);
                Future<ToolExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    results.add(ToolExecutionResult.timedOut(invocation, toolBatchTimeoutSeconds));
                    continue;
                }

                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(ToolExecutionResult.failed(invocation, "工具执行被中断"));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误"
                            : cause.getMessage();
                    results.add(ToolExecutionResult.failed(invocation, message));
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return invocations.stream()
                    .map(invocation -> ToolExecutionResult.failed(invocation, "工具批次执行被中断"))
                    .toList();
        } finally {
            executor.shutdownNow();
        }
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    private String executeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) {
            return "执行命令失败: 命令不能为空";
        }
        if (isDisallowedBroadScan(normalized)) {
            return "拒绝执行命令: 不允许扫描 /、~ 或整个文件系统。请改用项目内相对路径，或优先使用 read_file、list_dir、search_code。";
        }

        ExecutorService outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-command-output");
            thread.setDaemon(true);
            return thread;
        });

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", normalized);
            pb.directory(new File(projectPath));
            pb.redirectErrorStream(true);
            process = pb.start();

            Process runningProcess = process;
            Future<String> outputFuture = outputReaderExecutor.submit(() -> readProcessOutput(runningProcess));

            boolean finished = process.waitFor(commandTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                return "命令执行超时（" + commandTimeoutSeconds + "秒），已强制终止";
            }

            String output = getCommandOutput(outputFuture);
            int exitCode = process.exitValue();
            return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return "执行命令失败: " + e.getMessage();
        } finally {
            outputReaderExecutor.shutdownNow();
        }
    }

    private boolean isDisallowedBroadScan(String command) {
        String normalized = command.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return normalized.contains("find /")
                || normalized.contains("find ~")
                || normalized.contains("find $home");
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                    int remaining = MAX_COMMAND_OUTPUT_CHARS - output.length();
                    if (line.length() > remaining) {
                        output.append(line, 0, remaining);
                    } else {
                        output.append(line);
                    }
                    output.append("\n");
                }
            }
        }
        if (output.length() >= MAX_COMMAND_OUTPUT_CHARS) {
            return output.substring(0, MAX_COMMAND_OUTPUT_CHARS) + "\n...(输出已截断)";
        }
        return output.toString();
    }

    private String getCommandOutput(Future<String> outputFuture) throws Exception {
        try {
            return outputFuture.get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outputFuture.cancel(true);
            return "(命令已结束，但输出读取超时)";
        }
    }

    // 记录定义
    private record Param(String name, String type, String description, boolean required) {}

    /**
     * 每个工具包含四个部分
     * 描述和参数定义会传给 LLM，让 LLM 知道什么时候该用这个工具、需要什么参数。
     * 执行逻辑是实际的 Java 代码，负责完成任务。
     * @param name 工具名称
     * @param description 工具描述
     * @param parameters 工具参数
     * @param executor 工具执行器
     */
    public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {}

    public interface ToolExecutor {
        String execute(Map<String, String> args);
    }

    /** 工具调用请求 —— 来自 LLM 返回的 tool_calls 中的单条 */
    public record ToolInvocation(String id, String name, String argumentsJson) {}

    /** 工具执行结果 —— 包含原始调用信息、执行结果、耗时与超时标记 */
    public record ToolExecutionResult(String id, String name, String argumentsJson,
                                      String result, long elapsedMillis, boolean timedOut) {
        private static ToolExecutionResult completed(ToolInvocation invocation, String result, long elapsedMillis) {
            return new ToolExecutionResult(
                    invocation.id(), invocation.name(), invocation.argumentsJson(), result, elapsedMillis, false);
        }

        private static ToolExecutionResult failed(ToolInvocation invocation, String message) {
            return completed(invocation, "工具执行失败: " + message, 0);
        }

        private static ToolExecutionResult timedOut(ToolInvocation invocation, long timeoutSeconds) {
            return new ToolExecutionResult(
                    invocation.id(),
                    invocation.name(),
                    invocation.argumentsJson(),
                    "工具执行超时（" + timeoutSeconds + "秒），已取消",
                    timeoutSeconds * 1000,
                    true
            );
        }
    }
}