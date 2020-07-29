package com.sankuai.inf.leaf.segment.model;

import java.util.concurrent.atomic.AtomicLong;

public class Segment {

    /**
     * 使用原子操作类，每次获取不同的id
     */
    private AtomicLong value = new AtomicLong(0);

    /**
     * 目前所被分配的ID号段的最大值
     */
    private volatile long max;

    /**
     * 目前分配的号段长度
     */
    private volatile int step;

    /**
     * 持有SegmentBuffer引用，可进行SegmentBuffer相关方法操作
     */
    private SegmentBuffer buffer;

    public Segment(SegmentBuffer buffer) {
        this.buffer = buffer;
    }

    public AtomicLong getValue() {
        return value;
    }

    public void setValue(AtomicLong value) {
        this.value = value;
    }

    public long getMax() {
        return max;
    }

    public void setMax(long max) {
        this.max = max;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public SegmentBuffer getBuffer() {
        return buffer;
    }

    public long getIdle() {
        return this.getMax() - getValue().get();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Segment(");
        sb.append("value:");
        sb.append(value);
        sb.append(",max:");
        sb.append(max);
        sb.append(",step:");
        sb.append(step);
        sb.append(")");
        return sb.toString();
    }
}
