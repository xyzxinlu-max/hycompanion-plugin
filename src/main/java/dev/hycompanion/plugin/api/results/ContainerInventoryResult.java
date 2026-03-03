package dev.hycompanion.plugin.api.results;

import java.util.List;
import java.util.Map;

/**
 * Result object for container inventory retrieval
 */
public class ContainerInventoryResult {
    private final boolean success;
    private final String message;
    private final List<Map<String, Object>> items;

    public ContainerInventoryResult(boolean success, String message, List<Map<String, Object>> items) {
        this.success = success;
        this.message = message;
        this.items = items;
    }

    public static ContainerInventoryResult success(List<Map<String, Object>> items) {
        return new ContainerInventoryResult(true, null, items);
    }

    public static ContainerInventoryResult failure(String message) {
        return new ContainerInventoryResult(false, message, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<Map<String, Object>> getItems() {
        return items;
    }
}
