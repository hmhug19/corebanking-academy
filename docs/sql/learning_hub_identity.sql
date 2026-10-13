-- PostgreSQL 14+ | Khoi tao schema identity Learning Hub, doc lap voi Flyway.
-- Chay trong DATABASE dich da tao san bang psql, pgAdmin hoac DBeaver.
-- Khong tao database, khong xoa/thay the bang da ton tai.
-- Chay mot lan. Neu bang da ton tai, transaction se loi va rollback.
-- USER_ID la ma tu he thong xac thuc; khong luu mat khau/token he thong nguon.
-- BRN/BRCD/CRA la snapshot nguon; orgs.parent_id moi quyet dinh cay to chuc.
-- Khong mac dinh BRN -> BRCD -> CRA; quan he cha-con duoc nap sau.
-- Timestamp duoc luu co mui gio; hien thi theo timezone cua session.

BEGIN;

CREATE SCHEMA IF NOT EXISTS learning_hub;
COMMENT ON SCHEMA learning_hub IS 'Du lieu nguoi dung, to chuc, nhom, chuc danh va phan quyen Learning Hub.';

CREATE TABLE learning_hub.users (
    user_id varchar(20) PRIMARY KEY,
    user_name varchar(100) NOT NULL,
    email varchar(100),
    full_name varchar(255),
    brn varchar(20),
    brcd varchar(20),
    cra varchar(20),
    brn_name varchar(255),
    brcd_name varchar(255),
    cra_name varchar(255),
    dept_code varchar(50),
    dept_name varchar(255),
    bds_code varchar(20),
    bds_name varchar(255),
    qlns_brn_code varchar(20),
    status varchar(10) NOT NULL DEFAULT 'active',
    auth_ver bigint NOT NULL DEFAULT 0,
    last_sync_time timestamptz,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_users_id CHECK (user_id = btrim(user_id) AND user_id <> ''),
    CONSTRAINT uq_users_name UNIQUE (user_name),
    CONSTRAINT uq_users_id_name UNIQUE (user_id, user_name),
    CONSTRAINT ck_users_name CHECK (user_name = lower(btrim(user_name)) AND user_name <> ''),
    CONSTRAINT ck_users_status CHECK (status IN ('active', 'locked', 'disabled')),
    CONSTRAINT ck_users_auth_ver CHECK (auth_ver >= 0)
);
COMMENT ON TABLE learning_hub.users IS 'Nguoi dung; USER_ID la ma can bo/userInfo.userID cua he thong xac thuc.';
COMMENT ON COLUMN learning_hub.users.user_id IS 'Ma can bo tu he thong nguon, vi du 183617; kieu chuoi de giu so 0 dau. Co the dung de dang nhap.';
COMMENT ON COLUMN learning_hub.users.user_name IS 'Ten dang nhap duy nhat, vi du hunghm8; luu chu thuong, khong co khoang trang dau/cuoi. UNIQUE tu tao index tra cuu.';
COMMENT ON COLUMN learning_hub.users.email IS 'Email; chua rang buoc unique vi USER_ID moi la dinh danh.';
COMMENT ON COLUMN learning_hub.users.full_name IS 'Ho ten nguoi dung.';
COMMENT ON COLUMN learning_hub.users.brn IS 'Ma BRN tu userInfo.brnCode; snapshot nguon, khong phai khoa ngoai.';
COMMENT ON COLUMN learning_hub.users.brcd IS 'Ma BRCD tu userInfo.brcd; snapshot nguon.';
COMMENT ON COLUMN learning_hub.users.cra IS 'Ma CRA tu userInfo.cra; co the lap giua cac to chuc.';
COMMENT ON COLUMN learning_hub.users.brn_name IS 'Ten BRN tu userInfo.brnName.';
COMMENT ON COLUMN learning_hub.users.brcd_name IS 'Ten BRCD tu userInfo.brcdName.';
COMMENT ON COLUMN learning_hub.users.cra_name IS 'Ten CRA tu userInfo.craName.';
COMMENT ON COLUMN learning_hub.users.dept_code IS 'Ma phong nguon tu deptCode; chua mac dinh dong nhat voi CRA.';
COMMENT ON COLUMN learning_hub.users.dept_name IS 'Ten phong nguon tu deptName.';
COMMENT ON COLUMN learning_hub.users.bds_code IS 'Ma BDS nguon tu bdsCode.';
COMMENT ON COLUMN learning_hub.users.bds_name IS 'Ten BDS nguon tu bdsName.';
COMMENT ON COLUMN learning_hub.users.qlns_brn_code IS 'Ma chi nhanh nhan su tu qlnsbrnCode.';
COMMENT ON COLUMN learning_hub.users.status IS 'Trang thai cuc bo: active, locked, disabled; dong bo nguon khong duoc tu mo khoa.';
COMMENT ON COLUMN learning_hub.users.auth_ver IS 'Phien ban xac thuc de ung dung thu hoi token; ung dung tang khi can.';
COMMENT ON COLUMN learning_hub.users.last_sync_time IS 'Lan dong bo thong tin tu he thong nguon gan nhat.';
COMMENT ON COLUMN learning_hub.users.create_time IS 'Thoi diem tao ban ghi.';
COMMENT ON COLUMN learning_hub.users.update_time IS 'Thoi diem cap nhat; trigger tu dong gan.';
CREATE INDEX ix_users_email ON learning_hub.users (lower(email)) WHERE email IS NOT NULL;
CREATE INDEX ix_users_org_src ON learning_hub.users (brn, brcd, cra);
CREATE INDEX ix_users_dept ON learning_hub.users (dept_code) WHERE dept_code IS NOT NULL;

