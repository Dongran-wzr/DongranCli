package com.dr.memory;

import com.dr.agent.Message;

import java.util.ArrayList;
import java.util.List;

public class TokenBudgetManager {
    private final int hardBudgetTokens;
    private final int reserveTokens;
    private final int keepRecentMessages;

    public TokenBudgetManager(int hardBudgetTokens, int reserveTokens, int keepRecentMessages) {
        this.hardBudgetTokens = hardBudgetTokens;
        this.reserveTokens = reserveTokens;
        this.keepRecentMessages = keepRecentMessages;
    }

    public List<Message> buildManagedContext(
            List<Message> fullHistory,
            List<MemoryEntry> longTermMemories,
            List<String> shortTermNotes,
            String compressedContext
    ) {
        List<Message> result = new ArrayList<>();
        if (fullHistory.isEmpty()) {
            return result;
        }

        Message system = fullHistory.get(0);
        result.add(system);

        String memoryBlock = memoryBlock(longTermMemories, shortTermNotes, compressedContext);
        if (!memoryBlock.isBlank()) {
            result.add(Message.system(memoryBlock));
        }

        int available = Math.max(0, hardBudgetTokens - reserveTokens - estimateTokens(result));
        List<Message> tail = recentMessages(fullHistory);

        int used = 0;
        List<Message> selected = new ArrayList<>();
        for (int i = tail.size() - 1; i >= 0; i--) {
            Message m = tail.get(i);
            int t = estimateTokens(m.getContent()) + 8;
            if (used + t > available) {
                break;
            }
            selected.add(0, m);
            used += t;
        }
        result.addAll(selected);
        return result;
    }

    public int estimateTokens(List<Message> messages) {
        int sum = 0;
        for (Message m : messages) {
            sum += estimateTokens(m.getContent()) + 10;
        }
        return sum;
    }

    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    private List<Message> recentMessages(List<Message> history) {
        int from = Math.max(1, history.size() - keepRecentMessages);
        return new ArrayList<>(history.subList(from, history.size()));
    }

    private String memoryBlock(List<MemoryEntry> longTermMemories, List<String> shortTermNotes, String compressed) {
        StringBuilder sb = new StringBuilder();
        if (shortTermNotes != null && !shortTermNotes.isEmpty()) {
            sb.append("短期记忆:\n");
            for (String s : shortTermNotes) {
                sb.append("- ").append(s).append("\n");
            }
        }
        if (longTermMemories != null && !longTermMemories.isEmpty()) {
            sb.append("长期记忆检索:\n");
            for (MemoryEntry e : longTermMemories) {
                sb.append("- ").append(e.getContent()).append("\n");
            }
        }
        if (compressed != null && !compressed.isBlank()) {
            sb.append("压缩上下文:\n").append(compressed).append("\n");
        }
        return sb.toString();
    }
}
