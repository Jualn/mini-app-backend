-- Synthetic fixtures ONLY in a fresh disposable official_content_test schema after V34 and before V35.
INSERT INTO media_attachment(type,kind,registered,url,original_name)
VALUES(1,'IMAGE',1,'https://example.org/old.jpg','old');
INSERT INTO event_attachment_link VALUES(2,123,LAST_INSERT_ID(),0);
INSERT INTO user_profile(openid) VALUES('m01-migration-legacy');
INSERT INTO post(user_id,title,content,status) VALUES(LAST_INSERT_ID(),'旧帖子','不得丢失',1);
