package org.telegram.messenger.merge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, manifest = Config.NONE, sdk = 28)
public final class MergeRegressionCanaryTest {

    private static final String REGISTRY_PATH = "docs/FORK_FEATURES.md";
    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile("```json\\s*(.*?)\\s*```", Pattern.DOTALL);
    private static final Pattern FEATURE_HEADING_PATTERN = Pattern.compile("(?m)^##\\s+([A-Za-z0-9_-]+)\\s*$");
    private static final Pattern LAST_DB_VERSION_PATTERN = Pattern.compile("LAST_DB_VERSION\\s*=\\s*(\\d+)");
    private static final int SHIPPED_DB_FLOOR = 175;

    @Test
    public void registryAnchorsAreStillPresent() throws Exception {
        ArrayList<FeatureSpec> features = readRegistry();
        int checkedAnchors = 0;
        for (int i = 0; i < features.size(); i++) {
            FeatureSpec feature = features.get(i);
            for (int j = 0; j < feature.anchors.size(); j++) {
                AnchorSpec anchor = feature.anchors.get(j);
                Path path = repoRoot().resolve(anchor.path);
                assertTrue(failureMessage(feature.id, anchor), Files.isRegularFile(path));
                String source = readFile(path);
                assertTrue(failureMessage(feature.id, anchor), anchorMatches(source, anchor));
                checkedAnchors++;
            }
        }
        assertEquals("Unexpected registry feature count", 15, features.size());
        assertEquals("Unexpected registry anchor count", 153, checkedAnchors);
    }

    @Test
    public void externalPreviewDatabaseMigrationHooksAreStillPresent() throws Exception {
        String messagesStorage = readRepoFile("TMessagesProj/src/main/java/org/telegram/messenger/MessagesStorage.java");
        String migrationHelper = readRepoFile("TMessagesProj/src/main/java/org/telegram/messenger/DatabaseMigrationHelper.java");
        String externalPreviewStorage = readRepoFile("TMessagesProj/src/main/java/org/telegram/messenger/browser/external/ExternalPreviewStorage.java");
        String previewRepository = readRepoFile("TMessagesProj/src/main/java/org/telegram/messenger/browser/external/PreviewRepository.java");

        assertLastDbVersionAtLeast(messagesStorage, SHIPPED_DB_FLOOR);

        assertContains(dbMessage("ExternalPreviewStorage create schema missing"), externalPreviewStorage, "CREATE TABLE IF NOT EXISTS external_previews_v1");
        assertContains(dbMessage("ExternalPreviewStorage create schema missing extra column"), externalPreviewStorage, "extra TEXT");
        assertContains(dbMessage("external_previews_v1 migration create missing"), migrationHelper, "CREATE TABLE IF NOT EXISTS external_previews_v1");
        assertContains(dbMessage("external_previews_v1 migration index create must be idempotent"), migrationHelper, "CREATE INDEX IF NOT EXISTS external_previews_v1_updated_at_idx");
        assertContains(dbMessage("external_previews_v1 migration extra-column upgrade must be idempotent"), migrationHelper, "executeNoException(database, \"ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT\")");
        assertFalse(dbMessage("external_previews_v1 extra-column upgrade must not use bare executeFast") + ": ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT", migrationHelper.contains("database.executeFast(\"ALTER TABLE external_previews_v1 ADD COLUMN extra TEXT\""));

        assertContains(dbMessage("MessagesStorage createTables delegate missing"), messagesStorage, "ExternalPreviewStorage.createTables(database)");
        assertContains(dbMessage("MessagesStorage putExternalPreview delegate missing"), messagesStorage, "ExternalPreviewStorage.put(this, preview)");
        assertContains(dbMessage("MessagesStorage getExternalPreview delegate missing"), messagesStorage, "ExternalPreviewStorage.get(this, canonicalUrl, onComplete)");
        assertContains(dbMessage("MessagesStorage format purge delegate missing"), messagesStorage, "ExternalPreviewStorage.purgeForFormatUpgrade(this, currentAccount)");
        assertContains(dbMessage("MessagesStorage clear cache delegate missing"), messagesStorage, "ExternalPreviewStorage.clearCacheLocked(this)");

        assertContains(dbMessage("EXTERNAL_PREVIEW_FORMAT_VERSION constant missing"), previewRepository, "EXTERNAL_PREVIEW_FORMAT_VERSION = 3");
        assertContains(dbMessage("format upgrade purge path missing"), externalPreviewStorage, "purgeForFormatUpgrade");
        assertContains(dbMessage("format upgrade clearDebugState missing"), externalPreviewStorage, "ExternalPreviewManager.clearDebugState()");
        assertContains(dbMessage("format upgrade delete path missing"), externalPreviewStorage, "DELETE FROM external_previews_v1");
    }

    @Test
    public void launchActivityConfigurationChangesStayMeasureDriven() throws Exception {
        String launchActivity = readRepoFile("TMessagesProj/src/main/java/org/telegram/ui/LaunchActivity.java");
        String body = methodBody(launchActivity, "public void onConfigurationChanged(Configuration newConfig)");

        assertContains("fold-tablet onConfigurationChanged sentinel missing", body, "measuredWindowWidth = 0");
        assertContains("fold-tablet onConfigurationChanged sentinel missing", body, "super.onConfigurationChanged(newConfig)");
        assertFalse("fold-tablet onConfigurationChanged must not reset tablet state directly", body.contains("AndroidUtilities.resetTabletFlag()"));
        assertFalse("fold-tablet onConfigurationChanged must not invalidate tablet layout directly", body.contains("invalidateTabletMode()"));
        assertFalse("fold-tablet onConfigurationChanged must not run checkLayout directly", body.contains("checkLayout()"));
    }

