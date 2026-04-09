package dev.hycompanion.plugin.core.npc;

import dev.hycompanion.plugin.api.HytaleAPI;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 管理游戏世界中所有活跃的NPC
 *
 * 负责追踪NPC数据、位置和实体绑定关系。
 * 通过遍历NPC实例来高效查找附近的NPC。
 * 与NpcConfigManager配合实现数据持久化。
 */
public class NpcManager {

    private final PluginLogger logger;

    // 以外部ID为键存储所有活跃NPC的映射表（线程安全）
    private final Map<String, NpcData> activeNpcs = new ConcurrentHashMap<>();

    // 实体UUID到外部ID的映射表，用于通过游戏实体反查NPC数据
    private final Map<UUID, String> entityToExternalId = new ConcurrentHashMap<>();

    // Hytale服务器API接口，用于与游戏世界交互
    private HytaleAPI hytaleAPI;

    /**
     * 构造NPC管理器
     * @param logger 插件日志记录器
     * @param hytaleAPI Hytale服务器API接口
     */
    public NpcManager(PluginLogger logger, HytaleAPI hytaleAPI) {
        this.logger = logger;
        this.hytaleAPI = hytaleAPI;
    }

    /**
     * 注册或更新一个NPC
     * 如果NPC已存在，则保留运行时状态并更新属性；否则新增注册。
     * 同时更新所有相关NPC实例的实体UUID映射。
     */
    public void registerNpc(NpcData npc) {
        // 保留运行时状态，如果NPC已存在则只更新属性
        NpcData existing = activeNpcs.get(npc.externalId());

        if (existing != null) {
            existing.updateFrom(npc);
            logger.debug("NPC updated: " + npc.externalId() + " (" + npc.name() + ")");
        } else {
            activeNpcs.put(npc.externalId(), npc);
            logger.debug("NPC registered: " + npc.externalId() + " (" + npc.name() + ")");
        }

        // 遍历所有NPC实例，为属于该NPC的实例建立实体UUID到外部ID的映射
        Set<NpcInstanceData> npcInstances = hytaleAPI.getNpcInstances();
        for (NpcInstanceData npcInstance : npcInstances) {
            if (npcInstance.npcData().externalId().equals(npc.externalId())) {
                entityToExternalId.put(npcInstance.entityUuid(), npc.externalId());

            }
        }
    }

    /**
     * 注销一个NPC
     * 移除NPC数据，清除所有相关实体映射，并从游戏世界中移除NPC实例。
     */
    public void unregisterNpc(String externalId) {
        NpcData npc = activeNpcs.remove(externalId);

        if (npc != null) {
            // 遍历所有NPC实例，移除属于该NPC的实体映射并从世界中移除
            Set<NpcInstanceData> npcInstances = hytaleAPI.getNpcInstances();

            for (NpcInstanceData npcInstance : npcInstances) {
                if (npcInstance.npcData().externalId().equals(npc.externalId())) {
                    entityToExternalId.remove(npcInstance.entityUuid());

                    // 从游戏世界中移除该NPC实例
                    hytaleAPI.removeNpc(npcInstance.entityUuid());
                }
            }

        }

        logger.debug("NPC unregistered: " + externalId);
    }

    /**
     * 根据外部ID获取NPC
     */
    public Optional<NpcData> getNpc(String externalId) {
        return Optional.ofNullable(activeNpcs.get(externalId));
    }

    /**
     * 根据显示名称获取NPC（不区分大小写）
     */
    public Optional<NpcData> getNpcByName(String name) {
        return activeNpcs.values().stream()
                .filter(npc -> npc.name().equalsIgnoreCase(name))
                .findFirst();
    }

    /**
     * 通过任意标识符查找NPC：外部ID、显示名称或实体UUID。
     * 按以下顺序尝试匹配：
     * 1. 外部ID精确匹配
     * 2. 显示名称（不区分大小写）
     * 3. 实体UUID
     * 为用户命令提供灵活的查找方式。
     */
    public Optional<NpcData> getNpcByAnyIdentifier(String identifier) {
        // 1. 尝试通过外部ID精确匹配
        NpcData byExternalId = activeNpcs.get(identifier);
        if (byExternalId != null) {
            return Optional.of(byExternalId);
        }

        // 2. 尝试通过显示名称匹配（不区分大小写）
        Optional<NpcData> byName = activeNpcs.values().stream()
                .filter(npc -> npc.name().equalsIgnoreCase(identifier))
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }

        // 3. 尝试将标识符解析为UUID并查找
        try {
            UUID uuid = UUID.fromString(identifier);
            String externalId = entityToExternalId.get(uuid);
            if (externalId != null) {
                return getNpc(externalId);
            }
        } catch (IllegalArgumentException e) {
            // 不是有效的UUID格式，忽略
        }

