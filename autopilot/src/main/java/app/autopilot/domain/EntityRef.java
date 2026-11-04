package app.autopilot.domain;

import java.util.Objects;

/** Identifies one entity inside the allowlisted account. The account itself is never a parameter. */
public record EntityRef(EntityKind kind, String id) {
    public EntityRef {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("entity id is required");
        }
        if (!id.matches("[A-Za-z0-9_~.=-]{1,128}")) {
            throw new IllegalArgumentException("entity id has unexpected characters");
        }
    }

    public static EntityRef campaign(String id) { return new EntityRef(EntityKind.CAMPAIGN, id); }
    public static EntityRef adGroup(String id) { return new EntityRef(EntityKind.AD_GROUP, id); }
    public static EntityRef ad(String id) { return new EntityRef(EntityKind.AD, id); }
    public static EntityRef keyword(String id) { return new EntityRef(EntityKind.KEYWORD, id); }
    public static EntityRef budget(String id) { return new EntityRef(EntityKind.BUDGET, id); }

    public String key() {
        return kind + ":" + id;
    }

    @Override
    public String toString() {
        return key();
    }
}
