package dev.hycompanion.plugin.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件主配置类
 * 从插件数据目录下的 config.yml 文件加载配置
 *
 * @param connection 连接相关配置（后端URL、API密钥、重连设置）
 * @param gameplay   游戏玩法配置（调试模式、表情、消息前缀等）
 * @param npc        NPC相关配置（缓存目录、启动同步）
 * @param logging    日志配置（日志级别、聊天/动作日志开关）
 */
public record PluginConfig(
        ConnectionConfig connection,
        GameplayConfig gameplay,
        NpcConfig npc,
        LoggingConfig logging) {

    /**
     * 从YAML文件加载配置
     * 使用简单的逐行解析方式，不依赖外部YAML库
     */
    public static PluginConfig load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);

        // 解析连接配置部分
        String url = getValue(lines, "url", "https://api.hycompanion.dev");
        String apiKey = getValue(lines, "api_key", "YOUR_SERVER_API_KEY");
        boolean reconnectEnabled = getBooleanValue(lines, "reconnect_enabled", true);
        int reconnectDelay = getIntValue(lines, "reconnect_delay_ms", 5000);

        // 解析游戏玩法配置部分
        boolean debugMode = getBooleanValue(lines, "debug_mode", false);
        boolean emotesEnabled = getBooleanValue(lines, "emotes_enabled", true);
        String messagePrefix = getValue(lines, "message_prefix", "[NPC] ");
        int greetingRange = getIntValue(lines, "greeting_range", 10);

        // 解析NPC配置部分
        String cacheDirectory = getValue(lines, "cache_directory", "data/npcs");
        boolean syncOnStartup = getBooleanValue(lines, "sync_on_startup", true);

        // 解析日志配置部分
        String logLevel = getValue(lines, "level", "INFO");
        boolean logChat = getBooleanValue(lines, "log_chat", false);
        boolean logActions = getBooleanValue(lines, "log_actions", true);

        return new PluginConfig(
                new ConnectionConfig(url, apiKey, reconnectEnabled, reconnectDelay),
                new GameplayConfig(debugMode, emotesEnabled, messagePrefix, greetingRange),
                new NpcConfig(cacheDirectory, syncOnStartup),
                new LoggingConfig(logLevel, logChat, logActions));
    }

    /**
     * 创建默认配置实例
     */
    public static PluginConfig defaults() {
        return new PluginConfig(
                new ConnectionConfig(
                        "https://api.hycompanion.dev",
                        "YOUR_SERVER_API_KEY",
                        true,
                        5000),
                new GameplayConfig(false, true, "[NPC] ", 10),
                new NpcConfig("data/npcs", true),
                new LoggingConfig("INFO", false, true));
    }

    // ========== YAML 解析辅助方法 ==========

    /**
     * 从YAML行列表中根据键名获取字符串值
     * 使用正则表达式匹配键值对，支持引号包裹的值
     *
     * @param lines        YAML文件的所有行
     * @param key          要查找的键名
     * @param defaultValue 未找到时的默认值
     * @return 匹配到的值，或默认值
     */
    private static String getValue(List<String> lines, String key, String defaultValue) {
        Pattern pattern = Pattern.compile("^\\s*" + key + ":\\s*[\"']?([^\"'#]+)[\"']?", Pattern.CASE_INSENSITIVE);

        for (String line : lines) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
        }
        return defaultValue;
    }

    /**
     * 从YAML行列表中获取布尔值，支持 "true"/"yes" 作为真值
     */
    private static boolean getBooleanValue(List<String> lines, String key, boolean defaultValue) {
        String value = getValue(lines, key, String.valueOf(defaultValue));
        return "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    /**
     * 从YAML行列表中获取整数值，解析失败时返回默认值
     */
    private static int getIntValue(List<String> lines, String key, int defaultValue) {
        String value = getValue(lines, key, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 在配置文件中更新API密钥，同时保留原有的注释和格式
     * 逐行扫描找到 api_key 所在行并替换，保持缩进不变
     */
    public static void updateApiKeyInFile(Path configPath, String newApiKey) throws IOException {
        List<String> lines = Files.readAllLines(configPath);
        List<String> newLines = new java.util.ArrayList<>();

        boolean updated = false;
        for (String line : lines) {
            if (line.trim().startsWith("api_key:")) {
                // 保持原始行的缩进格式
                int indentIndex = line.indexOf("api_key:");
                String indent = line.substring(0, indentIndex);
                newLines.add(indent + "api_key: \"" + newApiKey + "\"");
                updated = true;
            } else {
                newLines.add(line);
            }
        }

        if (updated) {
            Files.write(configPath, newLines);
        } else {
            throw new IOException("Could not find api_key field in config.yml");
        }
    }

    // ========== 嵌套配置记录类 ==========

    /**
     * 连接配置 - 包含后端URL、API密钥、重连开关和重连延迟
     */
    public record ConnectionConfig(
            String url,
            String apiKey,
            boolean reconnectEnabled,
            int reconnectDelayMs) {
    }

    /**
     * 游戏玩法配置 - 包含调试模式、表情开关、消息前缀、问候距离
     */
    public record GameplayConfig(
            boolean debugMode,
            boolean emotesEnabled,
            String messagePrefix,
            int greetingRange) {
    }

    /**
     * NPC配置 - 包含NPC数据缓存目录和启动时是否同步
     */
    public record NpcConfig(
            String cacheDirectory,
            boolean syncOnStartup) {
    }

    /**
     * 日志配置 - 包含日志级别、是否记录聊天和动作日志
     */
    public record LoggingConfig(
            String level,
            boolean logChat,
            boolean logActions) {
    }
}
