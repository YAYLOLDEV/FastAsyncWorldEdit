package com.fastasyncworldedit.core.entity;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nullable;
import java.util.function.Supplier;

public class LazyBaseEntity extends BaseEntity {

    private Supplier<LinCompoundTag> saveTag;
    private final Entity entity;

    public LazyBaseEntity(EntityType type, Supplier<LinCompoundTag> saveTag) {
        this(type, saveTag, null);
    }

    public LazyBaseEntity(EntityType type, Supplier<LinCompoundTag> saveTag, Entity entity) {
        super(type);
        this.saveTag = saveTag;
        this.entity = entity;
    }

    @Nullable
    @Override
    public synchronized LinCompoundTag getNbt() {
        Supplier<LinCompoundTag> tmp = saveTag;
        if (tmp != null) {
            saveTag = null;
            if (Fawe.isMainThread()) {
                setNbt(tmp.get());
            } else {
                setNbt(entity == null
                        ? TaskManager.taskManager().syncGlobal(tmp)
                        : TaskManager.taskManager().syncWith(tmp, entity));
            }
        }
        return super.getNbt();
    }

}
