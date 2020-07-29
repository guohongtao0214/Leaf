package com.sankuai.inf.leaf.segment.dao;

import com.sankuai.inf.leaf.segment.model.LeafAlloc;

import java.util.List;

public interface IDAllocDao {

    /**
     * 获取所有的LeafAlloc
     *
     * @return List<LeafAlloc>
     */
    List<LeafAlloc> getAllLeafAllocs();

    /**
     * 使用事务保证这两步的原子性(事务的隔离机制)
     * 根据数据库中对应tag的step来更新max_value,同时获取 tag的信息
     * 1. UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = #{tag}
     * 2. SELECT biz_tag, max_id, step FROM leaf_alloc WHERE biz_tag = #{tag}
     *
     * @param tag
     * @return LeafAlloc
     */
    LeafAlloc updateMaxIdAndGetLeafAlloc(String tag);

    /**
     * 动态调整MaxId step周期性发生变化
     *
     * @param leafAlloc
     * @return LeafAlloc
     */
    LeafAlloc updateMaxIdByCustomStepAndGetLeafAlloc(LeafAlloc leafAlloc);

    /**
     * 获取所有的tag
     *
     * @return List<String>
     */
    List<String> getAllTags();
}
