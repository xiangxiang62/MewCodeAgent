package dev.mewcode.agent.ui;

import org.junit.Assert;
import org.junit.Test;

/**
 * 验证审批阶段的按键解码逻辑，确保 Windows 和 ANSI 方向键都能被识别。
 */
public class ChatConsoleTest {
    /**
     * ANSI 上箭头应当被识别为向上选择。
     */
    @Test
    public void decodeApprovalActionSupportsAnsiUp() throws Exception {
        String action = ChatConsole.decodeApprovalAction(27, reader('[', 'A'));
        Assert.assertEquals("UP", action);
    }

    /**
     * ANSI 下箭头应当被识别为向下选择。
     */
    @Test
    public void decodeApprovalActionSupportsAnsiDown() throws Exception {
        String action = ChatConsole.decodeApprovalAction(27, reader('[', 'B'));
        Assert.assertEquals("DOWN", action);
    }

    /**
     * Windows 扩展上箭头应当被识别为向上选择。
     */
    @Test
    public void decodeApprovalActionSupportsWindowsUp() throws Exception {
        String action = ChatConsole.decodeApprovalAction(224, reader(72));
        Assert.assertEquals("UP", action);
    }

    /**
     * Windows 扩展下箭头应当被识别为向下选择。
     */
    @Test
    public void decodeApprovalActionSupportsWindowsDown() throws Exception {
        String action = ChatConsole.decodeApprovalAction(224, reader(80));
        Assert.assertEquals("DOWN", action);
    }

    /**
     * 回车应当提交当前选项。
     */
    @Test
    public void decodeApprovalActionSupportsSubmit() throws Exception {
        String action = ChatConsole.decodeApprovalAction('\r', reader());
        Assert.assertEquals("SUBMIT", action);
    }

    /**
     * 单独的 Esc 应当视为取消。
     */
    @Test
    public void decodeApprovalActionSupportsCancel() throws Exception {
        String action = ChatConsole.decodeApprovalAction(27, reader(-1));
        Assert.assertEquals("CANCEL", action);
    }

    private ChatConsole.ApprovalByteReader reader(final int... values) {
        return new ChatConsole.ApprovalByteReader() {
            private int index;

            @Override
            public int read(long timeoutMillis) {
                if (index >= values.length) {
                    return -1;
                }
                return values[index++];
            }
        };
    }
}
