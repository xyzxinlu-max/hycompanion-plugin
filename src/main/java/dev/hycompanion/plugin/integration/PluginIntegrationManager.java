package dev.hycompanion.plugin.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import dev.hycompanion.plugin.utils.PluginLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 插件集成管理中心
 *
 * 管理Hycompanion的可选插件依赖，提供统一接口来检测和调用其他插件，
 * 避免代码与这些插件产生紧耦合。
 *
 * 目前支持的集成：
 * - SpeechBubbles：在NPC头顶显示浮动对话气泡
 */
public class PluginIntegrationManager {

    private final PluginLogger logger;
    private final PluginManager pluginManager;

    /** 已缓存的集成实例，按插件标识符索引 */
    private final Map<PluginIdentifier, OptionalPluginIntegration> integrations;

    /** 已知的插件标识符常量 */
    public static final PluginIdentifier SPEECH_BUBBLES = new PluginIdentifier("dev.hycompanion", "SpeechBubbles");

    /**
     * 创建插件集成管理器并初始化所有集成
     */
    public PluginIntegrationManager(@Nonnull PluginLogger logger) {
        this.logger = logger;
        this.pluginManager = HytaleServer.get().getPluginManager();
        this.integrations = new HashMap<>();

        // 初始化所有集成
        initializeIntegrations();
    }

    /**
     * 初始化所有插件集成，检测可用性并记录统计信息
     */
    private void initializeIntegrations() {
        logger.info("Initializing plugin integrations...");

        // 初始化对话气泡插件集成
        SpeechBubbleIntegration speechBubbleIntegration = new SpeechBubbleIntegration(logger, pluginManager);
        integrations.put(SPEECH_BUBBLES, speechBubbleIntegration);

        // 输出可用集成数量的统计摘要
        long availableCount = integrations.values().stream()
            .filter(OptionalPluginIntegration::isAvailable)
            .count();
        
        logger.info("Plugin integrations initialized: " + availableCount + "/" + integrations.size() + " available");
    }
    
    /**
     * 获取对话气泡集成实例（始终非空，即使插件不可用也返回实例）
     */
    @Nonnull
    public SpeechBubbleIntegration getSpeechBubbles() {
        return (SpeechBubbleIntegration) integrations.get(SPEECH_BUBBLES);
    }
    
    /**
     * 检查指定插件是否可用且已启用
     */
    public boolean isPluginAvailable(@Nonnull PluginIdentifier pluginId) {
        OptionalPluginIntegration integration = integrations.get(pluginId);
        return integration != null && integration.isAvailable();
    }
    
    /**
     * 根据标识符获取插件实例，未找到时返回null
     */
    @Nullable
    public PluginBase getPlugin(@Nonnull PluginIdentifier pluginId) {
        try {
            return pluginManager.getPlugin(pluginId);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 关闭所有集成，释放资源
     */
    public void shutdown() {
        logger.debug("Shutting down plugin integrations...");
        
        for (OptionalPluginIntegration integration : integrations.values()) {
            try {
                integration.shutdown();
            } catch (Exception e) {
                logger.debug("Error shutting down integration: " + e.getMessage());
            }
        }
        
        integrations.clear();
    }
    
    /**
     * 可选插件集成的基础接口
     * 所有集成实现都需要提供可用性检查和关闭清理方法
     */
    public interface OptionalPluginIntegration {
        /** 检查插件是否可用且准备就绪 */
        boolean isAvailable();

        /** 关闭集成并清理资源 */
        void shutdown();
    }
}
