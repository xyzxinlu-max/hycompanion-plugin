package dev.hycompanion.plugin.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import dev.hycompanion.plugin.api.inventory.*;
import dev.hycompanion.plugin.api.results.ContainerActionResult;
import dev.hycompanion.plugin.api.results.ContainerInventoryResult;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.core.npc.NpcMoveResult;

/**
 * Hytale 服务器 API 抽象层
 *
 * 该接口定义了与 Hytale 游戏服务器的所有交互操作。
 * 当实际的 Hytale 服务器 API 可用时，应创建对应的实现类。
 *
 * 当前实现：
 * {@link dev.hycompanion.plugin.adapter.MockHytaleAdapter}
 *
 * @author Hycompanion Team
 */
public interface HytaleAPI {

        // ========== 玩家操作 ==========

        /**
         * 根据唯一 ID 获取玩家
         *
         * @param playerId 玩家的唯一标识符
         * @return 如果玩家在线且存在则返回该玩家
         */
        Optional<GamePlayer> getPlayer(String playerId);

        /**
         * 根据名称获取玩家
         *
         * @param playerName 玩家的显示名称
         * @return 如果玩家在线且存在则返回该玩家
         */
        Optional<GamePlayer> getPlayerByName(String playerName);

        /**
         * 根据标签查找最近的方块
         */
        CompletableFuture<Optional<Map<String, Object>>> findBlock(
                        UUID npcId, String tag, int radius);

        /**
         * 根据类型查找最近的实体
         */
        CompletableFuture<Optional<Map<String, Object>>> findEntity(
                        UUID npcId, String name, int radius);

        /**
         * 扫描周围环境并返回所有唯一方块类型及其最近坐标。
         * 按类别分组结果，每个方块 ID 包含其最近位置。
         *
         * @param npcId          NPC 实例 ID（扫描中心点）
         * @param radius         扫描半径（以方块为单位）
         * @param containersOnly 如果为 true，则仅返回容器类方块
         * @return 包含 current_position、radius、categories、blocks、totalUniqueBlocks 的 Map
         */
        CompletableFuture<Optional<Map<String, Object>>> scanBlocks(
                        UUID npcId, int radius, boolean containersOnly);

        /**
         * 扫描周围环境并返回所有实体及其详细信息。
         * 排除执行扫描的 NPC 自身。
         *
         * @param npcId  NPC 实例 ID（扫描中心点，同时被排除在结果外）
         * @param radius 扫描半径（以方块为单位）
         * @return 包含 current_position、radius、entities 数组、totalEntities 的 Map
         */
        CompletableFuture<Optional<Map<String, Object>>> scanEntities(
                        UUID npcId, int radius);

        /**
         * 获取在线玩家列表
         */
        List<GamePlayer> getOnlinePlayers();

        /**
         * 获取所有 NPC 实例
         *
         * @return NPC 实例集合
         */
        Set<NpcInstanceData> getNpcInstances();

        /**
         * 根据 UUID 获取特定的 NPC 实例
         *
         * @param npcInstanceUuid NPC 实例的 UUID
         * @return 如果存在则返回该 NPC 实例
         */
        NpcInstanceData getNpcInstance(UUID npcInstanceUuid);

        /**
         * 向指定玩家发送消息
         *
         * @param playerId 目标玩家的 ID
         * @param message  消息内容
         */
        void sendMessage(String playerId, String message);

        /**
         * 向玩家发送格式化的 NPC 消息
         *
         * @param npcInstanceId NPC 实例 ID
         * @param playerId      目标玩家的 ID
         * @param message       消息内容
         */
        void sendNpcMessage(UUID npcInstanceId, String playerId, String formattedMessage, String rawMessage);

        /**
         * 向所有指定玩家广播格式化的 NPC 消息
         *
         * @param npcInstanceId NPC 实例 ID
         * @param playerIds     目标玩家 ID 列表
         * @param message       消息内容
         */
        void broadcastNpcMessage(UUID npcInstanceId, List<String> playerIds, String formattedMessage, String rawMessage);

