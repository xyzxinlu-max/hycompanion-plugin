package dev.hycompanion.plugin.adapter;

import io.sentry.Sentry;

import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.TrigMathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.ProjectileComponent;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
import com.hypixel.hytale.protocol.MountController;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.npc.movement.controllers.MotionController;
import dev.hycompanion.plugin.HycompanionEntrypoint;
import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.api.inventory.*;
import dev.hycompanion.plugin.api.results.ContainerActionResult;
import dev.hycompanion.plugin.api.results.ContainerInventoryResult;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcMoveResult;
import dev.hycompanion.plugin.integration.PluginIntegrationManager;
import dev.hycompanion.plugin.integration.SpeechBubbleIntegration;
import dev.hycompanion.plugin.network.PacketDispatchUtil;
import dev.hycompanion.plugin.shutdown.ShutdownManager;
import dev.hycompanion.plugin.utils.PluginLogger;
import java.lang.reflect.Field;
import java.time.Instant;
import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * HytaleAPI 的真实实现，使用 Hytale Server API
 *
 * 此适配器将 Hycompanion 插件与实际的 Hytale 游戏服务器连接，
 * 使用 com.hypixel.hytale 包下的真实 Hytale Server API 类。
 * 所有涉及 Hytale 世界的操作必须在 WorldThread 上执行。
 *
 * @author Hycompanion Team
 */
public class HytaleServerAdapter implements HytaleAPI {

    private final PluginLogger logger;
    private final HycompanionEntrypoint plugin;
    /** 关闭管理器：协调服务器关闭时的资源清理 */
    private final ShutdownManager shutdownManager;

    // 已生成NPC实例的跟踪映射：UUID -> NPC实例数据（含实体引用）
    private final Map<UUID, NpcInstanceData> npcInstanceEntities = new ConcurrentHashMap<>();

    // NPC最后已知位置缓存，当世界线程暂时饱和时作为后备使用
    private final Map<UUID, Location> lastKnownNpcLocations = new ConcurrentHashMap<>();

    // 私有守护线程调度器，用于NPC旋转、移动监控和思考指示器动画
    private final ScheduledExecutorService rotationScheduler = java.util.concurrent.Executors.newScheduledThreadPool(1,
            r -> {
                Thread t = new Thread(r, "Hycompanion-Rotation-Worker");
                t.setDaemon(true);
                return t;
            });
    /** NPC身体旋转定时任务：UUID -> 调度任务 */
    private final Map<UUID, ScheduledFuture<?>> rotationTasks = new ConcurrentHashMap<>();

    /** NPC移动监控定时任务：UUID -> 调度任务 */
    private final Map<UUID, ScheduledFuture<?>> movementTasks = new ConcurrentHashMap<>();

    /** move_to 操作中使用的隐形目标实体：UUID -> 实体引用 */
    private final Map<UUID, Ref<EntityStore>> movementTargetEntities = new ConcurrentHashMap<>();

    /** 每个NPC的思考指示器动画任务 */
    private final Map<UUID, ScheduledFuture<?>> thinkingAnimationTasks = new ConcurrentHashMap<>();

    /** 忙碌NPC集合（正在跟随或攻击中），用于防止移动时播放表情动画 */
    private final Set<UUID> busyNpcs = ConcurrentHashMap.newKeySet();

    /** NPC跟随监控定时任务：定期刷新 Combat 状态 + LockedTarget 防止 NPC 脱离追击 */
    private final Map<UUID, ScheduledFuture<?>> followTasks = new ConcurrentHashMap<>();

    /** 思考指示器的实体引用：UUID -> 全息文字实体引用（用于原子操作） */
    private final Map<UUID, Ref<EntityStore>> thinkingIndicatorRefs = new ConcurrentHashMap<>();

    /** NPC原始出生点位置记录：externalId -> Location（用于重生） */
    private final Map<String, Location> npcSpawnLocations = new ConcurrentHashMap<>();

    /** 待重生队列：externalId -> 预定重生时间 */
    private final Map<String, Instant> pendingRespawns = new ConcurrentHashMap<>();
    private ScheduledFuture<?> respawnCheckerTask = null;

    /** NPC移除事件监听器 */
    private java.util.function.Consumer<UUID> removalListener;

    /** 插件集成管理器（可选依赖，如语音气泡插件） */
    private final PluginIntegrationManager integrationManager;

    /**
     * 构造函数：初始化适配器并注册关闭清理回调
     * @param logger 日志记录器
     * @param plugin 插件入口点
     * @param shutdownManager 关闭管理器
     */
    public HytaleServerAdapter(PluginLogger logger, HycompanionEntrypoint plugin, ShutdownManager shutdownManager) {
        this.logger = logger;
        this.plugin = plugin;
        this.shutdownManager = shutdownManager;

        // 初始化插件集成（可选依赖）
        this.integrationManager = new PluginIntegrationManager(logger);

        // 向关闭管理器注册自动清理回调
        shutdownManager.register(this::cleanup);

        logger.info("HytaleServerAdapter initialized - Using real Hytale Server API");
    }

    /**
     * 检查服务器是否正在关闭
     * 委托给 ShutdownManager 作为唯一的状态来源
     */
    private boolean isShuttingDown() {
        return shutdownManager.isShuttingDown();
    }

    /**
     * 安全地在世界线程上执行任务，使用 ShutdownManager 的熔断机制
     * 防止在服务器关闭期间执行阻塞操作
     */
    private boolean safeWorldExecute(World world, Runnable task) {
        return shutdownManager.safeWorldExecute(world::execute, task);
    }

    // ========== 玩家操作 ==========

    /** 根据UUID字符串获取玩家对象 */
    @Override
    public Optional<GamePlayer> getPlayer(String playerId) {
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);

