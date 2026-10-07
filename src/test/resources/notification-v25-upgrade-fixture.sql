-- Synthetic fixture ONLY for a disposable database migrated through V25.
INSERT INTO notification (id,user_id,type,title,content,is_read,inbox_generation,created_at) VALUES
 (91001,900000011,1,'historical read','synthetic',1,'LEGACY','2020-01-01 00:00:00'),
 (91002,900000011,7,'historical unread','synthetic',0,'LEGACY','2020-01-02 00:00:00'),
 (91003,900000011,8,'external only','synthetic',0,'CANONICAL','2020-01-03 00:00:00'),
 (91004,900000011,8,'in app delivery','synthetic',0,'CANONICAL','2020-01-04 00:00:00');
INSERT INTO notification_delivery (notification_id,channel,status) VALUES
 (91003,'WECHAT_OFFICIAL_ACCOUNT','DELIVERED'),(91004,'IN_APP','DELIVERED');
INSERT INTO activity (id,user_id,title,contract_version,publish_status,lifecycle_status) VALUES
 (92001,900000011,'synthetic activity',5,1,0);
INSERT INTO public_event (id,user_id,title,contract_version,publish_status,lifecycle_status) VALUES
 (92002,900000011,'synthetic public event',5,1,0);
INSERT INTO timeline (id,target_type,target_id,label,start_time,node_type,start_precision,node_key) VALUES
 (93001,2,92001,'registration end','2030-01-01 15:00:00','REGISTRATION_END',2,'synthetic-registration'),
 (93002,3,92002,'public start','2030-01-01 17:00:00','PUBLIC_EVENT_START',2,'synthetic-start'),
 (93003,3,92002,'public deadline','2030-01-01 18:00:00','REGISTRATION_END',2,'synthetic-deadline');
INSERT INTO notify_plan (id,source_type,source_id,timeline_id,rule_key,generation,recipient_scope,notify_type,title,content,send_at,status) VALUES
 (94001,1,92001,93001,'ACTIVITY_REGISTRATION_DEADLINE_REMINDER',5,'SUBSCRIBERS_NOT_REGISTERED',9,'t','c','2029-12-31 15:00:00',0),
 (94002,2,92002,93002,'PUBLIC_EVENT_START_REMINDER',5,'SUBSCRIBERS',10,'t','c','2030-01-01 16:00:00',0),
 (94003,2,92002,93003,'PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER',5,'SUBSCRIBERS',11,'t','c','2029-12-31 18:00:00',0),
 (94004,1,92001,93001,'ACTIVITY_REGISTRATION_DEADLINE_REMINDER',4,'SUBSCRIBERS_NOT_REGISTERED',9,'stale','c','2029-12-31 15:00:00',0),
 (94005,1,92001,93001,'processing-compatibility',5,'SUBSCRIBERS_NOT_REGISTERED',9,'processing','c','2029-12-31 15:00:00',3);
