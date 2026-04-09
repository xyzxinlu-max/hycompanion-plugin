package dev.hycompanion.plugin.shutdown;

import dev.hycompanion.plugin.utils.PluginLogger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 集中式关闭管理器 - 协调插件所有组件的优雅关闭
 *
 * 提供以下功能：
 * 1. 关闭状态的唯一可信来源（单一真相源）
 * 2. 世界线程操作的熔断器模式
 * 3. 监听器模式，允许组件注册清理回调
 * 4. 防止在服务器关闭期间执行危险操作
 */
public class ShutdownManager {

    /** 是否正在关闭 */
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** 世界操作是否被阻止 */
    private final AtomicBoolean worldOperationsBlocked = new AtomicBoolean(false);
    /** 正在等待执行的世界线程操作计数 */
    private final AtomicInteger pendingWorldOperations = new AtomicInteger(0);
    /** 关闭监听器列表（线程安全） */
    private final List<ShutdownListener> listeners = new CopyOnWriteArrayList<>();
    private final PluginLogger logger;

    public ShutdownManager(PluginLogger logger) {
        this.logger = logger;
    }

    /**
     * 检查服务器是否正在关闭
     * 这是关闭状态的唯一可信来源
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * 立即阻止所有世界操作
     * 在ShutdownEvent中、玩家移除开始之前调用
     */
    public void blockWorldOperations() {
        if (worldOperationsBlocked.compareAndSet(false, true)) {
            String threadName = Thread.currentThread().getName();
            logger.info("[ShutdownManager] World operations BLOCKED (thread: " + threadName + ")");
        }
    }

    /**
     * 检查世界操作是否被阻止
     */
    public boolean areWorldOperationsBlocked() {
        return worldOperationsBlocked.get();
    }

    /**
     * 设置关闭标志但不阻止世界操作
     * 在关闭早期阶段调用，标记关闭已开始，
     * 但仍允许Hytale的区块保存正常进行
     */
    public void setShuttingDown() {
        String threadName = Thread.currentThread().getName();
        if (shuttingDown.compareAndSet(false, true)) {
            logger.info("[ShutdownManager] Shutdown flag set (world operations NOT blocked) on thread: " + threadName);
        }
    }
    
    /**
     * 启动关闭序列
     * 此方法是幂等的 - 多次调用不会产生额外效果
     * 先阻止世界操作，再设置关闭标志，最后通知所有监听器
     *
     * @return 如果本次调用发起了关闭返回true，如果已在关闭中返回false
     */
    public boolean initiateShutdown() {
        String threadName = Thread.currentThread().getName();
        long timestamp = System.currentTimeMillis();
        
        // Block world operations FIRST
        blockWorldOperations();
        
        if (shuttingDown.compareAndSet(false, true)) {
            logger.info("[ShutdownManager] ============================================");
            logger.info("[ShutdownManager] SHUTDOWN INITIATED (with world operations BLOCKED)");
            logger.info("[ShutdownManager] Thread: " + threadName);
            logger.info("[ShutdownManager] Timestamp: " + timestamp);
            logger.info("[ShutdownManager] Pending world operations: " + pendingWorldOperations.get());
            logger.info("[ShutdownManager] Notifying " + listeners.size() + " listeners...");
            
            long notifyStart = System.currentTimeMillis();
            notifyListeners();
            long notifyTime = System.currentTimeMillis() - notifyStart;
            
            logger.info("[ShutdownManager] Listeners notified in " + notifyTime + "ms");
            logger.info("[ShutdownManager] ============================================");
            return true;
        }
        logger.debug("[ShutdownManager] Shutdown already initiated, ignoring duplicate call from thread: " + threadName);
        return false;
    }

