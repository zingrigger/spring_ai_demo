package com.example.auth.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcIdentityRepository implements IdentityRepository {

    private static final RowMapper<UserAccount> USER_ACCOUNT_MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getLong("id"),
            rs.getString("account"),
            rs.getString("name"),
            rs.getString("password"));

    private static final RowMapper<Organization> ORGANIZATION_MAPPER = (rs, rowNum) -> new Organization(
            rs.getLong("id"),
            rs.getString("name"));

    private static final RowMapper<Role> ROLE_MAPPER = (rs, rowNum) -> new Role(
            rs.getLong("id"),
            rs.getString("name"));

    private static final String SELECT_USER_BY_ACCOUNT =
            "SELECT id, account, name, password FROM sys_user WHERE account = ?";

    private static final String SELECT_ORGANIZATIONS_BY_USER =
            "SELECT DISTINCT o.id, o.name FROM sys_org o"
                    + " JOIN sys_user_org_role uor ON uor.org_id = o.id"
                    + " WHERE uor.user_id = ? ORDER BY o.id";

    private static final String SELECT_ROLES_BY_USER_AND_ORG =
            "SELECT DISTINCT r.id, r.name FROM sys_role r"
                    + " JOIN sys_user_org_role uor ON uor.role_id = r.id"
                    + " WHERE uor.user_id = ? AND uor.org_id = ? ORDER BY r.id";

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<UserAccount> findByAccount(String account) {
        return jdbcTemplate.query(SELECT_USER_BY_ACCOUNT, USER_ACCOUNT_MAPPER, account).stream().findFirst();
    }

    @Override
    public List<Organization> findOrganizations(long userId) {
        return jdbcTemplate.query(SELECT_ORGANIZATIONS_BY_USER, ORGANIZATION_MAPPER, userId);
    }

    @Override
    public List<Role> findRoles(long userId, long orgId) {
        return jdbcTemplate.query(SELECT_ROLES_BY_USER_AND_ORG, ROLE_MAPPER, userId, orgId);
    }
}
