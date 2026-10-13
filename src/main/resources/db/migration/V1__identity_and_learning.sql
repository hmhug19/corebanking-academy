create table users (
 id bigserial primary key, username varchar(100) not null unique,
 password_hash varchar(255) not null, full_name varchar(255) not null,
 status varchar(20) not null default 'ACTIVE' check(status in ('ACTIVE','LOCKED','DISABLED')),
 authorization_version bigint not null default 0
);
create table organizations (
 id bigserial primary key, parent_id bigint references organizations(id),
 code varchar(100) not null unique, name varchar(255) not null,
 type varchar(30) not null check(type in ('UNIT','DEPARTMENT','GROUP'))
);
create table user_organizations (
 user_id bigint not null references users(id), organization_id bigint not null references organizations(id),
 primary key(user_id,organization_id)
);
create table roles (id bigserial primary key, code varchar(100) not null unique, name varchar(255) not null,active boolean not null default true);
create table permissions (id bigserial primary key,code varchar(100) not null unique,resource varchar(100) not null, action varchar(100) not null);
create table role_permissions (role_id bigint not null references roles(id),permission_id bigint not null references permissions(id),primary key(role_id,permission_id));
create table user_role_assignments (
 id bigserial primary key,user_id bigint not null references users(id),role_id bigint not null references roles(id),
 scope_type varchar(20) not null check(scope_type in ('GLOBAL','UNIT','OWN','ASSIGNED')),
 scope_id bigint references organizations(id),active boolean not null default true,
 constraint scope_consistency check (
  (scope_type='UNIT' and scope_id is not null) or
  (scope_type in ('GLOBAL','OWN','ASSIGNED') and scope_id is null)
 )
);
create unique index uq_role_assignment on user_role_assignments(user_id,role_id,scope_type,coalesce(scope_id,-1));
create index idx_role_assignment_user on user_role_assignments(user_id) where active;
create index idx_organizations_parent on organizations(parent_id);
create table courses (
 id bigserial primary key,title varchar(255) not null,
 organization_id bigint not null references organizations(id),
 owner_id bigint not null references users(id),
 status varchar(30) not null default 'DRAFT' check(status in ('DRAFT','PUBLISHED','ARCHIVED'))
);
create index idx_courses_org on courses(organization_id);
create table audit_events (
 id bigserial primary key,actor_user_id bigint references users(id),entity_type varchar(100) not null,
 entity_id varchar(100),action varchar(100) not null,details text,
 created_at timestamptz not null default now()
);
