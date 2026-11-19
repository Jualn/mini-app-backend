-- Fixed relation belongs to the immutable safety submission. NULL means historical evidence unavailable.
ALTER TABLE content_safety_review ADD COLUMN snapshot_qa_relation JSON NULL
    COMMENT 'Fixed question/answerRoot/replyTo IDs at submission; NULL for historical rounds without evidence';
