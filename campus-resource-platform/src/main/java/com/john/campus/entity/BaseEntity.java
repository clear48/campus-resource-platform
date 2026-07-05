package com.john.campus.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 基础实体字段，承载所有业务表通用的主键和时间戳。
 */
@Getter
@Setter
public class BaseEntity {

    /**
     * 数据库自增主键，业务代码统一使用 Long 承载。
     */
    private Long id;
    /**
     * 记录创建时间，由数据库默认值生成，查询时映射回实体。
     */
    private LocalDateTime createdAt;
    /**
     * 记录更新时间，由数据库 ON UPDATE 机制维护。
     */
    private LocalDateTime updatedAt;
}