CREATE TABLE learning_hub.user_credentials (
    user_id varchar(20) PRIMARY KEY,
    user_name varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    password_changed_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_credentials_name UNIQUE (user_name),
    CONSTRAINT fk_user_credentials_user FOREIGN KEY (user_id, user_name)
        REFERENCES learning_hub.users(user_id, user_name) ON UPDATE CASCADE ON DELETE RESTRICT,
    CONSTRAINT ck_user_credentials_hash CHECK (btrim(password_hash) <> '')
);
COMMENT ON TABLE learning_hub.user_credentials IS 'Thong tin dang nhap cuc bo Learning Hub; moi user toi da mot ban ghi. User chi xac thuc qua he thong nguon khong can ban ghi nay.';
COMMENT ON COLUMN learning_hub.user_credentials.user_id IS 'Ma user dung cho dang nhap cuc bo; khoa ngoai toi users. Khoa chinh da tao index tra cuu.';
COMMENT ON COLUMN learning_hub.user_credentials.user_name IS 'Username phai khop voi user_id trong users; doi username tai users se tu dong cap nhat qua khoa ngoai. UNIQUE tu tao index tra cuu.';
COMMENT ON COLUMN learning_hub.user_credentials.password_hash IS 'Hash mat khau do backend tao bang BCrypt/PasswordEncoder; khong luu mat khau goc hoac mat khau he thong nguon, khong tra qua API ho so/audit.';
COMMENT ON COLUMN learning_hub.user_credentials.password_changed_time IS 'Thoi diem dat/doi mat khau; backend cap nhat khi password_hash thay doi.';
COMMENT ON COLUMN learning_hub.user_credentials.create_time IS 'Thoi diem tao thong tin dang nhap cuc bo.';
COMMENT ON COLUMN learning_hub.user_credentials.update_time IS 'Thoi diem cap nhat tu dong.';

CREATE TABLE learning_hub.orgs (
    org_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id bigint REFERENCES learning_hub.orgs(org_id) ON DELETE RESTRICT,
    org_type varchar(20) NOT NULL,
    code varchar(50) NOT NULL,
    name varchar(255) NOT NULL,
    status varchar(10) NOT NULL DEFAULT 'active',
    sort_no integer NOT NULL DEFAULT 0,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_orgs_type CHECK (org_type IN ('UNIT', 'DEPT', 'BRN', 'BRCD', 'CRA')),
    CONSTRAINT ck_orgs_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT ck_orgs_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_orgs_status CHECK (status IN ('active', 'disabled')),
    CONSTRAINT ck_orgs_parent CHECK (parent_id IS NULL OR parent_id <> org_id)
);
COMMENT ON TABLE learning_hub.orgs IS 'Cay don vi/phong; dinh danh bang org_id, ma chi unique theo loai va cha.';
COMMENT ON COLUMN learning_hub.orgs.org_id IS 'Khoa noi bo, khong phai ma BRN/BRCD/CRA.';
COMMENT ON COLUMN learning_hub.orgs.parent_id IS 'To chuc cha; NULL la goc. Ung dung phai ngan chu trinh nhieu cap.';
COMMENT ON COLUMN learning_hub.orgs.org_type IS 'Loai to chuc: UNIT, DEPT hoac loai nguon BRN, BRCD, CRA.';
COMMENT ON COLUMN learning_hub.orgs.code IS 'Ma to chuc; cho phep cung ma tai cac cha khac nhau.';
COMMENT ON COLUMN learning_hub.orgs.name IS 'Ten to chuc.';
COMMENT ON COLUMN learning_hub.orgs.status IS 'active/disabled; khong xoa vat ly khi da co tham chieu.';
COMMENT ON COLUMN learning_hub.orgs.sort_no IS 'Thu tu hien thi trong cung cap.';
COMMENT ON COLUMN learning_hub.orgs.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.orgs.update_time IS 'Thoi diem cap nhat tu dong.';
CREATE UNIQUE INDEX uq_orgs_root ON learning_hub.orgs (org_type, code) WHERE parent_id IS NULL;
CREATE UNIQUE INDEX uq_orgs_child ON learning_hub.orgs (parent_id, org_type, code) WHERE parent_id IS NOT NULL;
-- uq_orgs_child da ho tro truy van theo parent_id; khong tao index trung lap.

