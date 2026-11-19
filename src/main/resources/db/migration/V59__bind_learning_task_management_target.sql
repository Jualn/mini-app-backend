-- Existing assignments remain usable; historical ownership is deliberately not guessed.
ALTER TABLE learning_task ADD COLUMN management_target JSON NULL;
ALTER TABLE learning_task ADD CONSTRAINT chk_learning_management_target CHECK (
 management_target IS NULL OR COALESCE((
 JSON_TYPE(management_target)='OBJECT' AND (
 (JSON_UNQUOTE(JSON_EXTRACT(management_target,'$.type'))='PLATFORM' AND JSON_LENGTH(management_target)=1) OR
 (JSON_UNQUOTE(JSON_EXTRACT(management_target,'$.type'))='ORGANIZATION' AND JSON_LENGTH(management_target)=2
 AND JSON_TYPE(JSON_EXTRACT(management_target,'$.organizationId'))='STRING'
 AND LENGTH(JSON_UNQUOTE(JSON_EXTRACT(management_target,'$.organizationId')))>0))),FALSE));
