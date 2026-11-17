package dev.unstablesmp.combat;

/** Temporary states put on entities by abilities. Combos key off these. */
public enum Mark {
    /** Null Brand: takes bonus swarm damage. */
    NULL_BRAND,
    /** Law Sentence: chained in place. */
    SENTENCED,
    /** Law Order: can't use abilities; swarms go quiet. */
    SILENCED,
    /** Standing in a Null Field: no regeneration. */
    IN_NULL_FIELD,
    /** Derived, not stored: the entity is on fire. */
    BURNING
}