        return Optional.empty();
    }

    /**
     * 根据实体UUID获取NPC
     */
    public Optional<NpcData> getNpcByEntityUuid(UUID entityUuid) {
        String externalId = entityToExternalId.get(entityUuid);
        if (externalId != null) {
            return getNpc(externalId);
        }
        return Optional.empty();
    }

    /**
     * 获取所有活跃NPC的不可修改集合
     */
    public Collection<NpcData> getAllNpcs() {
        return Collections.unmodifiableCollection(activeNpcs.values());
    }

    /**
     * 查找指定位置附近的NPC，并根据每个NPC各自的聊天距离进行过滤。
     *
     * 遍历所有已生成的NPC实例，实时获取位置并计算距离。
     * 每个NPC使用自身配置的chatDistance，若未配置则使用默认范围。
     *
     * @param location     搜索中心位置
     * @param defaultRange 当NPC未配置chatDistance时使用的默认范围
     * @return 在各自听觉范围内的NPC列表
     */
    public List<NpcSearchResult> getNpcsNear(Location location, double defaultRange) {
        if (location == null) {
            return Collections.emptyList();
        }

        List<NpcSearchResult> result = new ArrayList<>();

        // 直接遍历所有活跃NPC实例，实时检查位置（未使用空间索引/缓存）
        for (NpcInstanceData npcInstance : hytaleAPI.getNpcInstances()) {
            // 跳过未生成的NPC
            if (!npcInstance.isSpawned()) {
                logger.debug("NPC instance not spawned: " + npcInstance.entityUuid());
                continue;
            }

            Optional<Location> npcLocOpt = hytaleAPI.getNpcInstanceLocation(npcInstance.entityUuid());

            if (npcLocOpt.isPresent()) {
                Location npcLoc = npcLocOpt.get();

                // 检查是否在同一个世界
                if (!npcLoc.world().equals(location.world())) {
                    logger.debug("NPC instance not in same world: " + npcInstance.entityUuid());
                    continue;
                }

                double distance = npcLoc.distanceTo(location);
                NpcData npc = npcInstance.npcData();

                // 优先使用NPC自身的聊天距离配置，否则使用默认范围
                double range = (npc != null && npc.chatDistance() != null)
                        ? npc.chatDistance().doubleValue()
                        : defaultRange;

                if (distance <= range) {
                    result.add(new NpcSearchResult(npcInstance, npcLoc, distance));
                    logger.debug("[NpcManager] Found nearby NPC: " + npcInstance.entityUuid() + " (" + distance + "m)");
                } else {
                    logger.debug("[NpcManager] NPC not in range: " + npcInstance.entityUuid() + " (" + distance + "m)");
                }
            } else {
                logger.debug("NPC instance loc not found: " + npcInstance.entityUuid());
            }
        }

        return result;
    }

    /**
     * 更新NPC位置和空间索引（预留接口）
     */

    /**
     * 将实体UUID绑定到NPC
     * 只有当NPC已注册时才会建立绑定关系。
     */
    public void bindEntity(String externalId, UUID entityUuid) {
        if (activeNpcs.containsKey(externalId)) {
            entityToExternalId.put(entityUuid, externalId);
            logger.debug("Entity bound: " + externalId + " → " + entityUuid);
        }
    }

    /**
     * 将实体UUID绑定到NPC并立即更新位置（预留接口）。
     * 确保绑定后空间索引立即更新。
     *
     * @param externalId NPC外部ID
     * @param entityUuid 要绑定的实体UUID
     * @param location   实体当前位置
     */

    /**
     * 解除实体与NPC的绑定关系
     */
    public void unbindEntity(UUID entityUuid) {
        entityToExternalId.remove(entityUuid);
    }

    /**
     * 检查指定实体是否为受管理的NPC
     */
    public boolean isNpcEntity(UUID entityUuid) {
        return entityToExternalId.containsKey(entityUuid);
    }

    /**
     * 获取已注册的NPC总数
     */
    public int getNpcCount() {
        return activeNpcs.size();
    }

    /**
     * 获取已在游戏世界中生成的NPC数量
     */
    public int getSpawnedNpcCount() {
        return (int) hytaleAPI.getNpcInstances().stream()
                .filter(NpcInstanceData::isSpawned)
                .count();
    }

    /**
     * 根据外部ID查找距离指定位置最近的已生成NPC实例。
     * 遍历所有已生成的NPC实例，筛选匹配外部ID的实例并返回距离最近的一个。
     *
     * @param externalId 要搜索的NPC外部ID
     * @param location   搜索的中心位置
     * @return 最近的NpcInstanceData，如果未找到则返回空Optional
     */
    public Optional<NpcInstanceData> findNearestSpawnedNpcByExternalId(String externalId, Location location) {
        if (location == null) {
            return Optional.empty();
        }

        NpcInstanceData nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (NpcInstanceData npcInstance : hytaleAPI.getNpcInstances()) {
            // 只检查已生成且外部ID匹配的NPC
            if (!npcInstance.isSpawned()) {
                continue;
            }

            if (!npcInstance.npcData().externalId().equals(externalId)) {
                continue;
            }

            Optional<Location> npcLocOpt = hytaleAPI.getNpcInstanceLocation(npcInstance.entityUuid());
            if (npcLocOpt.isEmpty()) {
                continue;
            }

            Location npcLoc = npcLocOpt.get();

            // 检查是否在同一个世界中
            if (!npcLoc.world().equals(location.world())) {
                continue;
            }

            // 计算距离，记录最近的NPC实例
            double distance = npcLoc.distanceTo(location);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = npcInstance;
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * 清除所有NPC数据和空间索引（预留接口）
     */

}
