package dev.hycompanion.plugin.api.results;

/**
 * Result object for standard container actions (store/take item)
 */
public class ContainerActionResult {
    private final boolean success;
    private final String message;

    public ContainerActionResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static ContainerActionResult success() {
        return new ContainerActionResult(true, null);
    }

    public static ContainerActionResult failure(String message) {
        return new ContainerActionResult(false, message);
    }

    public static ContainerActionResult partialSuccess(String message) {
        return new ContainerActionResult(true, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }
}
