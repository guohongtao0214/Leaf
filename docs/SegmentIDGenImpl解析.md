
### 属性解析
```
    /**
     * IDCache未初始化成功时的异常码
     */
    private static final long EXCEPTION_ID_IDCACHE_INIT_FALSE = -1;

    /**
     * key不存在时的异常码
     */
    private static final long EXCEPTION_ID_KEY_NOT_EXISTS = -2;

    /**
     * SegmentBuffer中的两个Segment均未从DB中装载时的异常码
     */
    private static final long EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL = -3;

    /**
     * 最大步长不超过100,0000
     */
    private static final int MAX_STEP = 1000000;

    /**
     * 一个Segment维持时间为15分钟
     */
    private static final long SEGMENT_DURATION = 15 * 60 * 1000L;

    /**
     * 执行segment切换的线程池
     */
    private ExecutorService service = new ThreadPoolExecutor(5, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), new UpdateThreadFactory());

    /**
     * SegmentIDGenImpl是否初始化
     */
    private volatile boolean initOK = false;

    /**
     * 存储SegmentBuffer的缓存
     */
    private Map<String, SegmentBuffer> cache = new ConcurrentHashMap<>();


    public static class UpdateThreadFactory implements ThreadFactory {

        private static int threadInitNumber = 0;

        private static synchronized int nextThreadNum() {
            return threadInitNumber++;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "Thread-Segment-Update-" + nextThreadNum());
        }
    }
```

