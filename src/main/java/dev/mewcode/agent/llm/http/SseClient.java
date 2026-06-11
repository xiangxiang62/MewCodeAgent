package dev.mewcode.agent.llm.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 轻量 SSE 读取器，用于按事件消费服务端流式响应。
 */
public final class SseClient {
    private SseClient() {
    }

    /**
     * 消费 SSE 输入流，并把每个事件的 data 内容按顺序回调出去。
     */
    public static void consume(InputStream inputStream, Consumer<String> dataConsumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    flush(data, dataConsumer);
                    continue;
                }
                if (line.startsWith("data:")) {
                    // SSE 允许同一个事件包含多行 data，需要按换行重新拼接。
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(stripLeading(line.substring(5)));
                }
            }
            flush(data, dataConsumer);
        }
    }

    /**
     * 如果当前已经收集到 data，则触发一次回调并清空缓冲区。
     */
    private static void flush(StringBuilder data, Consumer<String> dataConsumer) {
        if (data.length() == 0) {
            return;
        }
        dataConsumer.accept(data.toString());
        data.setLength(0);
    }

    /**
     * 去掉 `data:` 后面的前导空白，兼容不同服务端的格式差异。
     */
    private static String stripLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }
}
