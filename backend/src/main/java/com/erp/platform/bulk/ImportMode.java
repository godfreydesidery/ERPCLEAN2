package com.erp.platform.bulk;

/** Whether a bulk run is a dry-run (validate only, nothing persisted) or a real commit. */
public enum ImportMode {
    VALIDATE,
    COMMIT
}
