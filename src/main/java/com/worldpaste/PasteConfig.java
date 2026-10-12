package com.worldpaste;

/** Tunables for the paste. Kept as constants so behaviour is predictable on servers. */
public final class PasteConfig {
    private PasteConfig() {}

    /** Chunks pasted per second (spec: ~3/sec). */
    public static final int CHUNKS_PER_SECOND = 3;
    /** Folder (server root) where operators drop source world zips. */
    public static final String INBOX_DIR = "worldpaste_inbox";
    /** Temp extraction dir for the active paste. */
    public static final String TMP_DIR = "worldpaste_tmp";
    /** If a modded block/entity is missing on this server, skip it (true) instead of crashing. */
    public static final boolean SKIP_MISSING_MODDED_ENTRIES = true;
}
