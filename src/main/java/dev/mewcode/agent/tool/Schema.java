package dev.mewcode.agent.tool;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Schema {
    private Schema() {
    }

    static Map<String, Object> object(List<String> required, Object... fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 3) {
            properties.put((String) fields[i], field((String) fields[i + 1], (String) fields[i + 2]));
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    static List<String> required(String... names) {
        return Arrays.asList(names);
    }

    private static Map<String, Object> field(String type, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("description", description);
        return field;
    }
}