CREATE TABLE learning_hub.user_orgs (
    user_id varchar(20) NOT NULL REFERENCES learning_hub.users(user_id) ON DELETE RESTRICT,
    org_id bigint NOT NULL REFERENCES learning_hub.orgs(org_id) ON DELETE RESTRICT,
    is_primary boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, org_id)
);
COMMENT ON TABLE learning_hub.user_orgs IS 'Quan he user-to chuc; khong tu dong cap quyen ung dung.';
COMMENT ON COLUMN learning_hub.user_orgs.user_id IS 'Nguoi dung thanh vien.';
COMMENT ON COLUMN learning_hub.user_orgs.org_id IS 'To chuc thanh vien; co the luu nhieu quan he cho mot user.';
COMMENT ON COLUMN learning_hub.user_orgs.is_primary IS 'Toi da mot to chuc chinh dang active cho moi user.';
COMMENT ON COLUMN learning_hub.user_orgs.active IS 'Quan he con hieu luc.';
COMMENT ON COLUMN learning_hub.user_orgs.create_time IS 'Thoi diem tao quan he.';
COMMENT ON COLUMN learning_hub.user_orgs.update_time IS 'Thoi diem cap nhat tu dong.';
CREATE UNIQUE INDEX uq_user_orgs_primary ON learning_hub.user_orgs (user_id) WHERE active AND is_primary;
CREATE INDEX ix_user_orgs_org ON learning_hub.user_orgs (org_id, user_id);

CREATE TABLE learning_hub.teams (
    team_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    org_id bigint NOT NULL REFERENCES learning_hub.orgs(org_id) ON DELETE RESTRICT,
    code varchar(30) NOT NULL,
    name varchar(255) NOT NULL,
    description text,
    status varchar(10) NOT NULL DEFAULT 'active',
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_teams_code UNIQUE (org_id, code),
    CONSTRAINT uq_teams_org UNIQUE (team_id, org_id),
    CONSTRAINT ck_teams_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT ck_teams_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_teams_status CHECK (status IN ('active', 'disabled'))
);
COMMENT ON TABLE learning_hub.teams IS 'Nhom cong tac trong to chuc: DEP, LOAN, CHANNEL, CORE, INTEGRATION...';
COMMENT ON COLUMN learning_hub.teams.team_id IS 'Khoa noi bo cua nhom.';
COMMENT ON COLUMN learning_hub.teams.org_id IS 'To chuc quan ly nhom.';
COMMENT ON COLUMN learning_hub.teams.code IS 'Ma nhom; unique trong to chuc.';
COMMENT ON COLUMN learning_hub.teams.name IS 'Ten nhom.';
COMMENT ON COLUMN learning_hub.teams.description IS 'Mo ta nhom cong tac.';
COMMENT ON COLUMN learning_hub.teams.status IS 'active/disabled.';
COMMENT ON COLUMN learning_hub.teams.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.teams.update_time IS 'Thoi diem cap nhat tu dong.';

