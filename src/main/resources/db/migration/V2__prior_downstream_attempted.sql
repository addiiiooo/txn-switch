-- downstream_attempted used to be a single flag that a release overwrote. A retry that was
-- refused before sending anything therefore erased an earlier attempt that might have placed a
-- hold, and a takeover never set the flag at all, so a crash mid-call after a takeover went
-- uncounted.
--
-- The flag now has two halves. prior_downstream_attempted is what earlier claims on this key
-- may have done; it is snapshotted at each takeover and only ever grows. downstream_attempted
-- is what the current claim may be doing: set pessimistically when the claim is taken, and set
-- on release to prior OR "this attempt may have reached the acquirer".
ALTER TABLE idempotency_records
    ADD COLUMN prior_downstream_attempted BOOLEAN NOT NULL DEFAULT FALSE;
