package dev.hycompanion.plugin.api.results;

/**
 * 容器操作（存入/取出物品）的结果对象
 */
public class ContainerActionResult {
    /** 操作是否成功 */
    private final boolean success;
    /** 操作相关的消息（成功时可能为 null） */
    private final String message;

    public ContainerActionResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    /**
     * 创建一个成功的结果
     */
    public static ContainerActionResult success() {
        return new ContainerActionResult(true, null);
    }

    /**
     * 创建一个失败的结果
     */
    public static ContainerActionResult failure(String message) {
        return new ContainerActionResult(false, message);
    }

    /**
     * 创建一个部分成功的结果（操作成功但有附加信息）
     */
    public static ContainerActionResult partialSuccess(String message) {
        return new ContainerActionResult(true, message);
    }

    /** 获取操作是否成功 */
    public boolean isSuccess() {
        return success;
    }

    /** 获取操作消息 */
    public String getMessage() {
        return message;
    }
}
