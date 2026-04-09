package dev.hycompanion.plugin.handlers;

import io.sentry.Sentry;

import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.config.PluginConfig;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcManager;
import dev.hycompanion.plugin.utils.PluginLogger;
import org.json.JSONObject;

import java.util.Optional;
import java.util.UUID;

/**
 * 执行从后端接收到的 MCP 工具动作
 *
 * 所有 NPC 的输出（文本消息、表情动作、交易、任务等）都通过此类执行，
 * 这些输出是 LLM 发起 MCP 工具调用的结果。
 *
 * @see dev.hycompanion.plugin.network.SocketManager
 */
public class ActionExecutor {

    /** Hytale 游戏 API，用于操作游戏内实体和世界 */
    private final HytaleAPI hytaleAPI;
    /** NPC 管理器，负责 NPC 数据的注册与查询 */
    private final NpcManager npcManager;
    /** 日志记录器 */
    private final PluginLogger logger;
    /** 插件配置（可热重载） */
    private PluginConfig config;
    /** 聊天处理器，用于在动作完成后通知队列推进 */
    private ChatHandler chatHandler;

    /**
     * 构造函数 - 初始化动作执行器
     * @param hytaleAPI 游戏 API 接口
     * @param npcManager NPC 管理器
     * @param logger 日志记录器
     * @param config 插件配置
     */
    public ActionExecutor(HytaleAPI hytaleAPI, NpcManager npcManager, PluginLogger logger, PluginConfig config) {
        this.hytaleAPI = hytaleAPI;
        this.npcManager = npcManager;
        this.logger = logger;
        this.config = config;
    }

    /**
     * 设置更新后的配置（用于热重载）
     */
    public void setConfig(PluginConfig config) {
        this.config = config;
    }

    /**
     * 设置聊天处理器，以便在动作执行完成后通知其推进消息队列
     */
    public void setChatHandler(ChatHandler chatHandler) {
        this.chatHandler = chatHandler;
    }