        /**
         * 向玩家发送错误消息（以橙色显示）
         *
         * @param playerId 目标玩家的 ID
         * @param message  错误消息内容
         */
        void sendErrorMessage(String playerId, String message);

        /**
         * 向服务器上所有管理员（OP）玩家广播调试消息。
         * 用于仅向服务器管理员显示详细的错误信息。
         *
         * @param message 要显示的调试消息（以红色显示）
         */
        void broadcastDebugMessageToOps(String message);

        /**
         * 检查玩家是否为管理员（OP）。
         *
         * @param playerId 玩家的 ID
         * @return 如果玩家是管理员则返回 true
         */
        boolean isPlayerOp(String playerId);

        // ========== NPC 操作 ==========

        /**
         * 在游戏世界中生成一个 NPC 实体
         *
         * @param npcId    NPC 的外部 ID
         * @param name     显示名称
         * @param location 生成位置
         * @return 创建的实体 UUID
         */
        Optional<UUID> spawnNpc(String npcId, String name, Location location);

        /**
         * 从游戏世界中移除一个 NPC 实体
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 如果移除成功则返回 true
         */
        boolean removeNpc(UUID npcInstanceId);

        /**
         * 更新现有 NPC 实例的能力属性（无敌、击退等）
         *
         * @param externalId NPC 的外部 ID（角色 ID）
         * @param npcData    包含能力标志的更新后 NPC 数据
         */
        void updateNpcCapabilities(String externalId, dev.hycompanion.plugin.core.npc.NpcData npcData);

        /**
         * 触发 NPC 的动画
         *
         * 动画名称应与模型的 AnimationSets 中的键匹配
         * （例如 "Sit"、"Sleep"、"Howl"、"Greet"、"Wave"、"Idle" 等）
         * 可用动画因模型而异，可通过 getAvailableAnimations() 查询。
         *
         * @param npcInstanceId NPC 实例 ID
         * @param animationName 模型定义中的动画集名称
         */
        void triggerNpcEmote(UUID npcInstanceId, String animationName);

        /**
         * 移动 NPC 到指定位置
         *
         * @param npcInstanceId NPC 实例 ID
         * @param location      目标位置
         * @return 包含成功状态和最终位置的 CompletableFuture
         */
        CompletableFuture<NpcMoveResult> moveNpcTo(UUID npcInstanceId, Location location);

        /**
         * 获取 NPC 的当前位置
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 当前位置，如果未找到则返回空
         */
        Optional<Location> getNpcInstanceLocation(UUID npcInstanceId);

        /**
         * 旋转 NPC 使其面向目标位置（仅在 NPC 空闲/静止时）。
         * 实现时应避免旋转正在跟随目标的 NPC。
         *
         * @param npcInstanceId  NPC 实例 ID
         * @param targetLocation 要面向的位置
         */
        void rotateNpcInstanceToward(UUID npcInstanceId, Location targetLocation);

        /**
         * 检查 NPC 实体在游戏世界中是否仍然有效。
         * 如果 NPC 被 Hytale 命令移除（例如 /npc clean）或实体引用不再有效，则返回 false。
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 如果实体存在且有效则返回 true
         */
        boolean isNpcInstanceEntityValid(UUID npcInstanceId);

        /**
         * 发现并绑定游戏世界中特定角色的现有 NPC 实体。
         * 用于同步后查找由 Hytale 持久化的 NPC，并将其绑定到插件的追踪系统中。
         *
         * @param externalId NPC 的外部 ID（角色 ID）
         * @return 发现的实体 UUID 列表
         */
        List<UUID> discoverExistingNpcInstances(String externalId);

        /**
         * 注册监听器，当 NPC 实例被移除或失效时收到通知。
         * 确保 NpcManager 可以清理其追踪映射表。
         *
         * @param listener 接收被移除实体 UUID 的回调函数
         */
        void registerNpcRemovalListener(java.util.function.Consumer<UUID> listener);

