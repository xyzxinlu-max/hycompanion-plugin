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
 * Central manager for plugin integrations.
 * 
 * This class manages optional plugin dependencies for Hycompanion.
 * It provides a clean interface to detect and interact with other plugins
 * without tightly coupling the code to those plugins.
 * 
 * Supported integrations:
 * - SpeechBubbles: Display floating speech bubbles above NPCs
 * 
 * Usage:
 * <pre>
 * PluginIntegrationManager integrationManager = new PluginIntegrationManager(logger);
 * 
 * // Show a speech bubble
 * integrationManager.getSpeechBubbles().showBubble(npcUuid, playerUuid, "Hello!", 5000);
 * </pre>
 */
public class PluginIntegrationManager {
    
    private final PluginLogger logger;
    private final PluginManager pluginManager;
    
    // Cached integrations
    private final Map<PluginIdentifier, OptionalPluginIntegration> integrations;
    
    // Known plugin identifiers
    public static final PluginIdentifier SPEECH_BUBBLES = new PluginIdentifier("dev.hycompanion", "SpeechBubbles");
    
    /**
     * Create a new plugin integration manager
     * 
     * @param logger The plugin logger
     */
    public PluginIntegrationManager(@Nonnull PluginLogger logger) {
        this.logger = logger;
        this.pluginManager = HytaleServer.get().getPluginManager();
        this.integrations = new HashMap<>();
        
        // Initialize all integrations
        initializeIntegrations();
    }
    
    /**
     * Initialize all plugin integrations
     */
    private void initializeIntegrations() {
        logger.info("Initializing plugin integrations...");
        
        // Initialize Speech Bubbles integration
        SpeechBubbleIntegration speechBubbleIntegration = new SpeechBubbleIntegration(logger, pluginManager);
        integrations.put(SPEECH_BUBBLES, speechBubbleIntegration);
        
        // Log summary
        long availableCount = integrations.values().stream()
            .filter(OptionalPluginIntegration::isAvailable)
            .count();
        
        logger.info("Plugin integrations initialized: " + availableCount + "/" + integrations.size() + " available");
    }
    
    /**
     * Get the Speech Bubbles integration
     * 
     * @return The SpeechBubbleIntegration instance (never null)
     */
    @Nonnull
    public SpeechBubbleIntegration getSpeechBubbles() {
        return (SpeechBubbleIntegration) integrations.get(SPEECH_BUBBLES);
    }
    
    /**
     * Check if a specific plugin is available and enabled
     * 
     * @param pluginId The plugin identifier
     * @return true if the plugin is available and enabled
     */
    public boolean isPluginAvailable(@Nonnull PluginIdentifier pluginId) {
        OptionalPluginIntegration integration = integrations.get(pluginId);
        return integration != null && integration.isAvailable();
    }
    
    /**
     * Get a plugin by its identifier
     * 
     * @param pluginId The plugin identifier
     * @return The plugin base, or null if not found
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
     * Shutdown all integrations
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
     * Base interface for optional plugin integrations
     */
    public interface OptionalPluginIntegration {
        /**
         * Check if the plugin is available
         * 
         * @return true if available and ready to use
         */
        boolean isAvailable();
        
        /**
         * Shutdown the integration
         */
        void shutdown();
    }
}
