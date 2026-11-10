package com.team.vo;

import java.util.List;

/** 完整团队快照；所有业务ID为字符串，版本为JSON安全整数。 */
public record TeamVO(String id, String tenantId, String teamCode, String name, boolean enabled,
                     long version, List<String> membershipIds, List<String> siteIds) {}