    /**
     * 注册关闭监听器，在关闭时收到通知
     * 如果已经在关闭中，则立即调用监听器
     */
    public void register(ShutdownListener listener) {
        if (shuttingDown.get()) {
            // Already shutting down, call immediately
            try {
                listener.onShutdown();
            } catch (Exception e) {
                logger.warn("[ShutdownManager] Listener threw exception: " + e.getMessage());
            }
        } else {
            listeners.add(listener);
        }
    }

    /**
     * 注销关闭监听器，即使未注册也可安全调用
     */
    public void unregister(ShutdownListener listener) {
        listeners.remove(listener);
    }

    /**
     * 仅在未关闭时执行操作
     * 如果正在关闭则跳过，返回false
     */
    public boolean executeIfNotShutdown(Runnable operation) {
        if (shuttingDown.get()) {
            return false;
        }
        try {
            operation.run();
            return true;
        } catch (Exception e) {
            logger.debug("[ShutdownManager] Operation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 世界线程操作的熔断器检查
     * 在提交 world.execute() 之前调用，判断操作是否应被允许
     *
     * @return true表示允许执行，false表示应拒绝
     */
    public boolean allowWorldOperation() {
        return !worldOperationsBlocked.get() && !Thread.currentThread().isInterrupted();
    }

    /**
     * 世界线程操作的安全包装器
     * 返回true表示已提交，false表示因关闭而被拒绝
     *
     * 关键点：此方法必须在关闭期间拒绝任务，以防止"无效实体引用"错误。
     * 一旦关闭开始，不应再向世界线程提交新任务。
     * 采用双重检查机制：提交前检查 + 执行前再次检查
     */
    public boolean safeWorldExecute(java.util.function.Consumer<Runnable> worldExecutor, Runnable task) {
        // 激进策略：在任何操作之前先检查关闭标志
        if (worldOperationsBlocked.get()) {
            logger.debug("[ShutdownManager] World operation REJECTED - shutdown in progress (blocked)");
            return false;
        }
        
        // 跟踪待执行操作计数
        int pending = pendingWorldOperations.incrementAndGet();
        try {
            // 递增后再次检查（双重检查锁定模式）
            if (worldOperationsBlocked.get()) {
                logger.debug("[ShutdownManager] World operation REJECTED - shutdown started during check");
                return false;
            }
            
            worldExecutor.accept(() -> {
                try {
                    if (!worldOperationsBlocked.get()) {
                        task.run();
                    } else {
                        logger.debug("[ShutdownManager] World task SKIPPED - shutdown in progress");
                    }
                } catch (Exception e) {
                    logger.debug("[ShutdownManager] World task FAILED: " + e.getMessage());
                } finally {
                    int remaining = pendingWorldOperations.decrementAndGet();
                    if (remaining < 0) {
                        logger.warn("[ShutdownManager] Pending operations went negative: " + remaining);
                    }
                }
            });
            logger.debug("[ShutdownManager] World operation ACCEPTED (pending: " + pending + ")");
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.debug("[ShutdownManager] World operation REJECTED - executor rejected (shutting down?)");
            pendingWorldOperations.decrementAndGet();
            return false;
        } catch (Exception e) {
            logger.debug("[ShutdownManager] World operation FAILED: " + e.getMessage());
            pendingWorldOperations.decrementAndGet();
            return false;
        }
    }

    /**
     * 获取当前待执行的世界操作数量
     */
    public int getPendingWorldOperations() {
        return pendingWorldOperations.get();
    }

    /** 通知所有注册的关闭监听器，异常不会中断其他监听器的执行 */
    private void notifyListeners() {
        for (ShutdownListener listener : listeners) {
            try {
                listener.onShutdown();
            } catch (Exception e) {
                logger.warn("[ShutdownManager] Listener threw exception: " + e.getMessage());
            }
        }
    }

    /**
     * 关闭监听器函数式接口
     * 组件实现此接口以在关闭时执行清理操作
     */
    @FunctionalInterface
    public interface ShutdownListener {
        /** 关闭时回调，执行清理逻辑 */
        void onShutdown();
    }
}
