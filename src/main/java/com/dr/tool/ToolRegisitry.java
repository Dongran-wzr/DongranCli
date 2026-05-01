package com.dr.tool;

import java.nio.file.Path;

/**
 * 兼容旧拼写，避免外部引用立即失效。
 * 新代码请使用 {@link ToolRegistry}。
 */
@Deprecated
public class ToolRegisitry extends ToolRegistry {
    public ToolRegisitry(Path workspaceRoot) {
        super(workspaceRoot);
    }
}
