package com.equipops.domain.vo;

import java.util.List;

/** 成员范围响应，数据库标识符使用十进制字符串保留精度。 */
public record SiteScopeVO(String membershipId, Long version, List<String> siteIds) {}
