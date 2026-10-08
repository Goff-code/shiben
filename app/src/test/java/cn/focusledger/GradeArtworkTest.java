package cn.focusledger;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/** JVM-only checks: artwork identity, complete-canvas geometry, and decode allocation bounds. */
public final class GradeArtworkTest {
    private static final float EPSILON = 0.001f;
    private static final String[][] EXPECTED = {
            {"SSS+", "SSS-plus.png", "afbb67aa159c7c952dddc61fb7d0f41261b18fbf6c21d379cd87b716777cd92e"},
            {"SSS", "SSS.png", "de73c13fee15beb2fee2dd0c3578f9120d6b7f7fe6d8376849c01d7cc5f76daf"},
            {"SS", "SS.png", "3f670eb01b2051c447f3ff48d6211d73a56c620613d9b880f02b90d6e11f8c4e"},
            {"S", "S.png", "2432bc7aad69e49ca4ec76cbb85ce432f71ec1ca9ba209c9512fe4392db396b6"},
            {"A+", "A-plus.png", "4c824bf7eeb601aefac483a8d78cb3da36b243626042339bcbf8a3d639f61604"},
            {"A", "A.png", "074794fc096db2876e1c15b2d3a0a6561040426d3bccfb98c73805c0033a75d8"},
            {"A-", "A-minus.png", "0dbe63ab739f344283dd22347758b524f252957ff30a5fc5a71b9bfa7308fcf9"},
            {"B+", "B-plus.png", "0b12012a6ff4a5ef403788028356eb6cbf488ed94f9412b9ee4fb237fa963d92"},
            {"B", "B.png", "ca079785fe7f8e9c66d7a858ff775a9b9f3d328de6f9b5f5bc270d56865fe5e1"},
            {"B-", "B-minus.png", "6044f42ba69ea37bc06ba7be6224e7f2c251e7db63b12044810a8d19abfda603"},
            {"C", "C.png", "0e57448e3c9b34f3455decb1f4840df794dcbd49b0817758d3950ff106b90a5d"},
            {"D", "D.png", "b125e8f1369bee655a12d215d237b0d072456e99a7e3bb00e25e421dcdd9ef2c"},
            {"E", "E.png", "9972a8da529949bbf10a86e2d2b8fff0f925049281e25c62770a664874a264a2"}
    };

    @Test public void everyGradeMapsToItsOwnExactOriginalArtwork() {
        List<GradeArtwork.Spec> specs = GradeArtwork.all();
        assertEquals(13, specs.size());
        Set<String> grades = new HashSet<>(), files = new HashSet<>(), hashes = new HashSet<>();
        for (String[] expected : EXPECTED) {
            GradeArtwork.Spec spec = GradeArtwork.forGrade(expected[0]);
            assertNotNull(expected[0], spec);
            assertEquals(expected[0], spec.grade);
            assertEquals(expected[1], spec.fileName);
            assertEquals(expected[2], spec.sha256);
            assertEquals("export/grades/" + expected[1], spec.assetPath());
            assertTrue(specs.contains(spec));
            assertTrue("duplicate grade " + spec.grade, grades.add(spec.grade));
            assertTrue("reused artwork " + spec.fileName, files.add(spec.fileName));
            assertTrue("reused original " + spec.grade, hashes.add(spec.sha256));
        }
    }

    @Test public void artworkCatalogCannotBeChangedByCallers() {
        assertThrows(UnsupportedOperationException.class, () -> GradeArtwork.all().clear());
        assertEquals(13, GradeArtwork.all().size());
    }

    @Test public void packagedPngsRetainOriginalBytesRgbaAndDimensions() throws Exception {
        for (GradeArtwork.Spec spec : GradeArtwork.all()) {
            Path asset = assetFile(spec.assetPath());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(asset)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
            assertEquals("original bytes changed for " + spec.grade, spec.sha256, hex.toString());
            try (DataInputStream input = new DataInputStream(Files.newInputStream(asset))) {
                assertEquals("PNG signature for " + spec.grade, 0x89504e470d0a1a0aL, input.readLong());
                assertEquals(13, input.readInt());
                assertEquals("IHDR", 0x49484452, input.readInt());
                assertEquals(spec.width, input.readInt());
                assertEquals(spec.height, input.readInt());
                assertEquals("8-bit original for " + spec.grade, 8, input.readUnsignedByte());
                assertEquals("RGBA original for " + spec.grade, 6, input.readUnsignedByte());
            }
        }
    }