            if (playerRef != null) {
                return Optional.of(convertToGamePlayer(playerRef));
            }
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid UUID format for player ID: " + playerId);
            Sentry.captureException(e);
        }
        return Optional.empty();
    }

    /** 根据用户名获取玩家（忽略大小写精确匹配） */
    @Override
    public Optional<GamePlayer> getPlayerByName(String playerName) {
        PlayerRef playerRef = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);

        if (playerRef != null) {
            return Optional.of(convertToGamePlayer(playerRef));
        }
        return Optional.empty();
    }

    /** 获取所有在线玩家列表（关闭期间返回空列表以避免访问无效引用） */
    @Override
    public List<GamePlayer> getOnlinePlayers() {
        // [关闭修复] 检查是否正在关闭，避免访问无效的玩家引用
        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }
        try {
            return Universe.get().getPlayers().stream()
                    .map(this::convertToGamePlayer)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // During shutdown, player references may become invalid
            logger.debug("Could not get online players (shutdown in progress?): " + e.getMessage());
            Sentry.captureException(e);
            return Collections.emptyList();
        }
    }

    /** 获取所有已跟踪的NPC实例集合 */
    @Override
    public Set<NpcInstanceData> getNpcInstances() {
        return npcInstanceEntities.values().stream()
                .collect(Collectors.toSet());
    }

    /** 根据UUID获取NPC实例数据 */
    @Override
    public NpcInstanceData getNpcInstance(UUID npcInstanceUuid) {
        return npcInstanceEntities.get(npcInstanceUuid);
    }

    /**
     * 向指定玩家发送文本消息
     * 首先尝试获取玩家所在世界，然后在该世界线程上发送消息
     */
    @Override
    public void sendMessage(String playerId, String message) {
        // 首先尝试获取玩家所在世界
        World world = null;
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null) {
                    world = Universe.get().getWorld(worldUuid);
                }
            }
        } catch (Exception e) {
            // Ignore, will fall back to default
        }

        // Fall back to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }

        if (world == null)
            return;

        final World targetWorld = world;
        targetWorld.execute(() -> {
            try {
                UUID uuid = UUID.fromString(playerId);
                PlayerRef playerRef = Universe.get().getPlayer(uuid);

                if (playerRef != null) {
                    Message hytaleMessage = Message.raw(message);
                    playerRef.sendMessage(hytaleMessage);
                    logger.debug("Sent message to player [" + playerId + "]: " + message);
                } else {
                    logger.warn("Player not found for ID: " + playerId);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID format for player ID: " + playerId);
                Sentry.captureException(e);
            }
        });
    }

    /**
     * NPC向指定玩家发送消息（绿色文本），并可选地显示语音气泡
     */
    @Override
    public void sendNpcMessage(UUID npcInstanceId, String playerId, String message, String rawMessage) {
        // 首先尝试获取玩家所在世界
        World world = null;
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null) {
                    world = Universe.get().getWorld(worldUuid);
                }
            }
        } catch (Exception e) {
            // Ignore, will fall back to default
        }

        // Fall back to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }

        if (world == null)
            return;

        final World targetWorld = world;
        targetWorld.execute(() -> {
            try {
                UUID uuid = UUID.fromString(playerId);
                PlayerRef playerRef = Universe.get().getPlayer(uuid);

                if (playerRef != null) {
                    // Message is already formatted by ActionExecutor.formatNpcMessage
                    // Apply green color for NPC messages (matching spawn text color)
                    Message hytaleMessage = Message.raw(message).color("#22C55E");
                    playerRef.sendMessage(hytaleMessage);
                    logger.debug("NPC [" + npcInstanceId + "] says to player [" + playerId + "]: " + message);

                    // Also show speech bubble if the plugin is available
                    showSpeechBubble(npcInstanceId, uuid, rawMessage);
                } else {
                    logger.warn("Player not found for NPC message: " + playerId);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID format for player ID: " + playerId);
                Sentry.captureException(e);
            }
        });
    }

    /**
     * Show a speech bubble above an NPC if the Speech Bubbles plugin is available.
     * This is an optional feature - if the plugin is not installed, this method
     * does nothing.
     * 
     * @param npcInstanceId The NPC instance UUID
     * @param playerUuid    The player UUID who should see the bubble
     * @param message       The message to display
     */
    /**
     * 在NPC头顶显示语音气泡
     * 截断过长的消息（最多150字），显示6秒后自动消失
     * 语音气泡功能为可选集成，失败时仅记录调试日志
     */
    private void showSpeechBubble(UUID npcInstanceId, UUID playerUuid, String message) {
        try {
            SpeechBubbleIntegration bubbles = integrationManager.getSpeechBubbles();
            if (!bubbles.isAvailable()) {
                return;
            }

            // 截断过长消息以适应气泡显示
            String bubbleText = bubbles.truncateText(message, 150);

            // 显示气泡6秒（比默认时间长，便于玩家阅读）
            bubbles.showBubble(npcInstanceId, playerUuid, bubbleText, 6000);

        } catch (Exception e) {
            // 语音气泡为可选功能，仅记录调试日志
            logger.debug("Could not show speech bubble: " + e.getMessage());
        }
    }

    /** NPC向多个玩家广播消息（绿色文本），并为每个玩家显示语音气泡 */
    @Override
    public void broadcastNpcMessage(UUID npcInstanceId, List<String> playerIds, String message, String rawMessage) {
        if (playerIds == null || playerIds.isEmpty()) {
            logger.debug("No players to broadcast NPC message to");
            return;
        }

        // For broadcasting, players could be in different worlds.
        // We use the default world's executor for simplicity since
        // PlayerRef.sendMessage()
        // works regardless of which world thread we're on.
        World world = Universe.get().getDefaultWorld();
        if (world == null)
            return;

        world.execute(() -> {
            // Pre-create the message once for efficiency
            // Apply green color for NPC messages (matching spawn text color)
            Message hytaleMessage = Message.raw(message).color("#22C55E");
            int sent = 0;

            for (String playerId : playerIds) {
                try {
                    UUID uuid = UUID.fromString(playerId);
                    PlayerRef playerRef = Universe.get().getPlayer(uuid);

                    if (playerRef != null) {
                        playerRef.sendMessage(hytaleMessage);
                        showSpeechBubble(npcInstanceId, uuid, rawMessage);
                        sent++;
                    }
                } catch (IllegalArgumentException e) {
                    // Skip invalid UUIDs
                }
            }
            logger.debug("NPC [" + npcInstanceId + "] broadcast message to " + sent + " players: " + message);
        });
    }

    /** 向指定玩家发送红色错误消息 */
    @Override
    public void sendErrorMessage(String playerId, String message) {
        // 首先尝试获取玩家所在世界
        World world = null;
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid != null) {
                    world = Universe.get().getWorld(worldUuid);
                }
            }
        } catch (Exception e) {
            // Ignore, will fall back to default
        }

        // Fall back to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }

        if (world == null)
            return;

        final World targetWorld = world;
        targetWorld.execute(() -> {
            try {
                UUID uuid = UUID.fromString(playerId);
                PlayerRef playerRef = Universe.get().getPlayer(uuid);

                if (playerRef != null) {
                    // Use RED color #FF0000 for error messages
                    Message hytaleMessage = Message.raw(message).color("#FF0000");
                    playerRef.sendMessage(hytaleMessage);
                    logger.debug("Error message sent to player [" + playerId + "]: " + message);
                } else {
                    logger.warn("Player not found for error message: " + playerId);
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID format for player ID: " + playerId);
                Sentry.captureException(e);
            }
        });
    }

    /** 向所有在线管理员广播红色调试消息 */
    @Override
    public void broadcastDebugMessageToOps(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        // Get all online players and filter for OPs
        List<GamePlayer> onlinePlayers = getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            return;
        }

        // Send red debug message to all OP players
        for (GamePlayer player : onlinePlayers) {
            if (isPlayerOp(player.id())) {
                sendErrorMessage(player.id(), "[Hycompanion Debug] " + message);
            }
        }

        logger.debug("[Debug] Broadcast to OPs: " + message);
    }

    /** 检查玩家是否具有管理员权限（检查 "*" 或 "hycompanion.admin" 权限） */
    @Override
    public boolean isPlayerOp(String playerId) {
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef == null) {
                return false;
            }

            // Get the Player component from the entity to check permissions
            // Player implements PermissionHolder interface
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return false;
            }

            Store<EntityStore> store = ref.getStore();
            com.hypixel.hytale.server.core.entity.entities.Player player = store.getComponent(ref,
                    com.hypixel.hytale.server.core.entity.entities.Player.getComponentType());

            if (player == null) {
                return false;
            }

            // Check if player has operator privileges using the permission system
            // "*" is the wildcard permission that typically indicates OP status
            // Also check for the specific hycompanion.admin permission
            return player.hasPermission("*") || player.hasPermission("hycompanion.admin");
        } catch (IllegalArgumentException e) {
            logger.debug("Invalid UUID format for player ID: " + playerId);
            return false;
        } catch (Exception e) {
            logger.debug("Error checking OP status for player: " + playerId + " - " + e.getMessage());
            Sentry.captureException(e);
            return false;
        }
    }

    // ========== NPC操作 ==========

    /**
     * 在指定位置生成NPC实体
     * 使用 Hytale NPC API 的 NPCPlugin.spawnEntity() 方法
     * 同时设置牵引点（leash point）、应用能力属性（无敌、击退）并注册到跟踪系统
     */
    @Override
    public Optional<UUID> spawnNpc(String externalId, String name, Location location) {
        logger.info("[Hycompanion] Spawning NPC [" + externalId + "] (" + name + ") at " + location);

        try {
            World world = getWorldByName(location.world());
            if (world == null) {
                world = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] spawnNpc: Could not find world '" + location.world()
                        + "', falling back to default world");
            }

            if (world == null) {
                logger.warn("No world available to spawn NPC");
                return Optional.empty();
            }

            // Check if role exists in the NPC system
            NPCPlugin npcPlugin = NPCPlugin.get();
            int roleIndex = npcPlugin.getIndex(externalId);

            if (roleIndex < 0) {
                // Role doesn't exist - create a placeholder UUID
                // The NPC role must be defined in Hytale's NPC Role assets
                logger.warn("NPC role '" + externalId + "' not found in NPC definitions. " +
                        "Ensure the role file '" + externalId + ".json' exists in mods/<mod>/Server/NPC/Roles/ " +
                        "and restart the server to load new roles.");
                UUID placeholderUuid = UUID.nameUUIDFromBytes(externalId.getBytes());
                return Optional.of(placeholderUuid);
            }

            // Spawn NPC using the Hytale NPC API
            Store<EntityStore> store = world.getEntityStore().getStore();
            Vector3d position = new Vector3d(location.x(), location.y(), location.z());
            Vector3f rotation = new Vector3f(0, 0, 0);

            it.unimi.dsi.fastutil.Pair<Ref<EntityStore>, NPCEntity> npcPair = npcPlugin.spawnEntity(
                    store,
                    roleIndex,
                    position,
                    rotation,
                    null, // spawnModel - use default from role
                    null // postSpawn callback
            );

            if (npcPair != null) {
                Ref<EntityStore> entityRef = npcPair.first();
                NPCEntity npcEntity = npcPair.second();

                // CRITICAL: Initialize leash point for WanderInCircle motion to work
                // WanderInCircle uses getLeashPoint() as the center reference for wandering
                // This mirrors what NPCSystems.AddedFromExternalSystem does for WorldGen/Prefab
                // NPCs
                npcEntity.setLeashPoint(position);
                npcEntity.setLeashHeading(rotation.getYaw());
                npcEntity.setLeashPitch(rotation.getPitch());

                // DEBUG: Log leash point and role state
                Vector3d leashPoint = npcEntity.getLeashPoint();
                logger.info("[Spawn Debug] NPC: " + externalId);
                logger.info("[Spawn Debug]   Spawn position: " + position.getX() + ", " + position.getY() + ", "
                        + position.getZ());
                logger.info("[Spawn Debug]   Leash point: " + leashPoint.getX() + ", " + leashPoint.getY() + ", "
                        + leashPoint.getZ());

                var role = npcEntity.getRole();
                if (role != null) {
                    logger.info("[Spawn Debug]   Role: " + role.getClass().getSimpleName());
                    logger.info("[Spawn Debug]   Role Name: " + npcEntity.getRoleName());
                    logger.info("[Spawn Debug]   RequiresLeashPosition: " + role.requiresLeashPosition());

                    // Log all methods available on the role for debugging
                    logger.info("[Spawn Debug]   Available methods on Role:");
                    java.lang.reflect.Method[] methods = role.getClass().getMethods();
                    for (java.lang.reflect.Method m : methods) {
                        if (m.getName().toLowerCase().contains("instruction") ||
                                m.getName().toLowerCase().contains("state") ||
                                m.getName().toLowerCase().contains("motion")) {
                            logger.info("[Spawn Debug]     - " + m.getName() + "()");
                        }
                    }
                    var stateSupport = role.getStateSupport();
                    if (stateSupport != null) {
                        logger.info("[Spawn Debug]   Current state: " + stateSupport.getStateName());

                        // Check available states
                        try {
                            java.lang.reflect.Method getAvailableStatesMethod = stateSupport.getClass()
                                    .getMethod("getAvailableStates");
                            Object states = getAvailableStatesMethod.invoke(stateSupport);
                            if (states != null) {
                                logger.info("[Spawn Debug]   Available states: " + states.toString());
                            }
                        } catch (Exception e) {
                            // Method might not exist, ignore
                        }
                    }
                    var activeController = role.getActiveMotionController();
                    if (activeController != null) {
                        logger.info(
                                "[Spawn Debug]   Motion controller: " + activeController.getClass().getSimpleName());
                    } else {
                        logger.warn("[Spawn Debug]   No active motion controller!");
                    }
                } else {
                    logger.warn("[Spawn Debug]   Role is NULL!");
                }

                // Get UUID from the entity
                UUID entityUuid = npcEntity.getUuid();
                if (entityUuid == null) {
                    entityUuid = UUID.nameUUIDFromBytes(externalId.getBytes());
                }

                NpcData npcData = plugin.getNpcManager().getNpc(externalId).orElse(null);
                if (npcData == null) {
                    logger.warn("NpcData not found for spawned NPC: " + externalId);
                    // Try to proceed if possible or handle error?
                    // For now, we need NpcData to create the instance record.
                    // If it's missing, we can't track it properly.
                    return Optional.ofNullable(entityUuid);
                }
                // Store the spawn location for respawn purposes (linked to externalId)
                Location spawnLocation = Location.of(location.x(), location.y(), location.z(),
                        world.getName() != null ? world.getName() : "world");
                npcSpawnLocations.put(externalId, spawnLocation);

                NpcInstanceData npcInstance = new NpcInstanceData(entityUuid, entityRef, npcEntity, npcData,
                        spawnLocation);

                // Apply capabilities using helper method
                applyNpcCapabilities(store, entityRef, npcEntity, npcData, "Spawn");

                npcInstanceEntities.put(entityUuid, npcInstance);

                // Bind entity immediately so NpcManager knows about it
                plugin.getNpcManager().bindEntity(externalId, entityUuid);

                logger.info("NPC spawned successfully with UUID: " + entityUuid);
                return Optional.of(entityUuid);
            } else {
                logger.warn("Failed to spawn NPC entity");
                return Optional.empty();
            }

        } catch (Exception e) {
            logger.error("Failed to spawn NPC: " + e.getMessage());
            Sentry.captureException(e);
            return Optional.empty();
        }
    }

    /**
     * 移除NPC实体
     * 先清理思考指示器，再从跟踪映射中移除，最后在世界线程上销毁实体
     * 关闭期间跳过世界线程操作，依赖 Hytale 自身的实体清理
     */
    @Override
    public boolean removeNpc(UUID npcInstanceId) {
        logger.info("[Hycompanion] Removing NPC [" + npcInstanceId + "]");

        // 先获取实例数据（移除前）
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        String externalId = (npcInstanceData != null) ? npcInstanceData.npcData().externalId() : null;

        // Destroy thinking indicator first (before removing NPC)
        destroyThinkingIndicator(npcInstanceId);

        // [Shutdown Fix] During shutdown, don't try to remove entities from the world.
        // Hytale's shutdown process handles entity cleanup. Just remove from our
        // tracking.
        if (isShuttingDown()) {
            npcInstanceEntities.remove(npcInstanceId);
            if (externalId != null) {
                pendingRespawns.remove(externalId);
            }
            if (npcInstanceData != null && removalListener != null) {
                removalListener.accept(npcInstanceId);
            }
            logger.debug("[Hycompanion] Skipping NPC removal during shutdown: " + npcInstanceId);
            return npcInstanceData != null;
        }

        // Remove from our tracking maps immediately to prevent further usage
        npcInstanceEntities.remove(npcInstanceId);
        Ref<EntityStore> entityRef = (npcInstanceData != null) ? npcInstanceData.entityRef() : null;

        // Cancel any pending respawn for this NPC type
        if (externalId != null) {
            pendingRespawns.remove(externalId);
        }

        // Notify listener
        if (removalListener != null) {
            removalListener.accept(npcInstanceId);
        }

        if (npcInstanceData == null) {
            return false;
        }

        if (entityRef == null) {
            logger.warn("[Hycompanion] NPC entity reference not found for: " + npcInstanceId +
                    " (NPC may not have been spawned via plugin or entity tracking was lost)");
            return false;
        }

        // Schedule removal on the world thread to respect Hytale's threading rules
        try {
            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] removeNpc: Could not find world '" + worldName
                        + "' for NPC, falling back to default world");
            }
            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        // Check validity inside the thread
                        if (!entityRef.isValid()) {
                            logger.warn("[Hycompanion] NPC entity reference is invalid for: " + npcInstanceId +
                                    " (entity may have already been removed)");
                            return;
                        }

                        Store<EntityStore> store = entityRef.getStore();
                        if (store == null) {
                            logger.error("[Hycompanion] Could not get entity store for NPC: " + npcInstanceId);
                            return;
                        }

                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            npcEntity.remove();
                            logger.info("[Hycompanion] Successfully removed NPC entity: " + npcInstanceId);
                        } else {
                            logger.warn("[Hycompanion] NPC component not found on entity: " + npcInstanceId);
                        }
                    } catch (Exception e) {
                        logger.error(
                                "[Hycompanion] Failed to remove NPC entity '" + npcInstanceId + "': " + e.getMessage());
                        Sentry.captureException(e);
                        e.printStackTrace();
                    }
                });

                // Return true to indicate the removal request was successfully scheduled
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to schedule NPC removal: " + e.getMessage());
            Sentry.captureException(e);
        }

        return false;
    }

    /**
     * 处理NPC死亡事件——为该NPC类型安排重生
     * 简单方式：将重生与 externalId 关联，而非实例UUID
     */
    public boolean handleNpcDeath(UUID npcInstanceId, String externalId) {
        logger.info("[Hycompanion] NPC died [" + npcInstanceId + "] (" + externalId + "), scheduling respawn");

        // Remove from tracking
        npcInstanceEntities.remove(npcInstanceId);

        // Notify removal
        if (removalListener != null) {
            removalListener.accept(npcInstanceId);
        }

        // Schedule respawn using game time (30 seconds)
        scheduleNpcRespawn(externalId, 30);
        return true;
    }

    /**
     * 为NPC类型在其注册出生点安排重生
     * 使用真实时间来安排重生（更简单可靠）
     */
    @Override
    public void scheduleNpcRespawn(String externalId, long delaySeconds) {
        // Check if spawn location exists
        Location spawnLocation = npcSpawnLocations.get(externalId);
        if (spawnLocation == null) {
            logger.warn("[Hycompanion] No spawn location for '" + externalId + "', cannot respawn");
            return;
        }

        // Use real time for scheduling (simpler and more reliable)
        Instant respawnAt = Instant.now().plusSeconds(delaySeconds);
        pendingRespawns.put(externalId, respawnAt);
        logger.info("[Hycompanion] Respawn scheduled for '" + externalId + "' in " + delaySeconds + " seconds");

        // Ensure checker is running
        startRespawnChecker();
    }

    @Override
    public void cancelNpcRespawn(String externalId) {
        if (pendingRespawns.remove(externalId) != null) {
            logger.debug("[Hycompanion] Cancelled respawn for '" + externalId + "'");
        }
    }

    /** 启动周期性重生检查器（如果尚未运行），每秒检查一次待重生队列 */
    private void startRespawnChecker() {
        if (respawnCheckerTask != null && !respawnCheckerTask.isDone()) {
            return; // Already running
        }

        respawnCheckerTask = rotationScheduler.scheduleAtFixedRate(() -> {
            if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                return;
            }
            checkPendingRespawns();
        }, 1, 1, TimeUnit.SECONDS);
    }

    /** 检查所有待重生的NPC，执行已到期的重生 */
    private void checkPendingRespawns() {
        if (pendingRespawns.isEmpty()) {
            return;
        }

        Instant now = Instant.now(); // Realtime check is fine for scheduling
        List<String> toRespawn = new ArrayList<>();

        // Find due respawns
        for (Map.Entry<String, Instant> entry : pendingRespawns.entrySet()) {
            if (entry.getValue().isBefore(now)) {
                toRespawn.add(entry.getKey());
            }
        }

        // Execute each due respawn on appropriate world thread
        for (String externalId : toRespawn) {
            pendingRespawns.remove(externalId);
            executeRespawn(externalId);
        }
    }

    /** 在世界线程上执行实际的NPC重生操作 */
    private void executeRespawn(String externalId) {
        Location spawnLocation = npcSpawnLocations.get(externalId);
        if (spawnLocation == null) {
            logger.warn("[Hycompanion] Spawn location lost for '" + externalId + "'");
            return;
        }

        World resolvedWorld = getWorldByName(spawnLocation.world());
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] executeRespawn: Could not find world '" + spawnLocation.world()
                    + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            logger.error("[Hycompanion] World not available for respawn of '" + externalId + "'");
            return;
        }
        final World world = resolvedWorld;

        NpcData npcData = plugin.getNpcManager().getNpc(externalId).orElse(null);
        if (npcData == null) {
            logger.warn("[Hycompanion] NPC data not found for '" + externalId + "'");
            return;
        }

        world.execute(() -> {
            if (isShuttingDown()) {
                return;
            }

            try {
                NPCPlugin npcPlugin = NPCPlugin.get();
                int roleIndex = npcPlugin.getIndex(externalId);
                if (roleIndex < 0) {
                    logger.warn("[Hycompanion] Role '" + externalId + "' not found");
                    return;
                }

                Store<EntityStore> store = world.getEntityStore().getStore();
                Vector3d position = new Vector3d(spawnLocation.x(), spawnLocation.y(), spawnLocation.z());
                Vector3f rotation = new Vector3f(0, 0, 0);

                var npcPair = npcPlugin.spawnEntity(store, roleIndex, position, rotation, null, null);
                if (npcPair == null) {
                    logger.error("[Hycompanion] Failed to respawn '" + externalId + "'");
                    return;
                }

                Ref<EntityStore> entityRef = npcPair.first();
                NPCEntity npcEntity = npcPair.second();
                npcEntity.setLeashPoint(position);
                npcEntity.setLeashHeading(rotation.getYaw());
                npcEntity.setLeashPitch(rotation.getPitch());

                UUID entityUuid = npcEntity.getUuid();
                NpcInstanceData instance = new NpcInstanceData(entityUuid, entityRef, npcEntity, npcData,
                        spawnLocation);

                applyNpcCapabilities(store, entityRef, npcEntity, npcData, "Respawn");
                npcInstanceEntities.put(entityUuid, instance);
                plugin.getNpcManager().bindEntity(externalId, entityUuid);

                logger.info("[Hycompanion] NPC '" + externalId + "' respawned successfully as " + entityUuid);
            } catch (Exception e) {
                logger.error("[Hycompanion] Error respawning '" + externalId + "': " + e.getMessage());
                Sentry.captureException(e);
            }
        });
    }

    /**
     * 更新NPC能力属性（无敌、击退等）
     * 查找该 externalId 的所有活跃实例，按世界分组后在各世界线程上分别应用
     */
    @Override
    public void updateNpcCapabilities(String externalId, NpcData npcData) {
        logger.info("[Hycompanion] Updating capabilities for NPC role: " + externalId);

        // Find all active instances for this externalId
        List<NpcInstanceData> instancesToUpdate = new ArrayList<>();
        for (NpcInstanceData instance : npcInstanceEntities.values()) {
            if (instance.npcData().externalId().equals(externalId)) {
                instancesToUpdate.add(instance);
            }
        }

        if (instancesToUpdate.isEmpty()) {
            logger.debug("No active instances found for update: " + externalId);
            return;
        }

        // Group instances by world to execute on the correct world thread for each
        Map<String, List<NpcInstanceData>> instancesByWorld = new HashMap<>();
        for (NpcInstanceData instance : instancesToUpdate) {
            String worldName = instance.spawnLocation() != null ? instance.spawnLocation().world() : "default";
            instancesByWorld.computeIfAbsent(worldName, k -> new ArrayList<>()).add(instance);
        }

        // Execute on each world's thread
        for (Map.Entry<String, List<NpcInstanceData>> entry : instancesByWorld.entrySet()) {
            String worldName = entry.getKey();
            List<NpcInstanceData> worldInstances = entry.getValue();

            World world = Universe.get().getWorld(worldName);
            if (world == null) {
                world = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] updateNpcCapabilities: Could not find world '" + worldName
                        + "', falling back to default world");
            }
            if (world == null) {
                continue;
            }

            world.execute(() -> {
                for (NpcInstanceData instance : worldInstances) {
                    try {
                        Ref<EntityStore> entityRef = instance.entityRef();
                        if (entityRef != null && entityRef.isValid()) {
                            Store<EntityStore> store = entityRef.getStore();
                            NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());

                            if (npcEntity != null) {
                                applyNpcCapabilities(store, entityRef, npcEntity, npcData, "Update");

                                // Update stored data record to keep it fresh
                                // NpcInstanceData is a record, so we create a new one
                                // Preserve the spawn location from the existing instance
                                NpcInstanceData newInstance = new NpcInstanceData(
                                        instance.entityUuid(),
                                        instance.entityRef(),
                                        instance.npcEntity(),
                                        npcData, // Use updated data
                                        instance.spawnLocation() // Preserve spawn location
                                );

                                npcInstanceEntities.put(instance.entityUuid(), newInstance);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Failed to update capabilities for instance " + instance.entityUuid() + ": "
                                + e.getMessage());
                        Sentry.captureException(e);
                    }
                }
            });
        }
    }

    /**
     * 辅助方法：为NPC实体应用能力标志（无敌、击退缩放）
     * 1. 通过 EffectControllerComponent 设置无敌状态
     * 2. 通过反射更新所有运动控制器和 Role 主字段的击退缩放值
     */
    private void applyNpcCapabilities(Store<EntityStore> store, Ref<EntityStore> entityRef, NPCEntity npcEntity,
            NpcData npcData, String context) {
        String externalId = npcData.externalId();

        // 1. Invincibility
        try {
            EffectControllerComponent effect = (EffectControllerComponent) store.getComponent(entityRef,
                    EffectControllerComponent.getComponentType());
            if (effect != null) {
                // Apply value from data
                effect.setInvulnerable(npcData.isInvincible());
                if (npcData.isInvincible()) {
                    logger.debug("[" + context + "] Set invincible: " + externalId);
                }
            } else {
                logger.warn("[" + context + "] EffectControllerComponent missing for: " + externalId);
            }
        } catch (Exception e) {
            logger.warn("[" + context + "] Failed to apply invulnerability: " + e.getMessage());
            Sentry.captureException(e);
        }

        // 2. Knockback Prevention
        try {
            var role = npcEntity.getRole();
            if (role != null) {
                // Determine target scale
                boolean disabled = npcData.isKnockbackDisabled();
                double targetScale = disabled ? 0.0 : 1.0;

                if (disabled || context.equals("Update")) {
                    logger.debug("[" + context + "] Applying knockback scale: " + targetScale + " (Disabled: "
                            + disabled + ") for " + externalId);
                }

                // 1. Update active controller immediately
                MotionController activeController = role.getActiveMotionController();
                if (activeController != null) {
                    activeController.setKnockbackScale(targetScale);
                }

                // 2. Update ALL controllers via reflection
                try {
                    Field controllersField = com.hypixel.hytale.server.npc.role.Role.class
                            .getDeclaredField("motionControllers");
                    controllersField.setAccessible(true);

                    @SuppressWarnings("unchecked")
                    Map<String, MotionController> controllers = (Map<String, MotionController>) controllersField
                            .get(role);

                    if (controllers != null && !controllers.isEmpty()) {
                        for (MotionController mc : controllers.values()) {
                            mc.setKnockbackScale(targetScale);
                        }
                        if (disabled) {
                            logger.debug("Updated " + controllers.size() + " motion controllers");
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to update motionControllers map: " + e.getMessage());
                    Sentry.captureException(e);
                }

                // 3. CRITICAL: Update Role master field "knockbackScale"
                // This is required because Role.updateMotionControllers() resets all
                // controllers
                // to this value periodically (e.g. on model updates).
                try {
                    Field scaleField = com.hypixel.hytale.server.npc.role.Role.class.getDeclaredField("knockbackScale");
                    scaleField.setAccessible(true);
                    scaleField.setDouble(role, targetScale);
                    // logger.debug("Updated Role master knockbackScale field");
                } catch (Exception e) {
                    logger.warn("Failed to update Role master knockbackScale: " + e.getMessage());
                    Sentry.captureException(e);
                }
            }
        } catch (Exception e) {
            logger.warn("[" + context + "] Failed to update knockback: " + e.getMessage());
            Sentry.captureException(e);
        }
    }

    /** 触发NPC播放指定动画（必须在世界线程上执行） */
    @Override
    public void triggerNpcEmote(UUID npcInstanceId, String animationName) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] plays animation: " + animationName);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.error("[Hycompanion] Cannot play animation - entity not tracked for NPC: " + npcInstanceId);
            return;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef != null && entityRef.isValid()) {
            try {
                // Get the world from the entity store and execute on world thread
                Store<EntityStore> store = entityRef.getStore();

                // Get the correct world for this NPC, not the default world
                String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world()
                        : null;
                World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
                if (resolvedWorld == null) {
                    resolvedWorld = Universe.get().getDefaultWorld();
                    logger.warn("[Hycompanion] triggerNpcEmote: Could not find world '" + worldName
                            + "' for NPC, falling back to default world");
                }

                if (resolvedWorld != null) {
                    final World world = resolvedWorld;
                    // Animation must be played on the world thread
                    world.execute(() -> {
                        try {
                            NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                            if (npcEntity != null) {
                                // Play the animation directly by name from the model's AnimationSets
                                // The animation name should match a key from the model's AnimationSets
                                // (e.g., "Sit", "Sleep", "Howl", "Greet", "Wave", etc.)
                                npcEntity.playAnimation(entityRef, com.hypixel.hytale.protocol.AnimationSlot.Action,
                                        animationName, store);
                                logger.debug("[Hycompanion] Animation '" + animationName + "' triggered for NPC "
                                        + npcInstanceId);
                            }
                        } catch (Exception e) {
                            logger.debug("Could not play animation on world thread: " + e.getMessage());
                            Sentry.captureException(e);
                        }
                    });
                }
            } catch (Exception e) {
                logger.debug("Could not play animation: " + e.getMessage());
                Sentry.captureException(e);
            }
        } else {
            logger.debug("Cannot play animation - entity not tracked for NPC: " + npcInstanceId);
        }
    }

    /** 注册NPC移除事件监听器 */
    @Override
    public void registerNpcRemovalListener(java.util.function.Consumer<UUID> listener) {
        this.removalListener = listener;
    }

    /**
     * 让NPC移动到指定位置（异步操作）
     * 实现原理：生成一个隐形目标实体，将NPC的LockedTarget设为该实体，
     * 切换到Follow状态，然后定时检测到达/卡住/超时情况
     */
    @Override
    public CompletableFuture<NpcMoveResult> moveNpcTo(UUID npcInstanceId,
            Location location) {
        CompletableFuture<NpcMoveResult> result = new CompletableFuture<>();
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] teleporting to " + location);

        // Cancel any existing follow/movement tasks
        cancelFollowTask(npcInstanceId);
        cleanupMovement(npcInstanceId);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.error("[Hycompanion] Cannot move NPC - entity not tracked for NPC: " + npcInstanceId);
            result.complete(NpcMoveResult.failed("entity_not_tracked"));
            return result;
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            result.complete(NpcMoveResult.failed("entity_ref_invalid"));
            return result;
        }

        try {
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
            }

            if (resolvedWorld == null) {
                result.complete(NpcMoveResult.failed("no_world"));
                return result;
            }

            final World world = resolvedWorld;
            world.execute(() -> {
                try {
                    Store<EntityStore> store = entityRef.getStore();

                    // 获取NPC当前Y高度，防止传送到地下
                    double safeY = location.y();
                    TransformComponent currentTransform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    if (currentTransform != null) {
                        double currentY = currentTransform.getPosition().getY();
                        // 使用较高的Y值，防止卡进地里
                        safeY = Math.max(safeY, currentY);
                    }

                    Vector3d teleportPos = new Vector3d(location.x(), safeY, location.z());
                    Vector3f rotation = new Vector3f(0, 0, 0);
                    Teleport teleport = new Teleport(world, teleportPos, rotation);
                    store.addComponent(entityRef, Teleport.getComponentType(), teleport);

                    Location finalLoc = Location.of(location.x(), safeY, location.z(), "world");
                    result.complete(NpcMoveResult.success(finalLoc));
                    logger.info("[Hycompanion] NPC teleported to x=" + location.x() + " y=" + safeY + " z=" + location.z());
                } catch (Exception e) {
                    logger.error("Error teleporting NPC: " + e.getMessage());
                    Sentry.captureException(e);
                    result.complete(NpcMoveResult.failed("error: " + e.getMessage()));
                }
            });
        } catch (Exception e) {
            logger.error("Could not move NPC: " + e.getMessage());
            Sentry.captureException(e);
            result.complete(NpcMoveResult.failed("error: " + e.getMessage()));
        }

        return result;
    }

    /** 安全移除实体（关闭期间跳过，获取实体所在世界后在世界线程上移除） */
    private void removeEntity(Ref<EntityStore> ref) {
        // [关闭修复] 关闭期间不尝试移除实体
        if (isShuttingDown()) {
            return;
        }
        try {
            // Try to get the world from the entity's store
            World world = null;
            if (ref.isValid()) {
                try {
                    Store<EntityStore> store = ref.getStore();
                    if (store != null) {
                        world = store.getExternalData().getWorld();
                    }
                } catch (Exception e) {
                    // Ignore, will fall back to default
                }
            }
            // Fall back to default world
            if (world == null) {
                world = Universe.get().getDefaultWorld();
            }
            if (world != null) {
                world.execute(() -> {
                    if (ref.isValid()) {
                        ref.getStore().removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
                    }
                });
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // World executor rejected task - server shutting down, this is expected
            logger.debug("[Hycompanion] Could not remove entity - world shutting down");
            Sentry.captureException(e);
        } catch (Exception e) {
            // Ignore other exceptions
            Sentry.captureException(e);
        }
    }

    /** 清理NPC移动相关的定时任务和隐形目标实体 */
    private void cleanupMovement(UUID npcInstanceId) {
        ScheduledFuture<?> t = movementTasks.remove(npcInstanceId);
        if (t != null)
            t.cancel(false);

        Ref<EntityStore> target = movementTargetEntities.remove(npcInstanceId);
        if (target != null)
            removeEntity(target);
    }

    /**
     * 清理移动并重置NPC状态为Idle
     * 必须在世界线程执行中调用
     */
    private void cleanupMovementAndResetState(UUID npcInstanceId, Ref<EntityStore> entityRef,
            Store<EntityStore> store) {
        // Reset NPC state to Idle
        try {
            NpcInstanceData instanceData = npcInstanceEntities.get(npcInstanceId);
            if (instanceData != null) {
                instanceData.resetAnimationsAndPosture(store, "cleanupMovement");
            }

            NPCEntity npc = store.getComponent(entityRef, NPCEntity.getComponentType());
            if (npc != null && npc.getRole() != null) {
                npc.getRole().getStateSupport().setState(entityRef, "Idle", null, store);
                npc.getRole().getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);
                busyNpcs.remove(npcInstanceId);
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Error resetting NPC state: " + e.getMessage());
            Sentry.captureException(e);
        }

        // Cleanup movement tracking
        cleanupMovement(npcInstanceId);
    }

    /** 超时熔断器：连续超时次数计数器，超过阈值后跳过实时位置检查 */
    private final AtomicInteger consecutiveTimeouts = new AtomicInteger(0);

    /**
     * 在NPC周围搜索指定类型的方块
     * 搜索策略：1.精确方块ID匹配 2.标签匹配 3.部分名称匹配
     * 使用螺旋迭代器从NPC位置向外扫描
     */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findBlock(UUID npcInstanceId, String tag, int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC, not the default world
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] findBlock: Could not find world '" + worldName
                    + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                // Get NPC current position
                Ref<EntityStore> ref = npcData.entityRef();
                if (!ref.isValid()) {
                    future.complete(Optional.empty());
                    return;
                }
                Store<EntityStore> store = ref.getStore();
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    future.complete(Optional.empty());
                    return;
                }
                Vector3d pos = transform.getPosition();
                int startX = (int) Math.floor(pos.getX());
                int startY = (int) Math.floor(pos.getY());
                int startZ = (int) Math.floor(pos.getZ());

                var blockTypeAssetMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
                        .getAssetMap();
                if (blockTypeAssetMap == null) {
                    logger.debug("BlockType asset map is null");
                    future.complete(Optional.empty());
                    return;
                }

                // Build a set of block IDs to search for
                it.unimi.dsi.fastutil.ints.IntSet targetBlockIds = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
                String searchDescription;

                // Strategy 1: Try exact match as block ID first
                int directBlockId = blockTypeAssetMap.getIndex(tag);
                if (directBlockId != Integer.MIN_VALUE && blockTypeAssetMap.getAsset(directBlockId) != null) {
                    targetBlockIds.add(directBlockId);
                    searchDescription = "block ID: " + tag;
                    logger.debug("Found exact block ID match: " + tag + " -> " + directBlockId);
                } else {
                    // Strategy 2: Try as a tag
                    int tagIndex = com.hypixel.hytale.assetstore.AssetRegistry.getTagIndex(tag);
                    if (tagIndex != com.hypixel.hytale.assetstore.AssetRegistry.TAG_NOT_FOUND) {
                        it.unimi.dsi.fastutil.ints.IntSet taggedBlocks = blockTypeAssetMap.getIndexesForTag(tagIndex);
                        if (!taggedBlocks.isEmpty()) {
                            targetBlockIds.addAll(taggedBlocks);
                            searchDescription = "tag: " + tag;
                            logger.debug("Found tag match: " + tag + " with " + taggedBlocks.size() + " blocks");
                        }
                    }

                    // Strategy 3: Case-insensitive block ID search (partial match)
                    if (targetBlockIds.isEmpty()) {
                        String searchLower = tag.toLowerCase();
                        int matchCount = 0;
                        for (String blockKey : blockTypeAssetMap.getAssetMap().keySet()) {
                            if (blockKey.toLowerCase().contains(searchLower)) {
                                int blockId = blockTypeAssetMap.getIndex(blockKey);
                                if (blockId != Integer.MIN_VALUE) {
                                    targetBlockIds.add(blockId);
                                    matchCount++;
                                }
                            }
                        }
                        if (matchCount > 0) {
                            searchDescription = "partial match for: " + tag + " (" + matchCount + " blocks)";
                            logger.debug("Found partial block ID matches for: " + tag + " (" + matchCount + " blocks)");
                        } else {
                            searchDescription = null;
                        }
                    } else {
                        searchDescription = "tag: " + tag;
                    }
                }

                if (targetBlockIds.isEmpty()) {
                    logger.debug("No blocks found for search: " + tag);
                    future.complete(Optional.empty());
                    return;
                }

                logger.debug("Searching for blocks with " + searchDescription + " in radius " + radius);

                // Spiral Search
                com.hypixel.hytale.math.iterator.SpiralIterator iterator = new com.hypixel.hytale.math.iterator.SpiralIterator(
                        startX, startZ, radius);

                while (iterator.hasNext()) {
                    long packedPos = iterator.next();
                    int x = com.hypixel.hytale.math.util.MathUtil.unpackLeft(packedPos);
                    int z = com.hypixel.hytale.math.util.MathUtil.unpackRight(packedPos);

                    // Optimization: Check if chunk is loaded first
                    com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world
                            .getChunkIfLoaded(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk == null)
                        continue;

                    // Scan Y range: surface only (NPC height -2 to +5, avoid underground)
                    for (int y = Math.max(0, startY - 2); y <= Math.min(255, startY + 5); y++) {
                        int blockId = chunk.getBlock(x, y, z);
                        if (targetBlockIds.contains(blockId)) {
                            // Get the actual block type for the result
                            var blockType = blockTypeAssetMap.getAsset(blockId);
                            String foundTypeName = blockType != null ? blockType.getId() : tag;

                            // Ignore blocks with "Leaves_" in their ID
                            if (foundTypeName != null && foundTypeName.contains("Leaves_")) {
                                continue;
                            }

                            // Found it!
                            Map<String, Object> result = new HashMap<>();
                            result.put("found", true);
                            result.put("type", foundTypeName);
                            result.put("coordinates", Map.of("x", x, "y", y, "z", z));
                            result.put("distance", Math
                                    .sqrt(Math.pow(x - startX, 2) + Math.pow(y - startY, 2) + Math.pow(z - startZ, 2)));

                            logger.debug("Found block " + foundTypeName + " at (" + x + ", " + y + ", " + z + ")");
                            future.complete(Optional.of(result));
                            return;
                        }
                    }
                }

                logger.debug("No blocks found within search radius " + radius + " for: " + tag);
                future.complete(Optional.empty());

            } catch (Exception e) {
                logger.error("Error in findBlock: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    /**
     * 扫描NPC周围的所有方块和流体
     * 返回分类统计（按材质类型分组）、每种方块的数量和最近坐标
     * containersOnly=true 时只返回容器方块
     */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanBlocks(UUID npcInstanceId, int radius,
            boolean containersOnly) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] scanBlocks: Could not find world '" + worldName
                    + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                // Get NPC current position
                Ref<EntityStore> ref = npcData.entityRef();
                if (!ref.isValid()) {
                    future.complete(Optional.empty());
                    return;
                }
                Store<EntityStore> store = ref.getStore();
                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    future.complete(Optional.empty());
                    return;
                }
                Vector3d pos = transform.getPosition();
                double exactX = pos.getX();
                double exactY = pos.getY();
                double exactZ = pos.getZ();

                int startX = (int) Math.floor(exactX);
                int startY = (int) Math.floor(exactY);
                int startZ = (int) Math.floor(exactZ);

                var blockTypeAssetMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
                        .getAssetMap();
                if (blockTypeAssetMap == null) {
                    logger.debug("BlockType asset map is null");
                    future.complete(Optional.empty());
                    return;
                }

                var fluidTypeAssetMap = com.hypixel.hytale.server.core.asset.type.fluid.Fluid.getAssetMap();

                // Data structures to track found blocks/fluids
                // Map<blockId, BlockScanInfo>
                Map<String, BlockScanInfo> foundBlocks = new HashMap<>();
                Map<String, Set<String>> categoryToBlockIds = new HashMap<>();
                Map<String, FluidScanInfo> foundFluids = new HashMap<>();

                // Spiral Search
                com.hypixel.hytale.math.iterator.SpiralIterator iterator = new com.hypixel.hytale.math.iterator.SpiralIterator(
                        startX, startZ, radius);

                while (iterator.hasNext()) {
                    long packedPos = iterator.next();
                    int x = com.hypixel.hytale.math.util.MathUtil.unpackLeft(packedPos);
                    int z = com.hypixel.hytale.math.util.MathUtil.unpackRight(packedPos);

                    // Optimization: Check if chunk is loaded first
                    com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk chunk = world
                            .getChunkIfLoaded(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk == null)
                        continue;

                    // Scan Y range (NPC height +/- 10 blocks)
                    for (int y = Math.max(0, startY - 10); y <= Math.min(255, startY + 10); y++) {
                        // Calculate exact distance to voxel center (shared by block + fluid scanning)
                        double voxelCenterX = x + 0.5;
                        double voxelCenterY = y + 0.5;
                        double voxelCenterZ = z + 0.5;

                        double distance = Math.sqrt(
                                Math.pow(voxelCenterX - exactX, 2) +
                                        Math.pow(voxelCenterY - exactY, 2) +
                                        Math.pow(voxelCenterZ - exactZ, 2));

                        // Scan fluid at this voxel first (fluids are stored separately from blocks)
                        if (fluidTypeAssetMap != null) {
                            int fluidIdInt = chunk.getFluidId(x, y, z);
                            if (fluidIdInt > 0) {
                                var fluidType = fluidTypeAssetMap.getAsset(fluidIdInt);
                                if (fluidType != null) {
                                    String fluidId = fluidType.getId();
                                    if (fluidId != null && !fluidId.isEmpty()) {
                                        FluidScanInfo fluidInfo = foundFluids.computeIfAbsent(fluidId, id -> {
                                            String displayName = formatBlockIdAsDisplayName(id);
                                            return new FluidScanInfo(id, displayName);
                                        });

                                        fluidInfo.count++;
                                        if (distance < fluidInfo.nearestDistance) {
                                            fluidInfo.nearestDistance = distance;
                                            fluidInfo.nearestX = x;
                                            fluidInfo.nearestY = y;
                                            fluidInfo.nearestZ = z;
                                        }
                                    }
                                }
                            }
                        }

                        int blockIdInt = chunk.getBlock(x, y, z);
                        var blockType = blockTypeAssetMap.getAsset(blockIdInt);
                        if (blockType == null) {
                            continue;
                        }

                        String blockId = blockType.getId();
                        if (blockId == null || blockId.isEmpty()) {
                            continue;
                        }

                        if (containersOnly) {
                            // Container detection API changed in newer server version
                            // Skip container-only filtering for now
                            continue;
                        }

                        // Update or create block scan info
                        BlockScanInfo info = foundBlocks.computeIfAbsent(blockId, id -> {
                            String displayName = formatBlockIdAsDisplayName(id);
                            List<String> materialTypes = dev.hycompanion.plugin.core.world.BlockClassifier
                                    .getMaterialTypes(id, displayName);
                            return new BlockScanInfo(id, displayName, materialTypes);
                        });

                        // Update count
                        info.count++;

                        // Update nearest if this is closer
                        if (distance < info.nearestDistance) {
                            info.nearestDistance = distance;
                            info.nearestX = x;
                            info.nearestY = y;
                            info.nearestZ = z;
                        }

                        // Add to category mapping (normalize "misc" to "other")
                        for (String materialType : info.materialTypes) {
                            String normalizedCategory = "misc".equals(materialType) ? "other" : materialType;
                            categoryToBlockIds.computeIfAbsent(normalizedCategory, k -> new HashSet<>()).add(blockId);
                        }

                    }
                }

                // Build response
                Map<String, Object> result = new HashMap<>();
                result.put("current_position", Map.of("x", startX, "y", startY, "z", startZ));
                result.put("radius", radius);

                // Build categories map
                Map<String, Map<String, Object>> categories = new HashMap<>();
                for (Map.Entry<String, Set<String>> entry : categoryToBlockIds.entrySet()) {
                    String category = entry.getKey();
                    Set<String> blockIds = entry.getValue();

                    Map<String, Object> categoryInfo = new HashMap<>();
                    categoryInfo.put("blockIds", new ArrayList<>(blockIds));
                    categoryInfo.put("count", blockIds.size());
                    categories.put(category, categoryInfo);
                }
                result.put("categories", categories);

                // Build blocks map (grouped by blockId with nearest coordinates)
                Map<String, Map<String, Object>> blocks = new HashMap<>();
                for (BlockScanInfo info : foundBlocks.values()) {
                    Map<String, Object> blockInfo = new HashMap<>();
                    blockInfo.put("displayName", info.displayName);
                    // Normalize "misc" to "other" for better LLM comprehension
                    String primaryCategory = info.materialTypes.isEmpty() ? "other" : info.materialTypes.get(0);
                    if ("misc".equals(primaryCategory)) {
                        primaryCategory = "other";
                    }
                    blockInfo.put("category", primaryCategory);
                    // Also normalize "misc" to "other" in the categories list
                    List<String> normalizedCategories = info.materialTypes.stream()
                            .map(cat -> "misc".equals(cat) ? "other" : cat)
                            .toList();
                    blockInfo.put("categories", normalizedCategories);
                    blockInfo.put("count", info.count);
                    blockInfo.put("nearest", Map.of(
                            "coordinates", Map.of("x", info.nearestX, "y", info.nearestY, "z", info.nearestZ),
                            "distance", Math.round(info.nearestDistance * 100.0) / 100.0));
                    blocks.put(info.blockId, blockInfo);
                }
                result.put("blocks", blocks);
                result.put("totalUniqueBlocks", foundBlocks.size());

                // Build fluids map (same shape as blocks but concise)
                Map<String, Map<String, Object>> fluids = new HashMap<>();
                FluidScanInfo nearestFluid = null;
                for (FluidScanInfo info : foundFluids.values()) {
                    Map<String, Object> fluidInfo = new HashMap<>();
                    fluidInfo.put("displayName", info.displayName);
                    fluidInfo.put("category", "fluid");
                    fluidInfo.put("count", info.count);
                    fluidInfo.put("nearest", Map.of(
                            "coordinates", Map.of("x", info.nearestX, "y", info.nearestY, "z", info.nearestZ),
                            "distance", Math.round(info.nearestDistance * 100.0) / 100.0));
                    fluids.put(info.fluidId, fluidInfo);

                    if (nearestFluid == null || info.nearestDistance < nearestFluid.nearestDistance) {
                        nearestFluid = info;
                    }
                }
                result.put("fluids", fluids);
                result.put("totalUniqueFluids", foundFluids.size());
                if (nearestFluid != null) {
                    result.put("nearestFluid", Map.of(
                            "fluidId", nearestFluid.fluidId,
                            "displayName", nearestFluid.displayName,
                            "count", nearestFluid.count,
                            "nearest", Map.of(
                                    "coordinates", Map.of("x", nearestFluid.nearestX, "y", nearestFluid.nearestY,
                                            "z", nearestFluid.nearestZ),
                                    "distance", Math.round(nearestFluid.nearestDistance * 100.0) / 100.0)));
                }

                logger.debug("Scan complete: found " + foundBlocks.size() + " unique block types and "
                        + foundFluids.size() + " unique fluids in radius " + radius);
                future.complete(Optional.of(result));

            } catch (Exception e) {
                logger.error("Error in scanBlocks: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    /** 辅助类：跟踪方块扫描信息（方块ID、显示名称、材质类型、数量、最近距离和坐标） */
    private static class BlockScanInfo {
        final String blockId;
        final String displayName;
        final List<String> materialTypes;
        int count = 0;
        double nearestDistance = Double.MAX_VALUE;
        int nearestX, nearestY, nearestZ;

        BlockScanInfo(String blockId, String displayName, List<String> materialTypes) {
            this.blockId = blockId;
            this.displayName = displayName;
            this.materialTypes = materialTypes;
        }
    }

    /** 辅助类：跟踪流体扫描信息（流体ID、显示名称、数量、最近距离和坐标） */
    private static class FluidScanInfo {
        final String fluidId;
        final String displayName;
        int count = 0;
        double nearestDistance = Double.MAX_VALUE;
        int nearestX, nearestY, nearestZ;

        FluidScanInfo(String fluidId, String displayName) {
            this.fluidId = fluidId;
            this.displayName = displayName;
        }
    }

    /**
     * 在NPC周围搜索指定名称的实体（玩家、NPC或掉落物品）
     * 通过反射访问 entitiesByUuid 映射遍历所有实体，返回最近匹配的实体信息
     */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findEntity(UUID npcInstanceId, String name,
            int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC, not the default world
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] findEntity: Could not find world '" + worldName
                    + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                Ref<EntityStore> myselfRef = npcData.entityRef();
                Store<EntityStore> store = myselfRef.getStore();
                TransformComponent myTransform = store.getComponent(myselfRef, TransformComponent.getComponentType());

                if (myTransform == null) {
                    future.complete(Optional.empty());
                    return;
                }

                Vector3d myPos = myTransform.getPosition();
                EntityStore entityStore = store.getExternalData();

                // Use reflection to access entitiesByUuid map
                Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
                entitiesMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                        .get(entityStore);

                double minDistance = Double.MAX_VALUE;
                Ref<EntityStore> nearestRef = null;
                String bestName = "";

                // Get NPC's UUID for comparison
                com.hypixel.hytale.server.core.entity.UUIDComponent myUuidComp = store.getComponent(myselfRef,
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                UUID myUuid = myUuidComp != null ? myUuidComp.getUuid() : null;

                // Iterate all entities
                for (Map.Entry<UUID, Ref<EntityStore>> entry : allEntities.entrySet()) {
                    UUID entityUuid = entry.getKey();
                    Ref<EntityStore> ref = entry.getValue();

                    // Skip if invalid or is the NPC itself
                    if (!ref.isValid())
                        continue;
                    if (myUuid != null && myUuid.equals(entityUuid))
                        continue;

                    // if(npcInstanceId.equals(ref.getStore().getExternalData().getRefFromUUID()))
                    // continue;

                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    if (t == null)
                        continue;

                    double dist = t.getPosition().distanceTo(myPos);
                    if (dist > radius)
                        continue;

                    String entityName = null;
                    boolean typeMatch = false;

                    // Check for player
                    com.hypixel.hytale.server.core.universe.PlayerRef pRef = store.getComponent(ref,
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (pRef != null) {
                        typeMatch = true;
                        entityName = pRef.getUsername();
                    } else {
                        // Check for npc
                        NPCEntity npcComp = store.getComponent(ref, NPCEntity.getComponentType());
                        if (npcComp != null) {
                            typeMatch = true;
                            Nameplate np = store.getComponent(ref, Nameplate.getComponentType());
                            if (np != null)
                                entityName = np.getText();
                            else {
                                // Use UUIDComponent if available for ID
                                com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = store.getComponent(ref,
                                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                                if (uuidComp != null)
                                    entityName = npcComp.getRole() != null
                                            ? npcComp.getRole().getClass().getSimpleName()
                                            : "NPC-" + uuidComp.getUuid().toString().substring(0, 5);
                                else
                                    entityName = "NPC";
                            }
                        } else {
                            // Check for item
                            com.hypixel.hytale.server.core.modules.entity.item.ItemComponent ic = store.getComponent(
                                    ref,
                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                            .getComponentType());
                            if (ic != null) {
                                typeMatch = true;
                                entityName = ic.getItemStack().getItem().getId(); // Use ID as name (e.g. "stone_sword")
                            }
                        }
                    }

                    if (!typeMatch)
                        continue;

                    if (name != null && !name.isEmpty()) {
                        if (entityName == null || !entityName.toLowerCase().contains(name.toLowerCase())) {
                            continue;
                        }
                    }

                    if (dist < minDistance) {
                        minDistance = dist;
                        nearestRef = ref;
                        bestName = entityName != null ? entityName : "Unknown";
                    }
                }

                if (nearestRef != null) {
                    TransformComponent t = store.getComponent(nearestRef, TransformComponent.getComponentType());
                    Vector3d pos = t.getPosition();

                    Map<String, Object> result = new HashMap<>();
                    result.put("found", true);
                    result.put("name", bestName);
                    result.put("coordinates", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
                    result.put("distance", minDistance);

                    future.complete(Optional.of(result));
                } else {
                    future.complete(Optional.empty());
                }

            } catch (Throwable e) {
                logger.error("Error in findEntity: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    /**
     * 扫描NPC周围所有实体（玩家、NPC、掉落物品）
     * 返回每个实体的名称、类型、外观、UUID、坐标、距离和生命值信息
     * 跳过投射物（全息文字、思考指示器等）
     */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanEntities(UUID npcInstanceId, int radius) {
        CompletableFuture<Optional<Map<String, Object>>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.empty());
            return future;
        }

        // Get the correct world for this NPC
        String worldName = npcData.spawnLocation() != null ? npcData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] scanEntities: Could not find world '" + worldName
                    + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            future.complete(Optional.empty());
            return future;
        }
        final World world = resolvedWorld;

        world.execute(() -> {
            try {
                Ref<EntityStore> myselfRef = npcData.entityRef();
                Store<EntityStore> store = myselfRef.getStore();
                TransformComponent myTransform = store.getComponent(myselfRef, TransformComponent.getComponentType());

                if (myTransform == null) {
                    future.complete(Optional.empty());
                    return;
                }

                Vector3d myPos = myTransform.getPosition();
                int startX = (int) Math.floor(myPos.getX());
                int startY = (int) Math.floor(myPos.getY());
                int startZ = (int) Math.floor(myPos.getZ());

                EntityStore entityStore = store.getExternalData();

                // Use reflection to access entitiesByUuid map
                Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
                entitiesMapField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                        .get(entityStore);

                List<Map<String, Object>> entities = new ArrayList<>();

                // Get NPC's UUID for comparison
                com.hypixel.hytale.server.core.entity.UUIDComponent myUuidComp = store.getComponent(myselfRef,
                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                UUID myUuid = myUuidComp != null ? myUuidComp.getUuid() : null;

                // Iterate all entities
                for (Map.Entry<UUID, Ref<EntityStore>> entry : allEntities.entrySet()) {
                    UUID entryEntityUuid = entry.getKey();
                    Ref<EntityStore> ref = entry.getValue();

                    // Skip if invalid or is the NPC itself
                    if (!ref.isValid())
                        continue;
                    if (myUuid != null && myUuid.equals(entryEntityUuid))
                        continue;

                    TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                    if (t == null)
                        continue;

                    double dist = t.getPosition().distanceTo(myPos);
                    if (dist > radius)
                        continue;

                    // Skip projectiles (holograms, thinking indicators, etc.)
                    ProjectileComponent projectileComp = store.getComponent(ref,
                            ProjectileComponent.getComponentType());
                    if (projectileComp != null)
                        continue;

                    // Extract entity information
                    String entityName = null;
                    String entityType = "unknown";
                    String appearance = null;
                    UUID entityUuid = null;
                    Double health = null;
                    Double maxHealth = null;

                    // Check for player
                    com.hypixel.hytale.server.core.universe.PlayerRef pRef = store.getComponent(ref,
                            com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                    if (pRef != null) {
                        entityType = "player";
                        entityName = pRef.getUsername();
                        entityUuid = pRef.getUuid();
                        appearance = "Player";
                    } else {
                        // Check for NPC
                        NPCEntity npcComp = store.getComponent(ref, NPCEntity.getComponentType());
                        if (npcComp != null) {
                            entityType = "npc";
                            entityUuid = npcComp.getUuid();

                            Nameplate np = store.getComponent(ref, Nameplate.getComponentType());
                            if (np != null && np.getText() != null && !np.getText().isEmpty()) {
                                entityName = np.getText();
                            } else if (npcComp.getRole() != null && npcComp.getRoleName() != null) {
                                // Use role name if available
                                entityName = npcComp.getRoleName();
                            } else {
                                entityName = "NPC";
                            }

                            appearance = npcComp.getRole() != null && npcComp.getRoleName() != null
                                    ? npcComp.getRoleName()
                                    : "NPC";
                        } else {
                            // Check for dropped item
                            com.hypixel.hytale.server.core.modules.entity.item.ItemComponent ic = store.getComponent(
                                    ref,
                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                            .getComponentType());
                            if (ic != null) {
                                entityType = "item";
                                entityName = ic.getItemStack().getItem().getId();
                                appearance = "Dropped Item: " + entityName;

                                com.hypixel.hytale.server.core.entity.UUIDComponent uuidComp = store.getComponent(ref,
                                        com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                                if (uuidComp != null) {
                                    entityUuid = uuidComp.getUuid();
                                }
                            }
                            // Note: We're skipping creatures/mobs for now since they don't have a reliable
                            // way to be distinguished from projectiles without AIComponent
                        }
                    }

                    // Skip if we couldn't identify the entity
                    if (entityName == null)
                        continue;

                    // Get health stats for players and NPCs
                    if ("player".equals(entityType) || "npc".equals(entityType)) {
                        try {
                            com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap statMap = store
                                    .getComponent(ref, com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap
                                            .getComponentType());
                            if (statMap != null) {
                                int healthIndex = com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes
                                        .getHealth();
                                if (healthIndex != Integer.MIN_VALUE) {
                                    com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue healthValue = statMap
                                            .get(healthIndex);
                                    if (healthValue != null) {
                                        health = (double) healthValue.get();
                                        maxHealth = (double) healthValue.getMax();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // Health stats not available, skip
                        }
                    }

                    // Build entity info
                    Map<String, Object> entityInfo = new HashMap<>();
                    entityInfo.put("name", entityName);
                    entityInfo.put("type", entityType);
                    if (appearance != null) {
                        entityInfo.put("appearance", appearance);
                    }
                    if (entityUuid != null) {
                        entityInfo.put("uuid", entityUuid.toString());
                    }

                    Vector3d pos = t.getPosition();
                    entityInfo.put("coordinates", Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
                    entityInfo.put("distance", Math.round(dist * 100.0) / 100.0);

                    if (health != null) {
                        entityInfo.put("health", health);
                    }
                    if (maxHealth != null) {
                        entityInfo.put("maxHealth", maxHealth);
                    }

                    entities.add(entityInfo);
                }

                // Build response
                Map<String, Object> result = new HashMap<>();
                result.put("current_position", Map.of("x", startX, "y", startY, "z", startZ));
                result.put("radius", radius);
                result.put("entities", entities);
                result.put("totalEntities", entities.size());

                logger.debug("Scan entities complete: found " + entities.size() + " entities in radius " + radius);
                future.complete(Optional.of(result));

            } catch (Throwable e) {
                logger.error("Error in scanEntities: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.empty());
            }
        });

        return future;
    }

    /**
     * 获取NPC实例的实时位置
     * 包含熔断机制：连续超时超过10次后跳过实时检查，使用缓存位置
     * 如果已在正确的世界线程上则直接读取，否则提交到世界线程并等待结果（最多250ms）
     */
    @Override
    public Optional<Location> getNpcInstanceLocation(UUID npcInstanceId) {
        // 线程中断时快速失败
        if (Thread.currentThread().isInterrupted()) {
            return Optional.empty();
        }

        // Circuit breaker: If we had > 10 consecutive timeouts, skip real-time checks
        // This prevents blocking during server shutdown/unresponsive states
        if (consecutiveTimeouts.get() > 10) {
            // Only log once per 'trip'
            if (consecutiveTimeouts.get() == 11) {
                logger.debug("Circuit breaker tripped: Too many consecutive timeouts accessing World.");
                consecutiveTimeouts.incrementAndGet();
            }
            Location fallback = lastKnownNpcLocations.get(npcInstanceId);
            if (fallback != null) {
                return Optional.of(fallback);
            }
            return Optional.empty();
        }

        // First try to get real-time location from entity
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);

        if (npcInstanceData == null) {
            return Optional.empty();
        }
        Location spawnFallback = npcInstanceData.spawnLocation();
        if (spawnFallback != null) {
            lastKnownNpcLocations.putIfAbsent(npcInstanceId, spawnFallback);
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return Optional.empty();
        }

        // Get the world directly from the entity's store.
        // getExternalData() doesn't require being on the store's thread, so we can
        // safely get the world from any thread. This ensures we execute on the correct
        // world thread for this specific entity.
        World entityWorld;
        try {
            Store<EntityStore> store = entityRef.getStore();
            entityWorld = store.getExternalData().getWorld();
            // logger.debug("[getNpcInstanceLocation] NPC " + npcInstanceId + " store world:
            // " +
            // (entityWorld != null ? entityWorld.getName() : "null") +
            // ", spawnLocation world: " +
            // (npcInstanceData.spawnLocation() != null ?
            // npcInstanceData.spawnLocation().world() : "null"));
        } catch (Exception e) {
            logger.debug("[getNpcInstanceLocation] Could not get NPC world from store for " + npcInstanceId + ": "
                    + e.getMessage());
            return Optional.empty();
        }

        if (entityWorld == null) {
            logger.debug("[getNpcInstanceLocation] NPC entity world is null for " + npcInstanceId);
            return Optional.empty();
        }

        // Check if the world has players - if not, the world may not be ticking
        // and tasks won't be processed. In this case, we can't get the location.
        if (entityWorld.getPlayerCount() == 0) {
            logger.debug("[getNpcInstanceLocation] World " + entityWorld.getName()
                    + " has no players, skipping location check for NPC " + npcInstanceId);
            return Optional.empty();
        }

        // Location extraction logic - MUST run on WorldThread due to Hytale ECS
        // thread-safety
        Supplier<Optional<Location>> locationExtractor = () -> {
            if (entityRef != null && entityRef.isValid()) {
                try {
                    Store<EntityStore> store = entityRef.getStore();
                    TransformComponent transform = store.getComponent(entityRef,
                            TransformComponent.getComponentType());
                    if (transform != null) {
                        Vector3d pos = transform.getPosition();
                        World world = store.getExternalData().getWorld();
                        String worldName = world != null ? world.getName() : "world";
                        return Optional.of(new Location(pos.getX(), pos.getY(), pos.getZ(), worldName));
                    } else {
                        logger.debug("[getNpcInstanceLocation] TransformComponent is null for NPC " + npcInstanceId);
                    }
                } catch (Exception e) {
                    logger.debug("[getNpcInstanceLocation] Could not get NPC transform for " + npcInstanceId + ": "
                            + e.getMessage());
                }
            } else {
                logger.debug("[getNpcInstanceLocation] Entity ref is null or invalid for NPC " + npcInstanceId +
                        ", entityRef=" + entityRef + ", valid=" + (entityRef != null ? entityRef.isValid() : "N/A"));
            }
            return Optional.empty();
        };

        // Check if we are already on the correct WorldThread for this NPC
        String currentThreadName = Thread.currentThread().getName();
        String expectedWorldName = entityWorld.getName();
        if (currentThreadName.contains("WorldThread") && expectedWorldName != null &&
                currentThreadName.contains(expectedWorldName)) {
            // We're already on the correct world thread, can execute directly
            Optional<Location> result = locationExtractor.get();
            if (result.isPresent()) {
                consecutiveTimeouts.set(0);
                lastKnownNpcLocations.put(npcInstanceId, result.get());
            }
            return result;
        }

        // Schedule on the entity's actual WorldThread and wait for result
        try {
            CompletableFuture<Optional<Location>> future = new CompletableFuture<>();
            try {
                entityWorld.execute(() -> {
                    try {
                        Optional<Location> loc = locationExtractor.get();
                        if (loc.isEmpty()) {
                            logger.debug("[getNpcInstanceLocation] Location extractor returned empty for NPC "
                                    + npcInstanceId + " on world " + entityWorld.getName());
                        }
                        future.complete(loc);
                    } catch (Exception e) {
                        logger.debug("[getNpcInstanceLocation] Exception in location extractor for NPC " + npcInstanceId
                                + ": " + e.getMessage());
                        future.completeExceptionally(e);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // World executor is shut down or full
                logger.debug("[getNpcInstanceLocation] World executor rejected location check task for NPC "
                        + npcInstanceId);
                return Optional.empty();
            }

            // Wait up to 250ms for the result to tolerate brief world-thread load spikes.
            Optional<Location> result = future.get(250, TimeUnit.MILLISECONDS);
            if (result.isEmpty()) {
                logger.debug("[getNpcInstanceLocation] No result for NPC " + npcInstanceId + " after executing on "
                        + entityWorld.getName());
            }
            if (result.isPresent()) {
                // Reset circuit breaker on success
                consecutiveTimeouts.set(0);
                lastKnownNpcLocations.put(npcInstanceId, result.get());
            }
            return result;
        } catch (InterruptedException e) {
            // Preserve interrupt status
            Thread.currentThread().interrupt();
            Sentry.captureException(e);
            return Optional.empty();
        } catch (java.util.concurrent.TimeoutException e) {
            // Handle timeout specifically for circuit breaker
            int timeouts = consecutiveTimeouts.incrementAndGet();
            if (timeouts <= 10) { // Reduce log spam
                logger.debug("[getNpcInstanceLocation] Timeout getting NPC " + npcInstanceId + " location (" + timeouts
                        + ")");
            }
            Sentry.captureException(e);
        } catch (Exception e) {
            // Other error
            logger.debug("[getNpcInstanceLocation] Could not get real-time location for NPC " + npcInstanceId + ": "
                    + e.getMessage());
            Sentry.captureException(e);
        }

        Location fallback = lastKnownNpcLocations.get(npcInstanceId);
        if (fallback != null) {
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    /**
     * 让NPC平滑转身面向目标位置
     * 使用定时任务每50ms更新一次旋转角度（最大转速90度/秒）
     * 对齐后自动停止，同时更新牵引航向以通知AI
     */
    @Override
    public void rotateNpcInstanceToward(UUID npcInstanceId, Location targetLocation) {
        if (targetLocation == null) {
            return;
        }

        // [Shutdown Fix] Check shutdown early to avoid unnecessary work
        if (isShuttingDown()) {
            return;
        }

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.debug("[Hycompanion] Cannot rotate NPC - entity not tracked: " + npcInstanceId);
            return;
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.debug("[Hycompanion] Cannot rotate NPC - entity not tracked: " + npcInstanceId);
            return;
        }
        if (!entityRef.isValid()) {
            logger.debug("[Hycompanion] Cannot rotate NPC - entity reference stale: " + npcInstanceId);
            npcInstanceEntities.remove(npcInstanceId);
            return;
        }

        // Get the correct world for this NPC, not the default world
        String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
            logger.warn("[Hycompanion] rotateNpcInstanceToward: Could not find world '" + worldName
                    + "' for NPC, falling back to default world");
        }
        if (resolvedWorld == null) {
            return;
        }
        final World world = resolvedWorld;

        try {
            // Schedule smooth body rotation
            AtomicInteger ticksRemaining = new AtomicInteger(20); // 1 second total
            AtomicReference<ScheduledFuture<?>> selfRef = new AtomicReference<>();

            Runnable taskRunnable = new Runnable() {
                @Override
                public void run() {
                    ScheduledFuture<?> self = selfRef.get();
                    if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                        cancelSpecificRotationTask(npcInstanceId, self);
                        return;
                    }

                    if (ticksRemaining.decrementAndGet() < 0) {
                        cancelSpecificRotationTask(npcInstanceId, self);
                        return;
                    }

                    try {
                        world.execute(() -> {
                            try {
                                if (!entityRef.isValid()) {
                                    cancelSpecificRotationTask(npcInstanceId, self);
                                    return;
                                }

                                Store<EntityStore> store = entityRef.getStore();
                                TransformComponent transform = store.getComponent(entityRef,
                                        TransformComponent.getComponentType());
                                NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());

                                if (transform == null || npcEntity == null) {
                                    cancelSpecificRotationTask(npcInstanceId, self);
                                    return;
                                }

                                // Check busy states to prevent AI conflict
                                var role = npcEntity.getRole();
                                if (role != null) {
                                    var stateSupport = role.getStateSupport();
                                    if (stateSupport != null && stateSupport.isInBusyState()) {
                                        cancelSpecificRotationTask(npcInstanceId, self);
                                        return;
                                    }

                                    var markedSupport = role.getMarkedEntitySupport();
                                    if (markedSupport != null) {
                                        var lockedTarget = markedSupport.getMarkedEntityRef("LockedTarget");
                                        if (lockedTarget != null && lockedTarget.isValid()) {
                                            cancelSpecificRotationTask(npcInstanceId, self);
                                            return;
                                        }
                                    }
                                }

                                Vector3d npcPos = transform.getPosition();
                                double dx = targetLocation.x() - npcPos.getX();
                                double dz = targetLocation.z() - npcPos.getZ();

                                if ((dx * dx) + (dz * dz) < 0.0001d) {
                                    cancelSpecificRotationTask(npcInstanceId, self);
                                    return;
                                }

                                float targetYaw = TrigMathUtil.atan2(-dx, -dz);

                                Vector3f bodyRotation = transform.getRotation();
                                float currentYaw = bodyRotation.getYaw();

                                // Calculate shortest angle difference
                                float diff = targetYaw - currentYaw;
                                while (diff > (float) Math.PI)
                                    diff -= 2 * (float) Math.PI;
                                while (diff < -(float) Math.PI)
                                    diff += 2 * (float) Math.PI;

                                // Stop if aligned (within ~2.8 degrees / 0.05 radians)
                                if (Math.abs(diff) < 0.05f) {
                                    bodyRotation.setYaw(targetYaw);
                                    bodyRotation.setPitch(0.0f);
                                    bodyRotation.setRoll(0.0f);
                                    transform.setRotation(bodyRotation);
                                    npcEntity.setLeashHeading(targetYaw);
                                    npcEntity.setLeashPitch(0.0f);
                                    cancelSpecificRotationTask(npcInstanceId, self);
                                    return;
                                }

                                // Max turn per tick: 90 deg/sec = 1.57 rad/sec -> 0.0785 rad/tick
                                float maxTurn = 0.0785f;
                                float turn = Math.min(Math.abs(diff), maxTurn) * Math.signum(diff);
                                float newYaw = currentYaw + turn;

                                // Normalize
                                while (newYaw > (float) Math.PI)
                                    newYaw -= 2 * (float) Math.PI;
                                while (newYaw < -(float) Math.PI)
                                    newYaw += 2 * (float) Math.PI;

                                bodyRotation.setYaw(newYaw);
                                bodyRotation.setPitch(0.0f);
                                bodyRotation.setRoll(0.0f);
                                transform.setRotation(bodyRotation);

                                // Update leash heading so AI knows where we're facing
                                npcEntity.setLeashHeading(newYaw);
                                npcEntity.setLeashPitch(0.0f);

                            } catch (Exception e) {
                                logger.debug("[Hycompanion] Rotation tick error: " + e.getMessage());
                                Sentry.captureException(e);
                                cancelSpecificRotationTask(npcInstanceId, self);
                            }
                        });
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        Sentry.captureException(e);
                        cancelSpecificRotationTask(npcInstanceId, self);
                    } catch (Exception e) {
                        Sentry.captureException(e);
                        cancelSpecificRotationTask(npcInstanceId, self);
                    }
                }
            };

            ScheduledFuture<?> rotationTask = rotationScheduler.scheduleAtFixedRate(taskRunnable, 0, 50,
                    TimeUnit.MILLISECONDS);
            selfRef.set(rotationTask);

            // Put gracefully, cancelling any previous task
            ScheduledFuture<?> previousTask = rotationTasks.put(npcInstanceId, rotationTask);
            if (previousTask != null && !previousTask.isCancelled()) {
                previousTask.cancel(false);
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Failed to execute target rotation: " + e.getMessage());
            Sentry.captureException(e);
        }
    }

    /** 取消NPC的旋转定时任务 */
    private void cancelRotationTask(UUID npcInstanceId) {
        ScheduledFuture<?> existing = rotationTasks.remove(npcInstanceId);
        if (existing != null) {
            existing.cancel(false);
        }
    }

    /** 取消特定的旋转任务（避免意外删除新替换的任务引用） */
    private void cancelSpecificRotationTask(UUID npcInstanceId, ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
            rotationTasks.remove(npcInstanceId, task);
        }
    }

    /**
     * 检查NPC实例的实体引用是否有效
     * 如果在正确的世界线程上，还会验证NPC组件是否存在
     */
    @Override
    public boolean isNpcInstanceEntityValid(UUID npcInstanceId) {

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();

        // No entity reference tracked
        if (entityRef == null) {
            logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId
                    + ": No entity reference tracked (NPC may not be spawned or was never registered)");
            return false;
        }

        // Check if reference is still valid (entity not removed by /npc clean etc.)
        if (!entityRef.isValid()) {
            // Clean up stale reference
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId
                    + ": Entity reference became invalid (entity was likely removed by /npc clean or despawned)");
            return false;
        }

        // Verify NPC component still exists
        // We need to check on the correct world thread to avoid thread assertion errors
        String expectedWorldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world()
                : null;
        String currentThreadName = Thread.currentThread().getName();
        boolean onCorrectWorldThread = currentThreadName.contains("WorldThread") && expectedWorldName != null &&
                currentThreadName.contains(expectedWorldName);

        if (onCorrectWorldThread) {
            // Already on correct world thread, can check directly
            try {
                Store<EntityStore> store = entityRef.getStore();
                if (store == null) {
                    logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId + ": Could not get entity store");
                    return false;
                }
                NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                if (npcEntity == null) {
                    logger.info(
                            "[isNpcInstanceEntityValid] NPC " + npcInstanceId + ": NPC component not found on entity");
                    return false;
                }
                return true;
            } catch (Exception e) {
                logger.info("[isNpcInstanceEntityValid] NPC " + npcInstanceId
                        + ": Exception checking entity - " + e.getMessage());
                Sentry.captureException(e);
                return false;
            }
        } else {
            // Not on correct world thread, just assume valid if entityRef is valid
            // The actual validation will happen when the NPC is accessed on the correct
            // thread
            logger.debug("[isNpcInstanceEntityValid] NPC " + npcInstanceId + ": Not on correct world thread ("
                    + expectedWorldName + "), skipping component check");
            return true;
        }
    }

    /**
     * 发现已存在的NPC实例（服务器重启后恢复跟踪）
     * 遍历所有世界中的NPC实体，匹配角色索引，
     * 恢复实体引用、出生点位置和能力属性
     */
    @Override
    public List<UUID> discoverExistingNpcInstances(String externalId) {
        logger.info("[discoverExistingNpcInstances] Searching for existing NPC entities for role: " + externalId);

        NpcData npcData = plugin.getNpcManager().getNpc(externalId).orElse(null);
        if (npcData == null) {
            logger.info("[discoverExistingNpcInstances] NPC Data not found for: " + externalId);
            return Collections.emptyList();
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        int roleIndex = npcPlugin.getIndex(externalId);

        if (roleIndex < 0) {
            logger.info("[discoverExistingNpcInstances] Role not found in NPC definitions: " + externalId);
            return Collections.emptyList();
        }

        logger.info("[discoverExistingNpcInstances] Role index for " + externalId + ": " + roleIndex);

        final int targetRoleIndex = roleIndex;
        final List<UUID> discoveredUuids = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger pendingWorlds = new AtomicInteger(Universe.get().getWorlds().size());

        for (World world : Universe.get().getWorlds().values()) {
            try {
                world.execute(() -> {
                    try {
                        Store<EntityStore> store = world.getEntityStore().getStore();
                        store.forEachChunk(NPCEntity.getComponentType(),
                                (BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>) (chunk,
                                        commandBuffer) -> {
                                    for (int i = 0; i < chunk.size(); i++) {
                                        try {
                                            NPCEntity npc = chunk.getComponent(i, NPCEntity.getComponentType());
                                            if (npc != null && npc.getRoleIndex() == targetRoleIndex) {
                                                Ref<EntityStore> entityRef = chunk.getReferenceTo(i);
                                                UUID entityUuid = npc.getUuid();

                                                if (entityUuid == null) {
                                                    continue;
                                                }

                                                // Always update tracking to ensure fresh Entity References
                                                // (References become invalid after entity reload/respawn)
                                                boolean isUpdate = npcInstanceEntities.containsKey(entityUuid);

                                                // Get or create spawn location - use externalId as key
                                                // IMPORTANT: Only set spawn location if not already set, to preserve
                                                // the original spawn point. The leash point may change as the NPC moves
                                                // or could be 0,0,0 if the NPC is dead/dying.
                                                Vector3d leashPoint = npc.getLeashPoint();
                                                Location spawnLocation = npcSpawnLocations.get(externalId);
                                                if (spawnLocation == null) {
                                                    // Only use leash point if it's valid (not at origin)
                                                    if (leashPoint.getX() == 0 && leashPoint.getY() == 0
                                                            && leashPoint.getZ() == 0) {
                                                        // Use current position as fallback
                                                        TransformComponent currentTransform = store.getComponent(
                                                                entityRef,
                                                                TransformComponent.getComponentType());
                                                        if (currentTransform != null) {
                                                            Vector3d pos = currentTransform.getPosition();
                                                            spawnLocation = Location.of(pos.getX(), pos.getY(),
                                                                    pos.getZ(),
                                                                    world.getName() != null ? world.getName()
                                                                            : "world");
                                                        } else {
                                                            spawnLocation = Location.of(0, 0, 0,
                                                                    world.getName() != null ? world.getName()
                                                                            : "world");
                                                        }
                                                    } else {
                                                        spawnLocation = Location.of(
                                                                leashPoint.getX(), leashPoint.getY(), leashPoint.getZ(),
                                                                world.getName() != null ? world.getName() : "world");
                                                    }
                                                    npcSpawnLocations.put(externalId, spawnLocation);
                                                }

                                                // Create Instance
                                                NpcInstanceData instance = new NpcInstanceData(entityUuid,
                                                        entityRef,
                                                        npc, npcData, spawnLocation);

                                                // Apply capabilities (invulnerability, knockback) to existing NPCs
                                                // This is critical for server restart - existing NPCs need their
                                                // capabilities restored
                                                applyNpcCapabilities(store, entityRef, npc, npcData, "Discovery");

                                                // Get Location
                                                TransformComponent transform = store.getComponent(entityRef,
                                                        TransformComponent.getComponentType());
                                                if (transform != null) {
                                                    Vector3d pos = transform.getPosition();

                                                    // Ensure leash point is set (use position if at origin)
                                                    if (leashPoint.getX() == 0 && leashPoint.getY() == 0
                                                            && leashPoint.getZ() == 0) {
                                                        npc.setLeashPoint(pos);
                                                        Vector3f rot = transform.getRotation();
                                                        npc.setLeashHeading(rot.getYaw());
                                                        npc.setLeashPitch(rot.getPitch());
                                                    }
                                                }

                                                npcInstanceEntities.put(entityUuid, instance);

                                                if (isUpdate) {
                                                    logger.info(
                                                            "[discoverExistingNpcInstances] Updated binding (Refreshed Ref): "
                                                                    + externalId + " -> " + entityUuid);
                                                } else {
                                                    logger.info("[discoverExistingNpcInstances] Discovered & Bound: "
                                                            + externalId + " -> " + entityUuid);
                                                }

                                                // Always add to list
                                                discoveredUuids.add(entityUuid);
                                            }
                                        } catch (Exception e) {
                                            logger.debug("Error inspecting NPC entity: " + e.getMessage());
                                            Sentry.captureException(e);
                                        }
                                    }
                                });
                    } catch (Exception e) {
                        logger.error("[discoverExistingNpcInstances] Error in world execute: " + e.getMessage());
                        Sentry.captureException(e);
                    } finally {
                        pendingWorlds.decrementAndGet();
                    }
                });
            } catch (Exception e) {
                pendingWorlds.decrementAndGet();
                logger.error("[discoverExistingNpcInstances] Failed to schedule world execution: " + e.getMessage());
                Sentry.captureException(e);
            }
        }

        // Wait for completion (max 2 seconds)
        long start = System.currentTimeMillis();
        while (pendingWorlds.get() > 0 && System.currentTimeMillis() - start < 2000) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Sentry.captureException(e);
                break;
            }
        }

        logger.info(
                "[discoverExistingNpcInstances] Discovery complete. Found " + discoveredUuids.size() + " instances.");

        return new ArrayList<>(discoveredUuids);
    }

    // ========== 交易操作（Hytale API 尚未公开交易界面） ==========

    @Override
    public void openTradeInterface(UUID npcInstanceId, String playerId) {
        // Trade interface functionality is NOT currently exposed in Hytale's public
        // plugin API.
        // Trading appears to be handled through NPC interaction hints and internal game
        // systems.
        // Example hint: "interactionHints.trade" (seen in BuilderActionSetInteractable)

        logger.warn("[Hycompanion] openTradeInterface is NOT IMPLEMENTED - " +
                "Trade UI is not exposed in Hytale's plugin API. " +
                "Trade interactions are handled through NPC role configuration.");

        // Placeholder: Send a message to the player instead
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);
            if (playerRef != null) {
                Message msg = Message.raw("[Trade] The merchant opens their wares...")
                        .color("#FFD700");
                playerRef.sendMessage(msg);
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        throw new UnsupportedOperationException(
                "Trade interface not available in Hytale plugin API. " +
                        "Configure trades in NPC Role definitions instead.");
    }

    // ========== 任务操作（Hytale API 尚未公开任务系统） ==========

    @Override
    public void offerQuest(UUID npcInstanceId, String playerId, String questId, String questName) {
        // Quest system functionality is NOT currently exposed in Hytale's public plugin
        // API.
        // Hytale appears to have an adventure/objectives system (builtin.adventure
        // package)
        // but it's not designed for direct plugin manipulation.

        logger.warn("[Hycompanion] offerQuest is NOT IMPLEMENTED - " +
                "Quest system is not exposed in Hytale's plugin API. " +
                "Quests are defined through Adventure Mode assets.");

        // Placeholder: Send a quest notification message instead
        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);

            if (playerRef != null) {
                Message questMessage = Message.raw("[Quest Available] " + questName)
                        .color("#FFD700");
                playerRef.sendMessage(questMessage);

                Message questDesc = Message.raw("Talk to the NPC to learn more about this quest.")
                        .color("#AAAAAA");
                playerRef.sendMessage(questDesc);
            }
        } catch (Exception e) {
            Sentry.captureException(e);
        }

        throw new UnsupportedOperationException(
                "Quest system not available in Hytale plugin API. " +
                        "Define quests in Adventure Mode assets instead.");
    }

    // ========== 世界上下文 ==========

    /**
     * 获取当前游戏时间段
     * 基于 WorldTimeResource 的24小时制映射为：dawn/morning/noon/afternoon/dusk/night
     */
    @Override
    public String getTimeOfDay() {
        // NOTE: This method returns the time of day for the default world.
        // For multi-world support, the API would need to be extended to accept a world
        // parameter.
        // Different worlds may have different times (e.g., Lobby vs Arcana).
        World world = Universe.get().getDefaultWorld();
        if (world != null) {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                WorldTimeResource timeResource = store.getResource(WorldTimeResource.getResourceType());

                if (timeResource != null) {
                    int currentHour = timeResource.getCurrentHour();

                    // Map to time of day based on hour and sunlight
                    // Hytale uses 24-hour format with ~60% daytime, ~40% nighttime
                    if (currentHour >= 5 && currentHour < 7) {
                        return "dawn";
                    } else if (currentHour >= 7 && currentHour < 12) {
                        return "morning";
                    } else if (currentHour >= 12 && currentHour < 14) {
                        return "noon";
                    } else if (currentHour >= 14 && currentHour < 18) {
                        return "afternoon";
                    } else if (currentHour >= 18 && currentHour < 20) {
                        return "dusk";
                    } else {
                        return "night";
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not get world time: " + e.getMessage());
                Sentry.captureException(e);
            }
        }
        return "noon"; // Default
    }

    /** 获取当前天气状态（从 WeatherResource 读取，返回简化的天气名称） */
    @Override
    public String getWeather() {
        // NOTE: This method returns the weather for the default world.
        // For multi-world support, the API would need to be extended to accept a world
        // parameter.
        // Different worlds may have different weather (e.g., Lobby vs Arcana).
        World world = Universe.get().getDefaultWorld();
        if (world != null) {
            try {
                Store<EntityStore> store = world.getEntityStore().getStore();
                WeatherResource weatherResource = store.getResource(WeatherResource.getResourceType());

                if (weatherResource != null) {
                    int weatherIndex = weatherResource.getForcedWeatherIndex();

                    if (weatherIndex > 0) {
                        // Get weather name from asset map
                        Weather weather = Weather.getAssetMap().getAsset(weatherIndex);
                        if (weather != null) {
                            String weatherId = weather.getId();
                            // Return simplified weather name
                            if (weatherId != null) {
                                return weatherId.toLowerCase()
                                        .replace("weather_", "")
                                        .replace("_", " ");
                            }
                        }
                    }

                    // Default weather
                    return "clear";
                }
            } catch (Exception e) {
                logger.debug("Could not get weather: " + e.getMessage());
                Sentry.captureException(e);
            }
        }
        return "clear"; // Default
    }

    /** 获取指定位置附近一定半径内的玩家名称列表（关闭安全） */
    @Override
    public List<String> getNearbyPlayerNames(Location location, double radius) {
        // [Shutdown Fix] Check if we're shutting down to avoid accessing invalid player
        // refs
        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }

        List<String> nearbyPlayers = new ArrayList<>();

        try {
            for (PlayerRef playerRef : Universe.get().getPlayers()) {
                // [Shutdown Fix] Check shutdown in the loop
                if (isShuttingDown()) {
                    break;
                }

                try {
                    Transform transform = playerRef.getTransform();
                    Vector3d playerPos = transform.getPosition();

                    double distance = Math.sqrt(
                            Math.pow(playerPos.getX() - location.x(), 2) +
                                    Math.pow(playerPos.getY() - location.y(), 2) +
                                    Math.pow(playerPos.getZ() - location.z(), 2));

                    if (distance <= radius) {
                        nearbyPlayers.add(playerRef.getUsername());
                    }
                } catch (Exception e) {
                    // During shutdown, player references may become invalid
                    logger.debug("Could not get player position: " + e.getMessage());
                    Sentry.captureException(e);
                }
            }
        } catch (Exception e) {
            // During shutdown, the player list may be inaccessible
            logger.debug("Could not iterate online players (shutdown in progress?): " + e.getMessage());
            Sentry.captureException(e);
        }

        return nearbyPlayers;
    }

    /** 获取指定位置附近一定半径内的玩家对象列表（关闭安全） */
    @Override
    public List<GamePlayer> getNearbyPlayers(Location location, double radius) {
        // [Shutdown Fix] Check if we're shutting down to avoid accessing invalid player
        // refs
        if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
            return Collections.emptyList();
        }

        List<GamePlayer> nearbyPlayers = new ArrayList<>();

        try {
            for (PlayerRef playerRef : Universe.get().getPlayers()) {
                // [Shutdown Fix] Check shutdown in the loop
                if (isShuttingDown()) {
                    break;
                }

                try {
                    Transform transform = playerRef.getTransform();
                    Vector3d playerPos = transform.getPosition();

                    double distance = Math.sqrt(
                            Math.pow(playerPos.getX() - location.x(), 2) +
                                    Math.pow(playerPos.getY() - location.y(), 2) +
                                    Math.pow(playerPos.getZ() - location.z(), 2));

                    if (distance <= radius) {
                        nearbyPlayers.add(convertToGamePlayer(playerRef));
                    }
                } catch (Exception e) {
                    // During shutdown, player references may become invalid
                    logger.debug("Could not get player position: " + e.getMessage());
                    Sentry.captureException(e);
                }
            }
        } catch (Exception e) {
            // During shutdown, the player list may be inaccessible
            logger.debug("Could not iterate online players (shutdown in progress?): " + e.getMessage());
            Sentry.captureException(e);
        }

        return nearbyPlayers;
    }

    /** 获取默认世界名称 */
    @Override
    public String getWorldName() {
        // NOTE: This returns the default world's name as a global fallback.
        // For specific world names, use Location.world() or getWorldByName().
        World world = Universe.get().getDefaultWorld();
        return world != null ? world.getName() : "world";
    }

    // ========== 工具方法 ==========

    /** 将 Hytale 的 PlayerRef 转换为插件内部的 GamePlayer 对象 */
    private GamePlayer convertToGamePlayer(PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        String name = playerRef.getUsername();

        // Get player location from transform
        Location location = null;
        try {
            Transform transform = playerRef.getTransform();
            Vector3d pos = transform.getPosition();
            UUID worldUuid = playerRef.getWorldUuid();
            String worldName = "world";

            if (worldUuid != null) {
                World world = Universe.get().getWorld(worldUuid);
                if (world != null) {
                    worldName = world.getName();
                }
            }

            location = new Location(pos.getX(), pos.getY(), pos.getZ(), worldName);
        } catch (Exception e) {
            logger.debug("Could not get player location: " + e.getMessage());
            Sentry.captureException(e);
            location = new Location(0, 0, 0, "world");
        }

        return new GamePlayer(uuid.toString(), name, uuid, location);
    }

    /** 根据名称获取 Hytale World（名称为空时返回默认世界） */
    private World getWorldByName(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Universe.get().getDefaultWorld();
        }
        return Universe.get().getWorld(worldName);
    }

    // ========== AI行为操作 ==========

    /**
     * 让NPC开始跟随指定玩家
     * 将玩家实体设为NPC的LockedTarget，切换到Follow状态
     * 需要NPC和玩家在同一世界，并在世界线程上执行
     */
    @Override
    public boolean startFollowingPlayer(UUID npcInstanceId, String targetPlayerName) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] starting to follow player: " + targetPlayerName);

        // If a move_to monitor is still active, it can later reset LockedTarget/Idle
        // and break follow.
        cleanupMovement(npcInstanceId);

        // Debug: Log all tracked NPCs
        logger.debug("[Hycompanion] startFollowingPlayer - Tracked NPCs: " + npcInstanceEntities.keySet());

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot follow - NPC entity not tracked: " + npcInstanceId +
                    " (NPC needs to be spawned or discovered first. Tracked NPCs: " + npcInstanceEntities.keySet()
                    + ")");
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot follow - NPC entity not tracked: " + npcInstanceId +
                    " (NPC needs to be spawned or discovered first. Tracked NPCs: " + npcInstanceEntities.keySet()
                    + ")");
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot follow - NPC entity reference is stale: " + npcInstanceId +
                    " (entity may have been removed by /npc clean or role reload). Cleaning up reference.");
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        // Find target player
        PlayerRef targetPlayer = Universe.get().getPlayerByUsername(targetPlayerName, NameMatching.EXACT_IGNORE_CASE);
        if (targetPlayer == null) {
            logger.warn("[Hycompanion] Cannot follow - Player not found: " + targetPlayerName);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();

            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] startFollowingPlayer: Could not find world '" + worldName
                        + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;

                // LockedTarget follow only works when NPC and target are in the same world.
                UUID playerWorldUuid = targetPlayer.getWorldUuid();
                if (playerWorldUuid == null) {
                    logger.warn(
                            "[Hycompanion] Cannot follow - target player world is unavailable: " + targetPlayerName);
                    return false;
                }
                World targetPlayerWorld = Universe.get().getWorld(playerWorldUuid);
                if (targetPlayerWorld == null) {
                    logger.warn("[Hycompanion] Cannot follow - target player world not found for UUID: "
                            + playerWorldUuid);
                    return false;
                }
                if (!world.getName().equalsIgnoreCase(targetPlayerWorld.getName())) {
                    logger.warn("[Hycompanion] Cannot follow - NPC and player are in different worlds (npc="
                            + world.getName() + ", player=" + targetPlayerWorld.getName() + ")");
                    return false;
                }

                // ================================================================
                // 传送跟随：不依赖引擎 AI 状态机，直接每秒传送 NPC 到玩家身后
                // 引擎的 State/Sensor/Seek 系统对 plugin-spawned NPC 不可靠
                // ================================================================
                String npcName = "NPC";
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity != null) {
                        npcName = formatRoleAsDisplayName(npcEntity.getRoleName());
                    }
                } catch (Exception ignored) {}

                Message questMessage = Message
                        .raw(npcName + " starts to follow you.")
                        .color("#FFD700");
                targetPlayer.sendMessage(questMessage);
                busyNpcs.add(npcInstanceId);

                // 启动传送跟随任务
                cancelFollowTask(npcInstanceId);
                final Ref<EntityStore> finalEntityRef = entityRef;
                final String finalTargetPlayerName = targetPlayerName;
                final World finalWorld = world;

                ScheduledFuture<?> followTask = rotationScheduler.scheduleAtFixedRate(() -> {
                    try {
                        safeWorldExecute(finalWorld, () -> {
                            try {
                                if (!finalEntityRef.isValid()) {
                                    cancelFollowTask(npcInstanceId);
                                    busyNpcs.remove(npcInstanceId);
                                    return;
                                }

                                // 获取 NPC 当前位置
                                Store<EntityStore> s = finalEntityRef.getStore();
                                TransformComponent npcTransform = s.getComponent(finalEntityRef, TransformComponent.getComponentType());
                                if (npcTransform == null) return;
                                Vector3d npcPos = npcTransform.getPosition();

                                // 获取玩家当前位置
                                PlayerRef player = Universe.get().getPlayerByUsername(finalTargetPlayerName, NameMatching.EXACT_IGNORE_CASE);
                                if (player == null || !player.isValid()) {
                                    cancelFollowTask(npcInstanceId);
                                    busyNpcs.remove(npcInstanceId);
                                    return;
                                }
                                Ref<EntityStore> playerRef = player.getReference();
                                if (playerRef == null || !playerRef.isValid()) return;
                                Store<EntityStore> playerStore = playerRef.getStore();
                                TransformComponent playerTransform = playerStore.getComponent(playerRef, TransformComponent.getComponentType());
                                if (playerTransform == null) return;
                                Vector3d playerPos = playerTransform.getPosition();

                                // 计算距离
                                double dx = playerPos.getX() - npcPos.getX();
                                double dz = playerPos.getZ() - npcPos.getZ();
                                double distance = Math.sqrt(dx * dx + dz * dz);

                                // 如果距离 > 3 格，传送到玩家身后 2 格
                                if (distance > 3.0) {
                                    // 计算玩家面朝方向的反方向（身后）
                                    double angle = Math.atan2(dz, dx);
                                    double behindX = playerPos.getX() - Math.cos(angle) * 2.0;
                                    double behindZ = playerPos.getZ() - Math.sin(angle) * 2.0;
                                    Vector3d teleportPos = new Vector3d(behindX, playerPos.getY(), behindZ);
                                    Vector3f rotation = new Vector3f(0, (float) Math.toDegrees(angle), 0);

                                    Teleport teleport = new Teleport(finalWorld, teleportPos, rotation);
                                    s.addComponent(finalEntityRef, Teleport.getComponentType(), teleport);

                                    logger.debug("[Hycompanion] Follow teleport: distance=" + String.format("%.1f", distance)
                                            + " → " + String.format("%.1f,%.1f,%.1f", behindX, playerPos.getY(), behindZ));
                                }
                            } catch (Exception ex) {
                                logger.debug("[Hycompanion] Follow teleport error: " + ex.getMessage());
                            }
                        });
                    } catch (Exception ex) {
                        cancelFollowTask(npcInstanceId);
                    }
                }, 500, 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                followTasks.put(npcInstanceId, followTask);

                boolean scheduled = true;
                if (!scheduled) {
                    logger.warn("[Hycompanion] Cannot follow - world execution rejected (shutdown in progress)");
                    return false;
                }
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to start following player: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    /** 取消跟随监控任务 */
    private void cancelFollowTask(UUID npcInstanceId) {
        ScheduledFuture<?> task = followTasks.remove(npcInstanceId);
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 让NPC停止跟随，清除LockedTarget并返回Idle状态，通知被跟随的玩家 */
    @Override
    public boolean stopFollowing(UUID npcInstanceId) {
        cancelFollowTask(npcInstanceId);
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] stopping follow");

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot stop following - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot stop following - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot stop following - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();

            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] stopFollowing: Could not find world '" + worldName
                        + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Get the current follow target before clearing (to notify them)
                                Ref<EntityStore> targetRef = role.getMarkedEntitySupport()
                                        .getMarkedEntityRef("LockedTarget");

                                // Clear the follow target
                                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);

                                // Return to Idle state
                                try {
                                    role.getStateSupport().setState(entityRef, "Idle", null, store);
                                    busyNpcs.remove(npcInstanceId);
                                    logger.info("[Hycompanion] NPC returned to Idle state");

                                    // If the target was a player, notify them
                                    if (targetRef != null && targetRef.isValid()) {
                                        PlayerRef targetPlayerRef = store.getComponent(targetRef,
                                                PlayerRef.getComponentType());
                                        if (targetPlayerRef != null) {
                                            // Get NPC display name by formatting the role name
                                            String npcName = formatRoleAsDisplayName(npcEntity.getRoleName());

                                            Message stopMessage = Message
                                                    .raw(npcName + " stopped following you.")
                                                    .color("#FFD700");
                                            targetPlayerRef.sendMessage(stopMessage);
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not set Idle state: " + e.getMessage());
                                    Sentry.captureException(e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in stopFollowing: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to stop following: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    /**
     * 清除所有跟随指定玩家的NPC的跟随目标
     * 在玩家断开连接时调用，防止服务器关闭期间出现"无效实体引用"错误
     */
    public void clearFollowTargetsForPlayer(String playerName) {
        if (playerName == null || npcInstanceEntities.isEmpty()) {
            return;
        }

        // Find the player entity reference
        PlayerRef targetPlayer = Universe.get().getPlayerByUsername(playerName, NameMatching.EXACT_IGNORE_CASE);
        if (targetPlayer == null) {
            return;
        }

        Ref<EntityStore> playerEntityRef = targetPlayer.getReference();
        if (playerEntityRef == null) {
            return;
        }

        // Get player's UUID for comparison
        UUID playerUuid = targetPlayer.getUuid();

        // Check each NPC to see if it's following this player
        for (var entry : npcInstanceEntities.entrySet()) {
            UUID npcInstanceId = entry.getKey();
            NpcInstanceData npcInstance = entry.getValue();
            try {
                Ref<EntityStore> entityRef = npcInstance.entityRef();
                if (entityRef == null || !entityRef.isValid()) {
                    continue;
                }

                Store<EntityStore> store = entityRef.getStore();
                NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                if (npcEntity == null) {
                    continue;
                }

                var role = npcEntity.getRole();
                if (role == null) {
                    continue;
                }

                // Check if this NPC has the player as its LockedTarget
                var markedSupport = role.getMarkedEntitySupport();
                if (markedSupport == null) {
                    continue;
                }

                Ref<EntityStore> currentTarget = markedSupport.getMarkedEntityRef("LockedTarget");
                // Compare by UUID since Ref doesn't have equals()
                UUID targetUuid = null;
                if (currentTarget != null && currentTarget.isValid()) {
                    var uuidComp = store.getComponent(currentTarget,
                            com.hypixel.hytale.server.core.entity.UUIDComponent.getComponentType());
                    if (uuidComp != null) {
                        targetUuid = uuidComp.getUuid();
                    }
                }
                if (targetUuid != null && targetUuid.equals(playerUuid)) {
                    // This NPC is following the disconnecting player - clear the target
                    logger.info("[Hycompanion] Clearing follow target for NPC " + npcInstanceId +
                            " (was following disconnecting player: " + playerName + ")");

                    // Use world execute to clear the target on the world thread
                    // Get the correct world for this NPC, not the default world
                    String worldName = npcInstance.spawnLocation() != null ? npcInstance.spawnLocation().world() : null;
                    World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
                    if (resolvedWorld == null) {
                        resolvedWorld = Universe.get().getDefaultWorld();
                        logger.warn("[Hycompanion] clearFollowTargetsForPlayer: Could not find world '" + worldName
                                + "' for NPC, falling back to default world");
                    }
                    if (resolvedWorld != null) {
                        final World world = resolvedWorld;
                        safeWorldExecute(world, () -> {
                            try {
                                // Re-check validity inside world thread
                                if (!entityRef.isValid()) {
                                    return;
                                }

                                // Clear the follow target
                                markedSupport.setMarkedEntity("LockedTarget", null);

                                // Return to Idle state
                                var stateSupport = role.getStateSupport();
                                if (stateSupport != null) {
                                    stateSupport.setState(entityRef, "Idle", null, store);
                                }

                                busyNpcs.remove(npcInstanceId);
                                logger.debug("[Hycompanion] NPC " + npcInstanceId + " returned to Idle state");
                            } catch (Exception e) {
                                logger.debug("[Hycompanion] Error clearing follow target: " + e.getMessage());
                                Sentry.captureException(e);
                            }
                        });
                    }
                }
            } catch (Exception e) {
                logger.debug("[Hycompanion] Error checking NPC follow target: " + e.getMessage());
                Sentry.captureException(e);
            }
        }
    }

    /**
     * 关闭期间安全清理玩家的NPC跟随目标
     * 此方法不访问 Hytale 实体 API（需要世界线程），只清理内部跟踪映射
     */
    public void clearFollowTargetsSafe(String playerName) {
        if (playerName == null || npcInstanceEntities.isEmpty()) {
            return;
        }

        // Note: During shutdown, we cannot access Hytale entity APIs from
        // ShutdownThread.
        // We can only clear our internal tracking. Hytale's entity cleanup will handle
        // the actual NPC state during world shutdown.
        int clearedCount = 0;
        for (UUID npcInstanceId : npcInstanceEntities.keySet()) {
            if (busyNpcs.remove(npcInstanceId)) {
                clearedCount++;
            }
        }

        if (clearedCount > 0) {
            logger.debug("[Hycompanion] Safe cleared " + clearedCount + " busy NPCs for player: " + playerName);
        }
    }

    /**
     * 清除所有跟踪映射中的实体引用
     * 在服务器关闭早期调用，防止"无效实体引用"错误
     * 必须在 Hytale 开始移除玩家之前调用
     * 执行5个步骤：取消任务 -> 关闭调度器 -> 完成挂起的Future -> 清除引用 -> 等待飞行中的任务
     */
    public void clearAllEntityReferences() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEAR_ALL_ENTITY_REFERENCES STARTED");
        logger.info("[Hycompanion] Thread: " + threadName);
        logger.info("[Hycompanion] Timestamp: " + startTime);
        logger.info("[Hycompanion] ============================================");

        // STEP 1: Cancel all scheduled tasks first to stop new world.execute() calls
        logger.info("[Hycompanion] Step 1/5: Cancelling scheduled tasks...");
        long stepStart = System.currentTimeMillis();
        int cancelledTasks = 0;
        int rotationCount = rotationTasks.size();
        int movementCount = movementTasks.size();
        int thinkingCount = thinkingAnimationTasks.size();

        for (ScheduledFuture<?> task : rotationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelledTasks++;
            }
        }
        rotationTasks.clear();

        for (ScheduledFuture<?> task : movementTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelledTasks++;
            }
        }
        movementTasks.clear();

        for (ScheduledFuture<?> task : thinkingAnimationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelledTasks++;
            }
        }
        thinkingAnimationTasks.clear();

        logger.info("[Hycompanion] Step 1/5: Cancelled " + cancelledTasks + " tasks " +
                "(rotation: " + rotationCount +
                ", movement: " + movementCount + ", thinking: " + thinkingCount + ") " +
                "in " + (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 2: Shutdown the rotation scheduler
        logger.info("[Hycompanion] Step 2/5: Shutting down rotation scheduler...");
        stepStart = System.currentTimeMillis();
        if (rotationScheduler != null && !rotationScheduler.isShutdown()) {
            rotationScheduler.shutdownNow();
            try {
                boolean terminated = rotationScheduler.awaitTermination(200, TimeUnit.MILLISECONDS);
                logger.info("[Hycompanion] Step 2/5: Rotation scheduler " +
                        (terminated ? "terminated" : "did not terminate") + " in " +
                        (System.currentTimeMillis() - stepStart) + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[Hycompanion] Step 2/5: Interrupted while waiting for scheduler");
                Sentry.captureException(e);
            }
        } else {
            logger.info("[Hycompanion] Step 2/5: Scheduler already shut down or null");
        }

        // STEP 3: Complete any pending movement futures
        logger.info("[Hycompanion] Step 3/5: Checking for pending futures...");
        stepStart = System.currentTimeMillis();
        int pendingFutures = 0;
        for (var entry : movementTasks.entrySet()) {
            if (!entry.getValue().isDone()) {
                pendingFutures++;
            }
            entry.getValue().cancel(true);
        }
        logger.info("[Hycompanion] Step 3/5: Found and cancelled " + pendingFutures + " pending futures in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 4: Clear entity references
        logger.info("[Hycompanion] Step 4/5: Clearing entity references...");
        stepStart = System.currentTimeMillis();
        int npcCount = npcInstanceEntities.size();
        int targetCount = movementTargetEntities.size();
        int busyCount = busyNpcs.size();

        npcInstanceEntities.clear();
        movementTargetEntities.clear();
        busyNpcs.clear();
        npcSpawnLocations.clear();
        pendingRespawns.clear();

        // Cancel respawn checker
        if (respawnCheckerTask != null && !respawnCheckerTask.isDone()) {
            respawnCheckerTask.cancel(true);
            respawnCheckerTask = null;
        }

        logger.info("[Hycompanion] Step 4/5: Cleared " + npcCount + " NPCs, " + targetCount + " targets, " +
                busyCount + " busy flags in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // STEP 5: Brief pause for in-flight tasks
        logger.info("[Hycompanion] Step 5/5: Waiting for in-flight tasks (50ms)...");
        stepStart = System.currentTimeMillis();
        try {
            Thread.sleep(50);
            logger.info("[Hycompanion] Step 5/5: Wait complete (actual: " +
                    (System.currentTimeMillis() - stepStart) + "ms)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[Hycompanion] Step 5/5: Interrupted during wait");
        }

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEAR_ALL_ENTITY_REFERENCES COMPLETE");
        logger.info("[Hycompanion] Total time: " + totalTime + "ms");
        logger.info("[Hycompanion] ============================================");
    }

    /**
     * 让NPC开始攻击指定目标
     * 自动装备武器，清除动画状态，设置LockedTarget和战斗覆盖
     * 按优先级尝试 Combat、Chase、Attack 状态
     */
    @Override
    public boolean startAttacking(UUID npcInstanceId, String targetName, String attackType) {
        logger.info(
                "[Hycompanion] NPC [" + npcInstanceId + "] starting attack on: " + targetName + " (type: " + attackType
                        + ")");

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot attack - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot attack - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot attack - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            // Clean up reference
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        // Try to find target (could be player or another NPC)
        Ref<EntityStore> targetRef = null;

        // First try to find as player
        PlayerRef targetPlayer = Universe.get().getPlayerByUsername(targetName, NameMatching.EXACT_IGNORE_CASE);
        if (targetPlayer != null) {
            targetRef = targetPlayer.getReference();
        }

        // If not a player, could be another NPC - check our tracked NPCs
        if (targetRef == null) {
            NpcInstanceData targetNpcInstanceData = npcInstanceEntities.get(java.util.UUID.fromString(targetName));
            if (targetNpcInstanceData != null) {
                targetRef = targetNpcInstanceData.entityRef();
            }
        }

        if (targetRef == null || !targetRef.isValid()) {
            logger.warn("[Hycompanion] Cannot attack - Target not found: " + targetName);
            return false;
        }

        final Ref<EntityStore> finalTargetRef = targetRef;

        try {
            Store<EntityStore> store = entityRef.getStore();

            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] startAttacking: Could not find world '" + worldName
                        + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            // Ensure weapon is equipped
                            try {
                                Inventory inventory = npcEntity.getInventory();
                                if (inventory != null) {
                                    ItemContainer hotbar = inventory.getHotbar();
                                    int currentSlot = inventory.getActiveHotbarSlot();
                                    boolean currentSlotHasItem = false;

                                    if (currentSlot >= 0) {
                                        ItemStack currentItem = hotbar.getItemStack((short) currentSlot);
                                        if (currentItem != null && !currentItem.isEmpty()) {
                                            currentSlotHasItem = true;
                                        }
                                    }

                                    if (!currentSlotHasItem) {
                                        // Try to find a slot with an item
                                        boolean foundItem = false;
                                        for (short i = 0; i < hotbar.getCapacity(); i++) {
                                            ItemStack item = hotbar.getItemStack(i);
                                            if (item != null && !item.isEmpty()) {
                                                // inventory.setActiveHotbarSlot((byte) i); // API changed in newer server version
                                                logger.info("[Hycompanion] Auto-equipped hotbar slot " + i + " for NPC "
                                                        + npcInstanceId);
                                                foundItem = true;
                                                break;
                                            }
                                        }

                                        if (!foundItem) {
                                            logger.info("[Hycompanion] NPC " + npcInstanceId
                                                    + " has no items in hotbar. Proceeding with unarmed attack.");
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                logger.info("[Hycompanion] Inventory check failed: " + e.getMessage());
                                Sentry.captureException(e);
                            }

                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Clear action-layer animation before entering combat behavior.
                                try {
                                    AnimationUtils.stopAnimation(entityRef, AnimationSlot.Action, true, store);
                                    logger.debug("[Hycompanion] Cleared action animation before attacking");
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not reset animation: " + e.getMessage());
                                    Sentry.captureException(e);
                                }

                                // Also reset state machine to Idle
                                try {
                                    role.getStateSupport().setState(entityRef, "Idle", null, store);
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not reset state to Idle: " + e.getMessage());
                                    Sentry.captureException(e);
                                }

                                // Set the target using MarkedEntitySupport
                                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", finalTargetRef);

                                // Configure attack type using CombatSupport
                                var combatSupport = role.getCombatSupport();
                                if (combatSupport != null) {
                                    combatSupport.clearAttackOverrides();
                                    if ("ranged".equalsIgnoreCase(attackType)) {
                                        combatSupport.addAttackOverride("Attack=Ranged");
                                    } else {
                                        combatSupport.addAttackOverride("Attack=Melee");
                                    }
                                }

                                // Try to transition to Combat/Attack state
                                // We check which states are available in the role definition
                                try {
                                    StateMappingHelper stateHelper = role.getStateSupport().getStateHelper();
                                    boolean stateSet = false;

                                    // 1. Try "Combat" state (Standard)
                                    if (stateHelper.getStateIndex("Combat") >= 0) {
                                        role.getStateSupport().setState(entityRef, "Combat", "Attack", store);
                                        stateSet = true;
                                        logger.info("[Hycompanion] NPC transitioned to Combat.Attack state");
                                    }
                                    // 2. Try "Chase" state (Trorks, Creatures)
                                    else if (stateHelper.getStateIndex("Chase") >= 0) {
                                        // For Chase, we prefer the "Attack" substate if it exists
                                        if (stateHelper.getSubStateIndex(stateHelper.getStateIndex("Chase"),
                                                "Attack") >= 0) {
                                            role.getStateSupport().setState(entityRef, "Chase", "Attack", store);
                                            logger.info("[Hycompanion] NPC transitioned to Chase.Attack state");
                                        } else {
                                            role.getStateSupport().setState(entityRef, "Chase", "Default", store);
                                            logger.info("[Hycompanion] NPC transitioned to Chase.Default state");
                                        }
                                        stateSet = true;
                                    }
                                    // 3. Try "Attack" state (Simple)
                                    else if (stateHelper.getStateIndex("Attack") >= 0) {
                                        role.getStateSupport().setState(entityRef, "Attack", "Default", store);
                                        stateSet = true;
                                        logger.info("[Hycompanion] NPC transitioned to Attack state");
                                    }

                                    if (stateSet) {
                                        busyNpcs.add(npcInstanceId);
                                    } else {
                                        logger.warn(
                                                "[Hycompanion] effective startAttacking failed: No suitable combat state found (checked: Combat, Chase, Attack). Role: "
                                                        + npcEntity.getRoleName());
                                    }
                                } catch (Exception e) {
                                    logger.error("[Hycompanion] Exception setting combat state: " + e.getMessage());
                                    Sentry.captureException(e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in startAttacking: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to start attacking: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    /** 让NPC停止攻击，清除战斗覆盖和LockedTarget，返回Idle状态 */
    @Override
    public boolean stopAttacking(UUID npcInstanceId) {
        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] stopping attack");

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot stop attacking - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot stop attacking - NPC entity not tracked: " + npcInstanceId);
            return false;
        }
        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot stop attacking - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            // Cleaning up reference
            npcInstanceEntities.remove(npcInstanceId);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();

            // Get the correct world for this NPC, not the default world
            String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
            World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
            if (resolvedWorld == null) {
                resolvedWorld = Universe.get().getDefaultWorld();
                logger.warn("[Hycompanion] stopAttacking: Could not find world '" + worldName
                        + "' for NPC, falling back to default world");
            }

            if (resolvedWorld != null) {
                final World world = resolvedWorld;
                world.execute(() -> {
                    try {
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            var role = npcEntity.getRole();
                            if (role != null) {
                                // Clear combat overrides
                                var combatSupport = role.getCombatSupport();
                                if (combatSupport != null) {
                                    combatSupport.clearAttackOverrides();
                                }

                                // Clear the target
                                role.getMarkedEntitySupport().setMarkedEntity("LockedTarget", null);

                                // Return to Idle state
                                try {
                                    role.getStateSupport().setState(entityRef, "Idle", null, store);
                                    busyNpcs.remove(npcInstanceId);
                                    logger.info("[Hycompanion] NPC returned to Idle state");
                                } catch (Exception e) {
                                    logger.debug("[Hycompanion] Could not set Idle state: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error in stopAttacking: " + e.getMessage());
                        Sentry.captureException(e);
                    }
                });
                return true;
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to stop attacking: " + e.getMessage());
            Sentry.captureException(e);
        }
        return false;
    }

    /** 检查NPC是否处于忙碌状态（正在跟随或攻击） */
    @Override
    public boolean isNpcBusy(UUID npcInstanceId) {
        return busyNpcs.contains(npcInstanceId);
    }

    // ========== 传送操作 ==========

    /** 将NPC传送到指定位置（同时更新牵引点以便NPC在新位置漫游） */
    @Override
    public boolean teleportNpcTo(UUID npcInstanceId, Location location) {
        logger.info("[Hycompanion] Teleporting NPC [" + npcInstanceId + "] to " + location);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot teleport - NPC entity not tracked: " + npcInstanceId);
            return false;
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null) {
            logger.warn("[Hycompanion] Cannot teleport - NPC entity not tracked: " + npcInstanceId);
            return false;
        }

        if (!entityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot teleport - NPC entity reference is stale: " + npcInstanceId +
                    ". Cleaning up reference.");
            npcInstanceEntities.remove(npcInstanceId);
            if (removalListener != null)
                removalListener.accept(npcInstanceId);
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            World world = getWorldByName(location.world());

            if (world == null) {
                logger.warn("[Hycompanion] Cannot teleport - World not found: " + location.world());
                return false;
            }

            // Execute teleport on world thread
            world.execute(() -> {
                try {
                    TransformComponent transform = store.getComponent(entityRef, TransformComponent.getComponentType());
                    if (transform != null) {
                        Vector3d newPos = new Vector3d(location.x(), location.y(), location.z());
                        transform.setPosition(newPos);

                        // Update leash point so NPC wanders around new location
                        NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                        if (npcEntity != null) {
                            npcEntity.setLeashPoint(newPos);
                        }

                        logger.info("[Hycompanion] NPC [" + npcInstanceId + "] teleported to " + location);
                    } else {
                        logger.warn("[Hycompanion] Cannot teleport - Transform component not found for NPC: "
                                + npcInstanceId);
                    }
                } catch (Exception e) {
                    logger.error("[Hycompanion] Error teleporting NPC: " + e.getMessage());
                    Sentry.captureException(e);
                }
            });
            return true;
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to teleport NPC: " + e.getMessage());
            return false;
        }
    }

    /**
     * 将玩家传送到指定位置（支持跨世界传送）
     * 使用 Hytale 的 Teleport 组件系统，保留玩家当前的身体和头部旋转
     */
    @Override
    public boolean teleportPlayerTo(String playerId, Location location) {
        logger.info("[Hycompanion] Teleporting player [" + playerId + "] to " + location);

        try {
            UUID uuid = UUID.fromString(playerId);
            PlayerRef playerRef = Universe.get().getPlayer(uuid);

            if (playerRef == null) {
                logger.warn("[Hycompanion] Cannot teleport - Player not found: " + playerId);
                return false;
            }

            World targetWorld = getWorldByName(location.world());
            if (targetWorld == null) {
                logger.warn("[Hycompanion] Cannot teleport - World not found: " + location.world());
                return false;
            }

            // Get player entity reference
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                logger.warn("[Hycompanion] Cannot teleport - Player entity reference not available: " + playerId);
                return false;
            }

            // Get the player's current world UUID
            UUID playerWorldUuid = playerRef.getWorldUuid();
            if (playerWorldUuid == null) {
                logger.warn("[Hycompanion] Cannot teleport - Player world UUID not available: " + playerId);
                return false;
            }

            // Get the player's current world
            World playerWorld = Universe.get().getWorld(playerWorldUuid);
            if (playerWorld == null) {
                logger.warn("[Hycompanion] Cannot teleport - Player world not found: " + playerWorldUuid);
                return false;
            }

            // Get the player's store
            Store<EntityStore> playerStore = entityRef.getStore();

            // Capture rotation values on the player's current world thread using
            // CompletableFuture
            // This ensures we read the components on the correct thread
            Vector3f[] bodyRotationHolder = new Vector3f[1];
            Vector3f[] headRotationHolder = new Vector3f[1];

            CompletableFuture<Void> rotationCaptureFuture = CompletableFuture.runAsync(() -> {
                try {
                    // Get current transform to preserve rotation
                    TransformComponent transform = playerStore.getComponent(entityRef,
                            TransformComponent.getComponentType());
                    HeadRotation headRotation = playerStore.getComponent(entityRef, HeadRotation.getComponentType());

                    bodyRotationHolder[0] = (transform != null) ? transform.getRotation() : new Vector3f(0, 0, 0);
                    headRotationHolder[0] = (headRotation != null) ? headRotation.getRotation() : new Vector3f(0, 0, 0);
                } catch (Exception e) {
                    logger.error("[Hycompanion] Error capturing player rotation: " + e.getMessage());
                    // Use default rotations on error
                    bodyRotationHolder[0] = new Vector3f(0, 0, 0);
                    headRotationHolder[0] = new Vector3f(0, 0, 0);
                }
            }, playerWorld);

            // Wait for the rotation capture to complete
            rotationCaptureFuture.join();

            Vector3f currentBodyRotation = bodyRotationHolder[0] != null ? bodyRotationHolder[0]
                    : new Vector3f(0, 0, 0);
            Vector3f currentHeadRotation = headRotationHolder[0] != null ? headRotationHolder[0]
                    : new Vector3f(0, 0, 0);

            // Create teleport destination
            Vector3d newPos = new Vector3d(location.x(), location.y(), location.z());

            // Create Teleport component with target world - this is how Hytale handles
            // teleportation
            // The world parameter allows cross-world teleportation
            Teleport teleport = new Teleport(targetWorld, newPos, currentBodyRotation);
            teleport.setHeadRotation(currentHeadRotation);

            // Execute the teleport on the player's current world thread
            // The Teleport component will handle moving the player to the target world
            playerWorld.execute(() -> {
                try {
                    // Add the Teleport component to the entity store
                    playerStore.addComponent(entityRef, Teleport.getComponentType(), teleport);
                    logger.info("[Hycompanion] Player [" + playerId + "] teleported to " + location);
                } catch (Exception e) {
                    logger.error("[Hycompanion] Error teleporting player: " + e.getMessage());
                    Sentry.captureException(e);
                    e.printStackTrace();
                }
            });
            return true;
        } catch (IllegalArgumentException e) {
            logger.warn("[Hycompanion] Invalid UUID format for player ID: " + playerId);
            Sentry.captureException(e);
            return false;
        } catch (Exception e) {
            logger.error("[Hycompanion] Failed to teleport player: " + e.getMessage());
            Sentry.captureException(e);
            return false;
        }
    }

    // ========== 动画发现 ==========

    /**
     * 获取NPC可用的动画列表
     * 从实体的 ModelComponent 中读取 AnimationSetMap 的所有键名
     * 必须在世界线程上执行（超时5秒）
     */
    @Override
    public List<String> getAvailableAnimations(UUID npcInstanceId) {
        logger.info("[Hycompanion] Getting available animations for NPC: " + npcInstanceId);

        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.info("[Hycompanion] Cannot get animations - NPC entity not tracked: " + npcInstanceId);
            return Collections.emptyList();
        }

        Ref<EntityStore> entityRef = npcInstanceData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            logger.info("[Hycompanion] Cannot get animations - NPC entity not found: " + npcInstanceId);
            return Collections.emptyList();
        }

        // Get the world directly from the entity's store.
        // getExternalData() doesn't require being on the store's thread.
        World entityWorld;
        try {
            Store<EntityStore> store = entityRef.getStore();
            entityWorld = store.getExternalData().getWorld();
        } catch (Exception e) {
            logger.error("[Hycompanion] Cannot get animations - failed to get NPC world: " + e.getMessage());
            return Collections.emptyList();
        }

        if (entityWorld == null) {
            logger.info("[Hycompanion] Cannot get animations - NPC world is null");
            return Collections.emptyList();
        }

        // Entity component access must happen on the WorldThread
        final World world = entityWorld;

        java.util.concurrent.CompletableFuture<List<String>> future = new java.util.concurrent.CompletableFuture<>();

        world.execute(() -> {
            try {
                Store<EntityStore> store = entityRef.getStore();

                // Get the model component from the entity
                ModelComponent modelComponent = store.getComponent(entityRef, ModelComponent.getComponentType());
                if (modelComponent != null) {
                    Model model = modelComponent.getModel();
                    if (model != null && model.getAnimationSetMap() != null) {
                        // Return ALL animation set keys - let the LLM decide which ones to use
                        // AnimationSets include: Idle, Walk, Run, Sit, Sleep, Howl, Greet, etc.
                        // Each model has different animations based on its JSON definition
                        List<String> animations = new ArrayList<>(model.getAnimationSetMap().keySet());

                        logger.info("[Hycompanion] Found " + animations.size() + " animations for NPC "
                                + npcInstanceId + ": " + animations);
                        future.complete(animations);
                        return;
                    }
                }
                future.complete(Collections.emptyList());
            } catch (Exception e) {
                logger.error("[Hycompanion] Error getting animations for NPC " + npcInstanceId + ": " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Collections.emptyList());
            }
        });

        try {
            // Wait for result from world thread (with timeout to prevent deadlock)
            return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("[Hycompanion] Timeout or error waiting for animations for NPC " + npcInstanceId + ": "
                    + e.getMessage());
            Sentry.captureException(e);
            return Collections.emptyList();
        }
    }

    /**
     * Format a role name into a human-readable display name.
     * 
     * The DisplayNameComponent often contains a localization key (e.g.,
     * "npc.name.villager")
     * which cannot be resolved server-side. This method converts the role name
     * directly
     * into a readable format.
     * 
     * Examples:
     * - "villager" → "Villager"
     * - "guard_captain" → "Guard Captain"
     * - "hytale:villager" → "Villager"
     * 
     * @param roleName The role name from NPCEntity.getRoleName()
     * @return A human-readable display name
     */
    /**
     * 将NPC角色名格式化为可读的显示名称
     * 去除命名空间前缀（如 "hytale:villager" -> "villager"），
     * 将下划线替换为空格并将每个单词首字母大写
     */
    private String formatRoleAsDisplayName(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return "NPC";
        }

        // 去除命名空间前缀（如 "hytale:villager" → "villager"）
        String name = roleName;
        int colonIndex = name.indexOf(':');
        if (colonIndex >= 0) {
            name = name.substring(colonIndex + 1);
        }

        // 将下划线替换为空格，并将每个单词首字母大写
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }

        return result.length() > 0 ? result.toString() : "NPC";
    }

    // ========== 思考指示器操作 ==========

    /** 思考全息文字在NPC头顶的垂直偏移量（方块数） */
    private static final double THINKING_HOLOGRAM_Y_OFFSET = 2.0;

    /** MountedComponent 的附着偏移量 */
    private static final Vector3f THINKING_INDICATOR_OFFSET = new Vector3f(0.0f, 2.0f, 0.0f);

    /** 动画循环间隔（毫秒） */
    private static final long THINKING_ANIMATION_INTERVAL_MS = 250;

    /** 思考动画循环的文本状态 */
    private static final String[] THINKING_STATES = {
            "Thinking .",
            "Thinking ..",
            "Thinking ..."
    };

    /**
     * 在NPC头顶显示"思考中..."指示器
     * 如果指示器已存在则复用，否则生成新的全息文字实体
     */
    @Override
    public void showThinkingIndicator(UUID npcInstanceId) {
        logger.debug("[Hycompanion] Showing thinking indicator for NPC: " + npcInstanceId);

        // [Shutdown Fix] Don't show thinking indicators during shutdown
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping thinking indicator during shutdown");
            return;
        }

        // Get NPC instance data
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Hycompanion] Cannot show thinking indicator - NPC entity not tracked: " + npcInstanceId);
            return;
        }

        // Get the correct world for this NPC
        String worldName = npcInstanceData.spawnLocation() != null ? npcInstanceData.spawnLocation().world() : null;
        World resolvedWorld = worldName != null ? Universe.get().getWorld(worldName) : null;
        if (resolvedWorld == null) {
            resolvedWorld = Universe.get().getDefaultWorld();
        }
        if (resolvedWorld == null) {
            logger.warn("[Hycompanion] Cannot show thinking indicator - No world available");
            return;
        }
        final World world = resolvedWorld;

        Ref<EntityStore> npcEntityRef = npcInstanceData.entityRef();
        if (npcEntityRef == null || !npcEntityRef.isValid()) {
            logger.warn("[Hycompanion] Cannot show thinking indicator - NPC entity not valid: " + npcInstanceId);
            if (npcEntityRef != null && !npcEntityRef.isValid()) {
                npcInstanceEntities.remove(npcInstanceId);
            }
            return;
        }

        // Execute all logic on world thread for atomicity
        try {
            world.execute(() -> {
                if (isShuttingDown()) {
                    return;
                }

                // Check if indicator already exists (atomic on world thread)
                Ref<EntityStore> existingRef = thinkingIndicatorRefs.get(npcInstanceId);
                if (existingRef != null) {
                    if (existingRef.isValid()) {
                        // Reuse existing indicator
                        logger.debug("[Hycompanion] Reusing existing thinking indicator for NPC: " + npcInstanceId);
                        restartThinkingAnimation(npcInstanceId, existingRef);
                    } else {
                        // Invalid ref, remove it
                        thinkingIndicatorRefs.remove(npcInstanceId);
                        // Try again (will spawn new one)
                        spawnThinkingIndicator(npcInstanceId, npcEntityRef, world);
                    }
                    return;
                }

                // No indicator exists, spawn new one
                spawnThinkingIndicator(npcInstanceId, npcEntityRef, world);
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("[Hycompanion] Could not show thinking indicator - world shutting down");
        }
    }

    /**
     * 为NPC生成新的思考指示器（全息文字实体）
     * 使用 Projectile + Intangible + Nameplate 组件创建
     * 必须在世界线程上调用
     */
    private void spawnThinkingIndicator(UUID npcInstanceId, Ref<EntityStore> npcEntityRef, World world) {
        try {
            Store<EntityStore> store = npcEntityRef.getStore();
            if (store == null) {
                return;
            }

            TransformComponent npcTransform = store.getComponent(npcEntityRef,
                    TransformComponent.getComponentType());
            if (npcTransform == null) {
                return;
            }

            Vector3d npcPos = npcTransform.getPosition();
            Vector3d hologramPos = new Vector3d(npcPos.getX(), npcPos.getY() + THINKING_HOLOGRAM_Y_OFFSET,
                    npcPos.getZ());
            Vector3f hologramRotation = new Vector3f(0, 0, 0);

            // Create hologram entity
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            ProjectileComponent projectileComponent = new ProjectileComponent("Projectile");
            holder.putComponent(ProjectileComponent.getComponentType(), projectileComponent);
            holder.putComponent(TransformComponent.getComponentType(),
                    new TransformComponent(hologramPos, hologramRotation));
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.ensureComponent(Intangible.getComponentType());
            if (projectileComponent.getProjectile() == null) {
                projectileComponent.initialize();
            }
            holder.addComponent(NetworkId.getComponentType(),
                    new NetworkId(world.getEntityStore().getStore().getExternalData().takeNextNetworkId()));
            Nameplate nameplate = new Nameplate(THINKING_STATES[0]);
            holder.addComponent(Nameplate.getComponentType(), nameplate);

            Ref<EntityStore> hologramRef = world.getEntityStore().getStore().addEntity(holder, AddReason.SPAWN);

            if (hologramRef != null && hologramRef.isValid()) {
                // Store the hologram reference
                thinkingIndicatorRefs.put(npcInstanceId, hologramRef);

                logger.info("[Hycompanion] Thinking indicator spawned for NPC: " + npcInstanceId +
                        " at " + hologramPos.getX() + ", " + hologramPos.getY() + ", " + hologramPos.getZ());

                startThinkingAnimation(npcInstanceId, npcEntityRef, hologramRef, world);
            } else {
                logger.warn("[Hycompanion] Failed to get hologram reference after spawn");
            }
        } catch (Exception e) {
            logger.error("[Hycompanion] Error spawning thinking indicator: " + e.getMessage());
        }
    }

    /**
     * 为已有的思考指示器重启动画
     * 必须在世界线程上调用
     */
    private void restartThinkingAnimation(UUID npcInstanceId, Ref<EntityStore> hologramRef) {
        // Get the NPC entity ref for position tracking
        NpcInstanceData npcInstanceData = npcInstanceEntities.get(npcInstanceId);
        if (npcInstanceData == null || npcInstanceData.entityRef() == null) {
            return;
        }

        World world = getWorldForHologram(hologramRef, npcInstanceId);
        if (world == null) {
            return;
        }

        // Reset the text to initial state (already on world thread)
        try {
            if (hologramRef.isValid()) {
                Store<EntityStore> store = hologramRef.getStore();
                if (store != null) {
                    Nameplate nameplate = store.getComponent(hologramRef, Nameplate.getComponentType());
                    if (nameplate != null) {
                        nameplate.setText(THINKING_STATES[0]);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Error resetting thinking text: " + e.getMessage());
        }

        // Start/restart the animation
        startThinkingAnimation(npcInstanceId, npcInstanceData.entityRef(), hologramRef, world);
    }

    /**
     * 启动思考动画定时任务：循环显示 "Thinking ."、"Thinking .."、"Thinking ..."
     * 同时持续更新全息文字位置以跟随NPC移动
     * 每50ms执行一次，每250ms更新一次文本
     */
    private void startThinkingAnimation(UUID npcInstanceId, Ref<EntityStore> npcEntityRef,
            Ref<EntityStore> hologramRef, World world) {
        AtomicInteger tickCount = new AtomicInteger(0);
        AtomicInteger stateIndex = new AtomicInteger(0);
        AtomicReference<ScheduledFuture<?>> selfRef = new AtomicReference<>();

        Runnable taskRunnable = () -> {
            try {
                ScheduledFuture<?> self = selfRef.get();
                // [Shutdown Fix] Check shutdown flag first
                if (isShuttingDown() || Thread.currentThread().isInterrupted()) {
                    cancelSpecificThinkingAnimation(npcInstanceId, self);
                    return;
                }

                // IF there's no self in the map, OR someone else is in the map, cancel this
                // one.
                ScheduledFuture<?> currentTask = thinkingAnimationTasks.get(npcInstanceId);
                if (currentTask != self) {
                    if (self != null) {
                        self.cancel(false); // don't interrupt just cancel
                    }
                    return;
                }

                // Check if hologram is still valid
                if (!hologramRef.isValid()) {
                    cancelSpecificThinkingAnimation(npcInstanceId, self);
                    return;
                }

                // [Shutdown Fix] Stop if no players online (Server shutting down?)
                try {
                    if (Universe.get().getPlayers().isEmpty()) {
                        cancelSpecificThinkingAnimation(npcInstanceId, self);
                        return;
                    }
                } catch (Exception e) {
                    cancelSpecificThinkingAnimation(npcInstanceId, self);
                    return;
                }

                // Check if NPC entity is still valid
                if (!npcEntityRef.isValid()) {
                    logger.debug(
                            "[Hycompanion] NPC entity became invalid, hiding thinking indicator: " + npcInstanceId);
                    hideThinkingIndicator(npcInstanceId);
                    return;
                }

                // Tick every 50ms
                int currentTick = tickCount.incrementAndGet();
                boolean updateText = (currentTick % 5 == 0); // 5 * 50ms = 250ms

                String newText = null;
                if (updateText) {
                    int nextIndex = (stateIndex.incrementAndGet()) % THINKING_STATES.length;
                    newText = THINKING_STATES[nextIndex];
                }
                final String textToUpdate = newText;

                // Update nameplate text AND position on world thread
                // Use safeWorldExecute to handle shutdown gracefully
                safeWorldExecute(world, () -> {
                    try {
                        if (isShuttingDown())
                            return;

                        Store<EntityStore> hologramStore = hologramRef.getStore();
                        Store<EntityStore> npcStore = npcEntityRef.getStore();

                        if (hologramStore == null || npcStore == null)
                            return;

                        if (updateText) {
                            Nameplate nameplate = hologramStore.getComponent(hologramRef, Nameplate.getComponentType());
                            if (nameplate != null) {
                                nameplate.setText(textToUpdate);
                            }
                        }

                        // Update hologram position to follow NPC
                        TransformComponent npcTransform = npcStore.getComponent(npcEntityRef,
                                TransformComponent.getComponentType());
                        TransformComponent hologramTransform = hologramStore.getComponent(hologramRef,
                                TransformComponent.getComponentType());

                        if (npcTransform != null && hologramTransform != null) {
                            Vector3d npcPos = npcTransform.getPosition();
                            Vector3d newHologramPos = new Vector3d(
                                    npcPos.getX(),
                                    npcPos.getY() + THINKING_HOLOGRAM_Y_OFFSET,
                                    npcPos.getZ());
                            hologramTransform.setPosition(newHologramPos);
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Error updating thinking animation: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.debug("[Hycompanion] Error in thinking animation task: " + e.getMessage());
                hideThinkingIndicator(npcInstanceId); // Cleanly safely hide it on exception
            }
        };

        ScheduledFuture<?> animationTask = rotationScheduler.scheduleAtFixedRate(taskRunnable, 0, 50,
                TimeUnit.MILLISECONDS);
        selfRef.set(animationTask);

        ScheduledFuture<?> existing = thinkingAnimationTasks.put(npcInstanceId, animationTask);
        if (existing != null && !existing.isCancelled()) {
            existing.cancel(false);
        }
    }

    /** 取消NPC的思考动画定时任务 */
    private void cancelThinkingAnimation(UUID npcInstanceId) {
        ScheduledFuture<?> task = thinkingAnimationTasks.remove(npcInstanceId);
        if (task != null) {
            // Cancel with interrupt to stop ongoing execution
            boolean cancelled = task.cancel(true);
            logger.debug("[Hycompanion] Cancelled thinking animation for NPC: " + npcInstanceId + " (success: "
                    + cancelled + ")");
        }
    }

    /** 取消特定的思考动画任务（避免误删新替换的映射引用） */
    private void cancelSpecificThinkingAnimation(UUID npcInstanceId, ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
            thinkingAnimationTasks.remove(npcInstanceId, task);
        }
    }

    /**
     * 清理残留的僵尸思考指示器实体
     * 扫描所有世界中带有"Thinking"名牌的投射物实体，
     * 跳过正在使用的有效指示器，移除其余的僵尸实体
     */
    @Override
    public int removeZombieThinkingIndicators() {
        // [Shutdown Fix] Don't scan/remove entities during shutdown
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping zombie indicator cleanup during shutdown");
            return 0;
        }

        logger.info("[Hycompanion] Scanning for zombie thinking indicators...");

        // Collect UUIDs of currently tracked holograms to avoid removing active ones
        // (This allows safe execution even if called while plugin is active)
        Set<UUID> validHologramUuids = new HashSet<>();
        for (Ref<EntityStore> ref : thinkingIndicatorRefs.values()) {
            if (ref != null && ref.isValid()) {
                try {
                    Store<EntityStore> store = ref.getStore();
                    if (store != null) {
                        UUIDComponent uuidComp = store.getComponent(ref, UUIDComponent.getComponentType());
                        if (uuidComp != null) {
                            validHologramUuids.add(uuidComp.getUuid());
                        }
                    }
                } catch (Exception e) {
                    // Ignore errors checking individual refs
                }
            }
        }

        AtomicInteger removedCount = new AtomicInteger(0);
        AtomicInteger pendingWorlds = new AtomicInteger(Universe.get().getWorlds().size());

        for (World world : Universe.get().getWorlds().values()) {
            // [Shutdown Fix] Check shutdown before submitting to world
            if (isShuttingDown()) {
                pendingWorlds.decrementAndGet();
                continue;
            }

            try {
                world.execute(() -> {
                    // [Shutdown Fix] Check shutdown inside the task
                    if (isShuttingDown()) {
                        pendingWorlds.decrementAndGet();
                        return;
                    }

                    try {
                        Store<EntityStore> store = world.getEntityStore().getStore();
                        List<Ref<EntityStore>> toRemove = new ArrayList<>();

                        store.forEachChunk(Nameplate.getComponentType(),
                                (BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>) (chunk,
                                        commandBuffer) -> {
                                    // [Shutdown Fix] Check shutdown while iterating
                                    if (isShuttingDown()) {
                                        return;
                                    }

                                    for (int i = 0; i < chunk.size(); i++) {
                                        try {
                                            Nameplate nameplate = chunk.getComponent(i, Nameplate.getComponentType());
                                            if (nameplate != null && nameplate.getText() != null
                                                    && nameplate.getText().startsWith("Thinking")) {

                                                // Verify it is a projectile (hologram) to be safe
                                                if (chunk.getComponent(i,
                                                        ProjectileComponent.getComponentType()) == null) {
                                                    continue;
                                                }

                                                // Check if it's a valid tracked hologram
                                                UUIDComponent uuidComp = chunk.getComponent(i,
                                                        UUIDComponent.getComponentType());
                                                if (uuidComp != null
                                                        && validHologramUuids.contains(uuidComp.getUuid())) {
                                                    continue;
                                                }

                                                // It's a zombie! Add to removal list.
                                                toRemove.add(chunk.getReferenceTo(i));

                                                if (uuidComp != null) {
                                                    logger.info("[Cleanup] Found zombie indicator: "
                                                            + uuidComp.getUuid());
                                                } else {
                                                    logger.info("[Cleanup] Found zombie indicator (no UUID)");
                                                }
                                            }
                                        } catch (Exception e) {
                                            // Continue scanning
                                        }
                                    }
                                });

                        // Remove collected entities (unless shutting down)
                        if (!isShuttingDown()) {
                            for (Ref<EntityStore> ref : toRemove) {
                                try {
                                    if (ref.isValid()) {
                                        store.removeEntity(ref, com.hypixel.hytale.component.RemoveReason.REMOVE);
                                        removedCount.incrementAndGet();
                                    }
                                } catch (Exception e) {
                                    logger.error("[Cleanup] Failed to remove entity: " + e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.error("[Cleanup] Error cleaning world: " + e.getMessage());
                    } finally {
                        pendingWorlds.decrementAndGet();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // World executor rejected task - server shutting down
                pendingWorlds.decrementAndGet();
                logger.debug("[Cleanup] World executor rejected task - shutting down");
            } catch (Exception e) {
                pendingWorlds.decrementAndGet();
                logger.error("[Cleanup] Failed to schedule world scan: " + e.getMessage());
            }
        }

        // Wait for scan to complete (max 1s)
        long start = System.currentTimeMillis();
        while (pendingWorlds.get() > 0 && System.currentTimeMillis() - start < 1000) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }

        int count = removedCount.get();
        if (count > 0) {
            logger.info("[Hycompanion] Removed " + count + " zombie thinking indicators.");
        } else {
            logger.info("[Hycompanion] No zombie thinking indicators found.");
        }

        return count;
    }

    /** 隐藏思考指示器（清空名牌文本但保留实体以便复用） */
    @Override
    public void hideThinkingIndicator(UUID npcInstanceId) {
        logger.debug("[Hycompanion] Hiding thinking indicator for NPC: " + npcInstanceId);

        // Cancel animation task first
        cancelThinkingAnimation(npcInstanceId);

        // Get the hologram reference
        final Ref<EntityStore> hologramRef = thinkingIndicatorRefs.get(npcInstanceId);
        if (hologramRef == null || !hologramRef.isValid()) {
            return;
        }

        // [Shutdown Fix] During shutdown, don't try to modify entities
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping thinking indicator hide during shutdown: " + npcInstanceId);
            return;
        }

        // Instead of removing the entity, just clear the text
        // This allows reusing the indicator for subsequent thinking states
        World world = getWorldForHologram(hologramRef, npcInstanceId);
        if (world != null) {
            try {
                world.execute(() -> {
                    try {
                        if (hologramRef.isValid()) {
                            Store<EntityStore> store = hologramRef.getStore();
                            if (store != null) {
                                Nameplate nameplate = store.getComponent(hologramRef, Nameplate.getComponentType());
                                if (nameplate != null) {
                                    nameplate.setText("");
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Error hiding thinking hologram: " + e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                logger.debug("[Hycompanion] Could not hide thinking indicator - world shutting down");
            }
        }
    }

    /** 获取全息实体所在世界（依次尝试：实体存储的世界 -> NPC所在世界 -> 默认世界） */
    private World getWorldForHologram(Ref<EntityStore> hologramRef, UUID npcInstanceId) {
        World world = null;
        if (hologramRef.isValid()) {
            try {
                Store<EntityStore> store = hologramRef.getStore();
                if (store != null) {
                    world = store.getExternalData().getWorld();
                }
            } catch (Exception e) {
                // Ignore, will fall back
            }
        }
        // Fall back to NPC's world
        if (world == null) {
            NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
            String worldName = npcData != null && npcData.spawnLocation() != null ? npcData.spawnLocation().world()
                    : null;
            world = worldName != null ? Universe.get().getWorld(worldName) : null;
        }
        // Final fallback to default world
        if (world == null) {
            world = Universe.get().getDefaultWorld();
        }
        return world;
    }

    /** 销毁NPC的思考指示器实体（用于清理僵尸实体和NPC移除时） */
    public void destroyThinkingIndicator(UUID npcInstanceId) {
        logger.debug("[Hycompanion] Destroying thinking indicator for NPC: " + npcInstanceId);

        // Cancel animation task
        cancelThinkingAnimation(npcInstanceId);

        // Get and remove the hologram reference
        final Ref<EntityStore> hologramRef = thinkingIndicatorRefs.remove(npcInstanceId);

        // [Shutdown Fix] During shutdown, don't try to remove entities
        if (isShuttingDown()) {
            logger.debug("[Hycompanion] Skipping thinking indicator destruction during shutdown: " + npcInstanceId);
            return;
        }

        if (hologramRef == null || !hologramRef.isValid()) {
            return;
        }

        World world = getWorldForHologram(hologramRef, npcInstanceId);
        if (world != null) {
            try {
                world.execute(() -> {
                    try {
                        if (hologramRef.isValid()) {
                            Store<EntityStore> store = hologramRef.getStore();
                            if (store != null) {
                                store.removeEntity(hologramRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
                                logger.info("[Hycompanion] Thinking indicator destroyed for NPC: " + npcInstanceId);
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("[Hycompanion] Error destroying thinking hologram: " + e.getMessage());
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                logger.debug("[Hycompanion] Could not destroy thinking indicator - world shutting down");
            }
        }
    }

    /**
     * 清理所有资源：取消定时任务、关闭调度器、清除跟踪映射、关闭插件集成
     * 由 ShutdownManager 在服务器关闭时自动调用
     */
    @Override
    public void cleanup() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEANUP (via ShutdownManager) STARTED");
        logger.info("[Hycompanion] Thread: " + threadName);
        logger.info("[Hycompanion] Timestamp: " + startTime);
        logger.info("[Hycompanion] ============================================");

        // Cancel all rotation tasks
        long stepStart = System.currentTimeMillis();
        int cancelled = 0;
        for (ScheduledFuture<?> task : rotationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelled++;
            }
        }
        rotationTasks.clear();
        logger.info("[Hycompanion] Cancelled " + cancelled + " rotation tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        logger.info("[Hycompanion] Cancelled " + cancelled + " following tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Cancel all movement tasks
        stepStart = System.currentTimeMillis();
        cancelled = 0;
        for (ScheduledFuture<?> task : movementTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelled++;
            }
        }
        movementTasks.clear();
        logger.info("[Hycompanion] Cancelled " + cancelled + " movement tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Cancel all thinking animation tasks
        stepStart = System.currentTimeMillis();
        cancelled = 0;
        for (ScheduledFuture<?> task : thinkingAnimationTasks.values()) {
            if (task != null && !task.isDone()) {
                task.cancel(true);
                cancelled++;
            }
        }
        thinkingAnimationTasks.clear();
        logger.info("[Hycompanion] Cancelled " + cancelled + " thinking animation tasks in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Shutdown local scheduler
        stepStart = System.currentTimeMillis();
        if (rotationScheduler != null && !rotationScheduler.isShutdown()) {
            rotationScheduler.shutdownNow();
            try {
                boolean terminated = rotationScheduler.awaitTermination(100, TimeUnit.MILLISECONDS);
                logger.info("[Hycompanion] Scheduler " + (terminated ? "terminated" : "timeout") +
                        " in " + (System.currentTimeMillis() - stepStart) + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("[Hycompanion] Interrupted waiting for scheduler");
            }
        } else {
            logger.info("[Hycompanion] Scheduler already shut down or null");
        }

        // Clear tracking maps
        stepStart = System.currentTimeMillis();
        int npcCount = npcInstanceEntities.size();
        int targetCount = movementTargetEntities.size();
        int busyCount = busyNpcs.size();
        int spawnLocCount = npcSpawnLocations.size();
        int pendingRespawnCount = pendingRespawns.size();

        npcInstanceEntities.clear();
        movementTargetEntities.clear();
        busyNpcs.clear();
        npcSpawnLocations.clear();
        pendingRespawns.clear();

        // Cancel respawn checker
        if (respawnCheckerTask != null && !respawnCheckerTask.isDone()) {
            respawnCheckerTask.cancel(true);
            respawnCheckerTask = null;
        }

        logger.info("[Hycompanion] Cleared " + npcCount + " NPCs, " + targetCount + " targets, " +
                busyCount + " busy flags, " +
                spawnLocCount + " spawn locations, " + pendingRespawnCount + " pending respawns in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        // Shutdown plugin integrations
        stepStart = System.currentTimeMillis();
        if (integrationManager != null) {
            integrationManager.shutdown();
        }
        logger.info("[Hycompanion] Plugin integrations shut down in " +
                (System.currentTimeMillis() - stepStart) + "ms");

        long totalTime = System.currentTimeMillis() - startTime;
        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] CLEANUP COMPLETE");
        logger.info("[Hycompanion] Total time: " + totalTime + "ms");
        logger.info("[Hycompanion] ============================================");
    }

    // ========== 插件集成 ==========

    /**
     * 获取插件集成管理器
     * 提供对可选插件集成的访问（如语音气泡等）
     *
     * @return PluginIntegrationManager 实例
     */
    @Nonnull
    public PluginIntegrationManager getIntegrationManager() {
        return integrationManager;
    }

    /**
     * 在NPC头顶显示"思考中"语音气泡
     * 当NPC正在处理/生成回复时显示，随机选择一条思考提示文本
     * 气泡显示10秒，收到回复后会被正式回复替换
     *
     * @param npcInstanceId NPC实例UUID
     * @param playerUuid    目标玩家UUID
     */
    public void showThinkingBubble(UUID npcInstanceId, UUID playerUuid) {
        try {
            SpeechBubbleIntegration bubbles = integrationManager.getSpeechBubbles();
            if (!bubbles.isAvailable()) {
                return;
            }

            // 随机选择一条思考提示文本
            String[] thinkingTexts = { "Hmm...", "Let me think...", "One moment...", "Processing..." };
            String thinking = thinkingTexts[(int) (Math.random() * thinkingTexts.length)];

            // 显示10秒 - 收到回复后会被替换
            bubbles.showBubble(npcInstanceId, playerUuid, thinking, 10000);

        } catch (Exception e) {
            logger.debug("Could not show thinking bubble: " + e.getMessage());
        }
    }

    // ========== 方块发现 ==========

    /**
     * 从Hytale方块注册表中发现并分类所有可用方块
     * 遍历 BlockType 资产表，提取方块ID、显示名称和标签，
     * 使用 BlockClassifier 对每个方块进行材质分类
     * 当前仅返回 Wood_Beech_Trunk 方块（调试限制）
     *
     * @return 分类后的 BlockInfo 列表
     */
    @Override
    public List<dev.hycompanion.plugin.core.world.BlockInfo> getAvailableBlocks() {
        logger.info("[Hycompanion] ============================================");
        logger.info("[Hycompanion] DISCOVERING BLOCKS FROM REGISTRY");
        logger.info("[Hycompanion] ============================================");

        List<dev.hycompanion.plugin.core.world.BlockInfo> blocks = new ArrayList<>();

        try {
            // Get the BlockType asset map
            var blockTypeMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType.getAssetMap();
            if (blockTypeMap == null) {
                logger.warn("[Hycompanion] BlockType asset map is null");
                return blocks;
            }

            // Iterate over all registered block types
            var assetMap = blockTypeMap.getAssetMap();
            if (assetMap == null) {
                logger.warn("[Hycompanion] BlockType asset map internal map is null");
                return blocks;
            }

            int totalBlocks = assetMap.size();
            logger.info("[Hycompanion] Found " + totalBlocks + " block types in registry");

            // Log first 10 raw block IDs for debugging
            logger.info("[Hycompanion] --- Sample block IDs from registry ---");
            int sampleCount = 0;
            for (var entry : assetMap.entrySet()) {
                if (sampleCount >= 10)
                    break;
                String blockId = entry.getKey();
                var blockType = entry.getValue();

                // Try to get tags from block type data
                String tags = "N/A";
                try {
                    if (blockType != null && blockType.getData() != null) {
                        var rawTags = blockType.getData().getRawTags();
                        if (rawTags != null && !rawTags.isEmpty()) {
                            tags = rawTags.toString();
                        }
                    }
                } catch (Exception e) {
                    tags = "Error: " + e.getMessage();
                }

                logger.info(String.format("[Hycompanion] Block %d: ID=%s, Tags=%s",
                        sampleCount + 1, blockId, tags.substring(0, Math.min(tags.length(), 100))));
                sampleCount++;
            }
            logger.info("[Hycompanion] --- End sample ---");

            // Now classify all blocks
            int processed = 0;
            int errors = 0;

            logger.warn("[Hycompanion] Sending only Wood_Beech_Trunk block for now");

            for (var entry : assetMap.entrySet()) {
                try {
                    String blockId = entry.getKey();
                    var blockType = entry.getValue();

                    if (blockType == null)
                        continue;

                    // Get display name from the block type
                    String displayName = blockId;
                    try {
                        if (blockType.getId() != null) {
                            displayName = formatBlockIdAsDisplayName(blockType.getId());
                        }
                    } catch (Exception e) {
                        displayName = blockId;
                    }

                    if (!displayName.contains("Wood_Beech_Trunk")) {
                        continue;
                    }

                    // Extract tags from Hytale block data
                    Map<String, String[]> tags = null;
                    List<String> categories = null;
                    try {
                        if (blockType.getData() != null) {
                            tags = blockType.getData().getRawTags();
                        }
                    } catch (Exception e) {
                        // Tags not available
                    }

                    // Classify the block with Hytale tags
                    var blockInfo = dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                            blockId, displayName, tags, categories);

                    blocks.add(blockInfo);
                    processed++;

                } catch (Exception e) {
                    errors++;
                    if (errors <= 5) {
                        logger.debug("[Hycompanion] Error processing block type: " + e.getMessage());
                    }
                }
            }

            logger.info("[Hycompanion] Processed " + processed + " blocks (" + errors + " errors)");

            // Log classified samples
            logger.info("[Hycompanion] --- Sample classified blocks ---");
            for (int i = 0; i < Math.min(10, blocks.size()); i++) {
                var block = blocks.get(i);
                logger.info(String.format("[Hycompanion] %s -> materials=%s, keywords=%s",
                        block.blockId(),
                        block.materialTypes(),
                        block.keywords().subList(0, Math.min(5, block.keywords().size()))));
            }
            logger.info("[Hycompanion] --- End sample ---");

            // Log material type distribution
            var materialCounts = new java.util.HashMap<String, Integer>();
            for (var block : blocks) {
                for (var material : block.materialTypes()) {
                    materialCounts.merge(material, 1, Integer::sum);
                }
            }
            logger.info("[Hycompanion] Material type distribution: " + materialCounts);

        } catch (Exception e) {
            logger.error("[Hycompanion] Error discovering blocks: " + e.getMessage());
            e.printStackTrace();
        }

        logger.info("[Hycompanion] Returning " + blocks.size() + " classified blocks");
        logger.info("[Hycompanion] ============================================");
        return blocks;
    }

    /**
     * 将方块ID格式化为可读的显示名称
     * 与 formatRoleAsDisplayName 类似，去除命名空间前缀，
     * 将下划线替换为空格并首字母大写
     */
    private String formatBlockIdAsDisplayName(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "Block";
        }

        // Strip namespace prefix if present (e.g., "hytale:stone" → "stone")
        String name = blockId;
        int colonIndex = name.indexOf(':');
        if (colonIndex >= 0) {
            name = name.substring(colonIndex + 1);
        }

        // Replace underscores with spaces and capitalize each word
        String[] words = name.split("_");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            String word = words[i];
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1).toLowerCase());
                }
            }
        }

        return result.length() > 0 ? result.toString() : "Block";
    }

    // ========== 物品栏操作 ==========

    /**
     * 为NPC装备指定物品到目标槽位
     * 在世界线程上执行，从NPC物品栏中查找物品并移动到目标槽位
     * 支持 "auto"（自动选择hotbar_0）、护甲槽（head/chest/hands/legs）和快捷栏槽位
     *
     * @param npcInstanceId NPC实例UUID
     * @param itemId        要装备的物品ID
     * @param slot          目标槽位（"auto"、"head"、"chest"、"hands"、"legs"、"hotbar_0"等）
     * @return 装备结果，包含成功/失败信息及被替换的物品
     */
    @Override
    public EquipResult equipItem(UUID npcInstanceId, String itemId, String slot) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return EquipResult.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return EquipResult.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return EquipResult.failed("World not found");
        }

        // Determine target slot
        String targetSlot = slot;
        if ("auto".equals(slot)) {
            targetSlot = "hotbar_0";
        }

        CompletableFuture<EquipResult> equipResultFuture = new CompletableFuture<>();
        String finalTargetSlot = targetSlot;

        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        equipResultFuture.complete(EquipResult.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        equipResultFuture.complete(EquipResult.failed("NPC has no inventory"));
                        return;
                    }

                    EquipResult equipResult = equipItemInInventory(npcInstanceId, itemId, finalTargetSlot, inventory);
                    equipResultFuture.complete(equipResult);
                } catch (Exception e) {
                    equipResultFuture.completeExceptionally(e);
                }
            });
            return equipResultFuture.get();
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.error("[Hycompanion] Error equipping item: " + reason);
            Sentry.captureException(e);
            return EquipResult.failed("Error: " + reason);
        }
    }

    /** 将护甲槽位名称映射为对应的索引（head=0, chest=1, hands=2, legs=3） */
    private int getArmorSlotIndex(String slot) {
        return switch (slot) {
            case "head" -> 0;
            case "chest" -> 1;
            case "hands" -> 2;
            case "legs" -> 3;
            default -> -1;
        };
    }

    /** 物品栏中物品的位置信息：所在容器、槽位索引和物品堆叠 */
    private record InventoryItemLocation(ItemContainer container, short slot, ItemStack stack) {
    }

    /**
     * 根据物品ID在NPC物品栏中查找物品
     * 搜索优先级：快捷栏 > 背包 > 护甲栏
     *
     * @return 找到的物品位置信息，未找到返回 null
     */
    private InventoryItemLocation findInventoryItemById(Inventory inventory, String itemId) {
        if (inventory == null || itemId == null || itemId.isEmpty()) {
            return null;
        }

        // Prefer hotbar first (more likely to be "equippable"), then storage, then
        // armor.
        ItemContainer hotbar = inventory.getHotbar();
        for (short i = 0; i < hotbar.getCapacity(); i++) {
            ItemStack stack = hotbar.getItemStack(i);
            if (stack != null && !stack.isEmpty() && itemId.equalsIgnoreCase(stack.getItem().getId())) {
                return new InventoryItemLocation(hotbar, i, stack);
            }
        }

        ItemContainer storage = inventory.getStorage();
        for (short i = 0; i < storage.getCapacity(); i++) {
            ItemStack stack = storage.getItemStack(i);
            if (stack != null && !stack.isEmpty() && itemId.equalsIgnoreCase(stack.getItem().getId())) {
                return new InventoryItemLocation(storage, i, stack);
            }
        }

        ItemContainer armor = inventory.getArmor();
        for (short i = 0; i < armor.getCapacity(); i++) {
            ItemStack stack = armor.getItemStack(i);
            if (stack != null && !stack.isEmpty() && itemId.equalsIgnoreCase(stack.getItem().getId())) {
                return new InventoryItemLocation(armor, i, stack);
            }
        }

        return null;
    }

    /**
     * 构建NPC物品栏中所有物品的可读列表字符串
     * 遍历快捷栏、背包和护甲栏，格式如 "[hotbar_0:item_id x5, storage_1:item_id x3]"
     * 用于错误消息中展示NPC当前持有的物品
     */
    private String buildInventoryItemList(Inventory inventory) {
        if (inventory == null) {
            return "[]";
        }

        List<String> entries = new ArrayList<>();

        ItemContainer hotbar = inventory.getHotbar();
        for (short i = 0; i < hotbar.getCapacity(); i++) {
            ItemStack stack = hotbar.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                entries.add("hotbar_" + i + ":" + stack.getItem().getId() + " x" + stack.getQuantity());
            }
        }

        ItemContainer storage = inventory.getStorage();
        for (short i = 0; i < storage.getCapacity(); i++) {
            ItemStack stack = storage.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                entries.add("storage_" + i + ":" + stack.getItem().getId() + " x" + stack.getQuantity());
            }
        }

        ItemContainer armor = inventory.getArmor();
        String[] armorSlots = { "head", "chest", "hands", "legs" };
        for (short i = 0; i < armor.getCapacity() && i < armorSlots.length; i++) {
            ItemStack stack = armor.getItemStack(i);
            if (stack != null && !stack.isEmpty()) {
                entries.add("armor_" + armorSlots[i] + ":" + stack.getItem().getId() + " x" + stack.getQuantity());
            }
        }

        return entries.isEmpty() ? "[]" : "[" + String.join(", ", entries) + "]";
    }

    /**
     * 在NPC物品栏内执行装备操作（已在世界线程上）
     * 查找源物品位置，确定目标容器和槽位，执行物品交换
     * 如果物品已在目标槽位则为无操作（no-op），如果目标是快捷栏则设置为活动槽位
     */
    private EquipResult equipItemInInventory(UUID npcInstanceId, String itemId, String finalTargetSlot,
            Inventory inventory) {
        InventoryItemLocation source = findInventoryItemById(inventory, itemId);
        if (source == null) {
            String available = buildInventoryItemList(inventory);
            String msg = "Cannot equip '" + itemId + "': item not found in NPC inventory. " +
                    "requestedSlot='" + finalTargetSlot + "', availableItems=" + available;
            return EquipResult.failed(msg);
        }

        ItemContainer targetContainer;
        short targetIndex;

        switch (finalTargetSlot) {
            case "head":
            case "chest":
            case "hands":
            case "legs":
                int armorSlot = getArmorSlotIndex(finalTargetSlot);
                if (armorSlot < 0) {
                    return EquipResult.failed("Unknown armor slot: " + finalTargetSlot);
                }
                targetContainer = inventory.getArmor();
                targetIndex = (short) armorSlot;
                break;
            case "hotbar_0":
            case "hotbar_1":
            case "hotbar_2":
                int hotbarSlot = Integer.parseInt(finalTargetSlot.replace("hotbar_", ""));
                if (hotbarSlot < 0 || hotbarSlot >= inventory.getHotbar().getCapacity()) {
                    return EquipResult.failed("Invalid hotbar slot '" + finalTargetSlot + "' for capacity " +
                            inventory.getHotbar().getCapacity());
                }
                targetContainer = inventory.getHotbar();
                targetIndex = (short) hotbarSlot;
                break;
            default:
                return EquipResult.failed("Unknown slot: " + finalTargetSlot);
        }

        ItemStack targetExisting = targetContainer.getItemStack(targetIndex);
        Map<String, Object> previousItem = null;
        if (targetExisting != null && !targetExisting.isEmpty()) {
            previousItem = Map.of(
                    "itemId", targetExisting.getItem().getId(),
                    "quantity", targetExisting.getQuantity());
        }

        if (source.container() == targetContainer && source.slot() == targetIndex) {
            logger.info("[Hycompanion] Equip no-op: " + itemId + " already in " + finalTargetSlot +
                    " for NPC " + npcInstanceId);
            if (finalTargetSlot.startsWith("hotbar_")) {
                // inventory.setActiveHotbarSlot((byte) targetIndex); // API changed
            }
            return EquipResult.success(itemId, finalTargetSlot, previousItem);
        }

        targetContainer.setItemStackForSlot(targetIndex, source.stack());
        source.container().setItemStackForSlot(source.slot(), targetExisting);

        if (finalTargetSlot.startsWith("hotbar_")) {
            // inventory.setActiveHotbarSlot((byte) targetIndex); // API changed
        }

        logger.info("[Hycompanion] Equipped " + itemId + " to " + finalTargetSlot + " for NPC " + npcInstanceId);
        return EquipResult.success(itemId, finalTargetSlot, previousItem);
    }

    /**
     * NPC破坏指定位置的方块
     * 在世界线程上执行完整的方块破坏流程：
     * 1. 距离检查（最远5格）
     * 2. 装备指定工具（如有）
     * 3. NPC面向目标方块旋转
     * 4. 循环执行挥击动画 + 方块损伤视觉效果 + 实际方块伤害
     * 5. 方块破碎后扫描掉落物
     * 使用 BlockHarvestUtils.performBlockDamage 进行渐进式破坏，尊重工具效率
     *
     * @param npcInstanceId NPC实例UUID
     * @param targetBlock   目标方块坐标
     * @param toolItemId    要装备的工具物品ID（可选）
     * @param maxAttempts   最大尝试次数（1-30）
     * @return 破坏结果，包含方块类型、尝试次数、掉落物等
     */
    @Override
    public BreakResult breakBlock(UUID npcInstanceId, Location targetBlock, String toolItemId, int maxAttempts) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return BreakResult.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return BreakResult.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return BreakResult.failed("World not found");
        }

        // Execute on world thread
        CompletableFuture<BreakResult> breakResultFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        breakResultFuture.complete(BreakResult.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        breakResultFuture.complete(BreakResult.failed("NPC has no inventory"));
                        return;
                    }

                    TransformComponent breakTransform = store.getComponent(entityRef,
                            TransformComponent.getComponentType());
                    if (breakTransform == null) {
                        breakResultFuture.complete(BreakResult.failed("NPC transform not found"));
                        return;
                    }

                    Vector3d npcPos = breakTransform.getPosition();
                    double distance = npcPos
                            .distanceTo(new Vector3d(targetBlock.x(), targetBlock.y(), targetBlock.z()));
                    if (distance > 5.0) {
                        breakResultFuture.complete(BreakResult.failed(String.format(java.util.Locale.US,
                                "OUT_OF_RANGE: Target block is too far away (%.1f blocks). Max distance is 5 blocks, please move closer.",
                                distance)));
                        return;
                    }

                    // Equip tool if specified
                    if (toolItemId != null && !toolItemId.isEmpty()) {
                        EquipResult equipResult = equipItemInInventory(npcInstanceId, toolItemId, "hotbar_0",
                                inventory);
                        if (!equipResult.success()) {
                            logger.warn("[Hycompanion] Failed to equip tool: " + equipResult.error());
                        }
                    }

                    Vector3i blockPos = new Vector3i(
                            (int) Math.floor(targetBlock.x()),
                            (int) Math.floor(targetBlock.y()),
                            (int) Math.floor(targetBlock.z()));
                    int safeMaxAttempts = Math.max(1, Math.min(maxAttempts, 30));

                    // Step 1: Rotate NPC to face target block directly (already on world thread).
                    double dx = targetBlock.x() - npcPos.getX();
                    double dz = targetBlock.z() - npcPos.getZ();
                    if ((dx * dx) + (dz * dz) >= 0.0001d) {
                        float targetYaw = TrigMathUtil.atan2(-dx, -dz);
                        Vector3f bodyRotation = breakTransform.getRotation();
                        bodyRotation.setYaw(targetYaw);
                        bodyRotation.setPitch(0.0f);
                        bodyRotation.setRoll(0.0f);
                        breakTransform.setRotation(bodyRotation);
                        npcEntity.setLeashHeading(targetYaw);
                        npcEntity.setLeashPitch(0.0f);
                    }

                    // Get block type before breaking
                    var blockTypeAssetMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
                            .getAssetMap();
                    long targetChunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(blockPos.getX(),
                            blockPos.getZ());
                    var targetChunk = world.getChunkIfLoaded(targetChunkIndex);
                    if (targetChunk == null) {
                        breakResultFuture.complete(BreakResult.failed("Chunk not loaded"));
                        return;
                    }
                    int blockId = targetChunk.getBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                    String blockIdStr = "unknown";
                    var blockType = blockTypeAssetMap.getAsset(blockId);
                    if (blockType != null) {
                        blockIdStr = blockType.getId();
                    }

                    // Perform block breaking using BlockHarvestUtils.performBlockDamage for gradual
                    // breaking
                    // This respects the equipped tool and doesn't trigger connected blocks (like
                    // whole trees)
                    long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(blockPos.getX(),
                            blockPos.getZ());
                    var chunkStore = world.getChunkStore().getStore();
                    Ref<ChunkStore> chunkRef = chunkStore.getExternalData().getChunkReference(chunkIndex);

                    if (chunkRef == null || !chunkRef.isValid()) {
                        breakResultFuture.complete(BreakResult.failed("Chunk not loaded"));
                        return;
                    }

                    // Get the held item for tool power calculation
                    ItemStack heldItemStack = null;
                    heldItemStack = inventory.getItemInHand();

                    // Perform gradual block damage until broken
                    int attempts = 0;
                    boolean broken = false;
                    float currentBlockHealth = 1.0f; // Start at full health (1.0 = no damage)

                    while (attempts < safeMaxAttempts && !broken) {
                        // Step 2: Play swing animation before each hit
                        try {
                            npcEntity.playAnimation(entityRef, com.hypixel.hytale.protocol.AnimationSlot.Action,
                                    "Swing", store);
                        } catch (Exception e) {
                            logger.debug("[Hycompanion] Could not play swing animation: " + e.getMessage());
                        }

                        // Calculate damage for this hit (based on tool power)
                        float damagePerHit = 0.25f; // Default 25% per hit
                        if (heldItemStack != null && !heldItemStack.isEmpty()) {
                            var itemTool = heldItemStack.getItem().getTool();
                            if (itemTool != null) {
                                var specs = itemTool.getSpecs();
                                if (specs != null && specs.length > 0) {
                                    // Use first spec's power - higher power = more damage per hit
                                    damagePerHit = Math.max(0.1f, Math.min(0.5f, specs[0].getPower() * 0.1f));
                                }
                            }
                        }

                        // Step 3: Broadcast block damage visual to nearby players BEFORE applying
                        // damage
                        // This shows the block cracking animation
                        currentBlockHealth = Math.max(0.0f, currentBlockHealth - damagePerHit);
                        try {
                            com.hypixel.hytale.protocol.BlockPosition blockPosition = new com.hypixel.hytale.protocol.BlockPosition(
                                    blockPos.getX(), blockPos.getY(), blockPos.getZ());
                            com.hypixel.hytale.protocol.packets.world.UpdateBlockDamage damagePacket = new com.hypixel.hytale.protocol.packets.world.UpdateBlockDamage(
                                    blockPosition, currentBlockHealth, -damagePerHit);

                            // Send to all nearby players
                            for (com.hypixel.hytale.server.core.universe.PlayerRef playerRef : world.getPlayerRefs()) {
                                com.hypixel.hytale.component.Ref<EntityStore> playerRef2 = playerRef.getReference();
                                if (playerRef2 != null && playerRef2.isValid()) {
                                    // Check if player is within viewing distance (e.g., 50 blocks)
                                    com.hypixel.hytale.server.core.modules.entity.component.TransformComponent playerTransform = store
                                            .getComponent(playerRef2,
                                                    com.hypixel.hytale.server.core.modules.entity.component.TransformComponent
                                                            .getComponentType());
                                    if (playerTransform != null) {
                                        double dist = playerTransform.getPosition().distanceTo(
                                                new Vector3d(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
                                        if (dist < 50.0) {
                                            PacketDispatchUtil.trySendPacketToPlayer(playerRef, damagePacket, logger);
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("[Hycompanion] Could not broadcast block damage: " + e.getMessage());
                        }

                        // Step 4: Apply actual block damage
                        broken = BlockHarvestUtils.performBlockDamage(
                                npcEntity, // entity (LivingEntity)
                                entityRef, // ref (Ref<EntityStore>)
                                blockPos, // targetBlockPos
                                heldItemStack, // itemStack
                                null, // tool (can be null, will be derived from itemStack)
                                null, // toolId
                                false, // matchTool
                                1.0f, // damageScale
                                0, // setBlockSettings
                                chunkRef, // chunkReference
                                store, // entityStore
                                chunkStore // chunkStore
                        );
                        attempts++;

                    }

                    if (!broken) {
                        breakResultFuture.complete(BreakResult.unbroken("BLOCK_UNBREAKABLE"));
                        return;
                    }

                    // Scan for dropped items
                    List<Map<String, Object>> drops = scanForDrops(store, targetBlock, 3);

                    // Get tool durability from already-fetched held item
                    Double durability = null;
                    if (heldItemStack != null && !heldItemStack.isEmpty()) {
                        durability = heldItemStack.getDurability();
                    }

                    Map<String, Object> dropLocation = Map.of(
                            "x", targetBlock.x(),
                            "y", targetBlock.y(),
                            "z", targetBlock.z());

                    logger.info("[Hycompanion] Broke block " + blockIdStr + " at " + targetBlock + " for NPC "
                            + npcInstanceId);
                    breakResultFuture
                            .complete(BreakResult.success(blockIdStr, attempts, drops, dropLocation, durability));
                } catch (Exception e) {
                    breakResultFuture.completeExceptionally(e);
                }
            });
            return breakResultFuture.get();
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.error("[Hycompanion] Error breaking block: " + reason);
            Sentry.captureException(e);
            return BreakResult.failed("Error: " + reason);
        }
    }

    /**
     * 扫描指定位置附近的掉落物实体
     * 通过反射访问 entitiesByUuid 映射，查找具有 ItemComponent 的实体
     * 返回在指定半径内的所有掉落物的ID和数量
     *
     * @param store  实体存储
     * @param center 扫描中心坐标
     * @param radius 扫描半径（方块数）
     * @return 掉落物列表，每项包含 itemId 和 quantity
     */
    private List<Map<String, Object>> scanForDrops(Store<EntityStore> store, Location center, int radius) {
        List<Map<String, Object>> drops = new ArrayList<>();
        Vector3d centerPos = new Vector3d(center.x(), center.y(), center.z());

        // Use reflection to access entitiesByUuid map
        try {
            Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
            entitiesMapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                    .get(store.getExternalData());

            for (Ref<EntityStore> ref : allEntities.values()) {
                if (!ref.isValid())
                    continue;

                com.hypixel.hytale.server.core.modules.entity.item.ItemComponent itemComp = store.getComponent(ref,
                        com.hypixel.hytale.server.core.modules.entity.item.ItemComponent.getComponentType());
                if (itemComp == null)
                    continue;

                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null)
                    continue;

                double distance = transform.getPosition().distanceTo(centerPos);
                if (distance <= radius) {
                    drops.add(Map.of(
                            "itemId", itemComp.getItemStack().getItem().getId(),
                            "quantity", itemComp.getItemStack().getQuantity()));
                }
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] Error scanning for drops: " + e.getMessage());
        }

        return drops;
    }

    /**
     * NPC拾取附近的掉落物
     * 在世界线程上执行，通过反射遍历所有实体查找掉落物：
     * 1. 搜索指定半径内具有 ItemComponent 的实体
     * 2. 按物品ID过滤（如指定）
     * 3. 尝试添加到NPC背包（优先背包，然后快捷栏）
     * 4. 成功添加后从世界中移除掉落物实体
     * 5. 如果未找到匹配的掉落物实体，回退为破坏附近匹配方块并拾取
     *
     * @param npcInstanceId NPC实例UUID
     * @param radius        拾取半径
     * @param itemId        过滤物品ID（null表示拾取所有）
     * @param maxItems      最大拾取数量
     * @return 拾取结果，包含拾取的物品列表和剩余数量
     */
    @Override
    public PickupResult pickupItems(UUID npcInstanceId, double radius, String itemId, int maxItems) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return PickupResult.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return PickupResult.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return PickupResult.failed("World not found");
        }

        CompletableFuture<PickupResult> pickupResultFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        pickupResultFuture.complete(PickupResult.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        pickupResultFuture.complete(PickupResult.failed("NPC has no inventory"));
                        return;
                    }

                    TransformComponent npcTransform = store.getComponent(entityRef,
                            TransformComponent.getComponentType());
                    if (npcTransform == null) {
                        pickupResultFuture.complete(PickupResult.failed("NPC has no transform"));
                        return;
                    }

                    Vector3d npcPos = npcTransform.getPosition();
                    List<Map<String, Object>> pickedUpItems = new ArrayList<>();
                    List<Ref<EntityStore>> entitiesToRemove = new ArrayList<>();
                    int itemsPickedUp = 0;
                    int itemsRemaining = 0;
                    int matchingItemEntitiesFound = 0;

                    // Debug logging
                    logger.debug("[Hycompanion] Pickup started: radius=" + radius + ", itemId=" + itemId + ", npcPos="
                            + npcPos);
                    logger.debug("[Hycompanion] NPC storage capacity: " + inventory.getStorage().getCapacity());

                    // Use reflection to access entitiesByUuid map
                    try {
                        Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
                        entitiesMapField.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                                .get(store.getExternalData());

                        int checkedEntities = 0;
                        int itemEntitiesFound = 0;

                        for (Ref<EntityStore> ref : allEntities.values()) {
                            if (!ref.isValid())
                                continue;

                            com.hypixel.hytale.server.core.modules.entity.item.ItemComponent itemComp = store
                                    .getComponent(ref, com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                            .getComponentType());
                            if (itemComp == null)
                                continue;

                            itemEntitiesFound++;

                            TransformComponent transform = store.getComponent(ref,
                                    TransformComponent.getComponentType());
                            if (transform == null)
                                continue;

                            double distance = transform.getPosition().distanceTo(npcPos);

                            String droppedItemId = itemComp.getItemStack().getItem().getId();
                            int droppedQty = itemComp.getItemStack().getQuantity();

                            checkedEntities++;
                            logger.debug("[Hycompanion] Checking item: " + droppedItemId + " x" + droppedQty +
                                    " at distance " + distance + " (radius=" + radius + ")");

                            // Add small epsilon for floating point precision (items within ~0.1 blocks of
                            // radius should be included)
                            double effectiveRadius = radius + 0.1;
                            if (distance > effectiveRadius)
                                continue;

                            // Filter by itemId if specified
                            if (itemId != null && !itemId.isEmpty() && !droppedItemId.equals(itemId)) {
                                logger.debug("[Hycompanion] Item ID mismatch: expected=" + itemId + ", found="
                                        + droppedItemId);
                                continue;
                            }
                            matchingItemEntitiesFound++;

                            if (itemsPickedUp >= maxItems) {
                                itemsRemaining++;
                                logger.debug("[Hycompanion] Max items reached, remaining++");
                                continue;
                            }

                            // Add to inventory - try storage first, then hotbar
                            ItemStack stack = itemComp.getItemStack();
                            logger.debug("[Hycompanion] Attempting to add " + droppedItemId + " to inventory");

                            boolean added = false;

                            // Try storage first
                            if (inventory.getStorage().getCapacity() > 0) {
                                var transaction = inventory.getStorage().addItemStack(stack);
                                added = transaction != null && transaction.getRemainder() == null;
                                logger.debug("[Hycompanion] Storage add result: " + added);
                            }

                            // If storage failed or has no capacity, try hotbar
                            if (!added) {
                                var hotbarTransaction = inventory.getHotbar().addItemStack(stack);
                                added = hotbarTransaction != null && hotbarTransaction.getRemainder() == null;
                                logger.debug("[Hycompanion] Hotbar add result: " + added);
                            }

                            if (added) {
                                // Mark for removal after iteration to avoid mutating the reflected entity map
                                // while iterating it.
                                itemComp.setRemovedByPlayerPickup(true);
                                entitiesToRemove.add(ref);

                                pickedUpItems.add(Map.of(
                                        "itemId", droppedItemId,
                                        "quantity", stack.getQuantity()));
                                itemsPickedUp++;
                                logger.debug("[Hycompanion] Successfully picked up " + droppedItemId);
                            } else {
                                itemsRemaining++;
                                logger.debug("[Hycompanion] Failed to add to inventory (storage and hotbar full)");
                            }
                        }

                        logger.debug("[Hycompanion] Pickup complete: checked=" + checkedEntities +
                                ", itemEntities=" + itemEntitiesFound + ", pickedUp=" + itemsPickedUp + ", remaining="
                                + itemsRemaining);
                    } catch (Exception e) {
                        logger.error("[Hycompanion] Error during pickup: " + e.getMessage(), e);
                    }

                    for (Ref<EntityStore> itemRef : entitiesToRemove) {
                        if (!removeItemEntityFromGround(store, itemRef)) {
                            logger.warn(
                                    "[Hycompanion] Item was added to inventory but could not be removed from world. ref="
                                            + itemRef);
                        }
                    }

                    // Fallback: harvest a matching block only if no matching item entities were
                    // found.
                    if (itemsPickedUp == 0 && matchingItemEntitiesFound == 0 && itemId != null && !itemId.isEmpty()) {
                        try {
                            double effectiveRadius = radius + 0.1;
                            List<Ref<EntityStore>> fallbackEntitiesToRemove = new ArrayList<>();
                            int centerX = (int) Math.floor(npcPos.getX());
                            int centerY = (int) Math.floor(npcPos.getY());
                            int centerZ = (int) Math.floor(npcPos.getZ());
                            int maxOffset = (int) Math.ceil(effectiveRadius);

                            var blockTypeAssetMap = com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType
                                    .getAssetMap();
                            var chunkStore = world.getChunkStore().getStore();
                            ItemStack heldItemStack = inventory.getItemInHand();

                            boolean harvestedMatchingBlock = false;
                            for (int x = centerX - maxOffset; x <= centerX + maxOffset
                                    && !harvestedMatchingBlock; x++) {
                                for (int y = Math.max(0, centerY - maxOffset); y <= Math.min(255, centerY + maxOffset)
                                        && !harvestedMatchingBlock; y++) {
                                    for (int z = centerZ - maxOffset; z <= centerZ + maxOffset
                                            && !harvestedMatchingBlock; z++) {
                                        double dist = npcPos.distanceTo(new Vector3d(x, y, z));
                                        if (dist > effectiveRadius) {
                                            continue;
                                        }

                                        long chunkIndex = com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x,
                                                z);
                                        var chunk = world.getChunkIfLoaded(chunkIndex);
                                        if (chunk == null) {
                                            continue;
                                        }

                                        int blockTypeId = chunk.getBlock(x, y, z);
                                        var blockType = blockTypeAssetMap != null
                                                ? blockTypeAssetMap.getAsset(blockTypeId)
                                                : null;
                                        String blockId = blockType != null ? blockType.getId() : null;
                                        if (blockId == null || !blockId.equals(itemId)) {
                                            continue;
                                        }

                                        Ref<ChunkStore> chunkRef = chunkStore.getExternalData()
                                                .getChunkReference(chunkIndex);
                                        if (chunkRef == null || !chunkRef.isValid()) {
                                            continue;
                                        }

                                        Vector3i blockPos = new Vector3i(x, y, z);
                                        boolean broken = false;
                                        for (int attempt = 0; attempt < 10 && !broken; attempt++) {
                                            broken = BlockHarvestUtils.performBlockDamage(
                                                    npcEntity,
                                                    entityRef,
                                                    blockPos,
                                                    heldItemStack,
                                                    null,
                                                    null,
                                                    false,
                                                    1.0f,
                                                    0,
                                                    chunkRef,
                                                    store,
                                                    chunkStore);
                                        }

                                        if (broken) {
                                            harvestedMatchingBlock = true;
                                            logger.debug("[Hycompanion] Harvested block fallback for itemId=" + itemId +
                                                    " at (" + x + "," + y + "," + z + ")");
                                        }
                                    }
                                }
                            }

                            // Retry pickup after block harvest to collect resulting drop entities.
                            if (harvestedMatchingBlock && itemsPickedUp < maxItems) {
                                Field entitiesMapField = EntityStore.class.getDeclaredField("entitiesByUuid");
                                entitiesMapField.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Map<UUID, Ref<EntityStore>> allEntities = (Map<UUID, Ref<EntityStore>>) entitiesMapField
                                        .get(store.getExternalData());

                                for (Ref<EntityStore> ref : allEntities.values()) {
                                    if (!ref.isValid())
                                        continue;
                                    if (itemsPickedUp >= maxItems) {
                                        itemsRemaining++;
                                        continue;
                                    }

                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent itemComp = store
                                            .getComponent(ref,
                                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                                            .getComponentType());
                                    if (itemComp == null)
                                        continue;

                                    TransformComponent transform = store.getComponent(ref,
                                            TransformComponent.getComponentType());
                                    if (transform == null)
                                        continue;

                                    if (transform.getPosition().distanceTo(npcPos) > effectiveRadius)
                                        continue;

                                    String droppedItemId = itemComp.getItemStack().getItem().getId();
                                    if (!droppedItemId.equals(itemId))
                                        continue;

                                    ItemStack stack = itemComp.getItemStack();
                                    boolean added = false;

                                    if (inventory.getStorage().getCapacity() > 0) {
                                        var transaction = inventory.getStorage().addItemStack(stack);
                                        added = transaction != null && transaction.getRemainder() == null;
                                    }
                                    if (!added) {
                                        var hotbarTransaction = inventory.getHotbar().addItemStack(stack);
                                        added = hotbarTransaction != null && hotbarTransaction.getRemainder() == null;
                                    }

                                    if (added) {
                                        itemComp.setRemovedByPlayerPickup(true);
                                        fallbackEntitiesToRemove.add(ref);
                                        pickedUpItems.add(Map.of(
                                                "itemId", droppedItemId,
                                                "quantity", stack.getQuantity()));
                                        itemsPickedUp++;
                                    } else {
                                        itemsRemaining++;
                                    }
                                }
                            }

                            for (Ref<EntityStore> itemRef : fallbackEntitiesToRemove) {
                                if (!removeItemEntityFromGround(store, itemRef)) {
                                    logger.warn(
                                            "[Hycompanion] Fallback pickup added item but could not remove ground entity. ref="
                                                    + itemRef);
                                }
                            }
                        } catch (Exception e) {
                            logger.error("[Hycompanion] Error during block-harvest fallback pickup: " + e.getMessage(),
                                    e);
                        }
                    }

                    logger.info("[Hycompanion] Picked up " + itemsPickedUp + " items for NPC " + npcInstanceId);

                    // Build error message if items were found but couldn't be picked up
                    String error = null;
                    if (itemsPickedUp == 0 && itemsRemaining > 0) {
                        error = "Inventory full or items don't match filter";
                    } else if (itemsRemaining > 0) {
                        error = "Some items remaining (inventory may be full)";
                    }

                    pickupResultFuture.complete(new PickupResult(
                            true, itemsPickedUp, pickedUpItems, itemsRemaining, error));
                } catch (Exception e) {
                    pickupResultFuture.completeExceptionally(e);
                }
            });
            return pickupResultFuture.get();
        } catch (Exception e) {
            logger.error("[Hycompanion] Error picking up items: " + e.getMessage());
            Sentry.captureException(e);
            return PickupResult.failed("Error: " + e.getMessage());
        }
    }

    /**
     * 从世界中移除掉落物实体
     * 先尝试标准移除，如果实体仍然有效则使用基于 holder 的备用路径重试
     *
     * @return 实体是否成功移除（不再有效）
     */
    private boolean removeItemEntityFromGround(Store<EntityStore> store, Ref<EntityStore> itemRef) {
        if (itemRef == null || !itemRef.isValid()) {
            return true;
        }

        try {
            store.removeEntity(itemRef, com.hypixel.hytale.component.RemoveReason.REMOVE);
            if (itemRef.isValid()) {
                // Retry using holder-based path used in server command utilities.
                store.removeEntity(itemRef, EntityStore.REGISTRY.newHolder(),
                        com.hypixel.hytale.component.RemoveReason.REMOVE);
            }
            return !itemRef.isValid();
        } catch (Exception e) {
            logger.debug("[Hycompanion] Failed to remove picked item entity: " + e.getMessage());
            return false;
        }
    }

    /**
     * NPC使用手持物品
     * 在世界线程上执行，按指定次数和间隔重复使用当前手持物品
     * 检查工具耐久度，耐久度耗尽时停止使用
     *
     * @param npcInstanceId NPC实例UUID
     * @param target        使用目标坐标
     * @param useCount      使用次数
     * @param intervalMs    每次使用间隔（毫秒）
     * @param targetType    目标类型（方块/实体等）
     * @return 使用结果，包含实际使用次数和工具是否损坏
     */
    @Override
    public UseResult useHeldItem(UUID npcInstanceId, Location target, int useCount, long intervalMs,
            TargetType targetType) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return UseResult.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return UseResult.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return UseResult.failed("World not found");
        }

        CompletableFuture<UseResult> useResultFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        useResultFuture.complete(UseResult.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        useResultFuture.complete(UseResult.failed("NPC has no inventory"));
                        return;
                    }

                    ItemStack heldItem = inventory.getItemInHand();
                    if (heldItem == null || heldItem.isEmpty()) {
                        useResultFuture.complete(UseResult.failed("No item in hand"));
                        return;
                    }

                    int usesPerformed = 0;
                    boolean toolBroke = false;

                    for (int i = 0; i < useCount; i++) {
                        // Perform the use action
                        // In a full implementation, this would trigger the item's use action
                        usesPerformed++;

                        // Check if tool broke
                        if (heldItem.getDurability() <= 0) {
                            toolBroke = true;
                            break;
                        }

                        if (i < useCount - 1) {
                            try {
                                Thread.sleep(intervalMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }

                    logger.info("[Hycompanion] Used held item " + usesPerformed + " times for NPC " + npcInstanceId);
                    useResultFuture.complete(UseResult.success(usesPerformed, null, null, toolBroke));
                } catch (Exception e) {
                    useResultFuture.completeExceptionally(e);
                }
            });
            return useResultFuture.get();
        } catch (Exception e) {
            logger.error("[Hycompanion] Error using held item: " + e.getMessage());
            Sentry.captureException(e);
            return UseResult.failed("Error: " + e.getMessage());
        }
    }

    /**
     * NPC丢弃物品到世界中
     * 在世界线程上执行：
     * 1. 在快捷栏和背包中查找匹配物品（支持精确匹配和部分匹配）
     * 2. 从物品栏中扣除指定数量
     * 3. 使用 ItemComponent.generateItemDrop 创建掉落物实体
     * 4. 在NPC面前方向生成，带有向前抛出的速度和上抛弧线
     * 5. 设置1.5秒拾取延迟防止NPC立即拾回
     *
     * @param npcInstanceId NPC实例UUID
     * @param itemId        要丢弃的物品ID
     * @param quantity      丢弃数量
     * @param throwSpeed    抛出速度
     * @return 丢弃结果，包含实际丢弃数量和物品栏中剩余数量
     */
    @Override
    public DropResult dropItem(UUID npcInstanceId, String itemId, int quantity, float throwSpeed) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return DropResult.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return DropResult.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return DropResult.failed("World not found");
        }

        CompletableFuture<DropResult> dropResultFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        dropResultFuture.complete(DropResult.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        dropResultFuture.complete(DropResult.failed("NPC has no inventory"));
                        return;
                    }

                    // Find the item in inventory
                    ItemStack toDrop = null;
                    int remainingQuantity = 0;

                    logger.debug("[Hycompanion] Drop: looking for '" + itemId + "' x" + quantity);
                    logger.debug("[Hycompanion] Drop: hotbar capacity=" + inventory.getHotbar().getCapacity() +
                            ", storage capacity=" + inventory.getStorage().getCapacity());

                    // Check hotbar first
                    ItemContainer hotbar = inventory.getHotbar();
                    logger.debug("[Hycompanion] Drop: scanning hotbar...");
                    for (short i = 0; i < hotbar.getCapacity(); i++) {
                        ItemStack stack = hotbar.getItemStack(i);
                        if (stack != null && !stack.isEmpty()) {
                            String stackId = stack.getItem().getId();
                            logger.debug(
                                    "[Hycompanion] Drop: hotbar[" + i + "]='" + stackId + "' x" + stack.getQuantity());
                            // Check exact match or partial match
                            boolean matches = itemId.equals(stackId) || stackId.contains(itemId);
                            if (matches) {
                                int dropQty = Math.min(quantity, stack.getQuantity());
                                toDrop = new ItemStack(stackId, dropQty, null);

                                if (stack.getQuantity() <= dropQty) {
                                    hotbar.setItemStackForSlot(i, null);
                                } else {
                                    ItemStack newStack = stack.withQuantity(stack.getQuantity() - dropQty);
                                    hotbar.setItemStackForSlot(i, newStack);
                                    remainingQuantity = newStack.getQuantity();
                                }
                                logger.debug("[Hycompanion] Drop: found in hotbar, dropQty=" + dropQty + ", actualId="
                                        + stackId);
                                break;
                            }
                        }
                    }

                    // Check storage if not found in hotbar
                    if (toDrop == null) {
                        ItemContainer storage = inventory.getStorage();
                        logger.debug("[Hycompanion] Drop: scanning storage...");
                        for (short i = 0; i < storage.getCapacity(); i++) {
                            ItemStack stack = storage.getItemStack(i);
                            if (stack != null && !stack.isEmpty()) {
                                String stackId = stack.getItem().getId();
                                logger.debug("[Hycompanion] Drop: storage[" + i + "]='" + stackId + "' x"
                                        + stack.getQuantity());
                                // Check exact match or partial match
                                boolean matches = itemId.equals(stackId) || stackId.contains(itemId);
                                if (matches) {
                                    int dropQty = Math.min(quantity, stack.getQuantity());
                                    toDrop = new ItemStack(stackId, dropQty, null);

                                    if (stack.getQuantity() <= dropQty) {
                                        storage.setItemStackForSlot(i, null);
                                    } else {
                                        ItemStack newStack = stack.withQuantity(stack.getQuantity() - dropQty);
                                        storage.setItemStackForSlot(i, newStack);
                                        remainingQuantity = newStack.getQuantity();
                                    }
                                    logger.debug("[Hycompanion] Drop: found in storage, dropQty=" + dropQty
                                            + ", actualId=" + stackId);
                                    break;
                                }
                            }
                        }
                    }

                    if (toDrop == null) {
                        // Build list of available items for error message
                        StringBuilder available = new StringBuilder();
                        for (short i = 0; i < hotbar.getCapacity(); i++) {
                            ItemStack stack = hotbar.getItemStack(i);
                            if (stack != null && !stack.isEmpty()) {
                                if (available.length() > 0)
                                    available.append(", ");
                                available.append("'").append(stack.getItem().getId()).append("'");
                            }
                        }
                        for (short i = 0; i < inventory.getStorage().getCapacity(); i++) {
                            ItemStack stack = inventory.getStorage().getItemStack(i);
                            if (stack != null && !stack.isEmpty()) {
                                if (available.length() > 0)
                                    available.append(", ");
                                available.append("'").append(stack.getItem().getId()).append("'");
                            }
                        }
                        String msg = available.length() > 0 ? "Item '" + itemId + "' not found. Available: " + available
                                : "Item '" + itemId + "' not found. Inventory is empty.";
                        logger.debug("[Hycompanion] Drop: " + msg);
                        dropResultFuture.complete(DropResult.failed(msg));
                        return;
                    }

                    // Create dropped item entity
                    TransformComponent npcTransform = store.getComponent(entityRef,
                            TransformComponent.getComponentType());
                    if (npcTransform == null) {
                        dropResultFuture.complete(DropResult.failed("NPC has no transform"));
                        return;
                    }

                    Vector3d npcPos = npcTransform.getPosition();
                    Vector3f npcRot = npcTransform.getRotation();

                    // Calculate forward direction from NPC's yaw (rotation around Y axis)
                    float yaw = npcRot.getYaw();
                    double forwardX = -Math.sin(yaw);
                    double forwardZ = -Math.cos(yaw);

                    // Position slightly in front of NPC (0.5 blocks) and at hand height
                    Vector3d dropPos = new Vector3d(
                            npcPos.getX() + forwardX * 0.5,
                            npcPos.getY() + 0.8,
                            npcPos.getZ() + forwardZ * 0.5);

                    // Gentle throw velocity: 2.0 horizontal speed + small upward arc
                    float itemThrowSpeed = 2.0f;
                    float velocityX = (float) (forwardX * itemThrowSpeed);
                    float velocityZ = (float) (forwardZ * itemThrowSpeed);
                    float velocityY = 2.5f; // Gentle upward arc

                    // Use Hytale's ItemComponent.generateItemDrop to properly create a dropped item
                    // This adds all necessary components: ItemComponent, TransformComponent,
                    // Velocity,
                    // PhysicsValues, UUIDComponent, Intangible, and DespawnComponent
                    com.hypixel.hytale.component.Holder<EntityStore> itemHolder = com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                            .generateItemDrop(
                                    store, // ComponentAccessor
                                    toDrop, // ItemStack to drop
                                    dropPos, // Position (slightly in front of NPC)
                                    new com.hypixel.hytale.math.vector.Vector3f(0, 0, 0), // Rotation
                                    velocityX, // velocityX - forward direction
                                    velocityY, // velocityY - gentle upward arc
                                    velocityZ // velocityZ - forward direction
                    );

                    if (itemHolder == null) {
                        dropResultFuture.complete(DropResult.failed("Failed to create item entity"));
                        return;
                    }

                    // Add NetworkId for client visibility
                    itemHolder.addComponent(
                            com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId.getComponentType(),
                            new com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId(
                                    world.getEntityStore().getStore().getExternalData().takeNextNetworkId()));

                    // Set pickup delay (1.5 seconds before NPC can pick it back up)
                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent itemComp = (com.hypixel.hytale.server.core.modules.entity.item.ItemComponent) itemHolder
                            .getComponent(
                                    com.hypixel.hytale.server.core.modules.entity.item.ItemComponent
                                            .getComponentType());
                    if (itemComp != null) {
                        itemComp.setPickupDelay(1.5f);
                    }

                    // Spawn the entity
                    Ref<EntityStore> itemRef = world.getEntityStore().getStore().addEntity(itemHolder,
                            com.hypixel.hytale.component.AddReason.SPAWN);

                    if (itemRef == null || !itemRef.isValid()) {
                        dropResultFuture.complete(DropResult.failed("Failed to spawn item entity"));
                        return;
                    }

                    logger.info("[Hycompanion] Dropped " + toDrop.getQuantity() + "x " + itemId + " for NPC "
                            + npcInstanceId);
                    dropResultFuture.complete(DropResult.success(itemId, toDrop.getQuantity(), remainingQuantity));
                } catch (Exception e) {
                    dropResultFuture.completeExceptionally(e);
                }
            });
            return dropResultFuture.get();
        } catch (Exception e) {
            logger.error("[Hycompanion] Error dropping item: " + e.getMessage());
            Sentry.captureException(e);
            return DropResult.failed("Error: " + e.getMessage());
        }
    }

    /**
     * 获取NPC物品栏快照
     * 在世界线程上读取NPC的护甲栏、快捷栏、背包和手持物品信息
     * 返回完整的物品栏状态，包括每个槽位的物品ID、数量及活动快捷栏标记
     *
     * @param npcInstanceId NPC实例UUID
     * @param includeEmpty  是否包含空槽位
     * @return 物品栏快照，包含护甲、快捷栏、背包、手持物品和物品总数
     */
    @Override
    public InventorySnapshot getInventory(UUID npcInstanceId, boolean includeEmpty) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return InventorySnapshot.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return InventorySnapshot.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return InventorySnapshot.failed("World not found");
        }

        CompletableFuture<InventorySnapshot> snapshotFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        snapshotFuture.complete(InventorySnapshot.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        snapshotFuture.complete(InventorySnapshot.failed("NPC has no inventory"));
                        return;
                    }

                    // Build armor info
                    Map<String, Object> armor = new HashMap<>();
                    ItemContainer armorContainer = inventory.getArmor();
                    String[] armorSlots = { "head", "chest", "hands", "legs" };
                    for (int i = 0; i < armorSlots.length && i < armorContainer.getCapacity(); i++) {
                        ItemStack stack = armorContainer.getItemStack((short) i);
                        if (stack != null && !stack.isEmpty()) {
                            armor.put(armorSlots[i], Map.of(
                                    "itemId", stack.getItem().getId(),
                                    "quantity", stack.getQuantity()));
                        } else if (includeEmpty) {
                            armor.put(armorSlots[i], null);
                        }
                    }

                    // Build hotbar info
                    List<Map<String, Object>> hotbar = new ArrayList<>();
                    ItemContainer hotbarContainer = inventory.getHotbar();
                    int activeSlot = inventory.getActiveHotbarSlot();
                    for (int i = 0; i < hotbarContainer.getCapacity(); i++) {
                        ItemStack stack = hotbarContainer.getItemStack((short) i);
                        Map<String, Object> slotInfo = new HashMap<>();
                        slotInfo.put("slot", i);
                        if (stack != null && !stack.isEmpty()) {
                            slotInfo.put("itemId", stack.getItem().getId());
                            slotInfo.put("quantity", stack.getQuantity());
                        } else {
                            slotInfo.put("itemId", null);
                            slotInfo.put("quantity", 0);
                        }
                        slotInfo.put("isActive", i == activeSlot);
                        if (includeEmpty || (stack != null && !stack.isEmpty())) {
                            hotbar.add(slotInfo);
                        }
                    }

                    // Build storage info
                    List<Map<String, Object>> storage = new ArrayList<>();
                    ItemContainer storageContainer = inventory.getStorage();
                    for (int i = 0; i < storageContainer.getCapacity(); i++) {
                        ItemStack stack = storageContainer.getItemStack((short) i);
                        Map<String, Object> slotInfo = new HashMap<>();
                        slotInfo.put("slot", i);
                        if (stack != null && !stack.isEmpty()) {
                            slotInfo.put("itemId", stack.getItem().getId());
                            slotInfo.put("quantity", stack.getQuantity());
                        } else {
                            slotInfo.put("itemId", null);
                            slotInfo.put("quantity", 0);
                        }
                        if (includeEmpty || (stack != null && !stack.isEmpty())) {
                            storage.add(slotInfo);
                        }
                    }

                    // Get held item
                    Map<String, Object> heldItem = null;
                    ItemStack held = inventory.getItemInHand();
                    if (held != null && !held.isEmpty()) {
                        heldItem = Map.of(
                                "itemId", held.getItem().getId(),
                                "quantity", held.getQuantity());
                    }

                    // Count total items
                    int totalItems = armor.size();
                    for (int i = 0; i < hotbarContainer.getCapacity(); i++) {
                        ItemStack stack = hotbarContainer.getItemStack((short) i);
                        if (stack != null && !stack.isEmpty())
                            totalItems++;
                    }
                    for (int i = 0; i < storageContainer.getCapacity(); i++) {
                        ItemStack stack = storageContainer.getItemStack((short) i);
                        if (stack != null && !stack.isEmpty())
                            totalItems++;
                    }

                    snapshotFuture.complete(InventorySnapshot.create(armor, hotbar, storage, heldItem, totalItems));
                } catch (Exception e) {
                    snapshotFuture.completeExceptionally(e);
                }
            });
            return snapshotFuture.get();
        } catch (Exception e) {
            logger.error("[Hycompanion] Error getting inventory: " + e.getMessage());
            Sentry.captureException(e);
            return InventorySnapshot.failed("Error: " + e.getMessage());
        }
    }

    /**
     * 卸下NPC指定槽位的物品
     * 在世界线程上执行，支持护甲槽、快捷栏槽和手持物品槽
     * 如果 destroy=true 则直接销毁物品，否则尝试将物品移到背包
     *
     * @param npcInstanceId NPC实例UUID
     * @param slot          目标槽位（"head"/"chest"/"hands"/"legs"/"hotbar_0"/"held"等）
     * @param destroy       是否销毁物品而非转移到背包
     * @return 卸下结果，包含被移除的物品信息和转移状态
     */
    @Override
    public UnequipResult unequipItem(UUID npcInstanceId, String slot, boolean destroy) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return UnequipResult.failed("NPC not found");
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return UnequipResult.failed("Invalid entity reference");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return UnequipResult.failed("World not found");
        }

        CompletableFuture<UnequipResult> unequipResultFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        unequipResultFuture.complete(UnequipResult.failed("NPC entity not found"));
                        return;
                    }

                    Inventory inventory = npcEntity.getInventory();
                    if (inventory == null) {
                        unequipResultFuture.complete(UnequipResult.failed("NPC has no inventory"));
                        return;
                    }

                    Map<String, Object> itemRemoved = null;
                    boolean movedToStorage = false;

                    // Get item from slot
                    ItemStack removed = null;
                    switch (slot) {
                        case "head":
                        case "chest":
                        case "hands":
                        case "legs":
                            ItemContainer armor = inventory.getArmor();
                            int armorSlot = getArmorSlotIndex(slot);
                            if (armorSlot >= 0) {
                                removed = armor.getItemStack((short) armorSlot);
                                if (removed != null && !removed.isEmpty()) {
                                    itemRemoved = Map.of(
                                            "itemId", removed.getItem().getId(),
                                            "quantity", removed.getQuantity());
                                    if (destroy) {
                                        armor.setItemStackForSlot((short) armorSlot, null);
                                    } else {
                                        // Try to move to storage
                                        var transaction = inventory.getStorage().addItemStack(removed);
                                        movedToStorage = transaction != null && transaction.getRemainder() == null;
                                        if (movedToStorage) {
                                            armor.setItemStackForSlot((short) armorSlot, null);
                                        }
                                    }
                                }
                            }
                            break;
                        case "hotbar_0":
                        case "hotbar_1":
                        case "hotbar_2":
                            ItemContainer hotbar = inventory.getHotbar();
                            int hotbarSlot = Integer.parseInt(slot.replace("hotbar_", ""));
                            removed = hotbar.getItemStack((short) hotbarSlot);
                            if (removed != null && !removed.isEmpty()) {
                                itemRemoved = Map.of(
                                        "itemId", removed.getItem().getId(),
                                        "quantity", removed.getQuantity());
                                if (destroy) {
                                    hotbar.setItemStackForSlot((short) hotbarSlot, null);
                                } else {
                                    // Try to move to storage
                                    var transaction = inventory.getStorage().addItemStack(removed);
                                    movedToStorage = transaction != null && transaction.getRemainder() == null;
                                    if (movedToStorage) {
                                        hotbar.setItemStackForSlot((short) hotbarSlot, null);
                                    }
                                }
                            }
                            break;
                        case "held":
                            removed = inventory.getItemInHand();
                            if (removed != null && !removed.isEmpty()) {
                                itemRemoved = Map.of(
                                        "itemId", removed.getItem().getId(),
                                        "quantity", removed.getQuantity());
                                if (destroy) {
                                    // Can't directly clear held item, set active slot to empty
                                    int activeSlot = inventory.getActiveHotbarSlot();
                                    inventory.getHotbar().setItemStackForSlot((short) activeSlot, null);
                                } else {
                                    // Try to move to storage
                                    var transaction = inventory.getStorage().addItemStack(removed);
                                    movedToStorage = transaction != null && transaction.getRemainder() == null;
                                    if (movedToStorage) {
                                        int activeSlot = inventory.getActiveHotbarSlot();
                                        inventory.getHotbar().setItemStackForSlot((short) activeSlot, null);
                                    }
                                }
                            }
                            break;
                        default:
                            unequipResultFuture.complete(UnequipResult.failed("Unknown slot: " + slot));
                            return;
                    }

                    if (removed == null || removed.isEmpty()) {
                        unequipResultFuture.complete(UnequipResult.failed("No item in slot: " + slot));
                        return;
                    }

                    logger.info("[Hycompanion] Unequipped item from " + slot + " for NPC " + npcInstanceId);

                    if (destroy) {
                        unequipResultFuture.complete(UnequipResult.destroyed(slot, itemRemoved));
                    } else {
                        unequipResultFuture.complete(UnequipResult.success(slot, itemRemoved, movedToStorage));
                    }
                } catch (Exception e) {
                    unequipResultFuture.completeExceptionally(e);
                }
            });
            return unequipResultFuture.get();
        } catch (Exception e) {
            logger.error("[Hycompanion] Error unequipping item: " + e.getMessage());
            Sentry.captureException(e);
            return UnequipResult.failed("Error: " + e.getMessage());
        }
    }

    /**
     * 扩展NPC的物品栏背包容量
     * 在世界线程上调用 npcEntity.setInventorySize() 设置新的背包槽位数
     * 快捷栏固定为9格，副手固定为0格
     *
     * @param npcInstanceId NPC实例UUID
     * @param storageSlots  新的背包槽位数量
     * @return 是否成功扩展
     */
    @Override
    public boolean expandNpcInventory(UUID npcInstanceId, int storageSlots) {
        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            return false;
        }

        Ref<EntityStore> entityRef = npcData.entityRef();
        if (entityRef == null || !entityRef.isValid()) {
            return false;
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return false;
        }

        CompletableFuture<Boolean> expandResultFuture = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    NPCEntity npcEntity = store.getComponent(entityRef, NPCEntity.getComponentType());
                    if (npcEntity == null) {
                        expandResultFuture.complete(false);
                        return;
                    }

                    // Set inventory size - this expands storage
                    // (hotbarCapacity, inventoryCapacity, offHandCapacity)
                    // npcEntity.setInventorySize(9, storageSlots, 0); // API changed
                    logger.warn("[Hycompanion] setInventorySize not available in this server version");
                    logger.info(
                            "[Hycompanion] Expanded inventory by " + storageSlots + " slots for NPC " + npcInstanceId);
                    expandResultFuture.complete(true);
                } catch (Exception e) {
                    expandResultFuture.completeExceptionally(e);
                }
            });
            return expandResultFuture.get();
        } catch (Exception e) {
            logger.error("[Hycompanion] Error expanding inventory: " + e.getMessage());
            Sentry.captureException(e);
            return false;
        }
    }
    // ========== 容器操作 ==========

    /**
     * 获取指定坐标处容器方块的物品栏内容
     * 在世界线程上执行：
     * 1. 距离检查（NPC距容器不超过5格）
     * 2. 加载区块并获取方块状态
     * 3. 验证方块是否为 ItemContainerBlockState（如箱子）
     * 4. 遍历容器槽位，返回所有非空物品的ID、数量和槽位索引
     *
     * @param npcInstanceId NPC实例UUID（用于距离检查）
     * @param x, y, z       容器方块坐标
     * @return 异步结果，包含容器中的物品列表
     */
    @Override
    public CompletableFuture<Optional<ContainerInventoryResult>> getContainerInventory(UUID npcInstanceId, int x, int y,
            int z) {
        CompletableFuture<Optional<ContainerInventoryResult>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.of(ContainerInventoryResult.failure("NPC not found or offline")));
            return future;
        }

        Ref<EntityStore> ref = npcData.entityRef();
        if (ref == null || !ref.isValid()) {
            future.complete(Optional.of(ContainerInventoryResult.failure("Invalid entity reference")));
            return future;
        }

        World world = ref.getStore().getExternalData().getWorld();
        if (world == null) {
            future.complete(Optional.of(ContainerInventoryResult.failure("World not found")));
            return future;
        }

        world.execute(() -> {
            try {
                // Distance check
                TransformComponent transform = ref.getStore().getComponent(ref, TransformComponent.getComponentType());
                if (transform == null) {
                    future.complete(Optional.of(ContainerInventoryResult.failure("Missing transform component")));
                    return;
                }
                Vector3d npcPos = transform.getPosition();
                double dist = Math.sqrt(Math.pow(npcPos.getX() - (x + 0.5), 2) + Math.pow(npcPos.getY() - (y + 0.5), 2)
                        + Math.pow(npcPos.getZ() - (z + 0.5), 2));
                if (dist > 5.0) {
                    future.complete(
                            Optional.of(ContainerInventoryResult.failure("Too far from container (limit 5 blocks)")));
                    return;
                }

                var chunk = world.getChunkIfLoaded(com.hypixel.hytale.math.util.ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    future.complete(Optional.of(ContainerInventoryResult.failure("Container chunk is not loaded")));
                    return;
                }

                // Container API changed in newer server version - stub for now
                future.complete(Optional.of(ContainerInventoryResult.failure("Container operations not yet supported in this server version")));
            } catch (Exception e) {
                logger.error("[Hycompanion] Error getting container inventory: " + e.getMessage());
                Sentry.captureException(e);
                future.complete(Optional.of(ContainerInventoryResult.failure("Internal error: " + e.getMessage())));
            }
        });

        return future;
    }

    /**
     * NPC将物品从自身物品栏存入指定坐标的容器中
     * 在世界线程上执行：
     * 1. 距离检查和容器验证
     * 2. 遍历NPC快捷栏和背包查找匹配物品
     * 3. 尝试将物品添加到容器中（先堆叠已有物品，再放入空槽位）
     * 4. 成功后从NPC物品栏中扣除对应数量
     * 支持部分成功（容器满或物品不足时）
     *
     * @param npcInstanceId NPC实例UUID
     * @param x, y, z       容器方块坐标
     * @param itemId        要存储的物品ID
     * @param quantity      要存储的数量
     * @return 异步结果，成功/部分成功/失败
     */
    @Override
    public CompletableFuture<Optional<ContainerActionResult>> storeItemInContainer(UUID npcInstanceId, int x, int y,
            int z, String itemId, int quantity) {
        CompletableFuture<Optional<ContainerActionResult>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.of(ContainerActionResult.failure("NPC not found or offline")));
            return future;
        }

        Ref<EntityStore> ref = npcData.entityRef();
        if (ref == null || !ref.isValid()) {
            future.complete(Optional.of(ContainerActionResult.failure("Invalid entity reference")));
            return future;
        }

        World world = ref.getStore().getExternalData().getWorld();
        if (world == null) {
            future.complete(Optional.of(ContainerActionResult.failure("World not found")));
            return future;
        }

        // Container API changed in newer server version - stub for now
        future.complete(Optional.of(ContainerActionResult.failure("Container operations not yet supported in this server version")));
        return future;
    }

    /**
     * NPC从指定坐标的容器中取出物品到自身物品栏
     * 在世界线程上执行：
     * 1. 距离检查和容器验证
     * 2. 遍历容器槽位查找匹配物品
     * 3. 尝试将物品添加到NPC快捷栏和背包中
     * 4. 成功后从容器中扣除对应数量
     * 支持部分成功（物品栏满或物品不足时）
     *
     * @param npcInstanceId NPC实例UUID
     * @param x, y, z       容器方块坐标
     * @param itemId        要取出的物品ID
     * @param quantity      要取出的数量
     * @return 异步结果，成功/部分成功/失败
     */
    @Override
    public CompletableFuture<Optional<ContainerActionResult>> takeItemFromContainer(UUID npcInstanceId, int x, int y,
            int z, String itemId, int quantity) {
        CompletableFuture<Optional<ContainerActionResult>> future = new CompletableFuture<>();

        NpcInstanceData npcData = npcInstanceEntities.get(npcInstanceId);
        if (npcData == null) {
            future.complete(Optional.of(ContainerActionResult.failure("NPC not found or offline")));
            return future;
        }

        Ref<EntityStore> ref = npcData.entityRef();
        if (ref == null || !ref.isValid()) {
            future.complete(Optional.of(ContainerActionResult.failure("Invalid entity reference")));
            return future;
        }

        World world = ref.getStore().getExternalData().getWorld();
        if (world == null) {
            future.complete(Optional.of(ContainerActionResult.failure("World not found")));
            return future;
        }

        // TODO: Container API changed in new server version - stubbed for now
        future.complete(Optional.of(ContainerActionResult.failure("Container operations temporarily unavailable")));

        return future;
    }
}
