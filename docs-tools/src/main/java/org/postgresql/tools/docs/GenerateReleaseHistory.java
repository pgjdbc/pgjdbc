/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.tools.docs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Generates {@code docs/data/release-history.yaml} from local git refs
 * (release branches + release tags) merged with the hand-maintained
 * {@code docs/data/release-history-overlay.yaml}.
 *
 * <p>The output drives the {@code release-history} Hugo shortcode used
 * on the {@code /documentation/getting-started/compatibility/} page.
 * Source-of-truth principle (Phase 0.5): everything that CAN come from
 * the repository (tag names, commit dates) comes from there; only the
 * minimums that no file in the tree expresses (PostgreSQL server
 * version policy, classifier variants, per-branch notes) live in the
 * checked-in overlay.
 *
 * <h2>Invocation</h2>
 * <pre>
 *   GenerateReleaseHistory &lt;git-dir&gt; &lt;overlay-yaml&gt; &lt;output-yaml&gt;
 * </pre>
 * The Gradle task {@code :docs-tools:generateReleaseHistory} fills the
 * three arguments from the project layout.
 */
public final class GenerateReleaseHistory {

    /** {@code refs/heads/release/42.X.x} or {@code refs/remotes/origin/release/42.X.x}. */
    private static final Pattern BRANCH_PATTERN = Pattern.compile(
        "^refs/(?:heads|remotes/[^/]+)/release/(42\\.\\d+\\.x)$");

    /** Release tag. RC tags (REL42.7.11-rc1 etc.) are filtered out. */
    private static final Pattern TAG_PATTERN = Pattern.compile(
        "^refs/tags/REL(42\\.\\d+\\.\\d+)$");

