package com.sankuai.inf.leaf.segment.model;


public class LeafAlloc {

    /**
     * 业务key，用来去分业务
     */
    private String key;

    /**
     * 业务key目前所被分配的ID号段的最大值
     */
    private long maxId;

    /**
     * 每次分配的号段长度
     */
    private int step;

    /**
     * 更新时间
     */
    private String updateTime;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public long getMaxId() {
        return maxId;
    }

    public void setMaxId(long maxId) {
        this.maxId = maxId;
    }

    public int getStep() {
        return step;
    }

    public void setStep(int step) {
        this.step = step;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
