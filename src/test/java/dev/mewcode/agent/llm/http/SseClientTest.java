package dev.mewcode.agent.llm.http;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SseClientTest {
    /**
     * 验证多个 SSE 事件会按到达顺序触发回调。
     */
    @Test
    public void consumesDataEventsInOrder() throws Exception {
        String input = ""
                + "event: message\n"
                + "data: first\n"
                + "\n"
                + "data: second\n"
                + "\n";
        List<String> events = new ArrayList<>();

        SseClient.consume(stream(input), events::add);

        assertEquals(2, events.size());
        assertEquals("first", events.get(0));
        assertEquals("second", events.get(1));
    }

    /**
     * 验证同一个 SSE 事件里的多行 data 会按规范用换行拼接。
     */
    @Test
    public void joinsMultiLineDataEvents() throws Exception {
        String input = ""
                + "data: hello\n"
                + "data: world\n"
                + "\n";
        List<String> events = new ArrayList<>();

        SseClient.consume(stream(input), events::add);

        assertEquals(1, events.size());
        assertEquals("hello\nworld", events.get(0));
    }

    /**
     * 将字符串包装为 UTF-8 输入流，模拟 HTTP SSE 响应体。
     */
    private ByteArrayInputStream stream(String input) {
        return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    }
}
