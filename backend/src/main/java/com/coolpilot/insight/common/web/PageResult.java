package com.coolpilot.insight.common.web;

import java.util.List;

/**
 * 统一分页响应。
 *
 * <p>字段名与前端既有约定保持一致（records / total / size / current / pages），
 * 避免因后端换实现而改前端。
 */
public class PageResult<T> {

    private List<T> records;
    private long total;
    private int size;
    private int current;
    private long pages;

    public PageResult() {
    }

    public PageResult(List<T> records, long total, int current, int size) {
        this.records = records;
        this.total = total;
        this.current = current;
        this.size = size;
        this.pages = size <= 0 ? 0 : (total + size - 1) / size;
    }

    public static <T> PageResult<T> of(List<T> records, long total, int current, int size) {
        return new PageResult<>(records, total, current, size);
    }

    public List<T> getRecords() {
        return records;
    }

    public long getTotal() {
        return total;
    }

    public int getSize() {
        return size;
    }

    public int getCurrent() {
        return current;
    }

    public long getPages() {
        return pages;
    }
}
