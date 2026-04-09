package dev.hycompanion.plugin.adapter;

import dev.hycompanion.plugin.api.GamePlayer;
import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.api.inventory.*;
import dev.hycompanion.plugin.api.results.*;
import dev.hycompanion.plugin.core.npc.NpcData;
import dev.hycompanion.plugin.core.npc.NpcInstanceData;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HytaleAPI 的模拟实现，用于在没有真实游戏服务器的情况下进行测试
 *
 * 所有方法都将操作记录到控制台并模拟行为。
 * 当 Hytale API 可用时，替换为实际的 HytaleServerAdapter。
 *
 * TODO: [HYTALE-API] 替换为实际的服务器 API 实现
 */
public class MockHytaleAdapter implements HytaleAPI {

    private final PluginLogger logger;

    // 模拟数据存储：玩家和NPC实例
    private final Map<String, GamePlayer> mockPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, NpcInstanceData> npcInstanceEntities = new ConcurrentHashMap<>();

    // 模拟世界状态：时间、天气、世界名称
    private String currentTimeOfDay = "noon";
    private String currentWeather = "clear";
    private String worldName = "world";

    /**
     * 构造函数：初始化模拟适配器
     * @param logger 日志记录器
     */
    public MockHytaleAdapter(PluginLogger logger) {
        this.logger = logger;
        logger.info("MockHytaleAdapter initialized - Hytale API calls will be simulated");
    }

    // ========== 玩家操作 ==========

    /** 根据玩家ID获取玩家对象 */
    @Override
    public Optional<GamePlayer> getPlayer(String playerId) {
        return Optional.ofNullable(mockPlayers.get(playerId));
    }

    /** 根据玩家名称获取玩家对象（忽略大小写） */
    @Override
    public Optional<GamePlayer> getPlayerByName(String playerName) {
        return mockPlayers.values().stream()
                .filter(p -> p.name().equalsIgnoreCase(playerName))
                .findFirst();
    }

    /** 获取所有在线玩家列表 */
    @Override
    public List<GamePlayer> getOnlinePlayers() {
        return new ArrayList<>(mockPlayers.values());
    }

    /** 获取所有已生成的NPC实例集合 */
    @Override
    public Set<NpcInstanceData> getNpcInstances() {
        return new HashSet<>(npcInstanceEntities.values());
    }

    /** 根据NPC实例UUID获取NPC实例数据 */
    @Override
    public NpcInstanceData getNpcInstance(UUID npcInstanceUuid) {
        return npcInstanceEntities.get(npcInstanceUuid);
    }

    /** 向指定玩家发送文本消息 */
    @Override
    public void sendMessage(String playerId, String message) {
        logger.info("[MOCK] Sending message to player [" + playerId + "]: " + message);
    }