        // ========== 交易操作 ==========

        /**
         * 打开 NPC 与玩家之间的交易界面
         *
         * @param npcInstanceId NPC 实例 ID
         * @param playerId      目标玩家的 ID
         */
        void openTradeInterface(UUID npcInstanceId, String playerId);

        // ========== 任务操作 ==========

        /**
         * 由 NPC 向玩家提供一个任务
         *
         * @param npcInstanceId 发起任务的 NPC 实例 ID
         * @param playerId      目标玩家的 ID
         * @param questId       任务标识符
         * @param questName     可读的任务名称（可选）
         */
        void offerQuest(UUID npcInstanceId, String playerId, String questId, String questName);

        // ========== 世界上下文 ==========

        /**
         * 获取当前时间段
         *
         * @return 时间字符串（dawn 黎明、morning 早晨、noon 正午、afternoon 下午、dusk 黄昏、night 夜晚）
         */
        String getTimeOfDay();

        /**
         * 获取当前天气
         *
         * @return 天气字符串（clear 晴天、rain 下雨、storm 暴风雨、snow 下雪）
         */
        String getWeather();

        /**
         * 获取某位置附近的玩家名称
         *
         * @param location 中心位置
         * @param radius   搜索半径（以方块为单位）
         * @return 附近玩家名称列表
         */
        List<String> getNearbyPlayerNames(Location location, double radius);

        /**
         * 获取某位置附近的玩家（返回 GamePlayer 对象）
         *
         * @param location 中心位置
         * @param radius   搜索半径（以方块为单位）
         * @return 附近玩家列表
         */
        List<GamePlayer> getNearbyPlayers(Location location, double radius);

        /**
         * 获取世界/维度名称
         *
         * @return 世界名称
         */
        String getWorldName();

        // ========== AI 行为操作 ==========

        /**
         * 让 NPC 开始跟随玩家。
         * 使用 NPC 的状态机转换到跟随状态。
         *
         * @param npcInstanceId    NPC 实例 ID
         * @param targetPlayerName 要跟随的玩家用户名
         * @return 如果 NPC 成功开始跟随则返回 true
         */
        boolean startFollowingPlayer(UUID npcInstanceId, String targetPlayerName);

        /**
         * 让 NPC 停止跟随当前目标并返回空闲状态。
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 如果 NPC 成功停止跟随则返回 true
         */
        boolean stopFollowing(UUID npcInstanceId);

        /**
         * 让 NPC 开始攻击目标实体。
         *
         * @param npcInstanceId NPC 实例 ID
         * @param targetName    目标名称（玩家用户名或 NPC 名称）
         * @param attackType    攻击类型（"melee" 近战 或 "ranged" 远程）
         * @return 如果 NPC 成功开始攻击则返回 true
         */
        boolean startAttacking(UUID npcInstanceId, String targetName, String attackType);

        /**
         * 让 NPC 停止攻击并返回和平状态。
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 如果 NPC 成功停止攻击则返回 true
         */
        boolean stopAttacking(UUID npcInstanceId);

        /**
         * 检查 NPC 当前是否繁忙（正在跟随目标或战斗中）。
         * 用于判断是否应播放空闲动画（表情）。
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 如果 NPC 正在跟随或攻击则返回 true，空闲则返回 false
         */
        boolean isNpcBusy(UUID npcInstanceId);

        // ========== 动画发现 ==========

        /**
         * 根据 NPC 的模型获取所有可用的动画 ID。
         * 用于动态表情工具的生成。
         *
         * @param npcInstanceId NPC 实例 ID
         * @return 该 NPC 模型可用的动画 ID 列表
         */
        List<String> getAvailableAnimations(UUID npcInstanceId);

        // ========== 方块发现 ==========