### 方法解析
1.init()：初始化
```
public boolean init() {
    logger.info("Init ...");
    // 确保加载到kv后才初始化成功
    updateCacheFromDb();
    initOK = true;
    // 每分钟更新Cache(数据库->cache)
    updateCacheFromDbAtEveryMinute();
    // 返回true，初始成功过
    return initOK;
}
```
2.updateCacheFromDb()：主要更新cache，使cache和数据库里面的记录保持一致
```
private void updateCacheFromDb() {
    logger.info("update cache from db");
    // 计时器
    StopWatch sw = new Slf4JStopWatch();
    try {
        List<String> dbTags = dao.getAllTags();
        if (dbTags == null || dbTags.isEmpty()) {
            return;
        }
        // 将dbTags中新加的tag添加cache，通过遍历dbTags，判断是否在cache中存在，不存在就添加到cache
        for (String dbTag : dbTags) {
            if (!cache.containsKey(dbTag)) {
                SegmentBuffer buffer = new SegmentBuffer();
                buffer.setKey(dbTag);
                Segment segment = buffer.getCurrent();
                segment.setValue(new AtomicLong(0));
                segment.setMax(0);
                segment.setStep(0);
                cache.put(dbTag, buffer);
                logger.info("Add tag {} from db to IdCache, SegmentBuffer {}", dbTag, buffer);
            }
        }
        List<String> cacheTags = new ArrayList<>(cache.keySet());
        Set<String> dbTagSet = new HashSet<>(dbTags);
        // 将cache中已失效的tag从cache删除，通过遍历cacheTags，判断是否在dbTagSet中存在，不存在说明过期，直接删除
        for (String cacheTag : cacheTags) {
            if (!dbTagSet.contains(cacheTag)) {
                cache.remove(cacheTag);
                logger.info("Remove tag {} from IdCache", cacheTag);
            }
        }
    } catch (Exception e) {
        logger.warn("update cache from db exception", e);
    } finally {
        sw.stop("updateCacheFromDb");
    }
}
```
3.updateCacheFromDbAtEveryMinute()：每分钟更新cache
```
private void updateCacheFromDbAtEveryMinute() {
    // 值得借鉴，自定义ThreadFactory，但是不建议Executors创建线程池
    ScheduledExecutorService service = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName("check-idCache-thread");
            // 设置为守护线程
            t.setDaemon(true);
            return t;
        }
    });
    service.scheduleWithFixedDelay(new Runnable() {
        @Override
        public void run() {
            updateCacheFromDb();
        }
    }, 60, 60, TimeUnit.SECONDS);
}
```
4.get()：获取包含ID的结果集
```
public Result get(final String key) {
    // 必须在SegmentIDGenImpl初始化后执行. init()方法
    if (!initOK) {
        return new Result(EXCEPTION_ID_IDCACHE_INIT_FALSE, Status.EXCEPTION);
    }
    // 通过缓存获取SegmentBuffer
    if (cache.containsKey(key)) {
        // 从缓存中获取对应key的SegmentBuffer
        SegmentBuffer buffer = cache.get(key);
        // buffer没有被初始化，执行
        if (!buffer.isInitOk()) {
            synchronized (buffer) {
                // 双重判断,避免重复执行SegmentBuffer的初始化操作（并发情况下可能会出现重复执行）
                if (!buffer.isInitOk()) {
                    try {
                        // 更新segment
                        updateSegmentFromDb(key, buffer.getCurrent());
                        logger.info("Init buffer. Update leafkey {} {} from db", key, buffer.getCurrent());
                        // buffer初始化完成
                        buffer.setInitOk(true);
                    } catch (Exception e) {
                        logger.warn("Init buffer {} exception", buffer.getCurrent(), e);
                    }
                }
            }
        }
        // 返回结果集
        return getIdFromSegmentBuffer(cache.get(key));
    }
    return new Result(EXCEPTION_ID_KEY_NOT_EXISTS, Status.EXCEPTION);
}
```
5.updateSegmentFromDb(String key, Segment segment)：从数据获取最新记录，更新segment
```
public void updateSegmentFromDb(String key, Segment segment) {
    // 计时器
    StopWatch sw = new Slf4JStopWatch();
    SegmentBuffer buffer = segment.getBuffer();
    LeafAlloc leafAlloc;
    if (!buffer.isInitOk()) {
        // 第一次初始化
        leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
        buffer.setStep(leafAlloc.getStep());
        // leafAlloc中的step为DB中的step
        buffer.setMinStep(leafAlloc.getStep());
    } else if (buffer.getUpdateTimestamp() == 0) {
        // 第二次，设置updateTimestamp
        leafAlloc = dao.updateMaxIdAndGetLeafAlloc(key);
        buffer.setUpdateTimestamp(System.currentTimeMillis());
        buffer.setStep(leafAlloc.getStep());
        // leafAlloc中的step为DB中的step
        buffer.setMinStep(leafAlloc.getStep());
    } else {
        // 三次及三次以上 动态设置 nextStep
        long duration = System.currentTimeMillis() - buffer.getUpdateTimestamp();
        int nextStep = buffer.getStep();

        /**
         *  动态调整step
         *  1) duration < 15 分钟 : step 变为原来的2倍. 最大为 MAX_STEP
         *  2) 15分钟 < duration < 30分钟 : nothing
         *  3) duration > 30 分钟 : 缩小step ,最小为DB中配置的步数
         */

        // 15分钟
        if (duration < SEGMENT_DURATION) {
            if (nextStep * 2 > MAX_STEP) {
                //do nothing
            } else {
                nextStep = nextStep * 2;
            }
            // 15分 < duration < 30
        } else if (duration < SEGMENT_DURATION * 2) {
            //do nothing with nextStep
        } else {
            // duration > 30 步数缩小一半,但是大于最小步数(数据库中配置的步数)
            nextStep = nextStep / 2 >= buffer.getMinStep() ? nextStep / 2 : nextStep;
        }
        logger.info("leafKey[{}], step[{}], duration[{}mins], nextStep[{}]", key, buffer.getStep(), String.format("%.2f", ((double) duration / (1000 * 60))), nextStep);
        LeafAlloc temp = new LeafAlloc();
        temp.setKey(key);
        temp.setStep(nextStep);
        // 更新maxId by CustomStep (nextStep)
        leafAlloc = dao.updateMaxIdByCustomStepAndGetLeafAlloc(temp);
        // 更新 updateTimestamp
        buffer.setUpdateTimestamp(System.currentTimeMillis());
        // 设置 buffer的step
        buffer.setStep(nextStep);
        // leafAlloc的step为DB中的step
        buffer.setMinStep(leafAlloc.getStep());
    }
    // must set value before set max
    // 计算segment的初始值
    long value = leafAlloc.getMaxId() - buffer.getStep();
    // 设置segment的初始值
    segment.getValue().set(value);
    // 设置segment的下放id的最大值
    segment.setMax(leafAlloc.getMaxId());
    // 设置号段长度
    segment.setStep(buffer.getStep());
    sw.stop("updateSegmentFromDb", key + " " + segment);
}
```
6.getIdFromSegmentBuffer(final SegmentBuffer buffer)：获取返回的结果集
```
public Result getIdFromSegmentBuffer(final SegmentBuffer buffer) {
    while (true) {
        // 获取buffer的读锁
        buffer.rLock().lock();
        try {
            // 获取当前的号段
            final Segment segment = buffer.getCurrent();
            /**
             * nextReady is false (下一个号段没有初始化)
             * idle = max - currentValue (当前号段下发的值到达设置的阈值 0.9 )
             * buffer 中的 threadRunning字段. 代表是否已经提交线程池运行(是否有其他线程已经开始进行另外号段的初始化工作)
             * 使用CAS进行更新  buffer在任意时刻,只会有一个线程进行异步更新另外一个号段
             */
            if (!buffer.isNextReady()
                    && (segment.getIdle() < 0.9 * segment.getStep())
                    && buffer.getThreadRunning().compareAndSet(false, true)) {
                // 放入线程池进行异步更新
                service.execute(() -> {
                    // 切换下一个segment
                    Segment next = buffer.getSegments()[buffer.nextPos()];
                    // 设置更新成功的标志为false
                    boolean updateOk = false;
                    try {
                        // 更新新的segment
                        updateSegmentFromDb(buffer.getKey(), next);
                        updateOk = true;
                        logger.info("update segment {} from db {}", buffer.getKey(), next);
                    } catch (Exception e) {
                        logger.warn(buffer.getKey() + " updateSegmentFromDb exception", e);
                    } finally {
                        if (updateOk) {
                            // 获取buffer的写锁
                            buffer.wLock().lock();
                            // next准备完成
                            buffer.setNextReady(true);
                            // next运行标记位设置为false
                            buffer.getThreadRunning().set(false);
                            buffer.wLock().unlock();
                        } else {
                            buffer.getThreadRunning().set(false);
                        }
                    }
                });
            }

            // 获取value
            long value = segment.getValue().getAndIncrement();
            // value < 当前号段的最大值,则返回改值
            if (value < segment.getMax()) {
                return new Result(value, Status.SUCCESS);
            }
        } finally {
            buffer.rLock().unlock();
        }

        // 等待下一个号段执行完成,执行代码在-> execute()
        // buffer.setNextReady(true);
        // buffer.getThreadRunning().set(false);
        waitAndSleep(buffer);

        buffer.wLock().lock();
        try {
            // 获取value -> 为什么重复获取value, 多线程执行时,在进行waitAndSleep() 后,
            // 当前Segment可能已经被调换了.直接进行一次获取value的操作,可以提高id下发的速度(没必要再走一次循环)
            // 并且防止出错(在交换Segment前进行一次检查)
            final Segment segment = buffer.getCurrent();
            long value = segment.getValue().getAndIncrement();
            if (value < segment.getMax()) {
                return new Result(value, Status.SUCCESS);
            }

            // 执行到这里, 其他的线程没有进行号段的调换，并且当前号段所有号码已经下发完成。
            // 判断nextReady是否为true.
            if (buffer.isNextReady()) {
                buffer.switchPos();
                buffer.setNextReady(false);
            } else {
                // 进入这里的条件
                // 1. 当前号段获取到的值大于maxValue
                // 2. 另外一个号段还没有准备好
                // 3. 等待时长大于waitAndSleep中的时间
                logger.error("Both two segments in {} are not ready!", buffer);
                return new Result(EXCEPTION_ID_TWO_SEGMENTS_ARE_NULL, Status.EXCEPTION);
            }
        } finally {
            buffer.wLock().unlock();
        }
    }
}
```
7.waitAndSleep(SegmentBuffer buffer)：等待
```
private void waitAndSleep(SegmentBuffer buffer) {
    int roll = 0;
    while (buffer.getThreadRunning().get()) {
        roll += 1;
        if (roll > 10000) {
            try {
                TimeUnit.MILLISECONDS.sleep(10);
                break;
            } catch (InterruptedException e) {
                logger.warn("Thread {} Interrupted", Thread.currentThread().getName());
                break;
            }
        }
    }
}
```