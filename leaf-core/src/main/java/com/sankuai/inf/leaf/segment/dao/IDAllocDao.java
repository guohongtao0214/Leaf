package com.sankuai.inf.leaf.segment.dao;

import com.sankuai.inf.leaf.segment.model.LeafAlloc;

import java.util.List;

public interface IDAllocDao {

    List<LeafAlloc> getAllLeafAllocs();

    /**
     * 使用事务保证这两步的原子性(事务的隔离机制)
     * 根据数据库中对应tag的step来更新max_value,同时获取 tag的信息
     * 1. UPDATE leaf_alloc SET max_id = max_id + step WHERE biz_tag = #{tag}
     * 2. SELECT biz_tag, max_id, step FROM leaf_alloc WHERE biz_tag = #{tag}
     *
     * @param tag
     * @return
     */
    LeafAlloc updateMaxIdAndGetLeafAlloc(String tag);

    LeafAlloc updateMaxIdByCustomStepAndGetLeafAlloc(LeafAlloc leafAlloc);

    List<String> getAllTags();
}
