-- READ ONLY. Run against the explicitly approved V35 deployment target before V36.
-- Result counts must all be zero. AuditScene and TargetType are separate namespaces.
SELECT 'comment target 8' AS boundary,COUNT(*) AS conflicts FROM comment WHERE target_type=8
UNION ALL SELECT 'like target 8',COUNT(*) FROM like_record WHERE target_type=8
UNION ALL SELECT 'share target 8',COUNT(*) FROM share_record WHERE target_type=8
UNION ALL SELECT 'view target 8',COUNT(*) FROM view_log WHERE target_type=8
UNION ALL SELECT 'attachment target 8',COUNT(*) FROM media_attachment WHERE target_type=8
UNION ALL SELECT 'upload target 8',COUNT(*) FROM media_upload_record WHERE target_type=8
UNION ALL SELECT 'attachment link target 8',COUNT(*) FROM event_attachment_link WHERE target_type=8
UNION ALL SELECT 'notification target 8',COUNT(*) FROM notification WHERE target_type=8
UNION ALL SELECT 'search target 8',COUNT(*) FROM search_doc WHERE target_type=8
UNION ALL SELECT 'report target 8',COUNT(*) FROM report WHERE target_type=8
UNION ALL SELECT 'timeline target 8',COUNT(*) FROM timeline WHERE target_type=8
UNION ALL SELECT 'audit scene 9',COUNT(*) FROM content_audit_log WHERE target_type=9;
