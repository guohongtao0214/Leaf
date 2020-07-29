package com.sankuai.inf.leaf;

import com.sankuai.inf.leaf.common.Result;

public interface IDGen {

    /**
     * 获取返回的结果集
     *
     * @param key
     * @return Result
     */
    Result get(String key);

    /**
     * 初始化方法
     *
     * @return boolean
     */
    boolean init();
}
