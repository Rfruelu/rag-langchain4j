package com.lujia.rag.raglangchain4j.auth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户信息表（网页端登录用户）
 */
@Data
@TableName("user_info")
public class UserInfo {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 手机号（登录账号）
     */
    private String phone;

    /**
     * 登录密码
     */
    private String password;

    /**
     * 姓名
     */
    private String name;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像地址
     */
    private String avatar;

    /**
     * 状态：ACTIVE-正常、FROZEN-冻结
     */
    private String status;

    /**
     * 用户类型：VISITOR-访客, CUSTOMER-外部客户, STAFF-客服
     */
    private String userType;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
