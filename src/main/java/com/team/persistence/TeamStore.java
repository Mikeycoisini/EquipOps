package com.team.persistence;
import java.util.List;
/** 团队事务存储；每个读写SQL均显式限定可信企业。 */
public interface TeamStore {
    record Row(long id,long tenantId,String teamCode,String name,boolean enabled,long version) {}
    record Operation(String hash,String action,String resultJson) {}
    void lockTenant(long tenantId);
    boolean lockAdmin(long tenantId,long membershipId,long userId);
    Row find(long tenantId,long teamId,boolean lock);
    List<Row> list(long tenantId,long membershipId,boolean admin);
    List<Long> members(long tenantId,long teamId,boolean lock);
    List<Long> sites(long tenantId,long teamId,boolean lock);
    Operation operation(long tenantId,String requestId);
    List<Long> lockMembers(long tenantId,List<Long> ids);
    List<Long> lockSites(long tenantId,List<Long> ids);
    boolean hasOpenOrders(long tenantId,long teamId);
    boolean hasOpenOrdersForMembers(long tenantId,long teamId,List<Long> removed);
    boolean hasOpenOrdersForSites(long tenantId,long teamId,List<Long> removed);
    long insert(long tenantId,String code,String name,boolean enabled,long actorId);
    int update(long tenantId,long teamId,long version,String name,boolean enabled);
    void replaceMembers(long tenantId,long teamId,List<Long> before,List<Long> after);
    void replaceSites(long tenantId,long teamId,List<Long> before,List<Long> after);
    void saveOperation(long tenantId,long teamId,String requestId,String hash,String action,String resultJson);
}
