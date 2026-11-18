-- Test containers only: the runtime role with a login and a dummy, test-only password (never used outside tests).
-- V8 then enforces its attributes and grants, exactly as in other environments.
CREATE ROLE payassist_app LOGIN PASSWORD 'test-only-app-password';
