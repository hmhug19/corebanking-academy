-- Removes the TRAINING_CATALOG menu function and its role grants. The catalog data is not touched.
BEGIN;
SET LOCAL lock_timeout = '5s';

DELETE FROM learning_hub.role_functions
WHERE function_id IN (SELECT function_id FROM learning_hub.functions WHERE code = 'TRAINING_CATALOG');
DELETE FROM learning_hub.functions WHERE code = 'TRAINING_CATALOG';

COMMIT;
