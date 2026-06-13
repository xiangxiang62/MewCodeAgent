package dev.mewcode.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * 负责合并记忆索引并在后台异步更新记忆。
 */
public final class Manager {
    private static final Logger LOGGER = Logger.getLogger(Manager.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_INDEX_BYTES = 25 * 1024;

    private final Store projectStore;
    private final Store userStore;
    private final ReentrantLock updateLock = new ReentrantLock();

    private volatile LlmProvider provider;
    private volatile String model;

    /**
     * 创建一个同时管理项目级和用户级记忆的管理器。
     */
    public Manager(Path projectDir, Path userDir, LlmProvider provider, String model) {
        this.projectStore = new Store(projectDir);
        this.userStore = new Store(userDir);
        this.provider = provider;
        this.model = model == null ? "" : model;
    }

    /**
     * 延迟设置 provider，便于启动后复用主对话模型。
     */
    public void setProvider(LlmProvider provider, String model) {
        this.provider = provider;
        this.model = model == null ? "" : model;
    }

    /**
     * 加载并合并项目级与用户级索引文本。
     */
    public String loadIndex() throws IOException {
        String project = projectStore.loadIndex().trim();
        String user = userStore.loadIndex().trim();
        String merged;
        if (project.isEmpty()) {
            merged = user;
        } else if (user.isEmpty()) {
            merged = project;
        } else {
            merged = project + "\n\n" + user;
        }
        return truncateIndex(merged);
    }

    /**
     * 后台提取最近对话中的长期信息，并写入对应 store。
     */
    public void updateAsync(List<ChatMessage> recentMessages) {
        if (provider == null || recentMessages == null || recentMessages.isEmpty()) {
            return;
        }
        Thread.startVirtualThread(() -> {
            updateLock.lock();
            try {
                List<ChatMessage> requestMessages = new ArrayList<ChatMessage>();
                requestMessages.add(new ChatMessage(Role.USER, buildUserPayload(recentMessages)));
                ChatResponse response = provider.streamChat(
                        new LlmRequest(requestMessages, Collections.emptyList(), PromptTemplates.MEMORY_UPDATE_SYSTEM, "",
                                ""),
                        text -> {
                        });
                applyActions(parseActions(response.text()));
            } catch (Exception e) {
                LOGGER.warning("Update memory failed: " + e.getMessage());
            } finally {
                updateLock.unlock();
            }
        });
    }

    /**
     * 解析模型返回的动作数组。
     */
    private List<UpdateAction> parseActions(String text) throws IOException {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return JSON.readValue(text, new TypeReference<List<UpdateAction>>() {
        });
    }

    /**
     * 按 level 将动作分发到项目级或用户级 store。
     */
    private void applyActions(List<UpdateAction> actions) throws IOException {
        List<UpdateAction> projectActions = new ArrayList<UpdateAction>();
        List<UpdateAction> userActions = new ArrayList<UpdateAction>();
        for (UpdateAction action : actions) {
            if (action == null) {
                continue;
            }
            if ("user".equalsIgnoreCase(action.level())) {
                userActions.add(action);
            } else {
                projectActions.add(action);
            }
        }
        projectStore.apply(projectActions);
        userStore.apply(userActions);
    }

    /**
     * 将最近对话和现有索引整理成一段简单文本，供记忆提取模型使用。
     */
    private String buildUserPayload(List<ChatMessage> recentMessages) throws IOException {
        StringBuilder builder = new StringBuilder();
        builder.append("模型: ").append(model == null ? "" : model).append("\n\n");
        String existing = loadIndex();
        if (!existing.isBlank()) {
            builder.append("现有索引:\n").append(existing).append("\n\n");
        }
        builder.append("最近对话:\n");
        for (ChatMessage message : recentMessages) {
            builder.append("- ").append(message.role().wireName()).append(": ")
                    .append(message.content() == null ? "" : message.content()).append("\n");
        }
        return builder.toString();
    }

    /**
     * 将索引截断到最大字节数，并在尾部标出截断提示。
     */
    private String truncateIndex(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_INDEX_BYTES) {
            return text;
        }
        String prefix = new String(bytes, 0, MAX_INDEX_BYTES, StandardCharsets.UTF_8);
        return prefix + "\n(index truncated)";
    }
}
