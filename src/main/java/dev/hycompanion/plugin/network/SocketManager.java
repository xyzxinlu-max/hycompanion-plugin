package dev.hycompanion.plugin.network;

import io.sentry.Sentry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.ServerInfo;
import dev.hycompanion.plugin.config.NpcConfigManager;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.handlers.ActionExecutor;
import dev.hycompanion.plugin.handlers.ChatHandler;
import dev.hycompanion.plugin.role.RoleGenerator;
import dev.hycompanion.plugin.utils.PluginLogger;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;

import java.net.URI;
import java.util.UUID;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Socket.IO 客户端管理器，负责与后端云服务的通信
 *
 * 处理连接、断线重连以及事件路由分发。
 * 使用 Java 虚拟线程（Virtual Threads）实现非阻塞的事件处理。
 */
public class SocketManager {

    /** 后端 Socket.IO 服务器的 URL 地址 */
    private final String url;
    /** API 密钥，用于身份验证（非 final 以便运行时更新） */
    private String apiKey;
    /** 当前服务器的基本信息（版本号、在线人数等） */
    private final ServerInfo serverInfo;
    /** 动作执行器，负责将后端返回的 MCP 工具动作在游戏中执行 */
    private final ActionExecutor actionExecutor;
    /** NPC 管理器，追踪所有已注册的 NPC 数据和实例 */
    private final NpcManager npcManager;
    /** NPC 配置管理器，缓存 NPC 定义信息 */
    private final NpcConfigManager npcConfigManager;
    /** 角色生成器，为 NPC 生成并缓存角色文件 */
    private final RoleGenerator roleGenerator;
    /** 日志记录器 */
    private final PluginLogger logger;
    /** 插件配置（非 final 以便运行时更新） */
    private PluginConfig config;
    /** Hytale API 接口，用于与游戏服务器交互 */
    private final HytaleAPI hytaleAPI;
    /** JSON 序列化/反序列化工具 */
    private final Gson gson;
    /** 聊天处理器，用于在出错时中止操作 */
    private ChatHandler chatHandler;

    /** Socket.IO 客户端实例 */
    private Socket socket;
    /** 标记当前是否已连接到后端 */
    private final AtomicBoolean connected = new AtomicBoolean(false);
    /** 重连尝试次数计数器 */
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    /** 标记当前连接是否为重新连接（用于触发同步回调） */
    private final AtomicBoolean isReconnection = new AtomicBoolean(false);
    /** 重连调度器，用于延时重连 */
    private ScheduledExecutorService reconnectScheduler;

    /** 重连同步完成后的回调，用于触发 NPC 实体发现 */
    private Runnable onReconnectSyncComplete;

    /** 标记是否为主动断开连接（防止自动重连） */
    private final AtomicBoolean intentionalDisconnect = new AtomicBoolean(false);

    /**
     * 构造函数，初始化 SocketManager 的所有依赖项
     *
     * @param url               后端服务器 URL
     * @param apiKey            API 认证密钥
     * @param serverInfo        服务器信息
     * @param actionExecutor    动作执行器
     * @param npcManager        NPC 管理器
     * @param npcConfigManager  NPC 配置管理器
     * @param roleGenerator     角色生成器
     * @param logger            日志记录器
     * @param config            插件配置
     * @param hytaleAPI         Hytale API 接口
     */
    public SocketManager(
            String url,
            String apiKey,
            ServerInfo serverInfo,
            ActionExecutor actionExecutor,
            NpcManager npcManager,
            NpcConfigManager npcConfigManager,
            RoleGenerator roleGenerator,
            PluginLogger logger,
            PluginConfig config,
            HytaleAPI hytaleAPI) {
        this.url = url;
        this.apiKey = apiKey;
        this.serverInfo = serverInfo;
        this.actionExecutor = actionExecutor;
        this.npcManager = npcManager;
        this.npcConfigManager = npcConfigManager;
        this.roleGenerator = roleGenerator;
        this.logger = logger;
        this.config = config;
        this.hytaleAPI = hytaleAPI;
        this.gson = new Gson();
    }

    /**
     * 设置重连同步完成后的回调函数
     * 用于在 NPC 重新同步完成后触发实体发现流程
     *
     * @param callback 在重连批量同步完成后执行的回调
     */
    public void setOnReconnectSyncComplete(Runnable callback) {
        this.onReconnectSyncComplete = callback;
    }

