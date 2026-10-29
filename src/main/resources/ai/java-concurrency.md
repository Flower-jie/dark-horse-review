# Java 并发：线程、synchronized、线程池与并发工具

## 线程的创建与状态

Java 中创建线程的方式有：继承 Thread 并重写 run、实现 Runnable 交给 Thread 执行、实现 Callable 提交给线程池并获得 Future、或者直接向线程池提交任务。实际项目里几乎都使用线程池，因为线程的创建和销毁成本高，数量也不可控。

线程有六种状态：NEW、RUNNABLE、BLOCKED、WAITING、TIMED_WAITING、TERMINATED。BLOCKED 表示等待进入 synchronized 临界区；WAITING 是调用 wait、join、park 后的无限等待；TIMED_WAITING 是带超时的等待，例如 sleep。不要使用已经废弃的 Thread.stop、suspend、resume，停止线程应通过中断标志或共享的 volatile 标志位协作完成。

## synchronized 与 volatile

synchronized 是可重入锁：同一个线程重复进入同一把锁不会死锁。修饰实例方法时锁住的是 this 对象，修饰静态方法时锁住的是 Class 对象，修饰代码块时锁住括号里指定的对象。它同时保证原子性、可见性和有序性：进入临界区前读取主内存最新值，退出前把修改刷回主内存。

volatile 只保证可见性和禁止指令重排，不保证复合操作的原子性，所以 count++ 这类读改写仍然需要锁或原子类。volatile 的典型用法是状态标志位，以及双重检查锁定中的单例引用。

## 线程池的关键参数

ThreadPoolExecutor 的核心参数是：corePoolSize（核心线程数）、maximumPoolSize（最大线程数）、keepAliveTime 与 unit（非核心线程空闲存活时间）、workQueue（任务队列）、threadFactory（线程工厂）、handler（拒绝策略）。

任务提交的顺序是：当前线程数小于核心线程数时新建核心线程执行；核心线程满了以后任务进入队列；队列满了才继续创建线程直到最大线程数；仍满则执行拒绝策略。理解这个顺序非常重要，因为如果队列是无界的，maximumPoolSize 就永远不会生效，任务会一直堆积到内存耗尽。Executors.newFixedThreadPool 使用的正是无界 LinkedBlockingQueue，所以在生产环境更推荐显式 new ThreadPoolExecutor，并设置有界队列和合理的拒绝策略。

四种拒绝策略：AbortPolicy 抛 RejectedExecutionException（默认）；CallerRunsPolicy 让提交任务的线程自己执行，形成天然的反压；DiscardPolicy 静默丢弃；DiscardOldestPolicy 丢弃队列中最旧的任务。线程池不再使用时调用 shutdown 停止接收新任务并等待已提交任务结束，shutdownNow 会尝试中断正在执行的任务。

## ThreadLocal

ThreadLocal 让每个线程拥有独立的变量副本。每个 Thread 内部持有一个 ThreadLocalMap，key 是 ThreadLocal 对象本身的弱引用，value 是强引用。在线程池场景下，线程会被复用，如果使用完不调用 remove，value 会随着线程一直存活，造成内存泄漏，下一个任务还可能读到上一个任务残留的数据，产生脏数据。因此必须在 finally 中调用 remove。本项目的 UserHolder 就用 ThreadLocal 保存当前登录用户，并在拦截器的 afterCompletion 中清理。

## 常见并发工具

CountDownLatch 是一次性倒计数器，主线程 await 等待其他线程 countDown 到零，适合等待多个任务就绪。CyclicBarrier 是可循环使用的栅栏，一组线程互相等待到齐后一起继续。Semaphore 用许可数量控制同时访问的线程数，可用于限流。ReentrantLock 相比 synchronized 支持公平锁、可中断获取、超时获取和多个 Condition 条件队列。ReadWriteLock 读读不互斥、读写互斥，适合读多写少。AtomicInteger 等原子类基于 CAS 实现无锁的原子更新。BlockingQueue 的 put 和 take 天然实现生产者消费者模型。Java 8 的 CompletableFuture 支持异步编排和回调组合。

## 死锁与工程实践

死锁需要同时满足互斥、持有并等待、不可剥夺、循环等待四个条件。工程上最常见的处理方式是让所有线程按固定顺序加锁，或者使用带超时的 tryLock，失败后释放已持有的锁重试。

需要强调：synchronized 和 ReentrantLock 只在单个 JVM 内有效。本项目是单机部署时可以用本地锁，但秒杀场景下多个实例共享同一份库存，必须使用 Redis 分布式锁或 Redis + Lua 原子脚本，本地锁无法跨进程互斥。
