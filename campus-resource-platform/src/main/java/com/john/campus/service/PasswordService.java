package com.john.campus.service;

/**
 * 密码处理接口，隔离具体加密算法，便于后续替换或增强策略。
 */
public interface PasswordService {

    /**
     * 对原始密码进行不可逆哈希，保存到数据库。
     */
    String encode(String rawPassword);

    /**
     * 校验用户输入的原始密码和数据库中的密码哈希是否匹配。
     */
    boolean matches(String rawPassword, String encodedPassword);
}
