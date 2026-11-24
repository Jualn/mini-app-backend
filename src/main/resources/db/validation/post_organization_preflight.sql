-- Read-only. Run only on an explicitly authorized target before V40.
SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank;
SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()
 AND table_name IN ('post_category','post_category_type','topic','post_topic','post_v40_audit_preflight');
SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='post'
 AND column_name IN ('post_type','category_id','revision','audit_revision');
SELECT COUNT(*) AS occupied_candidate_scene FROM content_audit_log WHERE target_type=10;
SELECT status,audit_status,COUNT(*) FROM post GROUP BY status,audit_status;
SELECT COUNT(*) AS legacy_posts FROM post;
SELECT COUNT(*) AS legacy_post_comments FROM comment WHERE target_type=1;
