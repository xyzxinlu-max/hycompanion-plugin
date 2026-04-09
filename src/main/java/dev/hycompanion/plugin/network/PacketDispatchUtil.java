package dev.hycompanion.plugin.network;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import dev.hycompanion.plugin.utils.PluginLogger;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 数据包发送工具类
 * 使用反射以兼容不同服务器版本的方式向玩家发送网络数据包
 * 因为不同版本的 Hytale 服务器可能暴露不同的 PacketHandler 方法签名
 */
public final class PacketDispatchUtil {
    /** 兼容性警告是否已记录（确保只警告一次） */
    private static final AtomicBoolean COMPAT_WARNING_LOGGED = new AtomicBoolean(false);

    // 工具类，禁止实例化
    private PacketDispatchUtil() {
    }

    /**
     * 以运行时兼容的方式向玩家发送数据包
     * 某些服务器版本不暴露 PacketHandler.write(Packet) 方法，
     * 因此通过反射动态查找并调用兼容的发送方法
     *
     * @param playerRef 目标玩家引用
     * @param packet    要发送的数据包对象
     * @param logger    日志记录器（可为 null）
     * @return 是否成功发送数据包
     */
    public static boolean trySendPacketToPlayer(PlayerRef playerRef, Object packet, PluginLogger logger) {
        if (playerRef == null || packet == null) {
            return false;
        }

        // 尝试获取玩家的数据包处理器
        Object packetHandler;
        try {
            packetHandler = playerRef.getPacketHandler();
        } catch (Throwable t) {
            if (logger != null) {
                logger.debug("[Hycompanion] Could not access packet handler: " + t.getMessage());
            }
            return false;
        }
        if (packetHandler == null) {
            return false;
        }

        try {
            // 优先尝试查找单参数方法：writeNoCache(packet) 或 write(packet)
            Method directOneArg = findSingleArgCompatibleMethod(packetHandler.getClass(), packet.getClass(), "writeNoCache", "write");
            if (directOneArg != null) {
                directOneArg.invoke(packetHandler, packet);
                return true;
            }

            // 备选方案：某些版本暴露 writePacket(packet, cacheFlag) 两参数方法
            Method writePacketMethod = findWritePacketMethod(packetHandler.getClass(), packet.getClass());
            if (writePacketMethod != null) {
                writePacketMethod.invoke(packetHandler, packet, true);
                return true;
            }

            // 未找到兼容方法时记录警告（仅记录一次）
            if (logger != null && COMPAT_WARNING_LOGGED.compareAndSet(false, true)) {
                logger.warn("[Hycompanion] No compatible packet send method found on packet handler class: " +
                    packetHandler.getClass().getName());
            }
        } catch (Throwable t) {
            // 绝不允许数据包发送失败导致世界线程崩溃
            if (logger != null) {
                logger.debug("[Hycompanion] Packet dispatch failed safely: " + t.getMessage());
            }
        }

        return false;
    }

    /**
     * 在处理器类中查找与数据包类型兼容的单参数方法
     * 按方法名列表顺序查找，返回第一个匹配的方法
     *
     * @param handlerClass 处理器类
     * @param packetClass  数据包类
     * @param methodNames  要查找的方法名列表（按优先级排列）
     * @return 找到的兼容方法，未找到则返回 null
     */
    private static Method findSingleArgCompatibleMethod(Class<?> handlerClass, Class<?> packetClass, String... methodNames) {
        for (String methodName : methodNames) {
            for (Method method : handlerClass.getMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> paramType = method.getParameterTypes()[0];
                if (!paramType.isAssignableFrom(packetClass)) {
                    continue;
                }
                return method;
            }
        }
        return null;
    }

    /**
     * 查找 writePacket(packet, boolean) 形式的两参数方法
     * 某些服务器版本使用此方法签名发送数据包
     *
     * @param handlerClass 处理器类
     * @param packetClass  数据包类
     * @return 找到的方法，未找到则返回 null
     */
    private static Method findWritePacketMethod(Class<?> handlerClass, Class<?> packetClass) {
        for (Method method : handlerClass.getMethods()) {
            if (!method.getName().equals("writePacket")) {
                continue;
            }
            if (method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes[0].isAssignableFrom(packetClass) && (paramTypes[1] == boolean.class || paramTypes[1] == Boolean.class)) {
                return method;
            }
        }
        return null;
    }
}
