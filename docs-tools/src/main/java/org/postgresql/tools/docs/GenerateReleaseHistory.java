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
import java.time.LocalDate;
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
 * <p>The output is a flat {@code rows} list consumed by the
 * {@code release-history} Hugo shortcode on the
 * {@code /documentation/getting-started/compatibility/} page. Each row
 * represents one "compatibility segment":
 *
 * <ul>
 *   <li><b>segment row</b> — a contiguous run of tagged versions on a
 *       release branch with the same {@code (min_java, min_postgresql)}
 *       pair. The version range becomes the {@code 42.7.0-42.7.4} cell.
 *   <li><b>classifier row</b> — the latest {@code .jre6} / {@code .jre7}
 *       build of the 42.2.x line, surfaced as a separate row under the
 *       same release-line label.
 * </ul>
 *
 * <p>The {@code Status} column is derived from each line's
 * {@code .0} commit date plus the overlay's
 * {@code support_window_years}: rows older than the window are
 * "EOL since YYYY-MM", rows still inside are "Security until YYYY-MM",
 * and the latest segment of the latest release line is "Current".
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
                + "<project-root> <overlay-yaml> <output-yaml>");
            System.exit(2);
        }
        Path projectRoot = Paths.get(args[0]);
        Path overlayYaml = Paths.get(args[1]);
        Path outputYaml = Paths.get(args[2]);

        GitFacts facts = collectGitFacts(projectRoot);
        Overlay overlay = OverlayLoader.load(overlayYaml);
        List<Row> rows = buildRows(facts, overlay, LocalDate.now(ZoneOffset.UTC));
        YamlEmitter.writeTo(rows, outputYaml);
        System.out.println("Wrote " + rows.size() + " release-history rows to "
            + outputYaml);
    }

    /* ===== Git scan ======================================================== */

    static final class GitFacts {
        /** Branch ids, sorted descending: ["42.7.x", "42.6.x", ..., "42.2.x"]. */
        final List<String> branchIds = new ArrayList<>();
        final Map<String, List<TaggedRelease>> tagsByBranch = new LinkedHashMap<>();
    }

    static final class TaggedRelease {
        final String version;
        final String commitDate;   // ISO "yyyy-MM-dd"

        TaggedRelease(String version, String commitDate) {
            this.version = version;
            this.commitDate = commitDate;
        }
    }

    private static GitFacts collectGitFacts(Path projectRoot) throws IOException {
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
        int supportWindowYears = 5;
        final List<Breakpoint> minJava = new ArrayList<>();
        final List<Breakpoint> minPostgresql = new ArrayList<>();
        final List<Classifier> classifiers = new ArrayList<>();
        final List<String> securityReleases = new ArrayList<>();
        final Map<String, List<String>> testedJava = new LinkedHashMap<>();
        final Map<String, List<String>> testedPostgresql = new LinkedHashMap<>();
        final Map<String, String> branchNotes = new LinkedHashMap<>();
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

    /* ===== Row model ====================================================== */

    static final class Row {
        String releaseLine = "";    // "42.7.x"
        String versionRange = "";   // "42.7.0-42.7.4" or "42.7.5" or "42.2.27.jre6"
        String released = "";       // ISO date of the last release in the range
        String minJava = "";
        String minPostgresql = "";
        String status = "";         // "Current" / "Security until 2028-11" / "EOL since 2023-01"
        String notes = "";
    }

    /* ===== Row building =================================================== */

    private static List<Row> buildRows(GitFacts facts, Overlay overlay, LocalDate today) {
        List<Breakpoint> jBp = sortedAscending(overlay.minJava);
        List<Breakpoint> pgBp = sortedAscending(overlay.minPostgresql);

        Map<String, List<Classifier>> classifiersByBranch = new HashMap<>();
        for (Classifier c : overlay.classifiers) {
            classifiersByBranch
                .computeIfAbsent(c.branch, k -> new ArrayList<>())
                .add(c);
        }

        // First branch in branchIds is the latest (descending sort).
        String latestLine = facts.branchIds.isEmpty() ? "" : facts.branchIds.get(0);

        List<Row> out = new ArrayList<>();
        for (String branchId : facts.branchIds) {
            List<TaggedRelease> tags = facts.tagsByBranch.get(branchId);
            if (tags == null || tags.isEmpty()) {
                continue;
            }
            TaggedRelease latest = tags.get(tags.size() - 1);
            // Branch is rendered only if its latest tag falls within the
            // overlay's coverage (≥ lowest breakpoint).
            if (resolve(jBp, latest.version).isEmpty()
                    && resolve(pgBp, latest.version).isEmpty()) {
                continue;
            }

            // Compute the line's support window from its .0 (or earliest)
            // tag plus support_window_years. Used in the Status column
            // for every row of this line.
            LocalDate lineStart = LocalDate.parse(tags.get(0).commitDate);
            LocalDate supportEnd = lineStart.plusYears(overlay.supportWindowYears);
            String supportEndYm = DateTimeFormatter.ofPattern("yyyy-MM").format(supportEnd);

            // Group consecutive tags by (min_java, min_pg). Each group is
            // one "segment" — one row in the rendered matrix.
            List<Segment> segments = computeSegments(tags, jBp, pgBp);

            // Collect this branch's rows into a local list so we can sort
            // by released-desc before appending to the global output;
            // release-line groups stay in their outer (descending) order.
            List<Row> branchRows = new ArrayList<>();

            for (int i = 0; i < segments.size(); i++) {
                Segment seg = segments.get(i);
                boolean isLastSegment = i == segments.size() - 1;
                boolean isCurrent = isLastSegment && branchId.equals(latestLine);

                Row row = new Row();
                row.releaseLine = branchId;
                row.versionRange = formatRange(seg.firstVersion, seg.lastVersion);
                row.released = seg.lastDate;
                row.minJava = seg.minJava;
                row.minPostgresql = seg.minPg;
                if (!isLastSegment) {
                    // Intermediate segment: a newer segment on the same
                    // line raised one of the floors. Point readers at the
                    // version that introduced the next floor.
                    Segment next = segments.get(i + 1);
                    row.status = "Superseded by " + next.firstVersion + "+";
                } else if (isCurrent) {
                    row.status = "Current";
                } else if (today.isBefore(supportEnd)) {
                    row.status = "Security until " + supportEndYm;
                } else {
                    row.status = "EOL since " + supportEndYm;
                }
                row.notes = composeNotes(isCurrent, branchId, overlay);
                branchRows.add(row);
            }

            // Classifier rows piggy-back on the parent line's status.
            for (Classifier c : classifiersByBranch.getOrDefault(
                    branchId, Collections.emptyList())) {
                // Find the tag for this classifier's last_version to pull a date.
                String date = "";
                for (TaggedRelease t : tags) {
                    if (t.version.equals(c.lastVersion)) {
                        date = t.commitDate;
                        break;
                    }
                }
                Row row = new Row();
                row.releaseLine = branchId;
                row.versionRange = c.lastVersion + "." + c.id;
                row.released = date;
                row.minJava = c.minJava;
                row.minPostgresql = resolve(pgBp, c.lastVersion);
                row.status = today.isBefore(supportEnd)
                    ? "Security until " + supportEndYm
                    : "EOL since " + supportEndYm;
                // No per-classifier note: the Java column ("6+" / "7+")
                // and the version range column already convey that this
                // row is the latest jreN build of the line.
                branchRows.add(row);
            }

            // Sort within the branch by released-desc; stable sort keeps
            // tied dates in emission order (main segment before classifier
            // with the same date).
            branchRows.sort((a, b) -> b.released.compareTo(a.released));
            out.addAll(branchRows);
        }
        return out;
    }

    private static String composeNotes(boolean isCurrent, String branchId, Overlay overlay) {
        if (isCurrent) {
            List<String> jv = overlay.testedJava.getOrDefault(
                branchId, Collections.emptyList());
            List<String> pgv = overlay.testedPostgresql.getOrDefault(
                branchId, Collections.emptyList());
            if (jv.isEmpty() && pgv.isEmpty()) {
                return overlay.branchNotes.getOrDefault(branchId, "");
            }
            StringBuilder sb = new StringBuilder("CI: Java ");
            sb.append(String.join(", ", jv));
            sb.append("; PostgreSQL ");
            sb.append(collapsedPgList(pgv));
            sb.append('.');
            return sb.toString();
        }
        return overlay.branchNotes.getOrDefault(branchId, "");
    }

    /** Render PG list as "first&ndash;last" when it has at least five
     *  entries (the only realistic case is "every supported version in
     *  the modern Postgres line"); otherwise comma-separated. */
    private static String collapsedPgList(List<String> values) {
        if (values.size() >= 5) {
            return values.get(0) + "-" + values.get(values.size() - 1);
        }
        return String.join(", ", values);
    }

    /* ===== Segment computation =========================================== */

    static final class Segment {
        String firstVersion;
        String lastVersion;
        String firstDate;
        String lastDate;
        String minJava;
        String minPg;
    }

    private static List<Segment> computeSegments(List<TaggedRelease> tags,
                                                 List<Breakpoint> jBp,
                                                 List<Breakpoint> pgBp) {
        List<Segment> out = new ArrayList<>();
        Segment current = null;
        String prevJ = null;
        String prevPg = null;
        for (TaggedRelease t : tags) {
            String j = resolve(jBp, t.version);
            String pg = resolve(pgBp, t.version);
            if (current == null
                    || !j.equals(prevJ) || !pg.equals(prevPg)) {
                current = new Segment();
                current.firstVersion = t.version;
                current.firstDate = t.commitDate;
                current.minJava = j;
                current.minPg = pg;
                out.add(current);
            }
            current.lastVersion = t.version;
            current.lastDate = t.commitDate;
            prevJ = j;
            prevPg = pg;
        }
        return out;
    }

    private static String formatRange(String first, String last) {
        if (first == null || first.equals(last)) {
            return last == null ? "" : last;
        }
        return first + "-" + last;
    }

    private static List<Breakpoint> sortedAscending(List<Breakpoint> bps) {
        List<Breakpoint> copy = new ArrayList<>(bps);
        copy.sort(Comparator.comparing(b -> versionKey(b.pgjdbc)));
        return copy;
    }

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
            Object sw = root.get("support_window_years");
            if (sw instanceof Number) {
                overlay.supportWindowYears = ((Number) sw).intValue();
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
            for (Object o : (List<Object>) root.getOrDefault(
                    "security_releases", Collections.emptyList())) {
                overlay.securityReleases.add(String.valueOf(o));
            }
            Object tj = root.get("tested_java");
            if (tj instanceof Map) {
                for (Map.Entry<String, Object> e :
                        ((Map<String, Object>) tj).entrySet()) {
                    overlay.testedJava.put(e.getKey(), toStringList(e.getValue()));
                }
            }
            Object tpg = root.get("tested_postgresql");
            if (tpg instanceof Map) {
                for (Map.Entry<String, Object> e :
                        ((Map<String, Object>) tpg).entrySet()) {
                    overlay.testedPostgresql.put(e.getKey(), toStringList(e.getValue()));
                }
            }
            Object bn = root.get("branch_notes");
            if (bn instanceof Map) {
                for (Map.Entry<String, Object> e :
                        ((Map<String, Object>) bn).entrySet()) {
                    overlay.branchNotes.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            return overlay;
        }

        @SuppressWarnings("unchecked")
        private static List<String> toStringList(Object raw) {
            if (!(raw instanceof List)) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<>();
            for (Object o : (List<Object>) raw) {
                out.add(String.valueOf(o));
            }
            return out;
        }
    }

    /* ===== YAML emitter (hand-rolled) ==================================== */

    static final class YamlEmitter {
        private YamlEmitter() {
        }

        static void writeTo(List<Row> rows, Path output) throws IOException {
            Files.createDirectories(output.getParent());
            try (java.io.Writer w = Files.newBufferedWriter(
                    output, java.nio.charset.StandardCharsets.UTF_8)) {
                w.write("# Generated by :docs-tools:generateReleaseHistory — DO NOT EDIT.\n");
                w.write("# Source of truth: git refs (release/* branches, REL* tags)\n");
                w.write("#                  + docs/data/release-history-overlay.yaml.\n");
                w.write("# Run `./gradlew :docs-tools:generateReleaseHistory` to regenerate.\n");
                w.write("\n");
                w.write("rows:\n");
                for (Row r : rows) {
                    w.write("  - release_line:   " + quoted(r.releaseLine) + "\n");
                    w.write("    version_range:  " + quoted(r.versionRange) + "\n");
                    w.write("    released:       " + quoted(r.released) + "\n");
                    if (!r.minJava.isEmpty()) {
                        w.write("    min_java:       " + quoted(r.minJava) + "\n");
                    }
                    if (!r.minPostgresql.isEmpty()) {
                        w.write("    min_postgresql: " + quoted(r.minPostgresql) + "\n");
                    }
                    w.write("    status:         " + quoted(r.status) + "\n");
                    if (!r.notes.isEmpty()) {
                        w.write("    notes:          " + quoted(r.notes) + "\n");
                    }
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
