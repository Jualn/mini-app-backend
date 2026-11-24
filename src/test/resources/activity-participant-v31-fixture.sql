-- Synthetic fixtures, only in disposable activity_participant_test after V31.
INSERT INTO activity(id,user_id,title,summary,category,organizer,audience_department_ids,
    audience_summary,registration_mode,participant_mode,publish_status,contract_version)
VALUES
    (93000,9,'legacy unknown published','fixture',5,'fixture',JSON_ARRAY(),'students',1,0,1,7),
    (93001,9,'explicit individual','fixture',5,'fixture',JSON_ARRAY(),'students',1,1,0,7),
    (93002,9,'explicit team','fixture',5,'fixture',JSON_ARRAY(),'students',1,2,0,7),
    (93003,9,'legacy either','fixture',5,'fixture',JSON_ARRAY(),'students',1,3,0,7);
