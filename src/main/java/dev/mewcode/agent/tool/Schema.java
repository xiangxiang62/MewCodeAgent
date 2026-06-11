package dev.mewcode.agent.tool;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成简单 JSON Schema 的小工具，减少重复样板代码。
 */
final class Schema {
    private Schema() {
    }

    /**
     * 创建 object 类型 schema，并按三元组参数填充字段定义。
     */
    static Map<String, Object> object(List<String> required, Object... fields) {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 3) {
            properties.put((String) fields[i], field((String) fields[i + 1], (String) fields[i + 2]));
        }

        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    /**
     * 创建必填字段列表。
     */
    static List<String> required(String... names) {
        return Arrays.asList(names);
    }

    /**
     * 创建单个字段的类型和描述定义。
     */
    private static Map<String, Object> field(String type, String description) {
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("type", type);
        field.put("description", description);
        return field;
    }
}