    /**
     * 更新插件配置
     */
    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * 设置聊天处理器，用于在发生错误时中止操作
     */
    public void setChatHandler(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    /**
     * 更新 API 密钥并强制重新连接
     * 如果新密钥为空或与当前密钥相同则忽略
     */
    public void updateApiKey(String newApiKey) {
        if (newApiKey == null || newApiKey.equals(this.apiKey)) {
            return;
        }

        this.apiKey = newApiKey;
        logger.info("[Socket] Updating API Key and reconnecting...");

        // 断开当前连接并使用新密钥重新连接
        disconnect();
        connect();
    }

    /**
     * 连接到后端服务器
     * 配置 Socket.IO 选项（认证、传输方式），注册事件处理器并发起连接
     */
    public void connect() {
        try {
            // 重置主动断开标记
            intentionalDisconnect.set(false);

            // 配置 Socket.IO 连接选项
            IO.Options options = IO.Options.builder()
                    .setAuth(Map.of("apiKey", apiKey))
                    .setReconnection(false) // 禁用内置重连，使用自定义重连逻辑
                    .setTransports(new String[] { "websocket", "polling" })
                    .build();

            socket = IO.socket(URI.create(url), options);

            // 注册所有事件处理器
            registerEventHandlers();

            // 发起连接
            socket.connect();

        } catch (Exception e) {
            logger.error("Failed to create socket connection", e);
            Sentry.captureException(e);
            scheduleReconnect();
        }
    }

    /**
     * 断开与后端的连接
     * 此方法是幂等的，可安全多次调用
     * 会停止重连调度器、移除事件监听器并关闭 Socket
     */
    public void disconnect() {
        long startTime = System.currentTimeMillis();
        String threadName = Thread.currentThread().getName();

        // 已经断开，无需重复操作
        if (socket == null && !connected.get()) {
            logger.debug("[Socket] Already disconnected (thread: " + threadName + ")");
            return;
        }

        logger.info("[Socket] Disconnecting from backend (thread: " + threadName + ")...");

        intentionalDisconnect.set(true);

        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            logger.info("[Socket] Reconnect scheduler shut down");
        }

        if (socket != null) {
            try {
                // Remove listeners to prevent "disconnect" event triggering auto-reconnect
                socket.off();
                socket.disconnect();
                socket.close();
                logger.info("[Socket] Socket closed");
            } catch (NoClassDefFoundError e) {
                // During server shutdown, the plugin classloader may fail to load
                // inner classes (e.g., Socket$8). This is expected behavior during
                // plugin unload - just clean up our references.
                logger.debug("[Socket] NoClassDefFoundError during socket close (expected during shutdown): " + e.getMessage());
                Sentry.captureException(e);
            } catch (Exception e) {
                // Catch any other exceptions during close to ensure we always clean up
                logger.debug("[Socket] Exception during socket close: " + e.getMessage());
                Sentry.captureException(e);
            } finally {
                socket = null;
            }
        }

        connected.set(false);
        logger.info("[Socket] Disconnected from backend in " + (System.currentTimeMillis() - startTime) + "ms");
    }

    /**
     * 检查是否已连接到后端
     */
    public boolean isConnected() {
        return connected.get();
    }


