-- Fixtures for the read-only business identity tables. These tables are owned by
-- another system in production; tests create them so auth-server never migrates them.
-- OAuth state is reset too so every test starts without a stored authorization
-- or consent (both tables exist once Flyway has run).
DELETE FROM oauth2_authorization;
DELETE FROM oauth2_authorization_consent;

DROP TABLE IF EXISTS sys_user_org_role;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_org;
DROP TABLE IF EXISTS sys_user;

CREATE TABLE sys_user (
    id BIGINT NOT NULL,
    account VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    password VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_account (account)
);

CREATE TABLE sys_org (
    id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE sys_role (
    id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE sys_user_org_role (
    id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_sys_user_org_role_user_org (user_id, org_id)
);

-- alice-password / bob-password / test@123.., hashed with BCrypt.
INSERT INTO sys_user (id, account, name, password) VALUES
    (1, 'alice', 'Alice', '$2a$10$GjbGmfS63PiP9AquNwCWguef/LOJ0l/fxCbpB9SDXM.XO40cPaCxK'),
    (2, 'bob', 'Bob', '$2a$10$uTTumqOYb5ejKzGcYDpskOwlVfj1KZ1.8GsbqhgzNSjELSr3yD3Mm'),
    (3, 'superadmin', 'Super Admin', '$2a$10$Zpa.bf/QdB69l2QNalyuMOYCqFOsX0MB/oUha.HAyjuVSDRTo3jQq');

INSERT INTO sys_org (id, name) VALUES
    (10, 'Alpha'),
    (20, 'Beta');

INSERT INTO sys_role (id, name) VALUES
    (100, 'ADMIN'),
    (101, 'VIEWER'),
    (102, 'AUDITOR');

INSERT INTO sys_user_org_role (id, user_id, org_id, role_id) VALUES
    (1000, 1, 10, 100),
    (1001, 1, 10, 101),
    (1002, 1, 20, 102),
    (1003, 2, 20, 101);
