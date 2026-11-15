package com.coolskyai.coolpilot.insight.mapper;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 单表 CRUD 契约，替代 MyBatis-Plus 的 BaseMapper。
 *
 * 只声明方法签名，具体 SQL 在各 Mapper XML 中实现（见 resources/mapper）。
 * 之所以保留同名方法，是为了让既有 Service 调用点无需改动。
 */
public interface BaseCrudMapper<T> {

    T selectById(Serializable id);

    List<T> selectBatchIds(Collection<? extends Serializable> ids);

    int insert(T entity);

    int updateById(T entity);

    int deleteById(Serializable id);
}