    /**
     * 向后端发送聊天消息
     * 将玩家对 NPC 说的话发送到云端 LLM 进行处理
     *
     * @param npcId           NPC 的外部 ID（模板标识符）
     * @param npcInstanceUuid 已生成的 NPC 实体 UUID（未生成时为 null）
     * @param playerId        玩家 ID
     * @param playerName      玩家显示名称
     * @param message         聊天消息内容
     * @param context         世界上下文信息（位置、时间、附近玩家等）
     */
    public void sendChat(String npcId, String npcInstanceUuid, String playerId, String playerName, String message,
            JsonObject context) {
        if (!isConnected()) {
            logger.warn("Cannot send chat - not connected");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("npcId", npcId);
        if (npcInstanceUuid != null) {
            payload.addProperty("npcInstanceUuid", npcInstanceUuid);
        }
        payload.addProperty("playerId", playerId);
        payload.addProperty("playerName", playerName);
        payload.addProperty("message", message);
        payload.add("context", context != null ? context : new JsonObject());

        if (config.logging().logChat()) {
            logger.debug("Sending chat: " + playerId + " → " + npcId +
                    (npcInstanceUuid != null ? " (instance: " + npcInstanceUuid + ")" : "") +
                    ": " + message);
        }

        socket.emit(SocketEvents.PLUGIN_CHAT, new org.json.JSONObject(payload.toString()));
    }

    /**
     * 向后端请求 NPC 同步
     * 后端将返回当前所有 NPC 的最新数据
     */
    public void requestSync() {
        if (!isConnected()) {
            logger.warn("Cannot request sync - not connected");
            return;
        }

        socket.emit(SocketEvents.PLUGIN_REQUEST_SYNC, new org.json.JSONObject());
        logger.debug("NPC sync requested");
    }

    /**
     * 向后端报告 NPC 的可用动画列表
     * 在 NPC 实体被发现/生成后发送，以便后端为动画构建动态 MCP 工具
     *
     * @param npcId      NPC 的外部 ID
     * @param animations 从模型的 AnimationSets 中获取的动画名称列表
     */
    public void sendNpcAnimations(String npcId, java.util.List<String> animations) {
        if (!isConnected()) {
            logger.warn("Cannot send NPC animations - not connected");
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("npcId", npcId);

        // Convert List to JSONArray
        com.google.gson.JsonArray animArray = new com.google.gson.JsonArray();
        for (String anim : animations) {
            animArray.add(anim);
        }
        payload.add("animations", animArray);

        socket.emit(SocketEvents.PLUGIN_NPC_ANIMATIONS, new org.json.JSONObject(payload.toString()));
        logger.info("[Socket] Sent " + animations.size() + " animations for NPC: " + npcId);
    }

    /**
     * 向后端报告服务器上可用的方块列表
     * 启动时发送一次，以便后端为 LLM 构建可搜索的方块目录
     * （例如支持 "找木头"、"找石头" 等查询）
     *
     * @param blocks 包含丰富元数据的 BlockInfo 对象列表
     */
    public void sendAvailableBlocks(java.util.List<dev.hycompanion.plugin.core.world.BlockInfo> blocks) {
        if (!isConnected()) {
            logger.warn("Cannot send available blocks - not connected");
            return;
        }

        if (blocks == null || blocks.isEmpty()) {
            logger.warn("No blocks to send to backend");
            return;
        }

        com.google.gson.JsonObject payload = new com.google.gson.JsonObject();

        // 将 BlockInfo 列表转换为 JSON 数组
        com.google.gson.JsonArray blocksArray = new com.google.gson.JsonArray();
        for (dev.hycompanion.plugin.core.world.BlockInfo block : blocks) {
            com.google.gson.JsonObject blockObj = new com.google.gson.JsonObject();
            blockObj.addProperty("blockId", block.blockId());
            blockObj.addProperty("displayName", block.displayName());

            // 材质类型数组
            com.google.gson.JsonArray materialsArray = new com.google.gson.JsonArray();
            for (String material : block.materialTypes()) {
                materialsArray.add(material);
            }
            blockObj.add("materialTypes", materialsArray);

            // 关键词数组
            com.google.gson.JsonArray keywordsArray = new com.google.gson.JsonArray();
            for (String keyword : block.keywords()) {
                keywordsArray.add(keyword);
            }
            
            blockObj.add("keywords", keywordsArray);

            // 分类数组（可能为空）
            com.google.gson.JsonArray categoriesArray = new com.google.gson.JsonArray();
            for (String category : block.categories()) {
                categoriesArray.add(category);
            }
            blockObj.add("categories", categoriesArray);

            blocksArray.add(blockObj);
        }
        payload.add("blocks", blocksArray);

        // 添加汇总统计信息
        payload.addProperty("totalCount", blocks.size());

        // 按材质类型统计数量
        com.google.gson.JsonObject materialStats = new com.google.gson.JsonObject();
        java.util.Map<String, Integer> materialCounts = new java.util.HashMap<>();
        for (dev.hycompanion.plugin.core.world.BlockInfo block : blocks) {
            for (String material : block.materialTypes()) {
                materialCounts.merge(material, 1, Integer::sum);
            }
        }
        for (java.util.Map.Entry<String, Integer> entry : materialCounts.entrySet()) {
            materialStats.addProperty(entry.getKey(), entry.getValue());
        }
        payload.add("materialStats", materialStats);

        socket.emit(SocketEvents.PLUGIN_BLOCKS_AVAILABLE, new org.json.JSONObject(payload.toString()));
        logger.info("[Socket] Sent " + blocks.size() + " available blocks to backend");
    }

    /**
     * 注册所有 Socket.IO 事件处理器
     * 每个事件处理器都在虚拟线程中执行，避免阻塞 Socket.IO 的 IO 线程
     */
    private void registerEventHandlers() {
        // 连接建立成功
        socket.on(Socket.EVENT_CONNECT, args -> {
            // 使用虚拟线程实现非阻塞处理
            Thread.ofVirtual().start(() -> onConnect());
        });

        // 连接错误
        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            Thread.ofVirtual().start(() -> onConnectError(args));
        });

        // 连接断开
        socket.on(Socket.EVENT_DISCONNECT, args -> {
            Thread.ofVirtual().start(() -> onDisconnect(args));
        });

        // 后端动作事件（MCP 工具执行结果）
        socket.on(SocketEvents.BACKEND_ACTION, args -> {
            Thread.ofVirtual().start(() -> onAction(args));
        });

        // NPC 同步事件
        socket.on(SocketEvents.BACKEND_NPC_SYNC, args -> {
            Thread.ofVirtual().start(() -> onNpcSync(args));
        });

        // 后端错误事件
        socket.on(SocketEvents.BACKEND_ERROR, args -> {
            Thread.ofVirtual().start(() -> onError(args));
        });

        // 思维链状态更新事件（NPC 思考指示器）
        socket.on(SocketEvents.BACKEND_COT_UPDATE, args -> {
            Thread.ofVirtual().start(() -> onCotUpdate(args));
        });

        // 感知查询事件 —— 后端在 Gemini 推理过程中请求真实游戏数据
        socket.on(SocketEvents.BACKEND_QUERY, args -> {
            Thread.ofVirtual().start(() -> onQuery(args));
        });
    }

