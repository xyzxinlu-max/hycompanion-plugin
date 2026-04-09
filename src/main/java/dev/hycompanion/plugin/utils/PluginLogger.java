package dev.hycompanion.plugin.utils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 插件日志工具类
 *
 * 提供统一的日志格式输出，支持带颜色的控制台输出。
 * 包含四个日志级别：DEBUG、INFO、WARN、ERROR。
 * DEBUG日志仅在调试模式开启时输出。
 */
public class PluginLogger {

    /** 日志前缀，通常为插件名称 */
    private final String prefix;
    /** 调试模式开关，控制DEBUG级别日志是否输出 */
    private boolean debugMode = false;

    /** 时间格式化器，用于日志时间戳 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ANSI颜色码，用于控制台彩色输出
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";       // 红色 - 错误
    private static final String YELLOW = "\u001B[33m";    // 黄色 - 警告
    private static final String GREEN = "\u001B[32m";     // 绿色 - 信息
    private static final String CYAN = "\u001B[36m";      // 青色
    private static final String GRAY = "\u001B[90m";      // 灰色 - 调试

    public PluginLogger(String prefix) {
        this.prefix = "[" + prefix + "]";
    }

    /**
     * 设置调试模式开关
     */
    public void setDebugMode(boolean debugMode) {
        this.debugMode = debugMode;
    }

    /** 输出INFO级别日志 */
    public void info(String message) {
        log(Level.INFO, message);
    }

    /** 输出WARN级别日志 */
    public void warn(String message) {
        log(Level.WARN, message);
    }

    /** 输出ERROR级别日志 */
    public void error(String message) {
        log(Level.ERROR, message);
    }

    /**
     * 输出ERROR级别日志并附带异常信息
     * 调试模式下会打印完整的堆栈跟踪
     */
    public void error(String message, Throwable throwable) {
        log(Level.ERROR, message);
        if (throwable != null) {
            System.err.println(throwable.getMessage());
            if (debugMode) {
                throwable.printStackTrace();
            }
        }
    }

    /**
     * 输出DEBUG级别日志（仅在调试模式开启时有效）
     */
    public void debug(String message) {
        if (debugMode) {
            log(Level.DEBUG, message);
        }
    }

    /**
     * 内部日志方法 - 格式化并输出日志消息
     * 格式：[时间戳] [级别] [前缀] 消息内容
     * ERROR级别输出到stderr，其他输出到stdout
     */
    private void log(Level level, String message) {
        String timestamp = LocalDateTime.now().format(TIME_FORMAT);
        String colorCode = getColorCode(level);
        String levelStr = level.name();

        // TODO: [HYTALE-API] Use Hytale's logger instead of System.out
        // Example: HytaleServer.getLogger().log(level, message);

        String formattedMessage = String.format(
                "%s%s %s%s %s%s%s",
                GRAY, timestamp,
                colorCode, levelStr,
                prefix,
                RESET, " " + message);

        if (level == Level.ERROR) {
            System.err.println(formattedMessage);
        } else {
            System.out.println(formattedMessage);
        }
    }

    /**
     * 根据日志级别返回对应的ANSI颜色码
     */
    private String getColorCode(Level level) {
        return switch (level) {
            case DEBUG -> GRAY;
            case INFO -> GREEN;
            case WARN -> YELLOW;
            case ERROR -> RED;
        };
    }

    /**
     * 日志级别枚举：DEBUG（调试）、INFO（信息）、WARN（警告）、ERROR（错误）
     */
    public enum Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
