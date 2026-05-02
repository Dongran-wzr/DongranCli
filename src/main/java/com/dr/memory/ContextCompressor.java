package com.dr.memory;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.llm.DSV4Client;

import java.util.ArrayList;
import java.util.List;

public class ContextCompressor {
    private final DSV4Client llmClient;

    public ContextCompressor(DSV4Client llmClient) {
        this.llmClient = llmClient;
    }

    public String compress(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String fallback = fallbackCompress(messages);
        try {
            List<Message> prompt = new ArrayList<>();
            prompt.add(Message.system("""
                    你是上下文压缩器。请将以下对话压缩成可供后续推理使用的摘要。
                    要求：
                    1. 保留用户目标、已完成动作、关键结论、未解决问题。
                    2. 限制在 120-180 字。
                    3. 只输出摘要正文。
                    """));
            prompt.add(Message.user(asPlainText(messages)));
            ChatResponse res = llmClient.chat(prompt, List.of());
            String out = res.content() == null ? "" : res.content().trim();
            if (out.isBlank()) {
                return fallback;
            }
            return out.length() <= 220 ? out : out.substring(0, 220) + "...";
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String fallbackCompress(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 6; i--) {
            Message m = messages.get(i);
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                sb.append(m.getRole()).append(": ").append(trim(m.getContent(), 80)).append("; ");
                count++;
            }
        }
        return sb.toString();
    }

    private String asPlainText(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                sb.append(m.getRole()).append(": ").append(trim(m.getContent(), 220)).append("\n");
            }
        }
        return sb.toString();
    }

    private String trim(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "...";
    }
}