CREATE TABLE learning_hub.user_teams (
    user_id varchar(20) NOT NULL REFERENCES learning_hub.users(user_id) ON DELETE RESTRICT,
    team_id bigint NOT NULL REFERENCES learning_hub.teams(team_id) ON DELETE RESTRICT,
    is_primary boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, team_id)
);
COMMENT ON TABLE learning_hub.user_teams IS 'Thanh vien nhom; mot user co the tham gia nhieu nhom, khong tu cap role.';
COMMENT ON COLUMN learning_hub.user_teams.user_id IS 'Nguoi dung thanh vien.';
COMMENT ON COLUMN learning_hub.user_teams.team_id IS 'Nhom cong tac.';
COMMENT ON COLUMN learning_hub.user_teams.is_primary IS 'Toi da mot nhom chinh dang active cho moi user.';
COMMENT ON COLUMN learning_hub.user_teams.active IS 'Quan he thanh vien con hieu luc.';
COMMENT ON COLUMN learning_hub.user_teams.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.user_teams.update_time IS 'Thoi diem cap nhat tu dong.';
CREATE UNIQUE INDEX uq_user_teams_primary ON learning_hub.user_teams (user_id) WHERE active AND is_primary;
CREATE INDEX ix_user_teams_team ON learning_hub.user_teams (team_id, user_id);

CREATE TABLE learning_hub.positions (
    pos_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(30) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_positions_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT ck_positions_name CHECK (btrim(name) <> '')
);
COMMENT ON TABLE learning_hub.positions IS 'Danh muc chuc danh cong tac; tach biet voi vai tro phan quyen.';
COMMENT ON COLUMN learning_hub.positions.pos_id IS 'Khoa noi bo chuc danh.';
COMMENT ON COLUMN learning_hub.positions.code IS 'Ma chuc danh.';
COMMENT ON COLUMN learning_hub.positions.name IS 'Ten chuc danh.';
COMMENT ON COLUMN learning_hub.positions.active IS 'Chuc danh duoc su dung.';
COMMENT ON COLUMN learning_hub.positions.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.positions.update_time IS 'Thoi diem cap nhat tu dong.';

CREATE TABLE learning_hub.user_pos (
    up_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id varchar(20) NOT NULL REFERENCES learning_hub.users(user_id) ON DELETE RESTRICT,
    pos_id bigint NOT NULL REFERENCES learning_hub.positions(pos_id) ON DELETE RESTRICT,
    org_id bigint NOT NULL REFERENCES learning_hub.orgs(org_id) ON DELETE RESTRICT,
    team_id bigint,
    active boolean NOT NULL DEFAULT true,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_pos_team FOREIGN KEY (team_id, org_id)
        REFERENCES learning_hub.teams(team_id, org_id) ON DELETE RESTRICT
);
COMMENT ON TABLE learning_hub.user_pos IS 'Chuc danh cua user tai to chuc hoac nhom; ho tro kiem nhiem.';
COMMENT ON COLUMN learning_hub.user_pos.up_id IS 'Khoa ban ghi gan chuc danh.';
COMMENT ON COLUMN learning_hub.user_pos.user_id IS 'Nguoi giu chuc danh.';
COMMENT ON COLUMN learning_hub.user_pos.pos_id IS 'Chuc danh duoc gan.';
COMMENT ON COLUMN learning_hub.user_pos.org_id IS 'To chuc cua chuc danh.';
COMMENT ON COLUMN learning_hub.user_pos.team_id IS 'NULL: chuc danh tai to chuc; co gia tri: nhom thuoc dung org_id.';
COMMENT ON COLUMN learning_hub.user_pos.active IS 'Gan chuc danh con hieu luc; kich hoat lai ban ghi cu thay vi tao trung.';
COMMENT ON COLUMN learning_hub.user_pos.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.user_pos.update_time IS 'Thoi diem cap nhat tu dong.';
CREATE UNIQUE INDEX uq_user_pos_org ON learning_hub.user_pos (user_id, pos_id, org_id) WHERE team_id IS NULL;
CREATE UNIQUE INDEX uq_user_pos_team ON learning_hub.user_pos (user_id, pos_id, team_id) WHERE team_id IS NOT NULL;
CREATE INDEX ix_user_pos_org ON learning_hub.user_pos (org_id, user_id);
CREATE INDEX ix_user_pos_team ON learning_hub.user_pos (team_id, org_id) WHERE team_id IS NOT NULL;
CREATE INDEX ix_user_pos_pos ON learning_hub.user_pos (pos_id);

