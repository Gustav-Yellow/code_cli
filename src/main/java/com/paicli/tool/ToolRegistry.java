package com.paicli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.GLMClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public ToolRegistry() {
        // 注册内置工具
        registerFileTools();
        registerShellTools();
        registerCodeTools();
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
                "执行Shell命令",
                createParameters(new Param("command", "string", "要执行的命令", true)),
                args -> {
                    String command = args.get("command");
                    try {
                        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
                        pb.redirectErrorStream(true);
                        Process process = pb.start();

                        StringBuilder output = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream()))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                output.append(line).append("\n");
                            }
                        }

                        int exitCode = process.waitFor();
                        return String.format("命令执行完成 (exit code: %d)\n%s", exitCode, output);
                    } catch (Exception e) {
                        return "执行命令失败: " + e.getMessage();
                    }
                }
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
     * 这个方法把内部 Tool（含 executor）转换成 GLMClient.Tool（不含 executor），
     * 用于塞进 GLMClient.chat() 的 tools 参数
     */
    public List<GLMClient.Tool> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> new com.paicli.llm.GLMClient.Tool(t.name(), t.description(), t.parameters()))
                .toList();
    }

    /**
     * 执行工具调用
     * <p>
     * Agent 拿到 LLM 返回的工具调用后，通过本方法在本地真正执行工具。
     * 入参 {@code name} 和 {@code argumentsJson} 分别对应
     * {@code GLMClient.ToolCall.function().name()} 和
     * {@code GLMClient.ToolCall.function().arguments()}。
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
     * @param name          工具名，来自 GLMClient.ToolCall.function().name()
     * @param argumentsJson 参数 JSON 字符串，来自 GLMClient.ToolCall.function().arguments()
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

    public boolean hasTool(String name) {
        return tools.containsKey(name);
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
}
