-- Release publication of the persistent mentor owner and formal-answer gates delivered in V63.
-- No User Assignment, Person scope or mentor business qualification is granted here.
UPDATE rbac_permission_definition SET lifecycle='ENABLED',readiness='READY',grantable=TRUE,unavailable_reason=NULL
 WHERE code='mentor:qualification-manage' AND lifecycle='REGISTERED' AND readiness='NOT_READY';
UPDATE rbac_role SET lifecycle='ENABLED',readiness='READY',grantable=TRUE,unavailable_reason=NULL
 WHERE code='MENTOR_REGISTRAR' AND lifecycle='REGISTERED' AND readiness='NOT_READY';