    /**
     * 处理连接成功事件
     * 重置重连计数器，发送连接载荷（API 密钥和服务器信息）
     */
    private void onConnect() {
        boolean wasReconnect = reconnectAttempts.get() > 0;
        connected.set(true);
        reconnectAttempts.set(0);
        isReconnection.set(wasReconnect); // Track reconnection state for sync callback

        if (wasReconnect) {
            logger.info("[Socket] Reconnected to backend! NPCs will be re-synced.");
        } else {
            logger.info("[Socket] Connected to backend!");
        }

        // 构建并发送连接载荷
        JsonObject payload = new JsonObject();
        payload.addProperty("apiKey", apiKey);

        JsonObject serverInfoJson = new JsonObject();
        serverInfoJson.addProperty("version", serverInfo.version());
        serverInfoJson.addProperty("playerCount", serverInfo.playerCount());
        payload.add("serverInfo", serverInfoJson);

        logger.info("[Socket] Sending PLUGIN_CONNECT with apiKey and serverInfo");
        socket.emit(SocketEvents.PLUGIN_CONNECT, new org.json.JSONObject(payload.toString()));

        // Block discovery/report is intentionally disabled.
        // We now rely on runtime scanBlocks results instead of plugin:blocks_available.
        /*
         * logger.info("[Socket] Will report available blocks in 2 seconds...");
         * Thread.ofVirtual().start(() -> {
         * try {
         * Thread.sleep(2000); // Wait for connection to be fully established
         * if (!isConnected()) {
         * logger.debug("[Socket] No longer connected, skipping block report");
         * return;
         * }
         * 
         * // Discover blocks once and cache them
         * if (cachedBlocks == null) {
         * logger.info("[Socket] Discovering blocks for the first time...");
         * cachedBlocks = hytaleAPI.getAvailableBlocks();
         * if (cachedBlocks == null || cachedBlocks.isEmpty()) {
         * logger.warn("[Socket] No blocks discovered");
         * return;
         * }
         * logger.info("[Socket] Discovered and cached " + cachedBlocks.size() + " blocks");
         * }
         * 
         * // Send cached blocks (on every connection, including reconnections)
         * if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
         * sendAvailableBlocks(cachedBlocks);
         * blocksSent.set(true);
         * }
         * // } catch (InterruptedException e) {
         * // Thread.currentThread().interrupt();
         * } catch (Exception e) {
         * logger.error("[Socket] Error reporting available blocks: " + e.getMessage());
         * Sentry.captureException(e);
         * }
         * });
         */
    }

    /**
     * 处理连接错误事件
     * 标记为未连接并安排重连
     */
    private void onConnectError(Object[] args) {
        connected.set(false);

        String errorMsg = args.length > 0 ? args[0].toString() : "Unknown error";
        logger.error("Connection error: " + errorMsg);

        scheduleReconnect();
    }

    /**
     * 处理断开连接事件
     * 如果是主动断开则不重连，否则自动安排重连
     */
    private void onDisconnect(Object[] args) {
        connected.set(false);

        String reason = args.length > 0 ? args[0].toString() : "Unknown reason";

        // 如果是主动断开（如服务器关闭），则不进行自动重连
        if (intentionalDisconnect.get()) {
            // logger.info("Disconnected from backend (intentional): " + reason);
            return;
        }

        logger.warn("Disconnected from backend: " + reason);

        scheduleReconnect();
    }

