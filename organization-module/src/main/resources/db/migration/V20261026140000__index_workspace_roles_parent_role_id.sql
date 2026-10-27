-- workspace_roles.parent_role_id is a self-referencing foreign key (ADR-0027 section 3) and the one
-- foreign key a scan of every migration found with no index leading with its column (TD-PERF-047).
-- Deleting a role makes Postgres look for roles that still name it as their parent
-- (`WHERE parent_role_id = $1 FOR KEY SHARE`), which without an index is a scan of the table.
-- Measured on 200,000 rows: 61 ms a delete, 0.14 ms with this index.
--
-- Partial, because most roles have no parent: the index holds only the rows that do. A lookup by
-- `parent_role_id = $1` implies the predicate, so the planner uses it for the foreign-key check.
-- Plain CREATE INDEX, as in the sibling index migrations: Flyway is transactional here and the table
-- holds a handful of roles per Organization.
CREATE INDEX ix_workspace_roles_parent_role_id
    ON workspace_roles (parent_role_id)
    WHERE parent_role_id IS NOT NULL;
