package dev.mewcode.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.mewcode.agent.mcp.McpConfig.ServerConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责加载和校验 MCP 配置。
 */
public final class ConfigLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private ConfigLoader() {
    }

    /**
     * 读取两层 MCP 配置并合并为运行时配置。
     */
    public static McpConfig loadConfig(Path root) {
        Path normalizedRoot = root == null ? Paths.get(".").toAbsolutePath().normalize() : root.toAbsolutePath().normalize();
        Map<String, RawServer> user = loadFile(Paths.get(System.getProperty("user.home"), ".mewcode", "config.yaml"));
        Map<String, RawServer> project = loadFile(normalizedRoot.resolve(".mewcode.yaml"));

        applyExpansion(user);
        applyExpansion(project);

        Map<String, RawServer> merged = mergeServers(user, project);
        Map<String, ServerConfig> validServers = new LinkedHashMap<String, ServerConfig>();
        for (Map.Entry<String, RawServer> entry : merged.entrySet()) {
            Optional<ServerConfig> validated = validateServer(entry.getKey(), entry.getValue());
            if (validated.isPresent()) {
                validServers.put(entry.getKey(), validated.get());
            }
        }
        return new McpConfig(validServers);
    }

    /**
     * 加载单个 YAML 文件中的 mcp_servers 段。
     */
    static Map<String, RawServer> loadFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return Collections.emptyMap();
        }
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            JsonNode root = YAML.readTree(reader);
            if (root == null) {
                return Collections.emptyMap();
            }
            JsonNode serversNode = root.get("mcp_servers");
            if (serversNode == null || !serversNode.isObject()) {
                return Collections.emptyMap();
            }
            Map<String, RawServer> servers = new LinkedHashMap<String, RawServer>();
            Iterator<Map.Entry<String, JsonNode>> iterator = serversNode.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                servers.put(entry.getKey(), toRawServer(entry.getValue()));
            }
            return servers;
        } catch (Exception e) {
            System.err.println("[mcp] warn: skip config file " + path + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static RawServer toRawServer(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new RawServer(null, null, Collections.<String>emptyList(), Collections.<String, String>emptyMap(),
                    null, Collections.<String, String>emptyMap());
        }
        return new RawServer(
                text(node.get("type")),
                text(node.get("command")),
                strings(node.get("args")),
                stringMap(node.get("env")),
                text(node.get("url")),
                stringMap(node.get("headers")));
    }

    /**
     * 展开字符串中的 ${VAR} 引用。
     */
    static Expansion expandVars(String value) {
        if (value == null) {
            return new Expansion(null, Collections.<String>emptyList());
        }
        Matcher matcher = ENV_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        List<String> undefined = new ArrayList<String>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getenv(name);
            if (replacement == null) {
                replacement = "";
                undefined.add(name);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return new Expansion(buffer.toString(), undefined);
    }

    private static void applyExpansion(Map<String, RawServer> servers) {
        for (Map.Entry<String, RawServer> entry : servers.entrySet()) {
            servers.put(entry.getKey(), applyExpansion(entry.getKey(), entry.getValue()));
        }
    }

    static RawServer applyExpansion(String name, RawServer server) {
        if (server == null) {
            return null;
        }
        Set<String> warned = new LinkedHashSet<String>();
        Map<String, String> env = expandMap(name, server.env(), warned);
        Map<String, String> headers = expandMap(name, server.headers(), warned);
        return new RawServer(server.type(), server.command(), server.args(), env, server.url(), headers);
    }

    private static Map<String, String> expandMap(String serverName, Map<String, String> source, Set<String> warned) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            Expansion expansion = expandVars(entry.getValue());
            result.put(entry.getKey(), expansion.out());
            for (String undefined : expansion.undefined()) {
                if (warned.add(undefined)) {
                    System.err.println("[mcp] warn: undefined env var ${" + undefined + "} referenced by server "
                            + serverName);
                }
            }
        }
        return result;
    }

    static Map<String, RawServer> mergeServers(Map<String, RawServer> user, Map<String, RawServer> project) {
        Map<String, RawServer> merged = new LinkedHashMap<String, RawServer>();
        if (user != null) {
            merged.putAll(user);
        }
        if (project != null) {
            merged.putAll(project);
        }
        return merged;
    }

    static Optional<ServerConfig> validateServer(String name, RawServer server) {
        if (server == null) {
            warnSkip(name, "server config is empty");
            return Optional.empty();
        }
        if (!"stdio".equals(server.type()) && !"http".equals(server.type())) {
            warnSkip(name, "type must be stdio or http");
            return Optional.empty();
        }
        if ("stdio".equals(server.type()) && isBlank(server.command())) {
            warnSkip(name, "stdio server missing command");
            return Optional.empty();
        }
        if ("http".equals(server.type()) && isBlank(server.url())) {
            warnSkip(name, "http server missing url");
            return Optional.empty();
        }
        return Optional.of(new ServerConfig(
                server.type(),
                server.command(),
                server.args(),
                server.env(),
                server.url(),
                server.headers()));
    }

    private static void warnSkip(String name, String reason) {
        System.err.println("[mcp] warn: skip server " + name + ": " + reason);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        Iterator<JsonNode> iterator = node.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = iterator.next();
            if (entry.getValue().isTextual()) {
                values.put(entry.getKey(), entry.getValue().asText());
            }
        }
        return values;
    }

    /**
     * 原始 server 配置，允许字段为空，供校验前阶段使用。
     */
    static final class RawServer {
        private final String type;
        private final String command;
        private final List<String> args;
        private final Map<String, String> env;
        private final String url;
        private final Map<String, String> headers;

        RawServer(String type, String command, List<String> args, Map<String, String> env,
                String url, Map<String, String> headers) {
            this.type = type;
            this.command = command;
            this.args = args == null ? Collections.<String>emptyList() : new ArrayList<String>(args);
            this.env = env == null ? Collections.<String, String>emptyMap() : new LinkedHashMap<String, String>(env);
            this.url = url;
            this.headers = headers == null ? Collections.<String, String>emptyMap()
                    : new LinkedHashMap<String, String>(headers);
        }

        String type() {
            return type;
        }

        String command() {
            return command;
        }

        List<String> args() {
            return new ArrayList<String>(args);
        }

        Map<String, String> env() {
            return new LinkedHashMap<String, String>(env);
        }

        String url() {
            return url;
        }

        Map<String, String> headers() {
            return new LinkedHashMap<String, String>(headers);
        }
    }

    /**
     * 字符串变量展开结果。
     */
    static final class Expansion {
        private final String out;
        private final List<String> undefined;

        Expansion(String out, List<String> undefined) {
            this.out = out;
            this.undefined = undefined == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(undefined));
        }

        String out() {
            return out;
        }

        List<String> undefined() {
            return undefined;
        }
    }
}
