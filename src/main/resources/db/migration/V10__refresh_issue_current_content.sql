-- T09b: reconciliation runs refresh current issue content; per-run findings stay append-only.
-- migrator retains ownership; readonly still reads only recon.v_issue.
GRANT UPDATE (expected, actual, evidence_refs) ON recon.issue TO app;
