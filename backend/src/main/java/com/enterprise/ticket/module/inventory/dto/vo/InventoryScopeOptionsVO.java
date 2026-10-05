package com.enterprise.ticket.module.inventory.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 盘点范围可选值（P2）
 *
 * <p>范围分两类：**分类**（设备分类字典）与**存放位置**（从现有台账里 distinct 出来）。
 * 位置的取值不是一张字典表，而是「账号实际用过的位置」—— 让用户从一个不存在的
 * 位置列表里挑，比让他自己填一个拼错的还糟：盘点会得到「范围内 0 台设备」这种结果，
 * 而看起来像是系统坏了。
 */
@Data
public class InventoryScopeOptionsVO {

    private List<Option> categories;

    private List<Option> locations;

    @Data
    @AllArgsConstructor
    public static class Option {

        /** 提交值：分类为 id 字符串；位置为位置名本身 */
        private String value;

        private String label;
    }
}