    /** NPC向指定玩家发送消息（带原始消息文本） */
    @Override
    public void sendNpcMessage(UUID npcInstanceId, String playerId, String message, String rawMessage) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] says to player [" + playerId + "]: " + message);
    }

    /** NPC向多个玩家广播消息 */
    @Override
    public void broadcastNpcMessage(UUID npcInstanceId, List<String> playerIds, String message, String rawMessage) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] broadcasts to " + playerIds.size() + " players: " + message);
    }

    /** 向指定玩家发送错误消息 */
    @Override
    public void sendErrorMessage(String playerId, String message) {
        logger.warn("[MOCK] [ERROR] Sending error message to player [" + playerId + "]: " + message);
    }

    /** 向所有管理员广播调试消息 */
    @Override
    public void broadcastDebugMessageToOps(String message) {
        logger.warn("[MOCK] [DEBUG] Broadcasting to OPs: " + message);
    }

    /** 检查玩家是否为管理员（模拟模式下所有已注册玩家都视为管理员） */
    @Override
    public boolean isPlayerOp(String playerId) {
        // 模拟模式下，所有已注册的玩家都视为管理员，便于测试
        return mockPlayers.containsKey(playerId);
    }

    // ========== NPC操作 ==========

    /**
     * 在指定位置生成一个模拟NPC
     * @param externalId NPC的外部标识符（对应角色定义）
     * @param name NPC显示名称
     * @param location 生成位置
     * @return 生成的NPC实例UUID
     */
    @Override
    public Optional<UUID> spawnNpc(String externalId, String name, Location location) {
        logger.info("[MOCK] Spawning NPC [" + externalId + "] (" + name + ") at " + location);

        // 生成模拟实体UUID
        UUID entityUuid = UUID.randomUUID();

        // 创建模拟NPC数据
        NpcData npcData = NpcData.fromSync(
                externalId, // use external ID as ID for mock
                externalId,
                name,
                "Default Personality",
                "Hello!",
                10,
                false,
                "neutral",
                null, false, false);

        // 创建NPC实例数据（由于是模拟模式，实体引用为null）
        NpcInstanceData instanceData = new NpcInstanceData(
                entityUuid,
                null, // No real entity ref in mock
                null,
                npcData,
                location); // Use spawn location as the spawnLocation

        npcInstanceEntities.put(entityUuid, instanceData);

        return Optional.of(entityUuid);
    }

    /** 移除指定UUID的NPC实例 */
    @Override
    public boolean removeNpc(UUID npcInstanceId) {
        logger.info("[MOCK] Removing NPC [" + npcInstanceId + "]");

        if (npcInstanceEntities.remove(npcInstanceId) != null) {
            return true;
        }
        return false;
    }

    /** 触发NPC播放指定动画（如挥手、坐下等） */
    @Override
    public void triggerNpcEmote(UUID npcInstanceId, String animationName) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] plays animation: " + animationName);
    }

    /**
     * 让NPC移动到指定位置（异步操作）
     * @return 包含移动结果的CompletableFuture
     */
    @Override
    public CompletableFuture<dev.hycompanion.plugin.core.npc.NpcMoveResult> moveNpcTo(UUID npcInstanceId,
            Location location) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] moving to " + location);

        NpcInstanceData data = npcInstanceEntities.get(npcInstanceId);
        if (data != null) {
            // npcInstanceEntities.put(npcInstanceId, data.(location));
        }
        return CompletableFuture.completedFuture(dev.hycompanion.plugin.core.npc.NpcMoveResult.success(location));
    }

    /** 获取NPC实例的当前位置 */
    @Override
    public Optional<Location> getNpcInstanceLocation(UUID npcInstanceId) {
        NpcInstanceData data = npcInstanceEntities.get(npcInstanceId);
        return null;
        // return data != null ? Optional.ofNullable(data)) : Optional.empty();
    }

    /** 让NPC转身面向指定位置 */
    @Override
    public void rotateNpcInstanceToward(UUID npcInstanceId, Location targetLocation) {
        if (targetLocation == null) {
            return;
        }
        logger.info("[MOCK] NPC [" + npcInstanceId + "] rotates to face " + targetLocation.toCoordString());
    }

    /** 检查NPC实例的实体引用是否有效 */
    @Override
    public boolean isNpcInstanceEntityValid(UUID npcInstanceId) {
        return npcInstanceEntities.containsKey(npcInstanceId);
    }

    /** 发现已有的NPC实例（在模拟模式下返回匹配externalId的已跟踪NPC） */
    @Override
    public List<UUID> discoverExistingNpcInstances(String externalId) {
        // 模拟模式下，返回已跟踪的匹配externalId的NPC
        return npcInstanceEntities.values().stream()
                .filter(inst -> inst.npcData().externalId().equals(externalId))
                .map(NpcInstanceData::entityUuid)
                .toList();
    }

    /** 注册NPC移除监听器（模拟模式下为空操作） */
    @Override
    public void registerNpcRemovalListener(java.util.function.Consumer<UUID> listener) {
        // 模拟模式下为空操作
    }

    // ========== 交易操作 ==========

    /** 打开NPC与玩家之间的交易界面 */
    @Override
    public void openTradeInterface(UUID npcInstanceId, String playerId) {
        logger.info("[MOCK] Opening trade interface between NPC [" + npcInstanceId + "] and player [" + playerId + "]");
    }

    // ========== 任务操作 ==========

    /** NPC向玩家提供任务 */
    @Override
    public void offerQuest(UUID npcInstanceId, String playerId, String questId, String questName) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] offering quest '" + questName + "' (ID: " + questId
                + ") to player ["
                + playerId + "]");
    }

    // ========== 世界上下文 ==========

    /** 获取当前游戏时间段（如 noon、dawn、night 等） */
    @Override
    public String getTimeOfDay() {
        return currentTimeOfDay;
    }

    /** 获取当前天气状态 */
    @Override
    public String getWeather() {
        return currentWeather;
    }

    /** 获取指定位置附近一定半径内的玩家名称列表 */
    @Override
    public List<String> getNearbyPlayerNames(Location location, double radius) {
        return mockPlayers.values().stream()
                .filter(p -> p.location() != null && p.location().distanceTo(location) <= radius)
                .map(GamePlayer::name)
                .toList();
    }

    /** 获取指定位置附近一定半径内的玩家对象列表 */
    @Override
    public List<GamePlayer> getNearbyPlayers(Location location, double radius) {
        return mockPlayers.values().stream()
                .filter(p -> p.location() != null && p.location().distanceTo(location) <= radius)
                .toList();
    }

    /** 获取当前世界名称 */
    @Override
    public String getWorldName() {
        return worldName;
    }

    // ========== AI行为操作 ==========

    /** 让NPC开始跟随指定玩家 */
    @Override
    public boolean startFollowingPlayer(UUID npcInstanceId, String targetPlayerName) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] starting to follow player: " + targetPlayerName);
        return true;
    }

    /** 让NPC停止跟随 */
    @Override
    public boolean stopFollowing(UUID npcInstanceId) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] stopped following");
        return true;
    }

    /** 让NPC开始攻击指定目标（支持近战/远程类型） */
    @Override
    public boolean startAttacking(UUID npcInstanceId, String targetName, String attackType) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] attacking " + targetName + " (type: " + attackType + ")");
        return true;
    }

    /** 让NPC停止攻击 */
    @Override
    public boolean stopAttacking(UUID npcInstanceId) {
        logger.info("[MOCK] NPC [" + npcInstanceId + "] stopped attacking");
        return true;
    }

    /** 检查NPC是否处于忙碌状态（跟随或攻击中） */
    @Override
    public boolean isNpcBusy(UUID npcInstanceId) {
        return false;
    }

    // ========== 传送操作 ==========

    /** 将NPC传送到指定位置 */
    @Override
    public boolean teleportNpcTo(UUID npcInstanceId, Location location) {
        logger.info("[MOCK] Teleporting NPC [" + npcInstanceId + "] to " + location);
        return true;
    }

    /** 将玩家传送到指定位置 */
    @Override
    public boolean teleportPlayerTo(String playerId, Location location) {
        logger.info("[MOCK] Teleporting player [" + playerId + "] to " + location);
        return true;
    }

    // ========== 动画发现 ==========

    /** 获取NPC可用的动画列表（返回模拟数据） */
    @Override
    public List<String> getAvailableAnimations(UUID npcInstanceId) {
        logger.info("[MOCK] Getting available animations for NPC: " + npcInstanceId);
        return List.of(
                "Idle", "Walk", "Run", "Jump", "Fall",
                "Sit", "SitGround", "Sleep", "Crouch",
                "Hurt", "Death", "IdlePassive",
                "Howl", "Greet", "Threaten", "Track", "Laydown", "Wake");
    }

    // ========== 方块发现 ==========

    /**
     * 获取可用方块列表（返回模拟样本数据）
     * 包含木头、石头、矿石、植物、土壤、流体等类别
     */
    @Override
    public List<dev.hycompanion.plugin.core.world.BlockInfo> getAvailableBlocks() {
        logger.info("[MOCK] Getting available blocks (sample data)");

        // 基于 hytaleitemids.com 结构的模拟方块数据
        return List.of(
                // 木头方块
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Wood_Cedar_Trunk", "Cedar Log"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Wood_Oak_Trunk", "Oak Log"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Wood_Birch_Trunk", "Birch Log"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Wood_Spruce_Trunk", "Spruce Log"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Wood_Cedar_Plank", "Cedar Plank"),

                // 石头方块
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Stone_Cobble", "Cobblestone"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Stone_Granite_Raw", "Raw Granite"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Stone_Marble", "Marble"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Stone_Sandstone", "Sandstone"),

                // 矿石
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Ore_Iron", "Iron Ore"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Ore_Gold", "Gold Ore"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Ore_Coal", "Coal Ore"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Ore_Diamond", "Diamond Ore"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Ore_Copper", "Copper Ore"),

                // 植物
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Plant_Crop_Mushroom_Block_Blue_Trunk", "Blue Mushroom Trunk"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Plant_Flower_Rose", "Rose"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Plant_Grass", "Grass"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Plant_Leaf_Oak", "Oak Leaves"),

                // 土壤
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Dirt", "Dirt"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Sand", "Sand"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Gravel", "Gravel"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Clay", "Clay"),

                // 流体
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Fluid_Water", "Water"),
                dev.hycompanion.plugin.core.world.BlockClassifier.classify(
                        "Fluid_Lava", "Lava"));
    }

    // ========== 思考指示器 ==========

    /** 在NPC头顶显示"思考中..."的指示器 */
    @Override
    public void showThinkingIndicator(UUID npcInstanceId) {
        logger.info("[MOCK] Showing thinking indicator for NPC: " + npcInstanceId);
    }

    /** 隐藏NPC头顶的思考指示器 */
    @Override
    public void hideThinkingIndicator(UUID npcInstanceId) {
        logger.info("[MOCK] Hiding thinking indicator for NPC: " + npcInstanceId);
    }

    /** 清理残留的僵尸思考指示器实体 */
    @Override
    public int removeZombieThinkingIndicators() {
        logger.info("[MOCK] Removed 0 zombie thinking indicators (mock)");
        return 0;
    }

    // ========== 模拟数据管理（仅用于测试） ==========

    /** 添加一个模拟玩家 */
    public void addMockPlayer(GamePlayer player) {
        mockPlayers.put(player.id(), player);
    }

    /** 移除一个模拟玩家 */
    public void removeMockPlayer(String playerId) {
        mockPlayers.remove(playerId);
    }

    /** 设置模拟的游戏时间段 */
    public void setMockTimeOfDay(String time) {
        this.currentTimeOfDay = time;
    }

    /** 设置模拟的天气状态 */
    public void setMockWeather(String weather) {
        this.currentWeather = weather;
    }

    /** 更新NPC能力属性（模拟模式下未实现） */
    @Override
    public void updateNpcCapabilities(String externalId, NpcData npcData) {
        // TODO 自动生成的方法存根
        throw new UnsupportedOperationException("Unimplemented method 'updateNpcCapabilities'");
    }

    /** 在NPC周围搜索指定类型的方块（模拟模式下未实现） */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findBlock(UUID npcId, String tag, int radius) {
        // TODO 自动生成的方法存根
        throw new UnsupportedOperationException("Unimplemented method 'findBlock'");
    }

    /** 扫描NPC周围的方块信息（返回模拟数据） */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanBlocks(UUID npcId, int radius, boolean containersOnly) {
        logger.info("[MOCK] Scanning blocks for NPC " + npcId + " radius: " + radius + ", containersOnly: "
                + containersOnly);
        return CompletableFuture.completedFuture(Optional.of(Map.of(
                "current_position", Map.of("x", 0, "y", 0, "z", 0),
                "radius", radius,
                "categories", Map.of(),
                "blocks", Map.of())));
    }

    /** 扫描NPC周围的实体信息（模拟模式下未实现） */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> scanEntities(UUID npcId, int radius) {
        // TODO 自动生成的方法存根
        throw new UnsupportedOperationException("Unimplemented method 'scanEntities'");
    }

    /** 在NPC周围搜索指定名称的实体（模拟模式下未实现） */
    @Override
    public CompletableFuture<Optional<Map<String, Object>>> findEntity(UUID npcId, String name,
            int radius) {
        // TODO 自动生成的方法存根
        throw new UnsupportedOperationException("Unimplemented method 'findEntity'");
    }

    // ========== 背包操作 ==========

    /** 为NPC装备指定物品到指定槽位 */
    @Override
    public EquipResult equipItem(UUID npcInstanceId, String itemId, String slot) {
        logger.info("[MOCK] Equipping item " + itemId + " to slot " + slot + " for NPC " + npcInstanceId);
        return EquipResult.success(itemId, slot.equals("auto") ? "hotbar_0" : slot, null);
    }

    /** NPC破坏指定位置的方块（模拟木头方块的破坏） */
    @Override
    public BreakResult breakBlock(UUID npcInstanceId, Location targetBlock, String toolItemId, int maxAttempts) {
        logger.info("[MOCK] Breaking block at " + targetBlock + " for NPC " + npcInstanceId);
        // 模拟破坏一个木头方块
        List<Map<String, Object>> drops = List.of(
                Map.of("itemId", "Wood_Beech", "quantity", 2),
                Map.of("itemId", "Sapling_Beech", "quantity", 1));
        Map<String, Object> dropLocation = Map.of("x", targetBlock.x(), "y", targetBlock.y(), "z", targetBlock.z());
        return BreakResult.success("Wood_Beech_Trunk", 5, drops, dropLocation, 45.0);
    }

    /** NPC拾取周围掉落的物品 */
    @Override
    public PickupResult pickupItems(UUID npcInstanceId, double radius, String itemId, int maxItems) {
        logger.info("[MOCK] Picking up items within " + radius + " blocks for NPC " + npcInstanceId);
        List<Map<String, Object>> items = List.of(
                Map.of("itemId", itemId != null ? itemId : "Wood_Beech", "quantity", 2));
        return PickupResult.success(2, items, 0);
    }

    /** NPC使用手持物品（对指定目标执行多次使用操作） */
    @Override
    public UseResult useHeldItem(UUID npcInstanceId, Location target, int useCount, long intervalMs,
            TargetType targetType) {
        logger.info("[MOCK] Using held item " + useCount + " times on " + targetType + " at " + target + " for NPC "
                + npcInstanceId);
        return UseResult.success(useCount, false, null, false);
    }

    /** NPC丢弃指定物品到地面 */
    @Override
    public DropResult dropItem(UUID npcInstanceId, String itemId, int quantity, float throwSpeed) {
        logger.info("[MOCK] Dropping " + quantity + "x " + itemId + " with speed " + throwSpeed + " for NPC "
                + npcInstanceId);
        return DropResult.success(itemId, quantity, 0);
    }

    /** 获取NPC的背包快照（包括护甲、快捷栏、存储和手持物品） */
    @Override
    public InventorySnapshot getInventory(UUID npcInstanceId, boolean includeEmpty) {
        logger.info("[MOCK] Getting inventory for NPC " + npcInstanceId);
        Map<String, Object> armor = Map.of(
                "head", Map.of("itemId", "Helmet_Iron", "quantity", 1),
                "chest", Map.of("itemId", "Chestplate_Leather", "quantity", 1));
        List<Map<String, Object>> hotbar = List.of(
                Map.of("slot", 0, "itemId", "Axe_Steel", "quantity", 1, "isActive", true),
                Map.of("slot", 1, "itemId", "Pickaxe_Stone", "quantity", 1, "isActive", false),
                Map.of("slot", 2, "itemId", null, "quantity", 0, "isActive", false));
        List<Map<String, Object>> storage = List.of();
        Map<String, Object> heldItem = Map.of("itemId", "Axe_Steel", "quantity", 1);
        return InventorySnapshot.create(armor, hotbar, storage, heldItem, 3);
    }

    /** 从NPC指定槽位卸下装备（可选择销毁或移至存储） */
    @Override
    public UnequipResult unequipItem(UUID npcInstanceId, String slot, boolean destroy) {
        logger.info(
                "[MOCK] Unequipping item from slot " + slot + " (destroy=" + destroy + ") for NPC " + npcInstanceId);
        Map<String, Object> itemRemoved = Map.of("itemId", "Helmet_Iron", "quantity", 1);
        if (destroy) {
            return UnequipResult.destroyed(slot, itemRemoved);
        }
        return UnequipResult.success(slot, itemRemoved, true);
    }

    /** 扩展NPC的背包存储容量 */
    @Override
    public boolean expandNpcInventory(UUID npcInstanceId, int storageSlots) {
        logger.info("[MOCK] Expanding inventory by " + storageSlots + " slots for NPC " + npcInstanceId);
        return true;
    }

    // ========== 容器操作 ==========

    /** 获取指定坐标处容器的物品列表 */
    @Override
    public CompletableFuture<Optional<ContainerInventoryResult>> getContainerInventory(UUID npcInstanceId, int x, int y,
            int z) {
        logger.info("[MOCK] Getting container inventory at " + x + ", " + y + ", " + z + " for NPC " + npcInstanceId);
        List<Map<String, Object>> mockItems = List.of(
                Map.of("itemId", "Wood_Beech", "quantity", 10, "slot", 0));
        return CompletableFuture.completedFuture(Optional.of(ContainerInventoryResult.success(mockItems)));
    }

    /** 将NPC背包中的物品存入指定容器 */
    @Override
    public CompletableFuture<Optional<ContainerActionResult>> storeItemInContainer(UUID npcInstanceId, int x, int y,
            int z, String itemId, int quantity) {
        logger.info("[MOCK] Storing " + quantity + "x " + itemId + " in container at " + x + ", " + y + ", " + z
                + " for NPC " + npcInstanceId);
        return CompletableFuture.completedFuture(Optional.of(ContainerActionResult.success()));
    }

    /** 从指定容器中取出物品到NPC背包 */
    @Override
    public CompletableFuture<Optional<ContainerActionResult>> takeItemFromContainer(UUID npcInstanceId, int x, int y,
            int z, String itemId, int quantity) {
        logger.info("[MOCK] Taking " + quantity + "x " + itemId + " from container at " + x + ", " + y + ", " + z
                + " for NPC " + npcInstanceId);
        return CompletableFuture.completedFuture(Optional.of(ContainerActionResult.success()));
    }
}