CREATE TABLE learning_hub.roles (
    role_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(50) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    description text,
    active boolean NOT NULL DEFAULT true,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_roles_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT ck_roles_name CHECK (btrim(name) <> '')
);
COMMENT ON TABLE learning_hub.roles IS 'Vai tro Learning Hub; khong dong nhat voi roleInfo cua he thong nguon.';
COMMENT ON COLUMN learning_hub.roles.role_id IS 'Khoa vai tro.';
COMMENT ON COLUMN learning_hub.roles.code IS 'Ma vai tro Learning Hub.';
COMMENT ON COLUMN learning_hub.roles.name IS 'Ten vai tro.';
COMMENT ON COLUMN learning_hub.roles.description IS 'Mo ta vai tro.';
COMMENT ON COLUMN learning_hub.roles.active IS 'Vai tro con hieu luc.';
COMMENT ON COLUMN learning_hub.roles.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.roles.update_time IS 'Thoi diem cap nhat tu dong.';

CREATE TABLE learning_hub.permission (
    permission_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(100) NOT NULL UNIQUE,
    name varchar(255) NOT NULL,
    resource varchar(50) NOT NULL,
    action varchar(50) NOT NULL,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_permission_action UNIQUE (resource, action),
    CONSTRAINT ck_permission_code CHECK (code = btrim(code) AND code <> ''),
    CONSTRAINT ck_permission_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_permission_resource CHECK (resource = btrim(resource) AND resource <> ''),
    CONSTRAINT ck_permission_action CHECK (action = btrim(action) AND action <> '')
);
COMMENT ON TABLE learning_hub.permission IS 'Quyen chuc nang; API phai thuc thi cung pham vi cua role assignment.';
COMMENT ON COLUMN learning_hub.permission.permission_id IS 'Khoa quyen.';
COMMENT ON COLUMN learning_hub.permission.code IS 'Ma quyen resource.action.';
COMMENT ON COLUMN learning_hub.permission.name IS 'Ten quyen hien thi.';
COMMENT ON COLUMN learning_hub.permission.resource IS 'Doi tuong chuc nang.';
COMMENT ON COLUMN learning_hub.permission.action IS 'Hanh dong duoc phep.';
COMMENT ON COLUMN learning_hub.permission.create_time IS 'Thoi diem tao.';
COMMENT ON COLUMN learning_hub.permission.update_time IS 'Thoi diem cap nhat tu dong.';