    @Test public void onlyExplicitUnknownGradeUsesTextFallback() {
        assertNull(GradeArtwork.forGrade("未知"));
        for (String invalid : Arrays.asList(null, "", " ", "unknown", "SSSS", "SSS＋", "sss", "S ", "F", "A−")) {
            assertThrows("unexpected grade must fail closed: " + invalid,
                    IllegalArgumentException.class, () -> GradeArtwork.forGrade(invalid));
        }
    }

    @Test public void originalOpticalBoundsAreNonemptyAndInsideTheFullCanvas() {
        for (GradeArtwork.Spec spec : GradeArtwork.all()) {
            assertTrue(spec.grade, spec.width > 0 && spec.height > 0);
            assertTrue(spec.grade, spec.left >= 0 && spec.top >= 0);
            assertTrue(spec.grade, spec.right > spec.left && spec.bottom > spec.top);
            assertTrue(spec.grade, spec.right <= spec.width && spec.bottom <= spec.height);
        }
    }

    @Test public void everyPlacementPreservesAspectAndIncludesTheEntireOriginalCanvas() {
        for (GradeArtwork.Spec spec : GradeArtwork.all()) {
            GradeArtwork.Placement place = GradeArtwork.placement(spec);
            assertTrue(spec.grade, Float.isFinite(place.left) && Float.isFinite(place.top));
            assertTrue(spec.grade, Float.isFinite(place.scale) && place.scale > 0);
            assertEquals(spec.grade, spec.width * place.scale, place.width, EPSILON);
            assertEquals(spec.grade, spec.height * place.scale, place.height, EPSILON);
            assertEquals(spec.grade, (float) spec.width / spec.height, place.width / place.height, EPSILON);
            assertTrue(spec.grade, place.left >= GradeArtwork.SAFE_LEFT - EPSILON);
            assertTrue(spec.grade, place.top >= GradeArtwork.SAFE_TOP - EPSILON);
            assertTrue(spec.grade, place.right() <= GradeArtwork.SAFE_RIGHT + EPSILON);
            assertTrue(spec.grade, place.bottom() <= GradeArtwork.SAFE_BOTTOM + EPSILON);
            // Using canvas dimensions above, rather than ink dimensions, keeps glow and spray intact.
            assertEquals(spec.grade, place.left + place.width, place.right(), EPSILON);
            assertEquals(spec.grade, place.top + place.height, place.bottom(), EPSILON);
        }
    }

    @Test public void placementDoesNotExceedVisibleInkTargets() {
        for (GradeArtwork.Spec spec : GradeArtwork.all()) {
            GradeArtwork.Placement place = GradeArtwork.placement(spec);
            assertTrue(spec.grade, (spec.right - spec.left) * place.scale <= 600f + EPSILON);
            assertTrue(spec.grade, (spec.bottom - spec.top) * place.scale <= 440f + EPSILON);
        }
    }

    @Test public void multiLetterTopGradesRemainLargeAndReadable() {
        for (String grade : Arrays.asList("SS", "SSS", "SSS+")) {
            GradeArtwork.Spec spec = GradeArtwork.forGrade(grade);
            GradeArtwork.Placement place = GradeArtwork.placement(spec);
            assertTrue(grade + " canvas width", place.width >= 620f);
            assertTrue(grade + " canvas height", place.height >= 410f);
            assertTrue(grade + " visible width", (spec.right - spec.left) * place.scale >= 550f);
            assertTrue(grade + " visible height", (spec.bottom - spec.top) * place.scale >= 340f);
        }
    }