        /**
         * 获取服务器上所有可用方块类型及其丰富的元数据。
         * 在启动时发送给后端，以启用 LLM 方块发现功能。
         *
         * 返回列表包含方块 ID、显示名称、材质类型
         * （木材、石头、矿石等）以及用于语义匹配的关键词。
         *
         * @return 描述可用方块的 BlockInfo 对象列表
         */
        List<dev.hycompanion.plugin.core.world.BlockInfo> getAvailableBlocks();

        // ========== 思考指示器 ==========

        /**
         * 在 NPC 头顶显示带有动画省略号的浮动"思考中..."文字。
         * 文字每 500 毫秒循环显示 "Thinking ."、"Thinking .."、"Thinking ..."。
         *
         * @param npcInstanceId NPC 实例 ID
         */
        void showThinkingIndicator(UUID npcInstanceId);

        /**
         * 隐藏 NPC 头顶的思考指示器。
         * 应在 NPC 收到响应时调用。
         *
         * @param npcInstanceId NPC 实例 ID
         */
        void hideThinkingIndicator(UUID npcInstanceId);

        /**
         * 移除因崩溃而残留的"僵尸"思考指示器。
         * 这些是带有 "Thinking..." 名称标签但当前未被追踪的实体。
         *
         * @return 移除的指示器数量
         */
        int removeZombieThinkingIndicators();

        // ========== 传送操作 ==========

        /**
         * 将 NPC 实例传送到指定位置。
         *
         * @param npcInstanceId 要传送的 NPC 实例 ID
         * @param location      目标位置
         * @return 如果传送成功则返回 true
         */
        boolean teleportNpcTo(UUID npcInstanceId, Location location);

        /**
         * 将玩家传送到指定位置。
         *
         * @param playerId 要传送的玩家 ID
         * @param location 目标位置
         * @return 如果传送成功则返回 true
         */
        boolean teleportPlayerTo(String playerId, Location location);

        /**
         * 清理资源并取消待处理的任务。
         * 应在插件关闭时调用。
         */
        default void cleanup() {
        }

        // ========== 背包操作 ==========

        /**
         * 为 NPC 装备物品（护甲或武器/工具）
         *
         * @param npcInstanceId NPC 实例 ID
         * @param itemId        要装备的物品 ID
         * @param slot          目标槽位（auto 自动、head 头部、chest 胸部、hands 手部、legs 腿部、
         *                      hotbar_0、hotbar_1、hotbar_2 快捷栏）
         * @return 装备操作的结果
         */
        EquipResult equipItem(UUID npcInstanceId, String itemId, String slot);

        /**
         * 破坏方块并返回掉落物
         *
         * @param npcInstanceId NPC 实例 ID
         * @param targetBlock   要破坏的方块位置
         * @param toolItemId    可选的使用工具（null 则使用手持物品）
         * @param maxAttempts   放弃前的最大尝试次数
         * @return 破坏操作的结果
         */
        BreakResult breakBlock(UUID npcInstanceId, Location targetBlock, String toolItemId, int maxAttempts);

        /**
         * 拾取 NPC 附近的掉落物品
         *
         * @param npcInstanceId NPC 实例 ID
         * @param radius        拾取半径（以方块为单位）
         * @param itemId        可选的指定物品 ID（null 则拾取任意物品）
         * @param maxItems      最大拾取数量
         * @return 拾取操作的结果
         */
        PickupResult pickupItems(UUID npcInstanceId, double radius, String itemId, int maxItems);

        /**
         * 多次使用当前手持物品
         *
         * @param npcInstanceId NPC 实例 ID
         * @param target        目标位置（方块或实体）
         * @param useCount      使用次数
         * @param intervalMs    两次使用之间的间隔（毫秒）
         * @param targetType    目标类型（方块或实体）
         * @return 使用操作的结果
         */
        UseResult useHeldItem(UUID npcInstanceId, Location target, int useCount, long intervalMs,
                        TargetType targetType);