    /**
     * 执行从后端接收到的动作（带可选的确认回调）
     *
     * 这是动作分发的核心方法。根据动作类型（say、emote、move_to 等）
     * 将请求路由到对应的执行方法。
     *
     * @param npcInstanceId NPC 实例的 UUID
     * @param playerId      目标玩家 ID
     * @param action        动作类型（say, emote, open_trade, give_quest, move_to 等）
     * @param params        动作参数（JSON 格式）
     * @param ack           可选的 Socket.IO 确认回调
     */
    public void execute(UUID npcInstanceId, String playerId, String action, JSONObject params,
            io.socket.client.Ack ack) {
        // 思考指示器不在这里隐藏，由 ChatHandler 根据队列状态统一管理

        // 优先处理 error_message 动作 - 它不需要 NPC 实例
        // 确保即使找不到 NPC，错误消息也能发送给玩家
        if ("error_message".equals(action)) {
            try {
                executeErrorMessage(npcInstanceId, playerId, params);
                if (ack != null)
                    ack.call("{\"status\": \"success\"}");
            } catch (Exception e) {
                logger.error("Failed to execute error_message action", e);
                Sentry.captureException(e);
                if (ack != null)
                    ack.call("{\"error\": \"Exception: " + e.getMessage() + "\"}");
            }
            // 错误消息不推进聊天队列
            return;
        }

        // 解析 NPC 实例数据
        NpcInstanceData npcInstanceData = hytaleAPI.getNpcInstance(npcInstanceId);
        if (npcInstanceData == null) {
            // NPC 未在注册表中找到（可能是 external_id 与 id 不匹配）
            logger.warn("Action for unknown NPC: npcInstanceId=" + npcInstanceId + ", action=" + action +
                    ", playerId=" + playerId + " (NPC not in registry - possible external_id vs id mismatch)");
            if (ack != null) {
                ack.call("{\"error\": \"NPC instance not found\"}");
            }
            return;
        }

        // 根据动作类型分发执行 - 操作实体的动作会优雅地处理失败
        try {
            switch (action) {
                // === 基础交互动作 ===
                case "say" -> {                          // 发送文本消息给玩家
                    executeSay(npcInstanceData, playerId, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "emote" -> {                        // 播放 NPC 表情/动画
                    executeEmote(npcInstanceData, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "open_trade" -> {                   // 打开交易界面
                    executeOpenTrade(npcInstanceData, playerId);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "give_quest" -> {                   // 给予玩家任务
                    executeGiveQuest(npcInstanceData, playerId, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                // === 移动与跟随动作 ===
                case "move_to" -> {                      // 移动 NPC 到指定位置
                    executeMoveTo(npcInstanceData, params, ack);
                }
                case "follow_target" -> {                // 跟随目标玩家
                    executeFollowTarget(npcInstanceData, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "stop_following" -> {               // 停止跟随
                    executeStopFollowing(npcInstanceData);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                // === 战斗动作 ===
                case "start_attacking" -> {              // 开始攻击目标
                    executeStartAttacking(npcInstanceData, params);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                case "stop_attacking" -> {               // 停止攻击
                    executeStopAttacking(npcInstanceData);
                    if (ack != null)
                        ack.call("{\"status\": \"success\"}");
                }
                // === 感知/探测动作 ===
                case "find_block" -> executeFindBlock(npcInstanceData, params, ack);           // 搜索附近方块
                case "scan_blocks" -> executeScanBlocks(npcInstanceData, params, ack);         // 扫描周围方块类型
                case "scan_entities" -> executeScanEntities(npcInstanceData, params, ack);     // 扫描周围实体
                // case "find_entity" -> executeFindEntity(npcInstanceData, params, ack);      // 已弃用
                case "get_current_position" -> executeGetCurrentPosition(npcInstanceData, ack); // 获取 NPC 当前位置
                case "wait" -> executeWait(npcInstanceData, params, ack);                      // 等待指定时间
                case "teleport_player" -> executeTeleportPlayer(npcInstanceData, playerId, params, ack); // 传送玩家
                // === 物品栏管理动作 ===
                case "equip_item" -> executeEquipItem(npcInstanceData, params, ack);           // 装备物品
                case "break_block" -> executeBreakBlock(npcInstanceData, params, ack);         // 破坏方块
                case "pickup_item" -> executePickupItem(npcInstanceData, params, ack);         // 拾取掉落物
                case "use_held_item" -> executeUseHeldItem(npcInstanceData, params, ack);      // 使用手持物品
                case "drop_item" -> executeDropItem(npcInstanceData, params, ack);             // 丢弃物品
                case "get_inventory" -> executeGetInventory(npcInstanceData, params, ack);     // 获取物品栏内容
                case "unequip_item" -> executeUnequipItem(npcInstanceData, params, ack);       // 卸下装备
                // === 容器操作动作 ===
                case "get_container_inventory" -> executeGetContainerInventory(npcInstanceData, params, ack);     // 获取容器内容
                case "store_item_in_container" -> executeStoreItemInContainer(npcInstanceData, params, ack);      // 存入物品到容器
                case "take_item_from_container" -> executeTakeItemFromContainer(npcInstanceData, params, ack);    // 从容器取出物品
                default -> {
                    logger.warn("Unknown action received: " + action);
                    if (ack != null)
                        ack.call("{\"error\": \"Unknown action: " + action + "\"}");
                }
            }
        } catch (Exception e) {
            logger.error("Failed to execute action '" + action + "'", e);
            Sentry.captureException(e);
            if (ack != null) {
                ack.call("{\"error\": \"Exception: " + e.getMessage() + "\"}");
            }
        }

        // 通知 ChatHandler 动作已完成，以便推进消息队列中的下一条请求
        if (chatHandler != null) {
            chatHandler.onNpcAction(npcInstanceId);
        }
    }

    /**
     * 执行动作的简化重载（无确认回调）
     */
    public void execute(UUID npcInstanceId, String playerId, String action, JSONObject params) {
        execute(npcInstanceId, playerId, action, params, null);
    }

    /**
     * 执行感知查询 —— 不推进聊天队列
     * 用于后端在 Gemini function calling 循环中请求真实游戏数据的场景，
     * 此时 NPC 仍在"思考中"，不应触发 chatHandler.onNpcAction()
     */
    public void executeQuery(UUID npcInstanceId, String playerId, String action, JSONObject params,
            io.socket.client.Ack ack) {
        NpcInstanceData npcInstanceData = hytaleAPI.getNpcInstance(npcInstanceId);
        if (npcInstanceData == null) {
            logger.warn("[Query] NPC instance not found: " + npcInstanceId);
            if (ack != null) ack.call("{\"error\": \"NPC instance not found\"}");
            return;
        }

        // 确保 params 不为 null（某些感知方法会检查 params == null 并直接返回）
        if (params == null) params = new JSONObject();

        try {
            switch (action) {
                case "scan_blocks" -> executeScanBlocks(npcInstanceData, params, ack);
                case "find_block" -> executeFindBlock(npcInstanceData, params, ack);
                case "scan_entities" -> executeScanEntities(npcInstanceData, params, ack);
                case "get_current_position" -> executeGetCurrentPosition(npcInstanceData, ack);
                case "get_inventory" -> executeGetInventory(npcInstanceData, params, ack);
                case "get_container_inventory" -> executeGetContainerInventory(npcInstanceData, params, ack);
                default -> {
                    logger.warn("[Query] Unsupported query action: " + action);
                    if (ack != null) ack.call("{\"error\": \"Unsupported query: " + action + "\"}");
                }
            }
        } catch (Exception e) {
            logger.error("[Query] Failed to execute query '" + action + "'", e);
            Sentry.captureException(e);
            if (ack != null) ack.call("{\"error\": \"Exception: " + e.getMessage() + "\"}");
        }
        // 注意：不调用 chatHandler.onNpcAction() —— 查询不推进聊天队列
    }

    /**
     * 搜索方块 - 按标签在 NPC 附近搜索指定方块
     * 异步执行，通过 ack 回调返回搜索结果
     */
    private void executeFindBlock(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        String tag = params.optString("tag", "");
        int radius = params.optInt("radius", 10);

        if (tag.isEmpty()) {
            ack.call("{\"error\": \"Missing block tag\"}");
            return;
        }

        hytaleAPI.findBlock(npcInstanceData.entityUuid(), tag, radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"found\": false}");
            }
        }).exceptionally(e -> {
            logger.error("Error finding block: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error searching for block\"}");
            return null;
        });
    }

    /**
     * 扫描方块 - 扫描周围环境，返回所有唯一方块类型及其最近坐标
     */
    private void executeScanBlocks(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        int radius = params.optInt("radius", 16);
        boolean containersOnly = params.optBoolean("containersOnly", false);

        hytaleAPI.scanBlocks(npcInstanceData.entityUuid(), radius, containersOnly).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"blocks\": {}, \"radius\": " + radius + ", \"totalUniqueBlocks\": 0}");
            }
        }).exceptionally(e -> {
            logger.error("Error scanning blocks: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error scanning blocks\"}");
            return null;
        });
    }

    /**
     * 扫描实体 - 扫描周围环境，返回所有实体及其详细信息
     */
    private void executeScanEntities(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        int radius = params.optInt("radius", 32);

        hytaleAPI.scanEntities(npcInstanceData.entityUuid(), radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"entities\": [], \"radius\": " + radius + ", \"totalEntities\": 0}");
            }
        }).exceptionally(e -> {
            logger.error("Error scanning entities: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error scanning entities\"}");
            return null;
        });
    }

    /**
     * 搜索实体 - 按类型/名称在 NPC 附近搜索实体（已弃用）
     */
    private void executeFindEntity(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (params == null || ack == null)
            return;

        String name = params.optString("name", "");
        int radius = params.optInt("radius", 32);

        hytaleAPI.findEntity(npcInstanceData.entityUuid(), name, radius).thenAccept(result -> {
            if (result.isPresent()) {
                ack.call(new JSONObject(result.get()).toString());
            } else {
                ack.call("{\"found\": false}");
            }
        }).exceptionally(e -> {
            logger.error("Error finding entity: " + e.getMessage());
            Sentry.captureException(e);
            ack.call("{\"error\": \"Internal error searching for entity\"}");
            return null;
        });
    }

    /**
     * 发送消息 - 向玩家发送文本消息
     * 这是 NPC 与玩家沟通的主要方式。
     *
     * 如果 playerId 为空，则广播给所有附近的玩家。
     * 如果 params.broadcast 为 true，则在聊天距离内广播给所有附近玩家。
     *
     * 注意：Hytale 插件 API 暂不支持 NPC 头顶聊天气泡，
     * 消息通过玩家聊天栏发送。
     */
    private void executeSay(NpcInstanceData npcInstanceData, String playerId, JSONObject params) {

        if (npcInstanceData == null) {
            logger.warn("Say action for unknown NPC: playerId=" + playerId + ", params=" + params);
            return;
        }

        String message = params != null ? params.optString("message", "") : "";

        if (message.isEmpty()) {
            logger.warn("Say action with empty message for NPC: " + npcInstanceData.entityUuid());
            return;
        }

        // 检查是否启用广播（来自参数或 NPC 配置）
        boolean broadcastFromParams = params != null && params.optBoolean("broadcast", false);
        NpcData npc = npcInstanceData.npcData();
        boolean broadcastFromConfig = npc != null && npc.broadcastReplies();
        boolean shouldBroadcast = broadcastFromParams || broadcastFromConfig;

        // 检查是否提供了目标玩家 ID
        boolean hasPlayerId = playerId != null && !playerId.isEmpty();

        // 解析目标玩家名称用于消息格式化
        String targetPlayerName = "Player";
        if (hasPlayerId) {
            Optional<GamePlayer> playerOpt = hytaleAPI.getPlayer(playerId);
            if (playerOpt.isPresent()) {
                targetPlayerName = playerOpt.get().name();
            }
        }

        // 格式化消息，添加 NPC 名称前缀
        String npcName = npc != null ? npc.name() : npcInstanceData.entityUuid().toString();
        // 格式示例: [NPC] 战士 to Steve: 你好！
        String formattedMessage = formatNpcMessage(npcName, message, targetPlayerName);

        if (shouldBroadcast) {
            // 使用 NPC 的聊天距离广播给所有附近玩家
            Optional<Location> npcLoc = hytaleAPI.getNpcInstanceLocation(npcInstanceData.entityUuid());
            if (npcLoc.isEmpty()) {
                logger.warn("Cannot broadcast message - NPC location unknown: " + npcInstanceData.entityUuid());
                return;
            }

            // 使用 NPC 的聊天距离作为广播范围，若未设置则回退到配置中的问候范围
            Number chatDistance = npc != null ? npc.chatDistance() : null;
            double broadcastRange = chatDistance != null ? chatDistance.doubleValue()
                    : config.gameplay().greetingRange();

            java.util.List<dev.hycompanion.plugin.api.GamePlayer> nearbyPlayers = hytaleAPI
                    .getNearbyPlayers(npcLoc.get(), broadcastRange);

            if (nearbyPlayers.isEmpty()) {
                logger.debug("No nearby players to receive broadcast from NPC: " + npcInstanceData.entityUuid());
                return;
            }

            // 获取附近玩家 ID 列表
            java.util.List<String> playerIds = nearbyPlayers.stream()
                    .map(dev.hycompanion.plugin.api.GamePlayer::id)
                    .toList();

            // 向所有附近玩家广播消息
            hytaleAPI.broadcastNpcMessage(npcInstanceData.entityUuid(), playerIds, formattedMessage, message);

            if (config.logging().logActions()) {
                logger.info("NPC [" + npcInstanceData.entityUuid() + "] broadcasts to " +
                        nearbyPlayers.size() + " players (range: " + broadcastRange + "): " + message);
            }
        } else if (hasPlayerId) {
            // 回复时将 NPC 转向目标玩家
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    hytaleAPI.getPlayer(playerId).ifPresent(player -> {
                        if (player.location() != null) {
                            hytaleAPI.rotateNpcInstanceToward(npcInstanceData.entityUuid(), player.location());
                        }
                    });
                } catch (Exception e) {
                    Sentry.captureException(e);
                    // 忽略 - 旋转是可选操作
                }
            });

