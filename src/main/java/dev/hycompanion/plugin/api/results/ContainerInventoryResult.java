package dev.hycompanion.plugin.api.results;

import java.util.List;
import java.util.Map;

/**
 * 容器物品查询的结果对象
 */
public class ContainerInventoryResult {
    /** 查询是否成功 */
    private final boolean success;
    /** 查询相关的消息（成功时可能为 null） */
    private final String message;
    /** 容器中的物品列表，每个物品以键值对形式表示 */
    private final List<Map<String, Object>> items;

    public ContainerInventoryResult(boolean success, String message, List<Map<String, Object>> items) {
        this.success = success;
        this.message = message;
        this.items = items;
    }

    /**
     * 创建一个成功的结果，包含物品列表
     */
    public static ContainerInventoryResult success(List<Map<String, Object>> items) {
        return new ContainerInventoryResult(true, null, items);
    }

    /**
     * 创建一个失败的结果
     */
    public static ContainerInventoryResult failure(String message) {
        return new ContainerInventoryResult(false, message, null);
    }

    /** 获取查询是否成功 */
    public boolean isSuccess() {
        return success;
    }

    /** 获取查询消息 */
    public String getMessage() {
        return message;
    }

    /** 获取容器中的物品列表 */
    public List<Map<String, Object>> getItems() {
        return items;
    }
}
