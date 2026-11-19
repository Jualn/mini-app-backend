-- Additive immutable preview classification. Do not invent historical membership for old previews.
ALTER TABLE learning_task_preview
 ADD COLUMN new_assignment_count INT UNSIGNED NULL,
 ADD COLUMN already_assigned_count INT UNSIGNED NULL,
 ADD COLUMN already_assigned_user_ids JSON NULL,
 ADD CONSTRAINT ck_learning_preview_classification CHECK (
   (new_assignment_count IS NULL AND already_assigned_count IS NULL AND already_assigned_user_ids IS NULL)
   OR (new_assignment_count IS NOT NULL AND already_assigned_count IS NOT NULL
       AND already_assigned_user_ids IS NOT NULL AND JSON_TYPE(already_assigned_user_ids)='ARRAY'
       AND already_assigned_count=JSON_LENGTH(already_assigned_user_ids)
       AND new_assignment_count+already_assigned_count=JSON_LENGTH(user_ids)));
