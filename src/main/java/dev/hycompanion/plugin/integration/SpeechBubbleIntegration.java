package dev.hycompanion.plugin.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import com.hypixel.hytale.server.core.plugin.PluginState;
import dev.hycompanion.plugin.integration.PluginIntegrationManager.OptionalPluginIntegration;
import dev.hycompanion.plugin.utils.PluginLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 对话气泡插件的集成封装类
 *
 * 通过反射调用SpeechBubbles插件的API，无需直接导入其类，
 * 使该依赖变为真正的可选依赖。
 *
 * 支持延迟初始化：如果构造时目标插件尚未启用，会在首次调用时重试检测。
 */
public class SpeechBubbleIntegration implements OptionalPluginIntegration {

    /** 目标插件的标识符 */
    private static final PluginIdentifier PLUGIN_ID = new PluginIdentifier("dev.hycompanion.speech", "SpeechBubbles");
    /** 目标插件API类的全限定名 */
    private static final String API_CLASS = "dev.hycompanion.speechbubbles.api.SpeechBubbleAPI";

    private final PluginLogger logger;
    private final PluginManager pluginManager;

    // 反射方法缓存，避免重复查找
    private Class<?> apiClass;
    private Method showBubbleMethod;

    /** 插件是否可用（线程安全） */
    private volatile boolean available = false;
    /** 检测到的插件版本号 */
    private String pluginVersion = null;
    /** 是否已经尝试过检测 */
    private boolean detectionAttempted = false;
    
    /**
     * 创建对话气泡集成实例
     * 构造时尝试初次检测，如果目标插件未就绪会在后续调用时重试
     */
    public SpeechBubbleIntegration(@Nonnull PluginLogger logger, @Nonnull PluginManager pluginManager) {
        this.logger = logger;
        this.pluginManager = pluginManager;

        // 尝试初始检测，若插件尚未准备好也不影响
        detectPlugin();
    }

    /**
     * 检测并初始化SpeechBubbles插件
     * 通过反射加载API类并缓存方法引用
     * 同步方法，保证线程安全
     */
    private synchronized void detectPlugin() {
        if (detectionAttempted && available) {
            return; // Already successfully detected
        }
        
        detectionAttempted = true;
        
        try {
            PluginBase plugin = pluginManager.getPlugin(PLUGIN_ID);
            
            if (plugin == null) {
                logger.debug("Speech Bubbles plugin not found");
                return;
            }
            
            // Check if plugin is enabled
            if (plugin.getState() != PluginState.ENABLED) {
                logger.debug("Speech Bubbles plugin found but not yet enabled (state: " + 
                    plugin.getState() + ")");
                return;
            }
            
            // Store plugin info
            this.pluginVersion = plugin.getManifest().getVersion().toString();
            
            // Get the plugin's class loader
            ClassLoader pluginClassLoader = plugin.getClass().getClassLoader();
            
            // Load the API class
            this.apiClass = Class.forName(API_CLASS, true, pluginClassLoader);
            
            // Cache methods
            this.showBubbleMethod = apiClass.getMethod("showBubble", 
                UUID.class, UUID.class, String.class, long.class);
            
            // Verify the API is working by checking SpeechBubbleAPI.isAvailable()
            Method isAvailableMethod = apiClass.getMethod("isAvailable");
            Boolean apiAvailable = (Boolean) isAvailableMethod.invoke(null);
            
            if (apiAvailable != null && apiAvailable) {
                this.available = true;
                logger.info("Speech Bubbles plugin integrated (version " + pluginVersion + ")");
            } else {
                logger.warn("Speech Bubbles plugin API reports not available");
            }
            
        } catch (ClassNotFoundException e) {
            logger.debug("Speech Bubbles API class not found - plugin may be an older version");
        } catch (Exception e) {
            logger.debug("Speech Bubbles plugin detection failed: " + e.getMessage());
        }
    }
    
    /**
     * 如果当前不可用则重试检测
     * 其他公开方法会自动调用此方法
     */
    private void retryDetectionIfNeeded() {
        if (!available) {
            detectPlugin();
        }
    }
    