    private GenerateReleaseHistory() {
        // utility class
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: GenerateReleaseHistory "
                + "<git-dir> <overlay-yaml> <output-yaml>");
            System.exit(2);
        }
        Path gitDir = Paths.get(args[0]);
        Path overlayYaml = Paths.get(args[1]);
        Path outputYaml = Paths.get(args[2]);

        // 1. Open repo and scan refs.
        GitFacts facts = collectGitFacts(gitDir);

        // 2. Load overlay.
        Overlay overlay = OverlayLoader.load(overlayYaml);

        // 3. Build per-branch records, resolving sparse overlay breakpoints.
        List<BranchRecord> branches = buildBranches(facts, overlay);

        // 4. Build classifier records, enriching with commit date from the
        //    referenced last_version tag (if present).
        List<ClassifierRecord> classifiers = buildClassifiers(facts, overlay);

        // 5. Emit YAML.
        ReleaseHistoryYamlEmitter.writeTo(branches, classifiers, outputYaml);
        System.out.println("Wrote " + branches.size() + " branches + "
            + classifiers.size() + " classifier rows to " + outputYaml);
    }

    /* ===== Git scan ======================================================== */

    /** Plain data carrier: branches and (per branch) sorted versions with dates. */
    static final class GitFacts {
        /** Branch ids, sorted descending: ["42.7.x", "42.6.x", ..., "42.2.x"]. */
        final List<String> branchIds = new ArrayList<>();
        /** branchId -> versions on that branch, ordered ascending. */
        final Map<String, List<TaggedRelease>> tagsByBranch = new LinkedHashMap<>();
    }

    static final class TaggedRelease {
        final String version;
        /** Commit date (UTC), formatted ISO yyyy-MM-dd. Lightweight or annotated
         *  tag: we always peel to the commit and read its commit time. */
        final String commitDate;

        TaggedRelease(String version, String commitDate) {
            this.version = version;
            this.commitDate = commitDate;
        }
    }

    private static GitFacts collectGitFacts(Path projectRoot) throws IOException {
        // Git.open() resolves the `.git` pointer (regular directory or
        // worktree pointer file) and follows `commondir`; we end up with
        // a Repository whose object store is the shared main `.git/objects`
        // and whose ref database sees both the shared tags and the per-
        // worktree branch heads. JGit 7.x has solid worktree support.
        try (Git git = Git.open(projectRoot.toFile())) {
            return scanRefs(git.getRepository());
        }
    }

    private static GitFacts scanRefs(Repository repo) throws IOException {
        Set<String> branchIds = new java.util.TreeSet<>(versionDescending());
        for (Ref ref : repo.getRefDatabase().getRefs()) {
            Matcher m = BRANCH_PATTERN.matcher(ref.getName());
            if (m.matches()) {
                branchIds.add(m.group(1));
            }
        }

        // Tags: bucket by branch id (42.X.x) extracted from version (42.X.Y).
        // For annotated tags we peel through to the commit and read its
        // commit time. (There is no annotated/lightweight distinction we
        // need to preserve — both end up reduced to a commit date.)
        Map<String, List<TaggedRelease>> tagsByBranch = new HashMap<>();
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                Matcher m = TAG_PATTERN.matcher(ref.getName());
                if (!m.matches()) {
                    continue;
                }
                String version = m.group(1);
                String branchId = toBranchId(version);
                branchIds.add(branchId);

                ObjectId peeled = peelTag(repo, ref);
                if (peeled == null) {
                    continue;
                }
                RevCommit commit = walk.parseCommit(peeled);
                String date = formatCommitDate(commit);
                tagsByBranch
                    .computeIfAbsent(branchId, k -> new ArrayList<>())
                    .add(new TaggedRelease(version, date));
            }
        }
        for (List<TaggedRelease> list : tagsByBranch.values()) {
            list.sort(Comparator.comparing(t -> versionKey(t.version)));
        }

        GitFacts out = new GitFacts();
        out.branchIds.addAll(branchIds);
        for (String id : out.branchIds) {
            out.tagsByBranch.put(id, tagsByBranch.getOrDefault(id, Collections.emptyList()));
        }
        return out;
    }

    /** {@code 42.7.5} -> {@code 42.7.x}. */
    private static String toBranchId(String version) {
        int second = version.indexOf('.', version.indexOf('.') + 1);
        return version.substring(0, second) + ".x";
    }

    private static ObjectId peelTag(Repository repo, Ref ref) throws IOException {
        Ref peeled = repo.getRefDatabase().peel(ref);
        ObjectId id = peeled.getPeeledObjectId();
        return id != null ? id : ref.getObjectId();
    }

    private static String formatCommitDate(RevCommit commit) {
        Instant instant = Instant.ofEpochSecond(commit.getCommitTime());
        return DateTimeFormatter.ISO_LOCAL_DATE
            .withZone(ZoneOffset.UTC)
            .format(instant);
    }

    /* ===== Overlay model ================================================== */

    static final class Overlay {
        final List<Breakpoint> minJava = new ArrayList<>();
        final List<Breakpoint> minPostgresql = new ArrayList<>();
        final List<Classifier> classifiers = new ArrayList<>();
        final Map<String, String> notes = new LinkedHashMap<>();
    }

    static final class Breakpoint {
        final String pgjdbc;
        final String value;

        Breakpoint(String pgjdbc, String value) {
            this.pgjdbc = pgjdbc;
            this.value = value;
        }
    }

    static final class Classifier {
        final String id;
        final String branch;
        final String lastVersion;
        final String minJava;

        Classifier(String id, String branch, String lastVersion, String minJava) {
            this.id = id;
            this.branch = branch;
            this.lastVersion = lastVersion;
            this.minJava = minJava;
        }
    }

    /* ===== Per-branch record ============================================== */

    static final class ClassifierRecord {
        final String id;
        final String branch;
        final String lastVersion;
        final String lastRelease;  // commit date of REL<lastVersion>, may be ""
        final String minJava;

        ClassifierRecord(String id, String branch, String lastVersion,
                         String lastRelease, String minJava) {
            this.id = id;
            this.branch = branch;
            this.lastVersion = lastVersion;
            this.lastRelease = lastRelease;
            this.minJava = minJava;
        }
    }

    static final class BranchRecord {
        final String id;            // "42.7.x"
        final String latest;        // "42.7.11" — may be null if no tags
        final String firstRelease;  // ISO date of 42.X.0 (or earliest tag)
        final String lastRelease;   // ISO date of latest tag
        final int releaseCount;
        final String minJava;       // "8"
        final String minPostgresql; // "9.1"
        final String notes;         // may be empty

        BranchRecord(String id, String latest, String firstRelease, String lastRelease,
                     int releaseCount, String minJava, String minPostgresql, String notes) {
            this.id = id;
            this.latest = latest;
            this.firstRelease = firstRelease;
            this.lastRelease = lastRelease;
            this.releaseCount = releaseCount;
            this.minJava = minJava;
            this.minPostgresql = minPostgresql;
            this.notes = notes;
        }
    }

    /* ===== Build phase: merge facts + overlay ============================ */

    private static List<BranchRecord> buildBranches(GitFacts facts, Overlay overlay) {
        // Sort breakpoints ascending; resolver scans for the largest entry
        // whose pgjdbc <= target.
        List<Breakpoint> jBp = sortedAscending(overlay.minJava);
        List<Breakpoint> pgBp = sortedAscending(overlay.minPostgresql);

        List<BranchRecord> out = new ArrayList<>();
        for (String branchId : facts.branchIds) {
            List<TaggedRelease> tags = facts.tagsByBranch.get(branchId);
            if (tags == null || tags.isEmpty()) {
                // Branch with no shipped releases — skip. (Easy to lift this
                // later if we want to show in-progress lines.)
                continue;
            }
            TaggedRelease firstTag = tags.get(0);
            TaggedRelease lastTag = tags.get(tags.size() - 1);

            String minJava = resolve(jBp, lastTag.version);
            String minPg = resolve(pgBp, lastTag.version);
            // Branches older than the lowest overlay breakpoint render with
            // empty compatibility cells, which is worse than not rendering
            // them at all. The overlay's lowest breakpoint is therefore the
            // de facto floor for the rendered matrix.
            if (minJava.isEmpty() && minPg.isEmpty()) {
                continue;
            }
            String note = overlay.notes.getOrDefault(branchId, "");

            out.add(new BranchRecord(
                branchId,
                lastTag.version,
                firstTag.commitDate,
                lastTag.commitDate,
                tags.size(),
                minJava,
                minPg,
                note));
        }
        // Already in descending-version order via versionDescending().
        return out;
    }

    private static List<ClassifierRecord> buildClassifiers(GitFacts facts, Overlay overlay) {
        List<ClassifierRecord> out = new ArrayList<>();
        for (Classifier c : overlay.classifiers) {
            String date = "";
            List<TaggedRelease> tags = facts.tagsByBranch.get(c.branch);
            if (tags != null) {
                for (TaggedRelease t : tags) {
                    if (t.version.equals(c.lastVersion)) {
                        date = t.commitDate;
                        break;
                    }
                }
            }
            out.add(new ClassifierRecord(c.id, c.branch, c.lastVersion, date, c.minJava));
        }
        return out;
    }

    private static List<Breakpoint> sortedAscending(List<Breakpoint> bps) {
        List<Breakpoint> copy = new ArrayList<>(bps);
        copy.sort(Comparator.comparing(b -> versionKey(b.pgjdbc)));
        return copy;
    }

    /** Sparse-breakpoint resolver: largest breakpoint with pgjdbc &le; target wins. */
    private static String resolve(List<Breakpoint> ascending, String target) {
        String resolved = null;
        long targetKey = versionKey(target);
        for (Breakpoint bp : ascending) {
            if (versionKey(bp.pgjdbc) <= targetKey) {
                resolved = bp.value;
            } else {
                break;
            }
        }
        return resolved != null ? resolved : "";
    }

    /* ===== Version helpers =============================================== */

    /** Pack a dotted version into a sortable long. Up to 5 components,
     *  each in [0, 999]. "42.7.11" -> 42_007_011_000_000. */
    static long versionKey(String version) {
        long key = 0;
        int count = 0;
        for (String part : version.split("\\.")) {
            if ("x".equals(part)) {
                part = "0";
            }
            try {
                key = key * 1000 + Long.parseLong(part);
            } catch (NumberFormatException ex) {
                // Best-effort: unknown segment treated as 0.
                key = key * 1000;
            }
            count++;
            if (count == 5) {
                break;
            }
        }
        while (count < 5) {
            key = key * 1000;
            count++;
        }
        return key;
    }

    static Comparator<String> versionDescending() {
        return (a, b) -> Long.compare(versionKey(b), versionKey(a));
    }

    /* ===== Overlay loader (snakeyaml) ==================================== */

    static final class OverlayLoader {
        private OverlayLoader() {
        }

        @SuppressWarnings("unchecked")
        static Overlay load(Path file) throws IOException {
            Overlay overlay = new Overlay();
            if (!Files.isRegularFile(file)) {
                System.err.println("Overlay not found at " + file
                    + " — proceeding with no overlay data.");
                return overlay;
            }
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root;
            try (java.io.Reader r = Files.newBufferedReader(
                    file, java.nio.charset.StandardCharsets.UTF_8)) {
                Object parsed = yaml.load(r);
                if (!(parsed instanceof Map)) {
                    throw new IOException("Overlay root must be a mapping in " + file);
                }
                root = (Map<String, Object>) parsed;
            }
            for (Map<String, Object> e : (List<Map<String, Object>>)
                    root.getOrDefault("min_java", Collections.emptyList())) {
                overlay.minJava.add(new Breakpoint(
                    String.valueOf(e.get("pgjdbc")),
                    String.valueOf(e.get("java"))));
            }
            for (Map<String, Object> e : (List<Map<String, Object>>)
                    root.getOrDefault("min_postgresql", Collections.emptyList())) {
                overlay.minPostgresql.add(new Breakpoint(
                    String.valueOf(e.get("pgjdbc")),
                    String.valueOf(e.get("postgresql"))));
            }
            for (Map<String, Object> e : (List<Map<String, Object>>)
                    root.getOrDefault("classifiers", Collections.emptyList())) {
                overlay.classifiers.add(new Classifier(
                    String.valueOf(e.get("id")),
                    String.valueOf(e.get("branch")),
                    String.valueOf(e.get("last_version")),
                    String.valueOf(e.get("min_java"))));
            }
            Object notesRaw = root.get("notes");
            if (notesRaw instanceof Map) {
                for (Map.Entry<String, Object> e :
                        ((Map<String, Object>) notesRaw).entrySet()) {
                    overlay.notes.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            return overlay;
        }
    }

    /* ===== YAML emitter (hand-rolled, no snakeyaml-on-output) ============ */

    static final class ReleaseHistoryYamlEmitter {
        private ReleaseHistoryYamlEmitter() {
        }

        static void writeTo(List<BranchRecord> branches, List<ClassifierRecord> classifiers,
                            Path output) throws IOException {
            Files.createDirectories(output.getParent());
            try (java.io.Writer w = Files.newBufferedWriter(
                    output, java.nio.charset.StandardCharsets.UTF_8)) {
                w.write("# Generated by :docs-tools:generateReleaseHistory — DO NOT EDIT.\n");
                w.write("# Source of truth: git refs (release/* branches, REL* tags)\n");
                w.write("#                  + docs/data/release-history-overlay.yaml.\n");
                w.write("# Run `./gradlew :docs-tools:generateReleaseHistory` to regenerate.\n");
                w.write("\n");
                w.write("branches:\n");
                for (BranchRecord r : branches) {
                    w.write("  - id:             " + quoted(r.id) + "\n");
                    w.write("    latest:         " + quoted(r.latest) + "\n");
                    w.write("    first_release:  " + quoted(r.firstRelease) + "\n");
                    w.write("    last_release:   " + quoted(r.lastRelease) + "\n");
                    w.write("    release_count:  " + r.releaseCount + "\n");
                    w.write("    min_java:       " + quoted(r.minJava) + "\n");
                    w.write("    min_postgresql: " + quoted(r.minPostgresql) + "\n");
                    if (!r.notes.isEmpty()) {
                        w.write("    notes:          " + quoted(r.notes) + "\n");
                    }
                }
                w.write("\n");
                w.write("classifiers:\n");
                for (ClassifierRecord c : classifiers) {
                    w.write("  - id:           " + quoted(c.id) + "\n");
                    w.write("    branch:       " + quoted(c.branch) + "\n");
                    w.write("    last_version: " + quoted(c.lastVersion) + "\n");
                    if (!c.lastRelease.isEmpty()) {
                        w.write("    last_release: " + quoted(c.lastRelease) + "\n");
                    }
                    w.write("    min_java:     " + quoted(c.minJava) + "\n");
                }
            }
        }

        private static String quoted(String s) {
            if (s == null) {
                return "\"\"";
            }
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

}
