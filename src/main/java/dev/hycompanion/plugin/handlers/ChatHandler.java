package dev.hycompanion.plugin.handlers;

import io.sentry.Sentry;

import com.google.gson.JsonObject;
import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.core.context.ContextBuilder;
import dev.hycompanion.plugin.core.context.WorldContext;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.core.npc.NpcSearchResult;
import dev.hycompanion.plugin.network.SocketManager;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 处理玩家发送给 NPC 的聊天消息
 *
 * 拦截玩家聊天，检查附近范围内的 NPC，
 * 收集上下文信息，然后将消息发送到后端进行 LLM 处理。
 *
 * TODO: [HYTALE-API] 应连接到 Hytale 的聊天事件系统
 */
public class ChatHandler {

    /** NPC 管理器，用于查找和管理 NPC 实例 */
    private final NpcManager npcManager;
    /** 上下文构建器，收集玩家周围的世界信息 */
    private final ContextBuilder contextBuilder;
    /** 日志记录器 */
    private final PluginLogger logger;
    /** 插件配置（可热重载） */
    private PluginConfig config;
    /** Socket.IO 管理器，用于与后端通信 */
    private SocketManager socketManager;
    /** Hytale 游戏 API */
    private HytaleAPI hytaleAPI;

    /** 每个 NPC 的聊天请求队列（按 NPC UUID 索引） */
    private final Map<UUID, Queue<ChatRequest>> npcQueues = new ConcurrentHashMap<>();
    /** 记录当前正在等待后端响应的 NPC 集合 */
    private final Set<UUID> processingNpcs = ConcurrentHashMap.newKeySet();
    /** 每个 NPC 的超时任务追踪 */
    private final Map<UUID, ScheduledFuture<?>> pendingTimeouts = new ConcurrentHashMap<>();