    @Override
    public boolean isAvailable() {
        retryDetectionIfNeeded();
        return available;
    }
    
    /**
     * 获取插件版本号，不可用时返回null
     */
    @Nullable
    public String getVersion() {
        retryDetectionIfNeeded();
        return pluginVersion;
    }
    
    /**
     * 在实体头顶显示对话气泡
     *
     * @param entityUuid 气泡锚定的实体UUID
     * @param playerUuid 能看到气泡的玩家UUID
     * @param text       要显示的文本
     * @param durationMs 显示持续时间（毫秒）
     * @return 是否成功显示
     */
    public boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, 
                              @Nonnull String text, long durationMs) {
        retryDetectionIfNeeded();
        
        if (!available || showBubbleMethod == null) {
            return false;
        }
        
        try {
            return (Boolean) showBubbleMethod.invoke(null, entityUuid, playerUuid, text, durationMs);
        } catch (Exception e) {
            logger.debug("Failed to show speech bubble: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 显示对话气泡（默认持续5秒）
     */
    public boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text) {
        return showBubble(entityUuid, playerUuid, text, 5000);
    }
    
    /**
     * 向所有附近玩家显示对话气泡
     *
     * @return 收到气泡的玩家数量
     */
    public int showBubbleToAll(@Nonnull UUID entityUuid, @Nonnull String text, long durationMs) {
        retryDetectionIfNeeded();
        
        if (!available || apiClass == null) {
            return 0;
        }
        
        try {
            // Use the duration-based method via reflection
            Method showToAllMethod = apiClass.getMethod("showBubbleToAll",
                UUID.class, String.class, long.class);
            
            return (Integer) showToAllMethod.invoke(null, entityUuid, text, durationMs);
        } catch (Exception e) {
            logger.debug("Failed to show speech bubble to all: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 隐藏指定玩家能看到的所有气泡
     *
     * @return 被隐藏的气泡数量
     */
    public int hideAllBubblesForPlayer(@Nonnull UUID playerUuid) {
        retryDetectionIfNeeded();
        
        if (!available || apiClass == null) {
            return 0;
        }
        
        try {
            Method hideMethod = apiClass.getMethod("hideAllBubblesForPlayer", UUID.class);
            return (Integer) hideMethod.invoke(null, playerUuid);
        } catch (Exception e) {
            logger.debug("Failed to hide bubbles for player: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 隐藏指定实体上的所有气泡
     *
     * @return 被隐藏的气泡数量
     */
    public int hideAllBubblesForEntity(@Nonnull UUID entityUuid) {
        retryDetectionIfNeeded();
        
        if (!available || apiClass == null) {
            return 0;
        }
        
        try {
            Method hideMethod = apiClass.getMethod("hideAllBubblesForEntity", UUID.class);
            return (Integer) hideMethod.invoke(null, entityUuid);
        } catch (Exception e) {
            logger.debug("Failed to hide bubbles for entity: " + e.getMessage());
            return 0;
        }
    }
    
    /**
     * 截断文本以适配对话气泡的显示限制
     * 优先在句号处截断，其次在词边界截断，最后硬截断并添加省略号
     *
     * @param text      原始文本
     * @param maxLength 最大字符长度
     * @return 截断后的文本
     */
    @Nonnull
    public String truncateText(@Nonnull String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }

        // 优先尝试在句号处截断
        int lastSentence = text.lastIndexOf(".", maxLength);
        if (lastSentence > maxLength * 0.7) {
            return text.substring(0, lastSentence + 1);
        }

        // 其次在单词边界处截断
        int lastSpace = text.lastIndexOf(" ", maxLength - 3);
        if (lastSpace > maxLength * 0.5) {
            return text.substring(0, lastSpace) + "...";
        }

        // 最后硬截断
        return text.substring(0, maxLength - 3) + "...";
    }
    
    @Override
    public void shutdown() {
        // Nothing to clean up for this integration
        this.available = false;
        this.apiClass = null;
        this.showBubbleMethod = null;
    }
}