            // 仅发送给指定玩家
            hytaleAPI.sendNpcMessage(npcInstanceData.entityUuid(), playerId, formattedMessage, message);

            if (config.logging().logActions()) {
                logger.info(
                        "NPC [" + npcInstanceData.entityUuid() + "] (UUID: " + npcInstanceData.entityUuid().toString()
                                + ") says to [" + playerId + "]: " + message);
            }
        } else {
            // 没有指定玩家且未启用广播 - 记录警告
            logger.warn("Say action with no playerId and broadcast disabled for NPC: " + npcInstanceData.entityUuid());
        }
    }

    /**
     * 播放表情/动画 - 在 NPC 上播放指定动画
     *
     * 后端发送实际的动画名称（如 "Sit"、"Howl"、"Greet"），
     * 基于该 NPC 模型可用的动画。
     * 仅在 NPC 空闲时（未跟随或攻击）播放表情。
     */
    private void executeEmote(NpcInstanceData npcInstanceData, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();

        if (!config.gameplay().emotesEnabled()) {
            logger.debug("Animations disabled, skipping for NPC: " + npcInstanceId);
            return;
        }

        // NPC 忙碌时（跟随/攻击中）不播放表情
        if (hytaleAPI.isNpcBusy(npcInstanceId)) {
            logger.debug("Skipping emote for NPC " + npcInstanceId + " - NPC is busy (following/attacking)");
            return;
        }

        // 'animation' 参数包含模型中的实际动画名称
        // 向后兼容：回退到 'emotion' 参数
        String animationName = params != null
                ? params.optString("animation", params.optString("emotion", "Idle"))
                : "Idle";

        // 直接传递动画名称 - 无需映射
        // 动画名称应与模型的 AnimationSets 中的键匹配
        hytaleAPI.triggerNpcEmote(npcInstanceId, animationName);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] plays animation: " + animationName);
        }
    }

    /**
     * 打开交易 - 打开 NPC 与玩家之间的交易界面
     */
    private void executeOpenTrade(NpcInstanceData npcInstanceData, String playerId) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        hytaleAPI.openTradeInterface(npcInstanceId, playerId);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] opens trade with [" + playerId + "]");
        }
    }

    /**
     * 给予任务 - 向玩家提供一个任务
     */
    private void executeGiveQuest(NpcInstanceData npcInstanceData, String playerId, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        String questId = params != null ? params.optString("questId", "") : "";
        String questName = params != null ? params.optString("questName", questId) : questId;

        if (questId.isEmpty()) {
            logger.warn("Give quest action with empty questId for NPC: " + npcInstanceId);
            return;
        }

        hytaleAPI.offerQuest(npcInstanceId, playerId, questId, questName);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] offers quest '" + questName + "' to [" + playerId + "]");
        }
    }

    /**
     * 移动至 - 将 NPC 移动到指定坐标位置
     * 异步执行，通过 ack 回调返回移动结果（成功/卡住/中断）
     */
    private void executeMoveTo(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();
        if (params == null) {
            logger.warn("Move_to action without params for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"error\": \"Missing parameters\"}");
            return;
        }

        double x = params.optDouble("x", 0);
        double y = params.optDouble("y", 0);
        double z = params.optDouble("z", 0);

        Location destination = Location.of(x, y, z);

        hytaleAPI.moveNpcTo(npcInstanceId, destination).thenAccept(result -> {
            if (ack != null) {
                org.json.JSONObject response = new org.json.JSONObject();

                // 将移动结果状态映射为后端期望的响应格式
                String status = result.status();
                if ("success".equals(status)) {
                    response.put("success", true);
                } else if ("timeout".equals(status)) {
                    response.put("stuck", true);
                    response.put("reason", "Path blocked or movement timed out");
                } else if ("shutdown".equals(status)) {
                    response.put("interrupted", true);
                } else {
                    // Any other failure status
                    response.put("stuck", true);
                    response.put("reason", status);
                }

                if (result.finalLocation() != null) {
                    response.put("x", result.finalLocation().x());
                    response.put("y", result.finalLocation().y());
                    response.put("z", result.finalLocation().z());
                }

                ack.call(response);
            }
        });

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] moving to " + destination.toCoordString());
        }
    }

    /**
     * 错误消息 - 以红色文字向玩家显示错误信息
     * 当后端遇到错误时使用（如 LLM 调用失败）
     */
    private void executeErrorMessage(UUID npcInstanceId, String playerId, JSONObject params) {
        String message = params != null ? params.optString("message", "An error occurred.") : "An error occurred.";

        // 向玩家发送红色错误消息（跳过 NPC 实体检查，这只是错误显示）
        // HytaleAPI 实现会将此消息着色为 #FF0000（红色）
        hytaleAPI.sendErrorMessage(playerId, message);

        logger.warn("[Error] Sent to player [" + playerId + "] (NPC: " + npcInstanceId + "): " + message);
    }

    /**
     * 格式化 NPC 消息，添加前缀和名称
     * 注意：Hytale 不使用 Minecraft 风格的颜色代码（§ 或 &），
     * 颜色通过 Hytale Message API 在 sendNpcMessage 中应用。
     */
    private String formatNpcMessage(String npcName, String message, String targetPlayerName) {
        // 去除前缀中的旧版颜色代码
        String prefix = config.gameplay().messagePrefix();
        // 移除 § 和 & 颜色代码（如 §6、&r 等）
        prefix = prefix.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "");

        // E.g. [NPC] Warrior to Steve: Hello there!
        return prefix + npcName + " to " + targetPlayerName + ": " + message;
    }

    // ========== AI 行为动作方法 ==========

    /**
     * 跟随目标 - 让 NPC 开始跟随指定玩家
     */
    private void executeFollowTarget(NpcInstanceData npcInstanceData, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        String targetPlayerName = params != null ? params.optString("targetPlayerName", "") : "";

        if (targetPlayerName.isEmpty()) {
            logger.warn("Follow target action without targetPlayerName for NPC: " + npcInstanceId);
            return;
        }

        boolean success = hytaleAPI.startFollowingPlayer(npcInstanceId, targetPlayerName);

        if (config.logging().logActions()) {
            logger.info(
                    "[NPC:" + npcInstanceId + "] follow " + targetPlayerName + ": " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 停止跟随 - 让 NPC 停止跟随当前目标
     */
    private void executeStopFollowing(NpcInstanceData npcInstanceData) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        boolean success = hytaleAPI.stopFollowing(npcInstanceId);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] stop following: " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 开始攻击 - 让 NPC 开始攻击指定目标
     */
    private void executeStartAttacking(NpcInstanceData npcInstanceData, JSONObject params) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        String targetName = params != null ? params.optString("targetName", "") : "";
        String attackType = params != null ? params.optString("attackType", "melee") : "melee";

        if (targetName.isEmpty()) {
            logger.warn("Start attacking action without targetName for NPC: " + npcInstanceId);
            return;
        }

        boolean success = hytaleAPI.startAttacking(npcInstanceId, targetName, attackType);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] attack " + targetName + " (" + attackType + "): "
                    + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 停止攻击 - 让 NPC 停止攻击行为
     */
    private void executeStopAttacking(NpcInstanceData npcInstanceData) {
        if (npcInstanceData == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        boolean success = hytaleAPI.stopAttacking(npcInstanceId);

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] stop attacking: " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 获取当前位置 - 返回 NPC 的当前世界坐标
     */
    private void executeGetCurrentPosition(NpcInstanceData npcInstanceData, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        Optional<Location> location = hytaleAPI.getNpcInstanceLocation(npcInstanceId);

        if (location.isPresent()) {
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("success", true);
            response.put("x", location.get().x());
            response.put("y", location.get().y());
            response.put("z", location.get().z());

            if (ack != null) {
                ack.call(response);
            }

            if (config.logging().logActions()) {
                logger.info("[NPC:" + npcInstanceId + "] current position: (" +
                        location.get().x() + ", " + location.get().y() + ", " + location.get().z() + ")");
            }
        } else {
            if (ack != null) {
                ack.call("{\"success\": false, \"error\": \"Could not get NPC location\"}");
            }
            logger.warn("Could not get current position for NPC: " + npcInstanceId);
        }
    }

    /**
     * 等待 - 暂停指定时间（服务端处理）
     * 注意：实际等待在后端执行，此处仅发送确认
     */
    private void executeWait(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        int duration = params != null ? params.optInt("duration", 1000) : 1000;

        // 立即确认 - 实际等待在后端进行
        if (ack != null) {
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("success", true);
            response.put("waitedMs", duration);
            ack.call(response);
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] wait: " + duration + "ms");
        }
    }

    /**
     * 传送玩家 - 将玩家传送到指定坐标位置
     */
    private void executeTeleportPlayer(NpcInstanceData npcInstanceData, String playerId, JSONObject params,
            io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        if (playerId == null || playerId.isEmpty()) {
            logger.warn("Teleport player action without playerId for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"error\": \"No player specified\"}");
            return;
        }

        if (params == null) {
            logger.warn("Teleport player action without params for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"error\": \"Missing parameters\"}");
            return;
        }

        String locationName = params.optString("locationName", "Unknown");
        String worldName = params.optString("worldName", "default");
        double x = params.optDouble("x", 0);
        double y = params.optDouble("y", 0);
        double z = params.optDouble("z", 0);

        logger.info("Trying to teleport player action: " + locationName + " in world " + worldName + " at " + x + ", "
                + y + ", " + z);

        Location destination = Location.of(x, y, z, worldName);

        // 执行玩家传送
        boolean success = hytaleAPI.teleportPlayerTo(playerId, destination);

        // 向玩家发送传送确认消息
        if (success) {
            String message = "You have been teleported to " + locationName + ".";
            hytaleAPI.sendMessage(playerId, message);
        }

        if (ack != null) {
            org.json.JSONObject response = new org.json.JSONObject();
            response.put("success", success);
            if (!success) {
                response.put("error", "Failed to teleport player");
            }
            ack.call(response);
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] teleport player [" + playerId + "] to " + locationName +
                    " (" + x + ", " + y + ", " + z + "): " + (success ? "SUCCESS" : "FAILED"));
        }
    }

    // ========== 物品栏管理方法 ==========

    /**
     * 装备物品 - 为 NPC 装备指定物品到指定槽位
     */
    private void executeEquipItem(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        String itemId = params != null ? params.optString("itemId", "") : "";
        String slot = params != null ? params.optString("slot", "auto") : "auto";

        if (itemId.isEmpty()) {
            logger.warn("Equip item action without itemId for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"Missing itemId\"}");
            return;
        }

        var result = hytaleAPI.equipItem(npcInstanceId, itemId, slot);

        if (ack != null) {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("itemId", result.itemId() != null ? result.itemId() : org.json.JSONObject.NULL);
            json.put("equippedSlot", result.equippedSlot() != null ? result.equippedSlot() : org.json.JSONObject.NULL);
            json.put("previousItem", result.previousItem() != null ? new org.json.JSONObject(result.previousItem())
                    : org.json.JSONObject.NULL);
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] equip " + itemId + " to " + slot + ": " +
                    (result.success() ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 破坏方块 - 破坏指定位置的方块并返回掉落物信息
     */
    private void executeBreakBlock(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"blockBroken\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        if (params == null) {
            logger.warn("Break block action without params for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"success\": false, \"blockBroken\": false, \"error\": \"Missing parameters\"}");
            return;
        }

        double x = params.optDouble("targetX");
        double y = params.optDouble("targetY");
        double z = params.optDouble("targetZ");
        String toolItemId = params.optString("toolItemId", null);
        int maxAttempts = params.optInt("maxAttempts", 20);

        Location target = Location.of(x, y, z);

        var result = hytaleAPI.breakBlock(npcInstanceId, target, toolItemId, maxAttempts);

        if (ack != null) {
            // 手动构建 JSON，因为 org.json.JSONObject 不能很好地处理 record 类型
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("blockBroken", result.blockBroken());
            json.put("blockId", result.blockId() != null ? result.blockId() : org.json.JSONObject.NULL);
            json.put("attemptsNeeded", result.attemptsNeeded());
            json.put("drops",
                    result.drops() != null ? new org.json.JSONArray(result.drops()) : org.json.JSONObject.NULL);
            json.put("dropsDetectedAt",
                    result.dropsDetectedAt() != null ? new org.json.JSONObject(result.dropsDetectedAt())
                            : org.json.JSONObject.NULL);
            json.put("toolDurabilityRemaining",
                    result.toolDurabilityRemaining() != null ? result.toolDurabilityRemaining()
                            : org.json.JSONObject.NULL);
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] break block at " + target + ": " +
                    (result.blockBroken() ? "BROKEN" : "FAILED"));
        }
    }

    /**
     * 拾取物品 - 拾取 NPC 附近的掉落物品
     */
    private void executePickupItem(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        double radius = params != null ? params.optDouble("radius", 5) : 5;
        String itemId = params != null ? params.optString("itemId", null) : null;
        int maxItems = params != null ? params.optInt("maxItems", 10) : 10;

        var result = hytaleAPI.pickupItems(npcInstanceId, radius, itemId, maxItems);

        if (ack != null) {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("itemsPickedUp", result.itemsPickedUp());
            json.put("itemsByType", result.itemsByType() != null ? new org.json.JSONArray(result.itemsByType())
                    : org.json.JSONObject.NULL);
            json.put("itemsRemaining", result.itemsRemaining());
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] pickup items within " + radius + " blocks: " +
                    result.itemsPickedUp() + " items picked up");
        }
    }

    /**
     * 使用手持物品 - 多次使用当前手持的物品
     */
    private void executeUseHeldItem(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        if (params == null) {
            logger.warn("Use held item action without params for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"Missing parameters\"}");
            return;
        }

        double x = params.optDouble("targetX");
        double y = params.optDouble("targetY");
        double z = params.optDouble("targetZ");
        int useCount = params.optInt("useCount", 1);
        long useIntervalMs = params.optLong("useIntervalMs", 400);
        String targetTypeStr = params.optString("targetType", "block");

        Location target = Location.of(x, y, z);
        var targetType = "entity".equals(targetTypeStr) ? dev.hycompanion.plugin.api.HytaleAPI.TargetType.ENTITY
                : dev.hycompanion.plugin.api.HytaleAPI.TargetType.BLOCK;

        var result = hytaleAPI.useHeldItem(npcInstanceId, target, useCount, useIntervalMs, targetType);

        if (ack != null) {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("usesPerformed", result.usesPerformed());
            json.put("targetDestroyed",
                    result.targetDestroyed() != null ? result.targetDestroyed() : org.json.JSONObject.NULL);
            json.put("targetHealthRemaining",
                    result.targetHealthRemaining() != null ? result.targetHealthRemaining() : org.json.JSONObject.NULL);
            json.put("toolBroke", result.toolBroke());
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] use held item " + useCount + " times: " +
                    result.usesPerformed() + " uses performed");
        }
    }

    /**
     * 丢弃物品 - 将物品从物品栏丢弃到地面
     */
    private void executeDropItem(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        String itemId = params != null ? params.optString("itemId", "") : "";
        int quantity = params != null ? params.optInt("quantity", 1) : 1;
        float throwSpeed = (float) (params != null ? params.optDouble("throwSpeed", 1.0) : 1.0);

        if (itemId.isEmpty()) {
            logger.warn("Drop item action without itemId for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"Missing itemId\"}");
            return;
        }

        var result = hytaleAPI.dropItem(npcInstanceId, itemId, quantity, throwSpeed);

        if (ack != null) {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("itemId", result.itemId() != null ? result.itemId() : org.json.JSONObject.NULL);
            json.put("quantityDropped", result.quantityDropped());
            json.put("remainingQuantity", result.remainingQuantity());
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] drop " + quantity + "x " + itemId + ": " +
                    (result.success() ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 获取物品栏 - 获取 NPC 当前的物品栏内容（装备、快捷栏、存储）
     */
    private void executeGetInventory(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        boolean includeEmpty = params != null ? params.optBoolean("includeEmpty", false) : false;

        var result = hytaleAPI.getInventory(npcInstanceId, includeEmpty);

        if (ack != null) {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("armor",
                    result.armor() != null ? new org.json.JSONObject(result.armor()) : org.json.JSONObject.NULL);
            json.put("hotbar",
                    result.hotbar() != null ? new org.json.JSONArray(result.hotbar()) : org.json.JSONObject.NULL);
            json.put("storage",
                    result.storage() != null ? new org.json.JSONArray(result.storage()) : org.json.JSONObject.NULL);
            json.put("heldItem",
                    result.heldItem() != null ? new org.json.JSONObject(result.heldItem()) : org.json.JSONObject.NULL);
            json.put("totalItems", result.totalItems());
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] get inventory: " + result.totalItems() + " items total");
        }
    }

    /**
     * 卸下装备 - 从指定槽位移除物品
     */
    private void executeUnequipItem(NpcInstanceData npcInstanceData, JSONObject params, io.socket.client.Ack ack) {
        if (npcInstanceData == null) {
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"NPC data missing\"}");
            return;
        }
        UUID npcInstanceId = npcInstanceData.entityUuid();

        String slot = params != null ? params.optString("slot", "") : "";
        boolean destroy = params != null ? params.optBoolean("destroy", false) : false;

        if (slot.isEmpty()) {
            logger.warn("Unequip item action without slot for NPC: " + npcInstanceId);
            if (ack != null)
                ack.call("{\"success\": false, \"error\": \"Missing slot\"}");
            return;
        }

        var result = hytaleAPI.unequipItem(npcInstanceId, slot, destroy);

        if (ack != null) {
            org.json.JSONObject json = new org.json.JSONObject();
            json.put("success", result.success());
            json.put("slot", result.slot() != null ? result.slot() : org.json.JSONObject.NULL);
            json.put("itemRemoved", result.itemRemoved() != null ? new org.json.JSONObject(result.itemRemoved())
                    : org.json.JSONObject.NULL);
            json.put("movedToStorage", result.movedToStorage());
            json.put("destroyed", result.destroyed());
            json.put("error", result.error() != null ? result.error() : org.json.JSONObject.NULL);
            ack.call(json.toString());
        }

        if (config.logging().logActions()) {
            logger.info("[NPC:" + npcInstanceId + "] unequip item from " + slot +
                    (destroy ? " (destroyed)" : "") + ": " + (result.success() ? "SUCCESS" : "FAILED"));
        }
    }

    /**
     * 获取容器物品栏 - 获取指定坐标容器中的物品内容
     */
    private void executeGetContainerInventory(NpcInstanceData npcInstanceData, JSONObject params,
            io.socket.client.Ack ack) {
        if (npcInstanceData == null || params == null || ack == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        int x = params.optInt("targetX");
        int y = params.optInt("targetY");
        int z = params.optInt("targetZ");

        hytaleAPI.getContainerInventory(npcInstanceId, x, y, z).thenAccept(resultOpt -> {
            if (resultOpt.isPresent()) {
                var res = resultOpt.get();
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("success", res.isSuccess());
                json.put("message", res.getMessage() != null ? res.getMessage() : org.json.JSONObject.NULL);
                if (res.isSuccess()) {
                    org.json.JSONArray itemsArray = new org.json.JSONArray();
                    if (res.getItems() != null) {
                        for (var item : res.getItems()) {
                            itemsArray.put(new org.json.JSONObject(item));
                        }
                    }
                    json.put("items", itemsArray);
                }
                ack.call(json.toString());
            } else {
                ack.call("{\"success\": false, \"message\": \"Failed to get container inventory\"}");
            }
        }).exceptionally(e -> {
            logger.error("Error getting container inventory: " + e.getMessage());
            ack.call("{\"success\": false, \"message\": \"Internal error\"}");
            return null;
        });
    }

    /**
     * 存入容器 - 将 NPC 物品栏中的物品存入指定坐标的容器
     */
    private void executeStoreItemInContainer(NpcInstanceData npcInstanceData, JSONObject params,
            io.socket.client.Ack ack) {
        if (npcInstanceData == null || params == null || ack == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        int x = params.optInt("targetX");
        int y = params.optInt("targetY");
        int z = params.optInt("targetZ");
        String itemId = params.optString("itemId");
        int quantity = params.optInt("quantity", 1);

        hytaleAPI.storeItemInContainer(npcInstanceId, x, y, z, itemId, quantity).thenAccept(resultOpt -> {
            if (resultOpt.isPresent()) {
                var res = resultOpt.get();
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("success", res.isSuccess());
                json.put("message", res.getMessage() != null ? res.getMessage() : org.json.JSONObject.NULL);
                ack.call(json.toString());
            } else {
                ack.call("{\"success\": false, \"message\": \"Failed to store item\"}");
            }
        }).exceptionally(e -> {
            logger.error("Error storing item in container: " + e.getMessage());
            ack.call("{\"success\": false, \"message\": \"Internal error\"}");
            return null;
        });
    }

    /**
     * 从容器取出 - 从指定坐标的容器中取出物品到 NPC 物���栏
     */
    private void executeTakeItemFromContainer(NpcInstanceData npcInstanceData, JSONObject params,
            io.socket.client.Ack ack) {
        if (npcInstanceData == null || params == null || ack == null)
            return;
        UUID npcInstanceId = npcInstanceData.entityUuid();
        int x = params.optInt("targetX");
        int y = params.optInt("targetY");
        int z = params.optInt("targetZ");
        String itemId = params.optString("itemId");
        int quantity = params.optInt("quantity", 1);

        hytaleAPI.takeItemFromContainer(npcInstanceId, x, y, z, itemId, quantity).thenAccept(resultOpt -> {
            if (resultOpt.isPresent()) {
                var res = resultOpt.get();
                org.json.JSONObject json = new org.json.JSONObject();
                json.put("success", res.isSuccess());
                json.put("message", res.getMessage() != null ? res.getMessage() : org.json.JSONObject.NULL);
                ack.call(json.toString());
            } else {
                ack.call("{\"success\": false, \"message\": \"Failed to take item\"}");
            }
        }).exceptionally(e -> {
            logger.error("Error taking item from container: " + e.getMessage());
            ack.call("{\"success\": false, \"message\": \"Internal error\"}");
            return null;
        });
    }
}
