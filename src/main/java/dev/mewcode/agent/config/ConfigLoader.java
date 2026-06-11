package dev.mewcode.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责读取 YAML 配置并做启动前校验。
 */
public final class ConfigLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private ConfigLoader() {
    }

    /**
     * 从 YAML 文件加载应用配置，并完成环境变量展开和必填字段校验。
     */
    public static AppConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Config file not found: " + path);
        }

        String yaml = expandEnvironmentVariables(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        AppConfig config = YAML.readValue(yaml, AppConfig.class);
        validate(config, path);
        return config;
    }

    /**
     * 将配置中的 `${ENV_NAME}` 替换为同名环境变量值；未设置时保留占位符。
     */
    private static String expandEnvironmentVariables(String yaml) {
        Matcher matcher = ENV_PATTERN.matcher(yaml);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = System.getenv(name);
            if (value == null) {
                value = matcher.group(0);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    /**
     * 校验应用启动所需的最小配置集合。
     */
    private static void validate(AppConfig config, Path path) {
        if (config == null || config.llm() == null) {
            throw new IllegalArgumentException("Missing llm config in " + path);
        }

        LlmConfig llm = config.llm();
        require("llm.protocol", llm.protocol());
        require("llm.model", llm.model());
        require("llm.base_url", llm.baseUrl());
        require("llm.api_key", llm.apiKey());
    }

    /**
     * 校验单个字符串字段存在且非空。
     */
    private static void require(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing required config field: " + name);
        }
    }
}
