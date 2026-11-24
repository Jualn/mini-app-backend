-- Read-only inventory for an explicitly authorized V82+ database. Do not run on a pre-V82 schema.
-- Counts check shape and redundant values, not historical authority or lawful publication.
-- Counts reveal storage/evidence categories only. Never repair from today's binding/profile.
-- Incomplete/conflicting proofs require independent original evidence and a controlled repair plan.
WITH raw AS (
 SELECT c.user_id,c.mentor_evidence,c.mentor_attribution,
        NULLIF(TRIM(c.mentor_evidence),'') IS NOT NULL has_old,
        c.mentor_attribution IS NOT NULL has_new,
        IF(JSON_VALID(c.mentor_evidence),CAST(c.mentor_evidence AS JSON),JSON_OBJECT()) old_proof
 FROM comment c JOIN post p ON p.id=c.target_id
 WHERE c.target_type=1 AND c.parent_id IS NULL AND p.post_type='MENTOR_QA'
), normalized AS (
 SELECT raw.*,COALESCE(mentor_attribution,old_proof) proof,
 CASE WHEN has_old AND has_new THEN 'BOTH' WHEN has_old THEN 'OLD_ONLY'
      WHEN has_new THEN 'NEW_ONLY' ELSE 'MISSING' END storage_shape
 FROM raw
), classified AS (
 SELECT normalized.*,
 CASE WHEN NOT has_old AND NOT has_new THEN 'MISSING'
 WHEN has_old AND (NOT JSON_VALID(mentor_evidence) OR JSON_TYPE(old_proof)<>'OBJECT') THEN 'INVALID'
 WHEN has_old AND has_new AND EXISTS (
  SELECT 1 FROM JSON_TABLE('["$.userId","$.personId","$.bindingId","$.qualificationId","$.qualificationVersion","$.qualifiedAt","$.profileAtAnswer.realName","$.profileAtAnswer.position","$.profileAtAnswer.affiliation"]',
   '$[*]' COLUMNS(proof_path VARCHAR(100) PATH '$')) paths
  WHERE JSON_CONTAINS_PATH(old_proof,'one',paths.proof_path)
  AND NOT (JSON_UNQUOTE(JSON_EXTRACT(old_proof,paths.proof_path)) <=> JSON_UNQUOTE(JSON_EXTRACT(mentor_attribution,paths.proof_path)))
 ) THEN 'CONFLICT'
 WHEN JSON_TYPE(proof)<>'OBJECT' OR EXISTS (
  SELECT 1 FROM JSON_TABLE('["$.userId","$.personId","$.bindingId","$.qualificationId","$.qualificationVersion","$.qualifiedAt","$.profileAtAnswer.realName","$.profileAtAnswer.position","$.profileAtAnswer.affiliation"]',
   '$[*]' COLUMNS(proof_path VARCHAR(100) PATH '$')) paths
  WHERE NULLIF(NULLIF(TRIM(JSON_UNQUOTE(JSON_EXTRACT(proof,paths.proof_path))),'null'),'') IS NULL
 ) THEN 'INCOMPLETE'
 WHEN JSON_UNQUOTE(JSON_EXTRACT(proof,'$.userId'))<>CAST(user_id AS CHAR) THEN 'AUTHOR_CONFLICT'
 ELSE 'COMPLETE_CONSISTENT' END proof_state
 FROM normalized
)
SELECT storage_shape,proof_state,COUNT(*) answer_count FROM classified
GROUP BY storage_shape,proof_state ORDER BY storage_shape,proof_state;