    /** 后端响应超时时间（60 秒） */
    private static final long BACKEND_TIMEOUT_SECONDS = 60;
    /** 超时调度器（守护线程） */
    private final ScheduledExecutorService timeoutScheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "Hycompanion-ChatTimeout");
        t.setDaemon(true);
        return t;
    });

    /**
     * 聊天请求记录 - 封装玩家和消息内容
     */
    private record ChatRequest(GamePlayer player, String message) {
    }

    /**
     * 构造函数 - 初始化聊天处理器
     */
    public ChatHandler(NpcManager npcManager, ContextBuilder contextBuilder, PluginLogger logger, PluginConfig config) {
        this.npcManager = npcManager;
        this.contextBuilder = contextBuilder;
        this.logger = logger;
        this.config = config;
    }

    /**
     * 设置 Hytale API（构造后注入，用于表情支持等）
     */
    public void setHytaleAPI(HytaleAPI hytaleAPI) {
        this.hytaleAPI = hytaleAPI;
    }

    /**
     * 设置 Socket 管理器（构造后注入）
     */
    public void setSocketManager(SocketManager socketManager) {
        this.socketManager = socketManager;
    }

    /**
     * 设置更新后的配置（用于热重载）
     */
    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * 处理玩家的聊天消息
     *
     * 此方法应从 Hytale 的聊天事件监听器中调用。
     * 流程：检查连接状态 -> 获取玩家位置 -> 查找附近 NPC -> 加入队列 -> 发送到后端
     *
     * @param player  发送消息的玩家
     * @param message 聊天消息内容
     * @return 如果消息被处理（发送给 NPC）返回 true，否则返回 false
     */
    public boolean handleChat(GamePlayer player, String message) {
        logger.info("[ChatHandler] handleChat called for player: " + player.name() + ", message: " + message);

        if (socketManager == null || !socketManager.isConnected()) {
            logger.warn("[ChatHandler] Socket not connected, ignoring chat (socketManager=" +
                    (socketManager == null ? "null" : "not connected") + ")");
            return false;
        }

        if (message == null || message.isBlank()) {
            return false;
        }

        // 查找玩家附近的 NPC
        Location playerLocation = player.location();

        // 尽可能从 API 获取最新的玩家位置
        // 传入的 GamePlayer record 可能是稍旧的快照
        if (hytaleAPI != null) {
            Optional<GamePlayer> freshPlayer = hytaleAPI.getPlayer(player.id());
            if (freshPlayer.isPresent()) {
                player = freshPlayer.get();
                playerLocation = player.location();
            }
        }

        if (playerLocation == null) {
            logger.warn("[ChatHandler] Player location unknown, cannot find nearby NPCs");
            return false;
        }

        logger.info("[ChatHandler] Player location: " + playerLocation.toCoordString() +
                ", checking " + npcManager.getNpcCount() + " registered NPCs");

        // 获���附近的 NPC - 先尝试使用已跟踪的位置，再回退到实时位置查询
        List<NpcSearchResult> nearbyNpcs = findNpcsNearPlayer(playerLocation);

        if (nearbyNpcs.isEmpty()) {
            logger.info("[ChatHandler] No NPCs near player: " + player.name());
            return false;
        }

        // 找到最近的 NPC
        NpcInstanceData closestNpc = findClosestNpc(nearbyNpcs, playerLocation);
        if (closestNpc == null) {
            return false;
        }

        // 将请求加入队列而非立即发送（确保消息按序处理）
        UUID npcId = closestNpc.entityUuid();
        if (npcId != null) {
            npcQueues.computeIfAbsent(npcId, k -> new ConcurrentLinkedQueue<>())
                    .add(new ChatRequest(player, message));

            logger.info("[ChatHandler] Enqueued chat from " + player.name() + " for NPC " + npcId +
                    ". Queue size: " + npcQueues.get(npcId).size());

            processQueue(npcId, closestNpc);
        } else {
            logger.warn("[ChatHandler] Cannot enqueue chat - NPC has no entity UUID");
        }

        return true;
    }

    /**
     * 处理 NPC 聊天队列中的下一条消息
     * 如果 NPC 正在处理中，则等待当前请求完成后再处理
     */
    private void processQueue(UUID npcId, NpcInstanceData npcInstance) {
        if (npcId == null)
            return;

        // 如果正在处理请求，等待完成（ActionExecutor 会调用 onNpcAction）
        if (processingNpcs.contains(npcId)) {
            logger.debug("[ChatHandler] NPC " + npcId + " is busy processing a request, waiting...");
            return;
        }

        Queue<ChatRequest> queue = npcQueues.get(npcId);
        if (queue == null || queue.isEmpty()) {
            return;
        }

        // 标记为处理中
        processingNpcs.add(npcId);
        ChatRequest request = queue.poll();

        if (request == null) {
            processingNpcs.remove(npcId);
            return;
        }

        // 为此请求安排超时计时
        scheduleTimeout(npcId);

        // 处理时让空闲的 NPC 转向玩家
        if (hytaleAPI != null) {
            hytaleAPI.rotateNpcInstanceToward(npcId, request.player.location());
        }

        // 确保处理期间思考指示器处于开启状态
        // 只要队列中有请求在处理，指示器就保持显示
        if (hytaleAPI != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    hytaleAPI.showThinkingIndicator(npcId);

                    // 如果启用了表情功能，还会触发"思考"表情（可选）
                    if (config.gameplay().emotesEnabled()) {
                        hytaleAPI.triggerNpcEmote(npcId, "thinking");
                    }
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });
        }

        // 构建世界上下文（轻量级，同步执行）
        WorldContext context = contextBuilder.buildContext(request.player.location());
        JsonObject contextJson = context.toJson();

        // 记录发送的聊天消息（异步执行，避免阻塞）
        if (config.logging().logChat()) {
            final String logMsg = "[" + request.player.name() + "] → [" + npcInstance.npcData().name() + "]: "
                    + request.message;
            java.util.concurrent.CompletableFuture.runAsync(() -> logger.info(logMsg));
        }

        // 获取 NPC 实例 UUID（实体 UUID）
        String npcInstanceUuid = npcId.toString();

        // 通过 Socket.IO 发送给后端
        logger.info("[Socket] Sending PLUGIN_CHAT to backend: npcId=" + npcInstance.npcData().externalId() +
                ", instanceUuid=" + npcInstanceUuid +
                ", playerId=" + request.player.id() + ", playerName=" + request.player.name() +
                ", messageLength=" + request.message.length());

        socketManager.sendChat(
                npcInstance.npcData().externalId(),
                npcInstanceUuid,
                request.player.id(),
                request.player.name(),
                request.message,
                contextJson);
    }

    /**
     * NPC 动作完成回调（从后端接收到响应时触发）
     * 表示当前请求已完成（NPC 已回复），可以处理队列中的下一条消息。
     */
    public void onNpcAction(UUID npcId) {
        if (npcId == null)
            return;

        logger.debug("[ChatHandler] Action received for NPC " + npcId + ", advancing queue");

        // 取消等待中的超时任务
        cancelTimeout(npcId);

        // 标记当前请求为已完成
        processingNpcs.remove(npcId);

        Queue<ChatRequest> queue = npcQueues.get(npcId);

        // 如果队列中还有更多消息，处理下一条
        if (queue != null && !queue.isEmpty()) {
            // Need NpcInstanceData again - fetch it
            NpcInstanceData npcInstance = hytaleAPI.getNpcInstance(npcId);
            if (npcInstance != null) {
                processQueue(npcId, npcInstance);
            } else {
                // 如果 NPC 仍然有效则不应发生此情况
                processingNpcs.remove(npcId); // 安全起见清除状态
            }
        } else {
            // 队列为空 - NPC 思考完成，隐藏思考指示器
            if (hytaleAPI != null) {
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        hytaleAPI.hideThinkingIndicator(npcId);
                    } catch (Exception e) {
                        Sentry.captureException(e);
                    }
                });
            }
        }
    }

    /**
     * 中止 NPC 的当前操作并清空其消息队列
     * 当后端发生错误时调用（如 LLM_ERROR）
     *
     * @param npcId NPC 实例的 UUID
     */
    public void abortOperation(UUID npcId) {
        if (npcId == null)
            return;

        logger.info("[ChatHandler] Aborting operation for NPC " + npcId);

        // 取消等待中的超时任务
        cancelTimeout(npcId);

        // 从处理集合中移除
        processingNpcs.remove(npcId);

        // 清空该 NPC 的消息队列
        Queue<ChatRequest> queue = npcQueues.get(npcId);
        if (queue != null) {
            int cleared = queue.size();
            queue.clear();
            logger.info("[ChatHandler] Cleared " + cleared + " pending request(s) for NPC " + npcId);
        }

        // 隐藏思考指示器
        if (hytaleAPI != null) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    hytaleAPI.hideThinkingIndicator(npcId);
                } catch (Exception e) {
                    Sentry.captureException(e);
                }
            });
        }
    }

    /**
     * 为等待后端响应的请求安排超时任务
     * 如果在 BACKEND_TIMEOUT_SECONDS 内没有收到响应，操作将被中止
     */
    private void scheduleTimeout(UUID npcId) {
        // 先取消现有的超时任务
        cancelTimeout(npcId);

        ScheduledFuture<?> timeoutTask = timeoutScheduler.schedule(() -> {
            logger.warn("[ChatHandler] Backend response timeout for NPC " + npcId +
                    " after " + BACKEND_TIMEOUT_SECONDS + " seconds");

            // 从待处理超时列表中移除（任务已完成）
            pendingTimeouts.remove(npcId);

            // 中止操作以清除状态和队列
            abortOperation(npcId);

            // 可选：通知玩家请求已超时
            Queue<ChatRequest> queue = npcQueues.get(npcId);
            if (queue != null && !queue.isEmpty()) {
                ChatRequest request = queue.peek();
                if (request != null && hytaleAPI != null) {
                    hytaleAPI.sendErrorMessage(request.player.id(),
                            "[NPC] Request timed out. Please try again.");
                }
            }
        }, BACKEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        pendingTimeouts.put(npcId, timeoutTask);
        logger.debug("[ChatHandler] Scheduled timeout for NPC " + npcId +
                " (" + BACKEND_TIMEOUT_SECONDS + "s)");
    }

    /**
     * 取消指定 NPC 的等待超时任务
     */
    private void cancelTimeout(UUID npcId) {
        ScheduledFuture<?> existingTask = pendingTimeouts.remove(npcId);
        if (existingTask != null) {
            existingTask.cancel(false);
            logger.debug("[ChatHandler] Cancelled timeout for NPC " + npcId);
        }
    }

    /**
     * 关闭超时调度器。应在插件禁用时调用。
     */
    public void shutdown() {
        logger.info("[ChatHandler] Shutting down timeout scheduler");
        timeoutScheduler.shutdownNow();
        pendingTimeouts.clear();
    }

    /**
     * 查找玩家附近的 NPC 实例（玩家在 NPC 的聊天范围内）
     *
     * 使用 NpcManager 的空间索引，逐个检查每个 NPC 的聊天范围。
     *
     * @param playerLocation 玩家的当前位置
     * @return 在聊天范围内的 NPC 实例列表
     */
    private List<NpcSearchResult> findNpcsNearPlayer(Location playerLocation) {
        // 如果 NPC 没有特定范围，使用全局配置作为默认值

        logger.info("[ChatHandler] Searching for NPCs near "
                + playerLocation.toCoordString());

        // 使用 NpcManager 的空间索引并检查每个 NPC 的聊天范围
        List<NpcSearchResult> nearbyNpcs = npcManager.getNpcsNear(playerLocation, 20);

        logger.info("[ChatHandler] Found " + nearbyNpcs.size() + " NPCs within range");
        return nearbyNpcs;
    }

    // /**
    // * Handle a chat message directed at a specific NPC
    // *
    // * @param player The player sending the message
    // * @param npcInstanceUuid Target NPC's instance UUID
    // * @param message The chat message content
    // * @return True if the message was sent, false otherwise
    // */
    // public boolean handleDirectChat(GamePlayer player, UUID npcInstanceUuid,
    // String message) {
    // if (socketManager == null || !socketManager.isConnected()) {
    // logger.debug("Socket not connected, ignoring chat");
    // return false;
    // }

    // if (message == null || message.isBlank()) {
    // return false;
    // }

    // // Verify NPC exists
    // Optional<NpcData> npcOpt = npcManager.getNpc(npcExternalId);
    // if (npcOpt.isEmpty()) {
    // logger.debug("NPC not found: " + npcExternalId);
    // return false;
    // }

    // NpcData npc = npcOpt.get();

    // // Rotate idle NPCs to face the player on direct chat
    // if (hytaleAPI != null && player.location() != null) {
    // hytaleAPI.rotateNpcInstanceToward(npc.externalId(), player.location());
    // }

    // // Build context
    // WorldContext context = contextBuilder.buildContext(player.location());
    // JsonObject contextJson = context.toJson();

    // // Get NPC instance UUID (entity UUID) for per-instance memory tracking
    // String npcInstanceUuid = npc.entityUuid() != null
    // ? npc.entityUuid().toString()
    // : null;

    // // Send to backend
    // if (config.logging().logChat()) {
    // logger.info("[" + player.name() + "] → [" + npc.name() + "]: " + message);
    // }

    // socketManager.sendChat(
    // npc.externalId(),
    // npcInstanceUuid,
    // player.id(),
    // player.name(),
    // message,
    // contextJson);

    // return true;
    // }

    /**
     * 从搜索结果中找到距离指定位置最近的 NPC
     */
    private NpcInstanceData findClosestNpc(List<NpcSearchResult> npcResults, Location location) {
        NpcInstanceData closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (NpcSearchResult result : npcResults) {
            // NpcManager 搜索已提供精确的位置和距离信息
            // 无需再次查询 API（避免超时风险）

            if (result.distance() < closestDistance) {
                closestDistance = result.distance();
                closest = result.instance();
            }
        }

        if (closest != null) {
            logger.info(
                    "[ChatHandler] findClosestNpc Selected: " + closest.entityUuid() + " Distance: " + closestDistance);
        }

        // 兜底处理：列表非空但未找到最近 NPC（理论上不应发生）
        return closest != null ? closest : (!npcResults.isEmpty() ? npcResults.get(0).instance() : null);
    }
}
