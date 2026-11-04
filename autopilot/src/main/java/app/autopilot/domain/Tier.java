package app.autopilot.domain;

/** Risk tier. Decided by the server for every call, never by the caller. */
public enum Tier {
    /** Read or internal record; no account change. */
    T0,
    /** Low-risk change inside hard limits. */
    T1,
    /** Meaningful spend or brand effect: Claude proposes, only the owner approves. */
    T2
}
