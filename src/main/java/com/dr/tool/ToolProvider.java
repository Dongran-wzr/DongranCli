package com.dr.tool;

import java.util.List;

public interface ToolProvider {
    List<ToolRegistry.RegisteredTool> provideTools();
}