    @Test public void sampleSizeAcceptsPositiveDimensionsAtAndBelowThePixelLimit() throws Exception {
        assertEquals(512L * 1024, GradeArtwork.MAX_SPRITE_PIXELS);
        assertEquals(1, GradeArtwork.sampleSize(1, 1));
        assertEquals(1, GradeArtwork.sampleSize(1024, 512));
        assertEquals(1, GradeArtwork.sampleSize(512, 1024));
        assertEquals(1, GradeArtwork.sampleSize(524288, 1));
        assertEquals(1, GradeArtwork.sampleSize(1, 524288));
        assertEquals(1, GradeArtwork.sampleSize(724, 724));
    }

    @Test public void onePixelOverBudgetInEitherDimensionTriggersSampling() throws Exception {
        assertEquals(2, GradeArtwork.sampleSize(1025, 512));
        assertEquals(2, GradeArtwork.sampleSize(512, 1025));
        assertEquals(2, GradeArtwork.sampleSize(524289, 1));
        assertEquals(2, GradeArtwork.sampleSize(1, 524289));
        assertEquals(2, GradeArtwork.sampleSize(725, 725));
    }

    @Test public void nonpositiveDecodeDimensionsFailBeforeAllocation() {
        int[][] invalid = {{0, 1}, {1, 0}, {-1, 1}, {1, -1}, {0, 0},
                {Integer.MIN_VALUE, Integer.MAX_VALUE}, {Integer.MAX_VALUE, Integer.MIN_VALUE}};
        for (int[] size : invalid) {
            assertThrows(IOException.class, () -> GradeArtwork.sampleSize(size[0], size[1]));
        }
    }

    @Test public void everyOriginalUsesBoundedHighQualitySampling() throws Exception {
        for (GradeArtwork.Spec spec : GradeArtwork.all()) {
            assertEquals(spec.grade, 2, GradeArtwork.sampleSize(spec.width, spec.height));
            assertSmallestBoundedSample(spec.width, spec.height);
        }
    }

    @Test public void extremeDimensionsCannotOverflowOrReturnAnInvalidSample() throws Exception {
        assertEquals(4194304, GradeArtwork.sampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(4096, GradeArtwork.sampleSize(Integer.MAX_VALUE, 1));
        assertEquals(4096, GradeArtwork.sampleSize(1, Integer.MAX_VALUE));
        for (int width : new int[]{1, 2, 511, 512, 1023, 1024, 65535, 65536, Integer.MAX_VALUE}) {
            for (int height : new int[]{1, 3, 513, 1025, 65535, 65536, Integer.MAX_VALUE}) {
                assertSmallestBoundedSample(width, height);
            }
        }
    }

    @Test public void sampledCeilingDimensionsAlwaysRespectBudgetAndUseSmallestPowerOfTwo() throws Exception {
        Random random = new Random(20261008L);
        for (int i = 0; i < 2000; i++) {
            assertSmallestBoundedSample(1 + random.nextInt(Integer.MAX_VALUE),
                    1 + random.nextInt(Integer.MAX_VALUE));
        }
    }

    private static void assertSmallestBoundedSample(int width, int height) throws IOException {
        int sample = GradeArtwork.sampleSize(width, height);
        String description = width + "x" + height + " / " + sample;
        assertTrue(description, sample > 0);
        assertEquals(description, 0, sample & (sample - 1));
        assertTrue(description, sampledPixels(width, height, sample) <= GradeArtwork.MAX_SPRITE_PIXELS);
        if (sample > 1) assertTrue(description + " must preserve maximum available resolution",
                sampledPixels(width, height, sample / 2) > GradeArtwork.MAX_SPRITE_PIXELS);
    }

    private static long sampledPixels(int width, int height, int sample) {
        return ((width + (long) sample - 1) / sample) * ((height + (long) sample - 1) / sample);
    }

    private static Path assetFile(String relativePath) throws IOException {
        // Gradle uses the app project directory; direct JVM runners may use the repository root.
        for (Path root : Arrays.asList(Paths.get("src/main/assets"), Paths.get("app/src/main/assets"))) {
            Path asset = root.resolve(relativePath);
            if (Files.isRegularFile(asset)) return asset;
        }
        throw new IOException("Missing packaged artwork: " + relativePath);
    }
}
