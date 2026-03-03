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
 * Integration wrapper for the Speech Bubbles plugin.
 * 
 * This class provides a clean interface to interact with the Speech Bubbles plugin
 * without directly importing its classes. It uses reflection to call the plugin's API,
 * making the dependency truly optional.
 * 
 * <p>Handles lazy initialization - if the plugin is not yet enabled when this class
 * is constructed, it will retry detection on first use.</p>
 * 
 * <p><b>Usage:</b></p>
 * <pre>
 * SpeechBubbleIntegration bubbles = integrationManager.getSpeechBubbles();
 * if (bubbles.isAvailable()) {
 *     bubbles.showBubble(npcUuid, playerUuid, "Hello!", 5000);
 * }
 * </pre>
 */
public class SpeechBubbleIntegration implements OptionalPluginIntegration {
    
    private static final PluginIdentifier PLUGIN_ID = new PluginIdentifier("dev.hycompanion.speech", "SpeechBubbles");
    private static final String API_CLASS = "dev.hycompanion.speechbubbles.api.SpeechBubbleAPI";
    
    private final PluginLogger logger;
    private final PluginManager pluginManager;
    
    // Reflection cache
    private Class<?> apiClass;
    private Method showBubbleMethod;
    
    private volatile boolean available = false;
    private String pluginVersion = null;
    private boolean detectionAttempted = false;
    
    /**
     * Create a new Speech Bubble integration
     * 
     * @param logger The plugin logger
     * @param pluginManager The Hytale plugin manager
     */
    public SpeechBubbleIntegration(@Nonnull PluginLogger logger, @Nonnull PluginManager pluginManager) {
        this.logger = logger;
        this.pluginManager = pluginManager;
        
        // Try initial detection, but don't worry if plugin isn't ready yet
        detectPlugin();
    }
    
    /**
     * Detect and initialize the Speech Bubbles plugin.
     * Note: This may be called during plugin startup, so the target plugin
     * might not be fully enabled yet. We'll retry on first use if needed.
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
     * Retry detection if not currently available.
     * This is called automatically by other methods.
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
     * Get the plugin version if available
     * 
     * @return The version string, or null if not available
     */
    @Nullable
    public String getVersion() {
        retryDetectionIfNeeded();
        return pluginVersion;
    }
    
    /**
     * Show a speech bubble above an entity
     * 
     * @param entityUuid The entity UUID to anchor the bubble to
     * @param playerUuid The player UUID who should see the bubble
     * @param text The text to display
     * @param durationMs Duration in milliseconds
     * @return true if the bubble was shown successfully
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
     * Show a speech bubble with default duration (5 seconds)
     * 
     * @param entityUuid The entity UUID
     * @param playerUuid The player UUID
     * @param text The text to display
     * @return true if successful
     */
    public boolean showBubble(@Nonnull UUID entityUuid, @Nonnull UUID playerUuid, @Nonnull String text) {
        return showBubble(entityUuid, playerUuid, text, 5000);
    }
    
    /**
     * Show a speech bubble to all nearby players
     * 
     * @param entityUuid The entity UUID
     * @param text The text to display
     * @param durationMs Duration in milliseconds
     * @return Number of players who received the bubble
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
     * Hide all bubbles for a specific player
     * 
     * @param playerUuid The player UUID
     * @return Number of bubbles hidden
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
     * Hide all bubbles for a specific entity
     * 
     * @param entityUuid The entity UUID
     * @return Number of bubbles hidden
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
     * Truncate text to fit in a speech bubble
     * 
     * @param text The text to truncate
     * @param maxLength Maximum length
     * @return Truncated text
     */
    @Nonnull
    public String truncateText(@Nonnull String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        
        // Try to break at a sentence
        int lastSentence = text.lastIndexOf(".", maxLength);
        if (lastSentence > maxLength * 0.7) {
            return text.substring(0, lastSentence + 1);
        }
        
        // Otherwise break at word boundary
        int lastSpace = text.lastIndexOf(" ", maxLength - 3);
        if (lastSpace > maxLength * 0.5) {
            return text.substring(0, lastSpace) + "...";
        }
        
        // Hard truncate
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
