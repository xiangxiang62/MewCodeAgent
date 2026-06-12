package dev.mewcode.agent.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 配置的归一化结果。
 */
public final class McpConfig {
    private final Map<String, ServerConfig> servers;

    /**
     * 创建 MCP 配置对象。
     */
    public McpConfig(Map<String, ServerConfig> servers) {
        this.servers = Collections.unmodifiableMap(new LinkedHashMap<String, ServerConfig>(
                servers == null ? Collections.<String, ServerConfig>emptyMap() : servers));
    }

    /**
     * 返回全部已校验通过的 server 配置。
     */
    public Map<String, ServerConfig> servers() {
        return servers;
    }

    /**
     * 单个 MCP server 的归一化配置。
     */
    public static final class ServerConfig {
        private final String type;
        private final String command;
        private final List<String> args;
        private final Map<String, String> env;
        private final String url;
        private final Map<String, String> headers;

        /**
         * 创建单个 server 配置。
         */
        public ServerConfig(String type, String command, List<String> args, Map<String, String> env,
                String url, Map<String, String> headers) {
            this.type = type;
            this.command = command;
            this.args = args == null ? Collections.<String>emptyList() : Collections.unmodifiableList(args);
            this.env = env == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(env));
            this.url = url;
            this.headers = headers == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
        }

        /**
         * 返回 server 传输类型。
         */
        public String type() {
            return type;
        }

        /**
         * 返回 stdio server 的命令。
         */
        public String command() {
            return command;
        }

        /**
         * 返回 stdio server 的命令参数。
         */
        public List<String> args() {
            return args;
        }

        /**
         * 返回 stdio server 的环境变量。
         */
        public Map<String, String> env() {
            return env;
        }

        /**
         * 返回 HTTP server 的基础地址。
         */
        public String url() {
            return url;
        }

        /**
         * 返回 HTTP server 的请求头。
         */
        public Map<String, String> headers() {
            return headers;
        }
    }
}
