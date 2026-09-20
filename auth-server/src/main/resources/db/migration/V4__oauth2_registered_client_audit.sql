-- 客户端管理审计：记录平台管理员对 oauth2_registered_client 的每次变更。
-- 只记录字段名，不记录任何 secret；不设外键，客户端删除后审计仍然保留。

CREATE TABLE oauth2_registered_client_audit (
    id bigint NOT NULL AUTO_INCREMENT,
    registered_client_id varchar(100) NOT NULL,
    client_id varchar(100) NOT NULL,
    action varchar(32) NOT NULL,
    actor_user_id bigint NOT NULL,
    actor_account varchar(64) NOT NULL,
    changed_fields varchar(500) DEFAULT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (id),
    KEY idx_registered_client_audit_client_id (client_id),
    KEY idx_registered_client_audit_created_at (created_at)
);