        /**
         * 将物品从背包丢弃到地面
         *
         * @param npcInstanceId NPC 实例 ID
         * @param itemId        要丢弃的物品 ID
         * @param quantity      丢弃数量
         * @param throwSpeed    投掷速度（0.5=轻抛、1.0=正常、2.0=远抛）
         * @return 丢弃操作的结果
         */
        DropResult dropItem(UUID npcInstanceId, String itemId, int quantity, float throwSpeed);

        /**
         * 获取 NPC 当前的背包内容
         *
         * @param npcInstanceId NPC 实例 ID
         * @param includeEmpty  是否在响应中包含空槽位
         * @return 背包快照
         */
        InventorySnapshot getInventory(UUID npcInstanceId, boolean includeEmpty);

        /**
         * 从指定槽位卸下装备
         *
         * @param npcInstanceId NPC 实例 ID
         * @param slot          要卸下的槽位
         * @param destroy       如果为 true，则销毁物品而非移至存储区
         * @return 卸下装备操作的结果
         */
        UnequipResult unequipItem(UUID npcInstanceId, String slot, boolean destroy);

        /**
         * 扩展 NPC 的背包存储容量
         *
         * @param npcInstanceId NPC 实例 ID
         * @param storageSlots  要添加的存储槽位数量
         * @return 如果扩展成功则返回 true
         */
        boolean expandNpcInventory(UUID npcInstanceId, int storageSlots);

        // ========== 容器操作 ==========

        /**
         * 获取容器方块的物品内容
         *
         * @param npcInstanceId 与容器交互的 NPC 实例 ID
         * @param x             目标容器的 X 坐标
         * @param y             目标容器的 Y 坐标
         * @param z             目标容器的 Z 坐标
         * @return 包含容器物品结果的 CompletableFuture
         */
        CompletableFuture<Optional<ContainerInventoryResult>> getContainerInventory(UUID npcInstanceId, int x, int y,
                        int z);

        /**
         * 将物品从 NPC 背包存入容器方块
         *
         * @param npcInstanceId 与容器交互的 NPC 实例 ID
         * @param x             目标容器的 X 坐标
         * @param y             目标容器的 Y 坐标
         * @param z             目标容器的 Z 坐标
         * @param itemId        要存入的物品 ID
         * @param quantity      要存入的数量
         * @return 包含交易结果的 CompletableFuture
         */
        CompletableFuture<Optional<ContainerActionResult>> storeItemInContainer(UUID npcInstanceId, int x, int y, int z,
                        String itemId, int quantity);

        /**
         * 从容器方块中取出物品到 NPC 背包
         *
         * @param npcInstanceId 与容器交互的 NPC 实例 ID
         * @param x             目标容器的 X 坐标
         * @param y             目标容器的 Y 坐标
         * @param z             目标容器的 Z 坐标
         * @param itemId        要取出的物品 ID
         * @param quantity      要取出的数量
         * @return 包含交易结果的 CompletableFuture
         */
        CompletableFuture<Optional<ContainerActionResult>> takeItemFromContainer(UUID npcInstanceId, int x, int y,
                        int z, String itemId, int quantity);

        // ========== NPC 重生操作 ==========

        /**
         * 安排 NPC 在指定延迟后重生。
         * NPC 将在原始生成位置以相同角色和能力重新生成。
         *
         * @param externalId   NPC 的外部 ID（角色）
         * @param delaySeconds 重生前的延迟时间（秒）
         */
        default void scheduleNpcRespawn(String externalId, long delaySeconds) {
        }

        /**
         * 取消待处理的 NPC 重生任务。
         * 可用于在需要时阻止 NPC 重生。
         *
         * @param externalId 要取消重生的 NPC 外部 ID（角色）
         */
        default void cancelNpcRespawn(String externalId) {
        }

        /**
         * useHeldItem 操作的目标类型枚举
         */
        enum TargetType {
                /** 方块目标 */
                BLOCK,
                /** 实体目标 */
                ENTITY
        }
}