CREATE TABLE learning_hub.role_permission (
    role_id bigint NOT NULL REFERENCES learning_hub.roles(role_id) ON DELETE RESTRICT,
    permission_id bigint NOT NULL REFERENCES learning_hub.permission(permission_id) ON DELETE RESTRICT,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE learning_hub.role_permission IS 'Tap quyen chuc nang cua tung vai tro.';
COMMENT ON COLUMN learning_hub.role_permission.role_id IS 'Vai tro duoc cap quyen.';
COMMENT ON COLUMN learning_hub.role_permission.permission_id IS 'Quyen chuc nang.';
COMMENT ON COLUMN learning_hub.role_permission.create_time IS 'Thoi diem cap quyen cho vai tro.';
CREATE INDEX ix_role_permission_permission ON learning_hub.role_permission (permission_id, role_id);

CREATE TABLE learning_hub.user_roles (
    ur_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id varchar(20) NOT NULL REFERENCES learning_hub.users(user_id) ON DELETE RESTRICT,
    role_id bigint NOT NULL REFERENCES learning_hub.roles(role_id) ON DELETE RESTRICT,
    scope varchar(10) NOT NULL,
    org_id bigint REFERENCES learning_hub.orgs(org_id) ON DELETE RESTRICT,
    team_id bigint,
    active boolean NOT NULL DEFAULT true,
    granted_by varchar(20) REFERENCES learning_hub.users(user_id) ON DELETE RESTRICT,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_roles_scope CHECK (
        (scope IN ('GLOBAL', 'OWN') AND org_id IS NULL AND team_id IS NULL) OR
        (scope = 'ORG' AND org_id IS NOT NULL AND team_id IS NULL) OR
        (scope = 'TEAM' AND org_id IS NOT NULL AND team_id IS NOT NULL)
    ),
    CONSTRAINT fk_user_roles_team FOREIGN KEY (team_id, org_id)
        REFERENCES learning_hub.teams(team_id, org_id) ON DELETE RESTRICT
);
COMMENT ON TABLE learning_hub.user_roles IS 'Gan role va pham vi tren cung ban ghi; khong ghep quyen va scope tu hai assignment.';
COMMENT ON COLUMN learning_hub.user_roles.ur_id IS 'Khoa assignment.';
COMMENT ON COLUMN learning_hub.user_roles.user_id IS 'Nguoi duoc gan vai tro.';
COMMENT ON COLUMN learning_hub.user_roles.role_id IS 'Vai tro Learning Hub.';
COMMENT ON COLUMN learning_hub.user_roles.scope IS 'GLOBAL: toan he thong; ORG: to chuc; TEAM: nhom; OWN: du lieu ban than. API phai thuc thi.';
COMMENT ON COLUMN learning_hub.user_roles.org_id IS 'To chuc cua scope ORG/TEAM; viec bao gom to chuc con do chinh sach API quyet dinh.';
COMMENT ON COLUMN learning_hub.user_roles.team_id IS 'Nhom cua scope TEAM; rang buoc thuoc dung org_id.';
COMMENT ON COLUMN learning_hub.user_roles.active IS 'Assignment con hieu luc; kich hoat lai ban ghi cu thay vi tao trung.';
COMMENT ON COLUMN learning_hub.user_roles.granted_by IS 'User cap role; NULL khi khoi tao/dong bo he thong.';
COMMENT ON COLUMN learning_hub.user_roles.create_time IS 'Thoi diem tao assignment.';
COMMENT ON COLUMN learning_hub.user_roles.update_time IS 'Thoi diem cap nhat tu dong.';
CREATE UNIQUE INDEX uq_user_roles_global ON learning_hub.user_roles (user_id, role_id, scope) WHERE scope IN ('GLOBAL', 'OWN');
CREATE UNIQUE INDEX uq_user_roles_org ON learning_hub.user_roles (user_id, role_id, org_id) WHERE scope = 'ORG';
CREATE UNIQUE INDEX uq_user_roles_team ON learning_hub.user_roles (user_id, role_id, team_id) WHERE scope = 'TEAM';
CREATE INDEX ix_user_roles_active ON learning_hub.user_roles (user_id, role_id) WHERE active;
CREATE INDEX ix_user_roles_role ON learning_hub.user_roles (role_id);
CREATE INDEX ix_user_roles_org ON learning_hub.user_roles (org_id) WHERE org_id IS NOT NULL;
CREATE INDEX ix_user_roles_team ON learning_hub.user_roles (team_id, org_id) WHERE team_id IS NOT NULL;
CREATE INDEX ix_user_roles_grantor ON learning_hub.user_roles (granted_by) WHERE granted_by IS NOT NULL;

CREATE TABLE learning_hub.audit_log (
    audit_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id varchar(20) REFERENCES learning_hub.users(user_id) ON DELETE RESTRICT,
    action varchar(100) NOT NULL,
    entity_type varchar(50) NOT NULL,
    entity_id varchar(100),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    create_time timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE learning_hub.audit_log IS 'Nhat ky do ung dung ghi trong cung transaction voi thay doi; khong luu password/token/secret.';
COMMENT ON COLUMN learning_hub.audit_log.audit_id IS 'Khoa su kien audit.';
COMMENT ON COLUMN learning_hub.audit_log.actor_id IS 'Nguoi thao tac; NULL cho tac vu he thong.';
COMMENT ON COLUMN learning_hub.audit_log.action IS 'Hanh dong, vi du USER_LOCK, ROLE_GRANT, TEAM_ADD_MEMBER.';
COMMENT ON COLUMN learning_hub.audit_log.entity_type IS 'Loai doi tuong bi tac dong.';
COMMENT ON COLUMN learning_hub.audit_log.entity_id IS 'Dinh danh doi tuong; chuoi de dung cho user va ID so.';
COMMENT ON COLUMN learning_hub.audit_log.details IS 'Chi tiet da loc thong tin bi mat; co the luu truoc/sau thay doi.';
COMMENT ON COLUMN learning_hub.audit_log.create_time IS 'Thoi diem phat sinh.';
CREATE INDEX ix_audit_actor_time ON learning_hub.audit_log (actor_id, create_time DESC);
CREATE INDEX ix_audit_entity_time ON learning_hub.audit_log (entity_type, entity_id, create_time DESC);
CREATE INDEX ix_audit_time ON learning_hub.audit_log (create_time DESC);

CREATE FUNCTION learning_hub.touch_update_time()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.update_time := CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;
COMMENT ON FUNCTION learning_hub.touch_update_time() IS 'Gan update_time tu dong cho moi UPDATE.';

DO $$
DECLARE
    table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'users', 'user_credentials', 'orgs', 'user_orgs', 'teams', 'user_teams',
        'positions', 'user_pos', 'roles', 'permission', 'user_roles'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_touch_time BEFORE UPDATE ON learning_hub.%I '
            'FOR EACH ROW EXECUTE FUNCTION learning_hub.touch_update_time()', table_name
        );
    END LOOP;
END;
$$;

-- Danh muc ban dau. Khong nap user that hay tu gan quyen cho user.
INSERT INTO learning_hub.positions (code, name) VALUES
    ('TECH_SPECIALIST', 'Chuyên viên kỹ thuật'),
    ('BIZ_SPECIALIST', 'Chuyên viên nghiệp vụ'),
    ('TEAM_DEPUTY', 'Phó nhóm'),
    ('TEAM_LEAD', 'Trưởng nhóm'),
    ('EXPERT', 'Chuyên gia'),
    ('DEPT_DEPUTY', 'Phó phòng'),
    ('DEPT_HEAD', 'Trưởng phòng'),
    ('DIRECTOR', 'Giám đốc'),
    ('DEPUTY_DIRECTOR', 'Phó giám đốc');

INSERT INTO learning_hub.roles (code, name, description) VALUES
    ('LEARNER', 'Học viên', 'Vai tro hoc tap; quyen khoa hoc bo sung khi trien khai learning.'),
    ('INSTRUCTOR', 'Giảng viên', 'Giang vien/can bo quan ly noi dung.'),
    ('ADMIN', 'Quản trị viên', 'Quan tri chuc nang trong pham vi duoc gan.'),
    ('REPORT_VIEWER', 'Người xem báo cáo', 'Chi xem bao cao trong pham vi duoc gan.');

INSERT INTO learning_hub.permission (code, name, resource, action) VALUES
    ('profile.view', 'Xem hồ sơ bản thân', 'profile', 'view'),
    ('user.view', 'Xem người dùng', 'user', 'view'),
    ('user.create', 'Tạo người dùng', 'user', 'create'),
    ('user.update', 'Sửa người dùng', 'user', 'update'),
    ('user.status', 'Đổi trạng thái tài khoản', 'user', 'status'),
    ('user.org', 'Gán tổ chức cho người dùng', 'user', 'org'),
    ('user.team', 'Gán nhóm cho người dùng', 'user', 'team'),
    ('user.position', 'Gán chức danh cho người dùng', 'user', 'position'),
    ('org.view', 'Xem tổ chức', 'org', 'view'),
    ('org.create', 'Tạo tổ chức', 'org', 'create'),
    ('org.update', 'Sửa tổ chức', 'org', 'update'),
    ('team.view', 'Xem nhóm', 'team', 'view'),
    ('team.create', 'Tạo nhóm', 'team', 'create'),
    ('team.update', 'Sửa nhóm', 'team', 'update'),
    ('position.view', 'Xem danh mục chức danh', 'position', 'view'),
    ('position.manage', 'Quản lý danh mục chức danh', 'position', 'manage'),
    ('role.view', 'Xem vai trò', 'role', 'view'),
    ('role.assign', 'Gán hoặc thu hồi vai trò', 'role', 'assign'),
    ('role.manage', 'Quản lý vai trò và tập quyền', 'role', 'manage'),
    ('audit.view', 'Xem nhật ký quản trị', 'audit', 'view'),
    ('report.view', 'Xem báo cáo', 'report', 'view');

INSERT INTO learning_hub.role_permission (role_id, permission_id)
SELECT r.role_id, p.permission_id
FROM learning_hub.roles r CROSS JOIN learning_hub.permission p
WHERE r.code = 'ADMIN'
   OR p.code = 'profile.view'
   OR (r.code = 'REPORT_VIEWER' AND p.code = 'report.view');

-- Cac quyen sua danh muc role/chuc danh la danh muc dung chung:
-- API chi chap nhan assignment GLOBAL cho role.manage/position.manage.
-- Admin ORG/TEAM khong duoc tu cap GLOBAL, vuot scope, hay sua role de nang quyen.
-- DB rang buoc du lieu; script nay KHONG thay the authorization cua API/RLS.
-- Chua tu dong tang auth_ver hay ghi audit; backend se lam trong transaction.

COMMIT;