    /**
     * 检查服务器是否正在关闭
     * 关闭期间应跳过事件处理，避免干扰 Hytale 的关闭流程
     */
    private boolean isShuttingDown() {
        // Check the static shutdown flag from HycompanionEntrypoint
        try {
            return dev.hycompanion.plugin.HycompanionEntrypoint.isShuttingDown();
        } catch (Exception e) {
            Sentry.captureException(e);
            return false;
        }
    }

    /**
     * 处理来自后端的动作事件（MCP 工具执行）
     * 解析动作载荷并交给 ActionExecutor 在游戏中执行
     */
    private void onAction(Object[] args) {
        if (args.length == 0)
            return;

        // 关键：在服务器关闭期间跳过动作处理，避免干扰 Hytale 世界线程的关闭流程
        if (isShuttingDown()) {
            logger.debug("[Socket] Skipping action processing - server shutting down");
            // Call ack with error if present to prevent client hanging
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                io.socket.client.Ack ack = (io.socket.client.Ack) args[args.length - 1];
                ack.call("{\"error\": \"Server shutting down\"}");
            }
            return;
        }

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            // 检查是否有确认回调（Ack）
            io.socket.client.Ack ack = null;
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                ack = (io.socket.client.Ack) args[args.length - 1];
            }

            String npcInstanceUuidStr = json.optString("npcInstanceUuid");

            // 从实例 UUID 字符串解析 NPC 实例 ID

            java.util.UUID npcInstanceId;
            if (npcInstanceUuidStr != null && !npcInstanceUuidStr.isEmpty()) {
                npcInstanceId = java.util.UUID.fromString(npcInstanceUuidStr);
            } else {
                logger.error("No npc instance found for UUID: " + npcInstanceUuidStr);
                if (ack != null)
                    ack.call("{\"error\": \"Missing/Invalid npcInstanceUuid\"}");
                return;
            }

            Optional<NpcData> npcData = this.npcManager.getNpcByEntityUuid(npcInstanceId);

            if (npcData.isEmpty()) {
                logger.error("No npc instance found for UUID: " + npcInstanceId);
                // We still pass it to executor because executor handles 'unknown NPC' logic
                // with optional Ack now
                // via execute(...) call below.
            }

            String playerId = json.getString("playerId");
            String action = json.getString("action");
            org.json.JSONObject params = json.optJSONObject("params");

            if (config.logging().logActions()) {
                logger.debug("Action received: " + action + " for NPC " + npcInstanceId + " (template: "
                        + (npcData.isPresent() ? npcData.get().externalId() : "unknown") + ")");
            }

            actionExecutor.execute(npcInstanceId, playerId, action, params, ack);

        } catch (Exception e) {
            logger.error("Failed to process action", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 处理来自后端的感知查询事件
     * 后端在 Gemini function calling 循环中需要真实游戏数据时调用
     * 复用 ActionExecutor 的现有感知方法，通过 ack 返回结果
     */
    private void onQuery(Object[] args) {
        if (args.length == 0) return;

        if (isShuttingDown()) {
            logger.debug("[Socket] Skipping query processing - server shutting down");
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                ((io.socket.client.Ack) args[args.length - 1]).call("{\"error\": \"Server shutting down\"}");
            }
            return;
        }

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            io.socket.client.Ack ack = null;
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                ack = (io.socket.client.Ack) args[args.length - 1];
            }

            if (ack == null) {
                logger.warn("[Query] Received query without ack callback - ignoring");
                return;
            }

            String npcInstanceUuidStr = json.optString("npcInstanceUuid");
            String action = json.optString("action");
            org.json.JSONObject params = json.optJSONObject("params");

            if (npcInstanceUuidStr == null || npcInstanceUuidStr.isEmpty()) {
                ack.call("{\"error\": \"Missing npcInstanceUuid\"}");
                return;
            }

            UUID npcInstanceId = UUID.fromString(npcInstanceUuidStr);

            logger.debug("[Query] " + action + " for NPC " + npcInstanceId);

            // 使用 executeQuery 而非 execute —— 不推进聊天队列，因为 NPC 仍在思考中
            actionExecutor.executeQuery(npcInstanceId, json.optString("playerId", ""), action, params, ack);

        } catch (Exception e) {
            logger.error("[Query] Failed to process query", e);
            Sentry.captureException(e);
            if (args.length > 1 && args[args.length - 1] instanceof io.socket.client.Ack) {
                ((io.socket.client.Ack) args[args.length - 1]).call("{\"error\": \"Exception: " + e.getMessage() + "\"}");
            }
        }
    }

    /**
     * 处理来自后端的 NPC 同步事件
     * 支持批量创建（bulk_create）、创建/更新（create/update）和删除（delete）操作
     */
    private void onNpcSync(Object[] args) {
        if (args.length == 0)
            return;

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            String syncAction = json.getString("action");

            switch (syncAction) {
                case "bulk_create" -> {
                    // 记录同步前已注册的 NPC（来自缓存/持久化），这些是孤立 NPC 清理的候选对象
                    java.util.Set<String> preSyncNpcIds = new java.util.HashSet<>();
                    for (dev.hycompanion.plugin.core.npc.NpcData npc : npcManager.getAllNpcs()) {
                        preSyncNpcIds.add(npc.externalId());
                    }
                    
                    // 批量处理 NPC 同步以降低延迟
                    org.json.JSONArray npcsArray = json.getJSONArray("npcs");
                    java.util.Set<String> validExternalIds = new java.util.HashSet<>();
                    
                    for (int i = 0; i < npcsArray.length(); i++) {
                        org.json.JSONObject npcJson = npcsArray.getJSONObject(i);
                        handleNpcCreateOrUpdate(npcJson);
                        // Collect valid external IDs for orphan cleanup
                        if (npcJson.has("externalId")) {
                            validExternalIds.add(npcJson.getString("externalId"));
                        }
                    }
                    logger.info("Bulk synced " + npcsArray.length() + " NPCs");
                    
                    // 清理后端中已不存在的孤立 NPC
                    // 传入同步前的 NPC ID 集合，只清理之前已注册的 NPC
                    cleanupOrphanedNpcs(preSyncNpcIds, validExternalIds);

                    // 重连后的批量同步完成后，触发实体发现流程
                    if (isReconnection.compareAndSet(true, false) && onReconnectSyncComplete != null) {
                        logger.info("[Socket] Triggering entity discovery after reconnection sync...");
                        Thread.ofVirtual().start(onReconnectSyncComplete);
                    }
                }
                case "create", "update" -> handleNpcCreateOrUpdate(json.getJSONObject("npc"));
                case "delete" -> handleNpcDelete(json.getJSONObject("npc"));
                default -> logger.warn("Unknown sync action: " + syncAction);
            }

        } catch (Exception e) {
            logger.error("Failed to process NPC sync", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 处理 NPC 创建/更新同步
     * 解析 NPC 数据，注册到管理器，更新能力配置，并缓存角色文件
     */
    private void handleNpcCreateOrUpdate(org.json.JSONObject json) {
        try {
            logger.info("Received NPC raw data: " + json.toString());
            String id = json.getString("id");
            String externalId = json.getString("externalId");
            String name = json.getString("name");
            Number chatDistance = json.getNumber("chatDistance");
            String personality = json.getString("personality");
            // 问候语是可选的 - null 表示 NPC 不会主动向玩家打招呼
            String greeting = json.isNull("greeting") ? null : json.optString("greeting", null);
            // 将空字符串转为 null 以保持一致性
            if (greeting != null && greeting.isEmpty()) {
                greeting = null;
            }
            String alignment = json.optString("alignment", "neutral");

            // 解析道德档案（MoralProfile）
            NpcData.MoralProfile moralProfile = NpcData.MoralProfile.DEFAULT;
            if (json.has("moralProfile")) {
                org.json.JSONObject mpJson = json.getJSONObject("moralProfile");
                var ideals = new java.util.ArrayList<String>();
                if (mpJson.has("ideals")) {
                    org.json.JSONArray idealsArray = mpJson.getJSONArray("ideals");
                    for (int i = 0; i < idealsArray.length(); i++) {
                        ideals.add(idealsArray.getString(i));
                    }
                }
                String resistance = mpJson.optString("persuasionResistance", "strong");
                moralProfile = new NpcData.MoralProfile(ideals, resistance);
            }

            boolean isInvincible = json.optBoolean("isInvincible", false);
            boolean preventKnockback = json.optBoolean("preventKnockback", false);
            boolean broadcastReplies = json.optBoolean("broadcastReplies", false);

            // 创建 NPC 数据对象
            NpcData npc = NpcData.fromSync(id, externalId, name, personality, greeting, chatDistance, broadcastReplies, alignment,
                    moralProfile, isInvincible, preventKnockback);

            // 在管理器中注册 NPC
            npcManager.registerNpc(npc);
            npcConfigManager.upsertNpc(npc);

            // 更新已存在的 NPC 实例的能力配置
            if (hytaleAPI != null) {
                hytaleAPI.updateNpcCapabilities(externalId, npc);
            }

            // 生成并缓存角色文件，供下次启动使用
            if (roleGenerator != null) {
                // Pass the full JSON to handle root properties like preventKnockback
                RoleGenerator.NpcRoleData roleData = RoleGenerator.NpcRoleData.fromNpcJson(json);
                if (roleGenerator.generateAndCacheRole(roleData)) {
                    logger.debug("Role file cached for: " + externalId);
                }
            }

            logger.info("NPC synced: " + externalId + " (" + name + ")");

            // 持久化 NPC 的实体发现现在通过 HycompanionEntrypoint 中的 AllNPCsLoadedEvent 触发
            // 这确保我们在 Hytale 完全加载所有 NPC 实体之后才进行发现，而不是使用任意延迟

        } catch (Exception e) {
            logger.error("Failed to create/update NPC", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 处理 NPC 删除同步
     * 从管理器中注销 NPC 并删除缓存的角色文件
     */
    private void handleNpcDelete(org.json.JSONObject json) {
        try {
            String externalId = json.getString("externalId");

            npcManager.unregisterNpc(externalId);
            npcConfigManager.removeNpc(externalId);

            // 删除缓存的角色文件
            if (roleGenerator != null) {
                roleGenerator.removeRole(externalId);
            }

            logger.info("NPC removed: " + externalId);

        } catch (Exception e) {
            logger.error("Failed to delete NPC", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 清理孤立的 Hycompanion NPC（后端中已不存在的 NPC）
     * 在 bulk_create 同步后调用，用于删除服务器离线期间在控制面板中被删除的 NPC
     *
     * 仅清理 Hycompanion 管理的 NPC，不会影响世界中的其他 NPC
     *
     * @param preSyncNpcIds    同步前已注册的外部 ID 集合（来自缓存）
     * @param validExternalIds 后端中仍存在的有效外部 ID 集合
     */
    private void cleanupOrphanedNpcs(java.util.Set<String> preSyncNpcIds, java.util.Set<String> validExternalIds) {
        try {
            // 仅检查同步前已存在的 NPC - 当前 bulk_create 新注册的 NPC 不算孤立
            int cleanupCount = 0;
            for (String externalId : preSyncNpcIds) {
                // 如果这个之前存在的 NPC 不在后端的有效列表中，则视为孤立 NPC
                if (!validExternalIds.contains(externalId)) {
                    // 在清理前获取 NPC 数据用于日志记录
                    java.util.Optional<dev.hycompanion.plugin.core.npc.NpcData> npcOpt = npcManager.getNpc(externalId);
                    String npcName = npcOpt.map(dev.hycompanion.plugin.core.npc.NpcData::name).orElse(externalId);
                    
                    logger.info("[Sync] Cleaning up orphaned NPC: " + externalId + " (" + npcName + ")");
                    
                    // 从 NpcManager 中注销（会同时取消生成实例）
                    npcManager.unregisterNpc(externalId);
                    
                    // 从配置缓存中移除
                    npcConfigManager.removeNpc(externalId);
                    
                    // 删除角色文件
                    if (roleGenerator != null) {
                        roleGenerator.removeRole(externalId);
                    }
                    
                    cleanupCount++;
                }
            }
            
            if (cleanupCount > 0) {
                logger.info("[Sync] Cleaned up " + cleanupCount + " orphaned NPC(s)");
            } else if (!preSyncNpcIds.isEmpty()) {
                logger.debug("[Sync] No orphaned NPCs to cleanup (" + preSyncNpcIds.size() + " pre-existing NPCs checked)");
            }
        } catch (Exception e) {
            logger.error("[Sync] Failed to cleanup orphaned NPCs", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 处理来自后端的错误事件
     * 包括 API 密钥验证失败、LLM 错误等，会向玩家显示错误信息并通知管理员
     */
    private void onError(Object[] args) {
        if (args.length == 0)
            return;

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            String code = json.optString("code", "UNKNOWN");
            String message = json.optString("message", "Unknown error");
            String npcInstanceUuidStr = json.optString("npcInstanceUuid", json.optString("npcInstanceId", null));

            java.util.UUID npcInstanceId = null;
            if (npcInstanceUuidStr != null && !npcInstanceUuidStr.isEmpty()) {
                npcInstanceId = java.util.UUID.fromString(npcInstanceUuidStr);
            }

            String playerId = json.optString("playerId", null);

            logger.error("Backend error [" + code + "]: " + message);

            // 处理特定错误类型
            if ("INVALID_API_KEY".equals(code)) {
                logger.error("Invalid API key! Please check your config.yml");
                disconnect();
                return;
            }

            // LLM 错误 - 在玩家聊天中显示红色错误消息并中止操作
            if ("LLM_ERROR".equals(code) && npcInstanceId != null) {
                // 中止当前 NPC 的世界操作
                if (chatHandler != null) {
                    chatHandler.abortOperation(npcInstanceId);
                }

                // 获取 NPC 名称用于显示
                String npcName = npcInstanceId.toString();
                var npcOpt = npcManager.getNpcByEntityUuid(npcInstanceId);
                if (npcOpt.isPresent()) {
                    npcName = npcOpt.get().name();
                }

                // 向触发错误的玩家发送面向用户的错误消息
                if (playerId != null) {
                    String errorMessage = "[" + npcName + "] " + message;
                    actionExecutor.execute(npcInstanceId, playerId, "error_message",
                            new org.json.JSONObject().put("message", errorMessage));
                }

                // 处理调试信息，向管理员（OP 玩家）发送红色调试消息
                if (json.has("debug")) {
                    org.json.JSONObject debug = json.getJSONObject("debug");
                    String errorType = debug.optString("type", "UNKNOWN");
                    String details = debug.optString("details", "No details available");
                    String suggestion = debug.optString("suggestion", "");

                    // 构建发送给管理员的调试消息
                    StringBuilder debugMessage = new StringBuilder();
                    debugMessage.append("[").append(npcName).append("] ");
                    debugMessage.append("Error Type: ").append(errorType).append(" | ");
                    debugMessage.append("Details: ").append(details);
                    if (!suggestion.isEmpty()) {
                        debugMessage.append(" | Suggestion: ").append(suggestion);
                    }

                    // 向所有管理员广播调试消息
                    hytaleAPI.broadcastDebugMessageToOps(debugMessage.toString());
                }
            }

        } catch (Exception e) {
            logger.error("Failed to process error", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 处理来自后端的思维链（Chain-of-Thought）状态更新
     * 在 NPC 头顶显示动态的"思考中"指示器
     */
    private void onCotUpdate(Object[] args) {
        if (args.length == 0)
            return;

        try {
            org.json.JSONObject json = (org.json.JSONObject) args[0];

            String npcInstanceUuidStr = json.optString("npcInstanceUuid");
            String type = json.optString("type", "thinking");
            String message = json.optString("message", "Thinking");
            String toolName = json.optString("toolName", "");

            if (npcInstanceUuidStr == null || npcInstanceUuidStr.isEmpty()) {
                logger.debug("CoT update missing npcInstanceUuid");
                return;
            }

            UUID npcInstanceId;
            try {
                npcInstanceId = UUID.fromString(npcInstanceUuidStr);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid npcInstanceUuid in CoT update: " + npcInstanceUuidStr);
                Sentry.captureException(e);
                return;
            }

            // 根据状态类型构建显示文本
            String displayText;
            switch (type) {
                case "thinking":
                    displayText = message != null && !message.isEmpty() ? message : "Thinking";
                    break;
                case "tool_executing":
                    displayText = (message != null && !message.isEmpty()) ? message : 
                                  (toolName != null && !toolName.isEmpty() ? "Executing: " + toolName : "Working...");
                    break;
                case "tool_completed":
                    displayText = message != null && !message.isEmpty() ? message : "Done";
                    break;
                case "tool_failed":
                    displayText = message != null && !message.isEmpty() ? message : "Failed";
                    break;
                case "completed":
                    displayText = message != null && !message.isEmpty() ? message : "Done";
                    break;
                default:
                    displayText = message != null && !message.isEmpty() ? message : "Thinking";
            }

            // 根据状态类型显示或隐藏思考指示器
            if ("completed".equals(type) || "tool_completed".equals(type) || "tool_failed".equals(type)) {
                // 完成/失败时隐藏思考指示器
                hytaleAPI.hideThinkingIndicator(npcInstanceId);
            } else if("tool_executing".equals(type)) {
                // 正在执行工具时显示思考指示器
                hytaleAPI.showThinkingIndicator(npcInstanceId);
            }

            if (config.logging().logActions()) {
                logger.debug("CoT update [" + type + "] for NPC " + npcInstanceId + ": " + displayText);
            }

        } catch (Exception e) {
            logger.error("Failed to process CoT update", e);
            Sentry.captureException(e);
        }
    }

    /**
     * 安排断线重连
     * 使用配置的延迟时间进行重连，会无限次尝试直到连接成功或手动停止
     */
    private void scheduleReconnect() {
        if (!config.connection().reconnectEnabled()) {
            logger.info("Reconnection disabled");
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        // Removed max attempts check - try to reconnect indefinitely

        int delay = config.connection().reconnectDelayMs();
        logger.info("Reconnecting in " + delay + "ms (attempt " + attempts + ")");

        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Hycompanion-Socket-Reconnect");
                t.setDaemon(true); // daemon thread
                return t;
            });
        }

        reconnectScheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }
}
