package dev.hycompanion.plugin.core.npc;

import java.util.UUID;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.modules.entity.component.ActiveAnimationComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hycompanion.plugin.api.Location;
import dev.hycompanion.plugin.utils.PluginLogger;

/**
 * NPC实例数据 - 表示游戏世界中一个具体的NPC实体实例。
 * 包含实体UUID、实体引用、NPC实体组件、NPC配置数据和出生位置。
 * 作为不可变record，通过with*方法创建修改后的副本。
 *
 * @param entityUuid    游戏中的实体UUID（为null表示未生成）
 * @param entityRef     实体存储引用，用于访问ECS组件
 * @param npcEntity     Hytale NPC实体组件，用于控制动画等
 * @param npcData       关联的NPC配置数据
 * @param spawnLocation NPC的出生/生成位置
 */
public record NpcInstanceData(
        UUID entityUuid,
        Ref<EntityStore> entityRef,
        NPCEntity npcEntity,
        NpcData npcData,
        Location spawnLocation) {

    private static final PluginLogger logger = new PluginLogger("NpcInstanceData");

    /** 需要清除的所有动画槽位，用于完全重置NPC的视觉状态 */
    private static final AnimationSlot[] ALL_SLOTS = {
            AnimationSlot.Action,
            AnimationSlot.Emote,
            AnimationSlot.Face,
            AnimationSlot.Status,
            AnimationSlot.Movement
    };

    /**
     * 检查NPC实例是否已在游戏世界中生成
     */
    public boolean isSpawned() {
        return entityUuid != null;
    }

    /**
     * 创建一个更新了实体UUID的副本
     */
    public NpcInstanceData withEntityUuid(UUID newEntityUuid) {
        return new NpcInstanceData(
                newEntityUuid, entityRef, npcEntity, npcData, spawnLocation);
    }

    /**
     * 创建一个更新了出生位置的副本
     */
    public NpcInstanceData withSpawnLocation(Location newSpawnLocation) {
        return new NpcInstanceData(
                entityUuid, entityRef, npcEntity, npcData, newSpawnLocation);
    }

    /**
     * 重置所有手动动画并强制NPC恢复站立姿态。
     * <p>
     * 必须在世界线程上调用。执行三个步骤：
     * <ol>
     * <li>清除{@link ActiveAnimationComponent}内部数组，使移动混合树不再被过期缓存值阻塞。</li>
     * <li>停止所有动画槽位（服务端缓存+客户端数据包）。</li>
     * <li>在Action槽位播放"Idle"然后置null，强制客户端将骨骼从持久姿态（坐下、睡觉等）过渡出来。</li>
     * </ol>
     *
     * @param store   实体存储（必须有效，在世界线程上获取）
     * @param context 调试日志的简短标签（如"moveNpcTo"）
     */
    public void resetAnimationsAndPosture(Store<EntityStore> store, String context) {
        // --- 第1步：清除ActiveAnimationComponent缓存 ---
        try {
            ActiveAnimationComponent activeAnimComp = store.getComponent(entityRef,
                    ActiveAnimationComponent.getComponentType());
            if (activeAnimComp != null) {
                String[] anims = activeAnimComp.getActiveAnimations();
                for (int i = 0; i < anims.length; i++) {
                    anims[i] = null;
                }
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] " + context
                    + ": could not clear ActiveAnimationComponent: " + e.getMessage());
        }

        // --- 第2步：停止所有动画槽位（服务端+客户端） ---
        NPCEntity npc = this.npcEntity;
        if (npc == null) {
            try {
                npc = store.getComponent(entityRef, NPCEntity.getComponentType());
            } catch (Exception e) {
                logger.debug("[Hycompanion] " + context
                        + ": could not get NPCEntity for animation reset: " + e.getMessage());
            }
        }

        for (AnimationSlot slot : ALL_SLOTS) {
            try {
                if (npc != null) {
                    npc.playAnimation(entityRef, slot, null, store);
                }
                AnimationUtils.stopAnimation(entityRef, slot, true, store);
            } catch (Exception e) {
                logger.debug("[Hycompanion] " + context
                        + ": could not clear " + slot + " animation: " + e.getMessage());
            }
        }

        // --- 第3步：强制姿态重置（在Action槽位闪播Idle） ---
        // 播放"Idle"强制客户端将骨骼从持久姿态（如坐下）过渡出来。
        // 随即置null，让移动混合树接管行走/跑步动画。
        try {
            if (npc != null) {
                npc.playAnimation(entityRef, AnimationSlot.Action, "Idle", store);
                npc.playAnimation(entityRef, AnimationSlot.Action, null, store);
            }
        } catch (Exception e) {
            logger.debug("[Hycompanion] " + context
                    + ": could not reset posture: " + e.getMessage());
        }
    }
}
