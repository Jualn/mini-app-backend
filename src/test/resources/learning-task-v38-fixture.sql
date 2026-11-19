-- Synthetic V38 history ONLY in this task's disposable learning_task_upgrade schema.
INSERT INTO user_profile(id,openid) VALUES(42,'lt-upgrade-42'),(43,'lt-upgrade-43');
INSERT INTO learning_task(id,title,description,requirements,audience,review_required,first_deadline,
 status,revision,created_by,created_at,updated_at,published_at)
VALUES(901,'v38-history','preserve task facts',
 JSON_OBJECT('confirmationRequired',true,'minimumTextLength',1,'minimumFiles',0,'maximumFiles',0,'allowedFileKinds',JSON_ARRAY()),
 JSON_OBJECT('userIds',JSON_ARRAY('42','43')),true,'2026-10-30 18:00:00',
 'PUBLISHED',2,9,'2026-10-14 10:00:00.123456','2026-10-14 11:00:00.123456','2026-10-14 11:00:00.123456');
INSERT INTO learning_task_assignment(id,task_id,user_id,progress,assigned_at,assigned_by,
 confirmed_at,first_submitted_at,on_time_first_submission,completed_at)
VALUES(902,901,42,'COMPLETED','2026-10-14 11:00:00',9,'2026-10-15 10:00:00',
 '2026-10-15 10:00:00',true,'2026-10-16 10:00:00');
INSERT INTO learning_task_assignment(id,task_id,user_id,progress,assigned_at,assigned_by,first_submitted_at,on_time_first_submission)
VALUES(904,901,43,'PENDING_REVIEW','2026-10-14 11:00:00',9,'2026-10-15 10:00:00',true);
INSERT INTO learning_task_submission(id,task_id,assignment_id,attempt,text,file_ids,confirmed_at,
 submitted_at,review_status,reviewed_at,reviewed_by,revision)
VALUES(903,901,902,1,'preserve-approved',JSON_ARRAY(),'2026-10-15 10:00:00',
 '2026-10-15 10:00:00','APPROVED','2026-10-16 10:00:00',9,2);
INSERT INTO learning_task_submission(id,task_id,assignment_id,attempt,text,file_ids,submitted_at,review_status)
VALUES(905,901,904,1,'preserve-pending',JSON_ARRAY(),'2026-10-15 10:00:00','PENDING_REVIEW');
UPDATE learning_task_assignment SET latest_submission_id=903 WHERE id=902;
UPDATE learning_task_assignment SET latest_submission_id=905 WHERE id=904;
INSERT INTO learning_task_preview(id,task_id,task_revision,actor_id,purpose,audience,user_ids,created_at,expires_at)
VALUES(906,901,2,9,'ADD',JSON_OBJECT('userIds',JSON_ARRAY('42','43')),JSON_ARRAY(42,43),
 '2026-10-14 11:00:00','2026-10-14 11:05:00');
