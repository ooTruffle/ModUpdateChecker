package com.ootruffle.modupdatechecker;

import java.nio.file.Path;

/**
 * One installed mod and what the checker found for it. Written by the checker and installer
 * threads, read by the UI; fields are volatile and the UI only ever reads them.
 */
public final class ModStatus {

    public enum Source { MODRINTH, GITHUB, NONE }

    public enum State {
        /** Waiting for its check. */
        CHECKING,
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        /** Newer than what's published (a dev build or pre-release). */
        NEWER,
        /** Not on Modrinth and no GitHub repository known. */
        NOT_FOUND,
        ERROR,
        DOWNLOADING,
        /** Downloaded; installed when the game closes. */
        DOWNLOADED,
    }

    public final String id;
    public final String name;
    public final String version;
    public final Path jar;
    /** "owner/repo" or null. */
    public final String githubRepo;

    public volatile Source source = Source.NONE;
    public volatile State state = State.CHECKING;
    public volatile String latestVersion;
    public volatile String pageUrl;
    public volatile String changelog;
    public volatile String downloadUrl;
    public volatile String downloadName;
    /** Expected hash of the download ("sha1:..." or "sha256:..."), or null. */
    public volatile String downloadHash;
    /** For a Modrinth update: its project and version ids, loaders, game versions and type (for Prism's metadata). */
    public volatile String modrinthProject;
    public volatile String modrinthVersion;
    public volatile java.util.List<String> modrinthLoaders;
    public volatile java.util.List<String> modrinthGameVersions;
    public volatile String modrinthReleaseType;
    /** The Modrinth project this jar was found in, or null. */
    public volatile String modrinthProjectId;
    public volatile String error;
    /** Download progress, 0..1. */
    public volatile float progress;

    public ModStatus(String id, String name, String version, Path jar, String githubRepo) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.jar = jar;
        this.githubRepo = githubRepo;
    }

    /** Whether this update was skipped with "Ignore". */
    public boolean ignored() {
        final String skipped = Config.get().ignoredVersions.get(id);
        return skipped != null && skipped.equals(latestVersion);
    }

    /** An update the user hasn't ignored. */
    public boolean wantsUpdate() {
        return state == State.UPDATE_AVAILABLE && !ignored();
    }

    public boolean canDownload() {
        return state == State.UPDATE_AVAILABLE && downloadUrl != null && downloadName != null;
    }
}
