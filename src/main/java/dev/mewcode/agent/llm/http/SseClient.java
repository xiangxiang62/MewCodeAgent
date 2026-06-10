package dev.mewcode.agent.llm.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class SseClient {
    private SseClient() {
    }

    /**
     * 消费 SSE 输入流，将每个事件的 data 内容按顺序回调给调用方。
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
                    // SSE 允许同一个事件出现多行 data，需要用换行拼回完整 data。
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
     * 如果当前事件已经收集到 data，则完成一次回调并清空缓冲。
     */
    private static void flush(StringBuilder data, Consumer<String> dataConsumer) {
        if (data.length() == 0) {
            return;
        }
        dataConsumer.accept(data.toString());
        data.setLength(0);
    }

    /**
     * 去掉 data: 后面的前导空白，兼容 "data: xxx" 和 "data:xxx" 两种写法。
     */
    private static String stripLeading(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

}
