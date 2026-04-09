package dev.hycompanion.plugin.systems;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import dev.hycompanion.plugin.HycompanionEntrypoint;

import javax.annotation.Nonnull;
import java.util.UUID;

/**
 * NPC重生系统 - 检测NPC死亡并触发重生
 *
 * 继承自Hytale的 DeathSystems.OnDeathSystem，注册到实体存储注册表中。
 * 当NPC实体获得 DeathComponent（表示死亡）时，
 * 通过 HytaleServerAdapter 调度重生逻辑。
 */
public class NpcRespawnSystem extends DeathSystems.OnDeathSystem {

    /** 查询条件：匹配所有NPC实体 */
    @Nonnull
    private final Query<EntityStore> query = NPCEntity.getComponentType();

    @Override
    @Nonnull
    public Query<EntityStore> getQuery() {
        return query;
    }

    /**
     * 当NPC实体被添加 DeathComponent 时触发（即NPC死亡时）
     * 提取NPC的UUID和角色名，然后委托适配器处理重生调度
     */
    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // 从存储中获取NPC组件，提取UUID和角色信息
        NPCEntity npcEntity = store.getComponent(ref, NPCEntity.getComponentType());
        if (npcEntity == null) {
            return;
        }

        UUID entityUuid = npcEntity.getUuid();
        String roleName = npcEntity.getRoleName();

        // UUID或角色名为空则忽略
        if (entityUuid == null || roleName == null) {
            return;
        }

        // 通过适配器调度NPC重生
        HycompanionEntrypoint entrypoint = HycompanionEntrypoint.getInstance();
        if (entrypoint != null && entrypoint.getHytaleAPI() != null) {
            var adapter = (dev.hycompanion.plugin.adapter.HytaleServerAdapter) entrypoint.getHytaleAPI();
            adapter.handleNpcDeath(entityUuid, roleName);
        }
    }
}
