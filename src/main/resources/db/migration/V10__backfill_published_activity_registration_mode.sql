-- V5 preserved legacy registration_mode=0 because the old rows could not be classified
-- safely from that column alone. Canonical public resources require an explicit mode, so
-- classify only already-public legacy rows from participation facts that were actually stored.
UPDATE activity
SET registration_mode = CASE
    WHEN form_schema IS NOT NULL
         AND (NULLIF(TRIM(join_method), '') IS NOT NULL OR NULLIF(TRIM(qrcode_url), '') IS NOT NULL) THEN 4
    WHEN form_schema IS NOT NULL THEN 2
    WHEN NULLIF(TRIM(join_method), '') IS NOT NULL
         OR NULLIF(TRIM(qrcode_url), '') IS NOT NULL
         OR registration_start IS NOT NULL
         OR registration_end IS NOT NULL
         OR enroll_deadline IS NOT NULL THEN 3
    ELSE 1
END
WHERE registration_mode = 0
  AND publish_status = 1;

-- Legacy platform enrollment was individual; retain TEAM only when the stored capacity unit
-- already states that durable fact.
UPDATE activity
SET participant_mode = CASE WHEN capacity_unit = 2 THEN 2 ELSE 1 END
WHERE participant_mode IN (0, 3)
  AND publish_status = 1;

-- Drafts intentionally retain 0 and must pass the normal publish validation before exposure.

-- The accepted personal registration model has only SUBMITTED and CANCELLED. Preserve the
-- terminal timestamp while folding the retired operator-invalidated state into CANCELLED.
UPDATE activity_registration
SET status = 2,
    cancelled_at = COALESCE(cancelled_at, invalidated_at, updated_at),
    contract_version = contract_version + 1
WHERE status = 3;

-- Do not fabricate required business facts for legacy rows. Incomplete public rows are withdrawn
-- until operations completes and republishes them through the canonical admin contract.
UPDATE activity
SET publish_status = 2,
    is_pinned = FALSE
WHERE publish_status = 1
  AND (
      NULLIF(TRIM(summary), '') IS NULL
      OR NULLIF(TRIM(organizer), '') IS NULL
      OR NULLIF(TRIM(audience_summary), '') IS NULL
      OR audience_scope IS NULL
      OR category IS NULL
      OR registration_mode NOT IN (1, 2, 3, 4)
      OR participant_mode NOT IN (1, 2)
      OR (capacity IS NULL) <> (capacity_unit IS NULL)
      OR (participant_mode = 1 AND capacity_unit = 2)
      OR (participant_mode = 2 AND capacity_unit = 1)
      OR (registration_mode IN (2, 4) AND (
          participant_mode <> 1
          OR registration_end IS NULL
          OR form_schema IS NULL
          OR form_version IS NULL
          OR (capacity_unit IS NOT NULL AND capacity_unit <> 1)
      ))
      OR (registration_mode = 1 AND (
          registration_start IS NOT NULL
          OR registration_end IS NOT NULL
          OR form_schema IS NOT NULL
      ))
      OR (registration_mode = 3 AND form_schema IS NOT NULL)
  );
