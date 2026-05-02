package com.dr.memory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ShortTermMemory {
    private final Deque<String> notes = new ArrayDeque<>();
    private final int maxNotes;

    public ShortTermMemory(int maxNotes) {
        this.maxNotes = Math.max(2, maxNotes);
    }

    public void addTurn(String userInput, String assistantOutput) {
        String note = "用户: " + compact(userInput) + " | 助手: " + compact(assistantOutput);
        notes.addLast(note);
        while (notes.size() > maxNotes) {
            notes.removeFirst();
        }
    }

    public List<String> snapshot() {
        return new ArrayList<>(notes);
    }

    public void clear() {
        notes.clear();
    }

    private String compact(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replaceAll("\\s+", " ").trim();
        return v.length() <= 180 ? v : v.substring(0, 180) + "...";
    }
}