    @Test
    public void registryJsonBlocksHaveMatchingProseSections() throws Exception {
        String registry = readRepoFile(REGISTRY_PATH);
        LinkedHashSet<String> headingIds = parseFeatureHeadings(registry);
        LinkedHashSet<String> jsonIds = new LinkedHashSet<>();
        ArrayList<FeatureSpec> features = parseRegistry(registry);
        for (int i = 0; i < features.size(); i++) {
            jsonIds.add(features.get(i).id);
        }
        assertEquals("docs/FORK_FEATURES.md headings and json ids differ", headingIds, jsonIds);
    }

    @Test
    public void anchorHelperDetectsPresentAndMissingMarkers() {
        AnchorSpec anchor = new AnchorSpec("memory", "FOLDOGRAM-MARKER", 2);
        assertTrue("helper should pass when source has enough markers", anchorMatches("a FOLDOGRAM-MARKER b FOLDOGRAM-MARKER", anchor));
        assertFalse("helper should fail when source has too few markers", anchorMatches("a FOLDOGRAM-MARKER", anchor));
        assertFalse("helper should fail when source misses marker", anchorMatches("plain source", new AnchorSpec("memory", "FOLDOGRAM-MARKER", 1)));
    }

    private static ArrayList<FeatureSpec> readRegistry() throws Exception {
        return parseRegistry(readRepoFile(REGISTRY_PATH));
    }

    private static ArrayList<FeatureSpec> parseRegistry(String markdown) throws Exception {
        ArrayList<FeatureSpec> result = new ArrayList<>();
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(markdown);
        while (matcher.find()) {
            JSONObject object = new JSONObject(matcher.group(1).trim());
            String id = object.getString("id");
            JSONArray anchorsJson = object.getJSONArray("anchors");
            ArrayList<AnchorSpec> anchors = new ArrayList<>();
            for (int i = 0; i < anchorsJson.length(); i++) {
                JSONObject anchorJson = anchorsJson.getJSONObject(i);
                String path = anchorJson.getString("path");
                String contains = anchorJson.getString("contains");
                int minCount = anchorJson.has("min_count") ? anchorJson.getInt("min_count") : 1;
                anchors.add(new AnchorSpec(path, contains, minCount));
            }
            result.add(new FeatureSpec(id, anchors));
        }
        return result;
    }

    private static LinkedHashSet<String> parseFeatureHeadings(String markdown) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = FEATURE_HEADING_PATTERN.matcher(markdown);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static boolean anchorMatches(String source, AnchorSpec anchor) {
        if (anchor.contains.length() == 0 || anchor.minCount < 1) {
            return false;
        }
        return countOccurrences(source, anchor.contains) >= anchor.minCount;
    }

    private static void assertLastDbVersionAtLeast(String messagesStorage, int expectedMinimum) {
        Matcher matcher = LAST_DB_VERSION_PATTERN.matcher(messagesStorage);
        assertTrue(dbMessage("LAST_DB_VERSION missing"), matcher.find());
        int actual = Integer.parseInt(matcher.group(1));
        assertTrue(dbMessage("LAST_DB_VERSION too low: expected >= " + expectedMinimum + ", actual " + actual), actual >= expectedMinimum);
    }

    private static String failureMessage(String featureId, AnchorSpec anchor) {
        return featureId + " anchor " + anchor.path + ":" + anchor.contains + " missing — likely wiped by upstream merge; see docs/FORK_FEATURES.md#" + featureId;
    }

    private static String dbMessage(String detail) {
        return "db-preview " + detail + " — likely wiped by upstream merge; see docs/FORK_FEATURES.md#db-preview";
    }

    private static String readRepoFile(String relativePath) throws IOException {
        return readFile(repoRoot().resolve(relativePath));
    }

    private static String readFile(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int signatureIndex = source.indexOf(signature);
        assertTrue("Method signature missing: " + signature, signatureIndex >= 0);
        int openBrace = source.indexOf('{', signatureIndex + signature.length());
        assertTrue("Method body missing: " + signature, openBrace >= 0);
        int closeBrace = findMatchingBrace(source, openBrace);
        String body = source.substring(openBrace + 1, closeBrace);
        assertFalse("Method body unexpectedly empty: " + signature, body.trim().isEmpty());
        return body;
    }

    private static int findMatchingBrace(String source, int openBrace) {
        int depth = 0;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (inLineComment) {
                if (c == '\n' || c == '\r') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString || inChar) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (inString && c == '"') {
                    inString = false;
                } else if (inChar && c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '"') {
                inString = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("Method body closing brace missing");
    }

    private static Path repoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(REGISTRY_PATH)) && Files.isDirectory(current.resolve("TMessagesProj"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("Foldogram repository root missing — likely running tests from unexpected directory");
    }

    private static void assertContains(String message, String source, String needle) {
        assertTrue(message + ": " + needle, source.contains(needle));
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while (true) {
            index = source.indexOf(needle, index);
            if (index < 0) {
                return count;
            }
            count++;
            index += needle.length();
        }
    }

    private static final class FeatureSpec {
        final String id;
        final ArrayList<AnchorSpec> anchors;

        FeatureSpec(String id, ArrayList<AnchorSpec> anchors) {
            this.id = id;
            this.anchors = anchors;
        }
    }

    private static final class AnchorSpec {
        final String path;
        final String contains;
        final int minCount;

        AnchorSpec(String path, String contains, int minCount) {
            this.path = path;
            this.contains = contains;
            this.minCount = minCount;
        }
    }
}
