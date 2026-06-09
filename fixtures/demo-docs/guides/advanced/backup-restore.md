---
title: Backup and Restore
tags: [ops, advanced]
review_after: 2027-06-01
---

# Backup and Restore

Back up `CONTENT_DIR` and `DATA_DIR`. Search indexes are derived state —
never back them up, just rebuild.

## Disaster drill

1. Delete `search.db`.
2. Run `plainbase reindex`.
3. Verify results match.
